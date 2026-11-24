/*
 * Phase 3 JNI bridge: wires UxPlay's raop callbacks into the Kotlin app.
 *
 * Architecture:
 *   UxPlay reader thread -> C++ cb_* -> g_listener (Kotlin object, global ref)
 *
 * The listener is registered from Kotlin via nativeSetListener before the
 * server starts. cb_* bodies stay allocation-light: one NewDirectByteBuffer
 * per media packet, queued synchronously by the listener before returning
 * (SPEC §5A performance rule).
 *
 * HLS callbacks (on_video_*) are no-ops: mirror/audio only.
 */
#include <jni.h>
#include <android/log.h>
#include <stdio.h>
#include <string.h>
#include <stdlib.h>

#include "raop.h"
#include "raop_ntp.h"
#include "dnssd.h"
#include "stream.h"

#define LOG_TAG "AirPlayJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ---------------------------------------------------------------------------
// JNI plumbing
// ---------------------------------------------------------------------------

static JavaVM *g_vm = nullptr;

// Kotlin event sink (global ref) + cached method IDs.
static jobject g_listener = nullptr;
struct ListenerIds {
    jmethodID onClientConnected;    // (Ljava/lang/String;Ljava/lang/String;)V
    jmethodID onClientDisconnected;  // (I)V
    jmethodID onVideoData;          // (Ljava/nio/ByteBuffer;IZJJ)V
    jmethodID onAudioData;         // (Ljava/nio/ByteBuffer;IIJJ)V
    jmethodID onVolumeChanged;     // (F)V
    jmethodID onVideoCodecChanged; // (ZIIII)V
    jmethodID onVideoPauseResume;  // (Z)V
    jmethodID onVideoReset;        // (I)V  — UxPlay reset_type_t, or -1 for flush
};
static ListenerIds g_lid;

/* RAII: UxPlay's reader threads are plain pthreads; attach on entry,
   detach on exit if we attached (SPEC §6.4). */
struct JniScope {
    JNIEnv *env = nullptr;
    bool attached = false;
    JniScope() {
        if (!g_vm) return;
        // C++ mode of jni.h: JavaVM is the _JavaVM struct (functions table
        // + 3 wrapper members); GetEnv lives on the table, not the struct.
        if (g_vm->functions->GetEnv(g_vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
            if (g_vm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
                attached = true;
            }
        }
    }
    ~JniScope() {
        if (attached && g_vm) g_vm->DetachCurrentThread();
    }
};

/* Forward one event; clear any exception so a Kotlin bug can't wedge
   UxPlay's reader thread. */
#define CALL_LISTENER(mid, ...)                                           \
    do {                                                                  \
        if (g_listener && g_listener_mid_valid) {                         \
            s.env->CallVoidMethod(g_listener, g_lid.mid, ##__VA_ARGS__); \
            if (s.env->ExceptionCheck()) {                                \
                LOGW("%s threw; clearing", #mid);                        \
                s.env->ExceptionDescribe();                               \
                s.env->ExceptionClear();                                  \
            }                                                             \
        }                                                                 \
    } while (0)

static bool g_listener_mid_valid = false;

// ---------------------------------------------------------------------------
// Server state (single client at a time — UxPlay is built with NOHOLD)
// ---------------------------------------------------------------------------

static raop_t *g_raop = nullptr;
static dnssd_t *g_dnssd = nullptr;
static double g_last_volume = 1.0;

// ---------------------------------------------------------------------------
// raop_callbacks_t implementations
// ---------------------------------------------------------------------------

static void cb_audio_process(void *cls, raop_ntp_t *ntp, audio_decode_struct *data) {
    if (!data || !data->data || data->data_len <= 0) return;
    JniScope s;
    if (!s.env) return;
    jobject buf = s.env->NewDirectByteBuffer(data->data, data->data_len);
    CALL_LISTENER(onAudioData, buf, (jint)data->ct, (jint)data->seqnum,
                  (jlong)data->ntp_time_local, (jlong)data->ntp_time_remote);
    s.env->DeleteLocalRef(buf); // hot path: avoid local-ref table overflow (SPEC §6.4)
}

static void cb_video_process(void *cls, raop_ntp_t *ntp, video_decode_struct *data) {
    if (!data || !data->data || data->data_len <= 0) return;
    JniScope s;
    if (!s.env) return;
    jobject buf = s.env->NewDirectByteBuffer(data->data, data->data_len);
    CALL_LISTENER(onVideoData, buf, (jint)data->nal_count, (jboolean)data->is_h265,
                  (jlong)data->ntp_time_local, (jlong)data->ntp_time_remote);
    s.env->DeleteLocalRef(buf);
}

static void cb_video_pause(void *cls) {
    JniScope s; if (!s.env) return;
    CALL_LISTENER(onVideoPauseResume, JNI_TRUE);
}

static void cb_video_resume(void *cls) {
    JniScope s; if (!s.env) return;
    CALL_LISTENER(onVideoPauseResume, JNI_FALSE);
}

static int cb_video_set_codec_and_size(void *cls, video_codec_t codec,
                                       unsigned short width_source, unsigned short height_source,
                                       unsigned short x_offset, unsigned short y_offset,
                                       unsigned short width, unsigned short height) {
    LOGI("video_set_codec_and_size codec=%d src=%ux%u off=%u,%u out=%ux%u",
         (int)codec, width_source, height_source, x_offset, y_offset, width, height);
    JniScope s;
    if (s.env) {
        CALL_LISTENER(onVideoCodecChanged,
                      (jboolean)(codec == VIDEO_CODEC_H265),
                      (jint)width, (jint)height, (jint)x_offset, (jint)y_offset);
    }
    return 1; // accept every geometry; decoder reconfigures on INFO_OUTPUT_FORMAT_CHANGED
}

static void cb_conn_feedback(void *cls) {}

static void cb_conn_reset(void *cls, int reason) {
    LOGI("conn_reset reason=%d", reason);
    JniScope s; if (!s.env) return;
    CALL_LISTENER(onClientDisconnected, (jint)reason);
}

static void cb_video_reset(void *cls, reset_type_t reset_type) {
    LOGI("video_reset type=%d", (int)reset_type);
    JniScope s; if (!s.env) return;
    CALL_LISTENER(onVideoReset, (jint)(int)reset_type);
}

static void cb_conn_init(void *cls) {
    LOGI("conn_init");
}

static void cb_conn_destroy(void *cls) {
    LOGI("conn_destroy");
    JniScope s; if (!s.env) return;
    CALL_LISTENER(onClientDisconnected, (jint)-1);
}

static void cb_audio_flush(void *cls) {
    LOGI("audio_flush");
    // Phase 5: flush AudioTrack / decoder here.
}

static void cb_video_flush(void *cls) {
    LOGI("video_flush");
    JniScope s; if (!s.env) return;
    CALL_LISTENER(onVideoReset, (jint)-1); // -1 = flush (distinct from reset_type_t values)
}

static double cb_audio_set_client_volume(void *cls) {
    return g_last_volume;
}

static void cb_audio_set_volume(void *cls, float volume) {
    g_last_volume = (double)volume;
    JniScope s; if (!s.env) return;
    CALL_LISTENER(onVolumeChanged, (jfloat)volume);
}

static void cb_audio_set_metadata(void *cls, const void *buffer, int buflen) {}
static void cb_audio_set_coverart(void *cls, const void *buffer, int buflen) {}
static void cb_audio_stop_coverart_rendering(void *cls) {}
static void cb_audio_remote_control_id(void *cls, const char *dacp_id, const char *active_remote_header) {}
static void cb_audio_set_progress(void *cls, uint32_t *start, uint32_t *curr, uint32_t *end) {}

static void cb_audio_get_format(void *cls, unsigned char *ct, unsigned short *spf,
                                bool *usingScreen, bool *isMedia, uint64_t *audioFormat) {
    LOGI("audio_get_format ct=%d spf=%u usingScreen=%d isMedia=%d fmt=0x%llx",
         *ct, *spf, *usingScreen ? 1 : 0, *isMedia ? 1 : 0, (unsigned long long)*audioFormat);
    // Phase 5 builds the MediaCodec csd-0 from `ct` / audioFormat.
}

static void cb_mirror_video_running(void *cls, bool is_running) {
    LOGI("mirror_video_running %d", is_running ? 1 : 0);
}

static void cb_report_client_request(void *cls, char *deviceid, char *model, char *name, bool *admit) {
    LOGI("client request: deviceid=%s model=%s name=%s", deviceid ? deviceid : "?",
         model ? model : "?", name ? name : "?");
    *admit = true;
    JniScope s; if (!s.env) return;
    jstring jid = s.env->NewStringUTF(deviceid ? deviceid : "");
    jstring jn = s.env->NewStringUTF(name ? name : "");
    CALL_LISTENER(onClientConnected, jid, jn);
    s.env->DeleteLocalRef(jid);
    s.env->DeleteLocalRef(jn);
}

static void cb_display_pin(void *cls, char *pin) {
    LOGI("display_pin (PIN pairing requested; unsupported in this build)");
}

static void cb_register_client(void *cls, const char *device_id, const char *pk_str, const char *name) {
    LOGI("register_client %s (%s)", name ? name : "?", device_id ? device_id : "?");
}

static bool cb_check_register(void *cls, const char *pk_str) { return true; }

static const char *cb_passwd(void *cls, int *len) { return nullptr; } // no password
static void cb_export_dacp(void *cls, const char *active_remote, const char *dacp_id) {}
static void cb_on_video_play(void *cls, const char *location, const float start_position) {}
static void cb_on_video_scrub(void *cls, const float position) {}
static void cb_on_video_rate(void *cls, const float rate) {}
static void cb_on_video_stop(void *cls) {}
static void cb_on_video_acquire_playback_info(void *cls, playback_info_t *p) {}
static float cb_on_video_playlist_remove(void *cls) { return 0.0f; }
static void cb_get_custom_profile(void *cls, const char **custom_profile) {}

static void log_cb(void *cls, int level, const char *msg) {
    __android_log_print(level <= 1 ? ANDROID_LOG_ERROR : level <= 3 ? ANDROID_LOG_WARN : ANDROID_LOG_INFO,
                       LOG_TAG, "%s", msg ? msg : "");
}

// ---------------------------------------------------------------------------
// Externs (Kotlin: class AirPlayNative instance methods)
// ---------------------------------------------------------------------------

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void * /*reserved*/) {
    g_vm = vm;
    JNIEnv *env = nullptr;
    if (vm->functions->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    LOGI("JNI_OnLoad ok");
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL
Java_com_uniq_airplay_AirPlayNative_nativeSetListener(JNIEnv *env, jobject thiz, jobject listener)
{
    if (g_listener && g_listener != listener) {
        env->DeleteGlobalRef(g_listener);
        g_listener = nullptr;
    }
    g_listener_mid_valid = false;
    if (!listener) {
        g_listener = nullptr;
        return;
    }
    g_listener = env->NewGlobalRef(listener);
    jclass c = env->FindClass("com/uniq/airplay/AirPlayNative$Listener");
    if (!c) {
        LOGE("Listener class not found");
        return;
    }
    g_lid.onClientConnected = env->GetMethodID(c, "onClientConnected", "(Ljava/lang/String;Ljava/lang/String;)V");
    g_lid.onClientDisconnected = env->GetMethodID(c, "onClientDisconnected", "(I)V");
    g_lid.onVideoData = env->GetMethodID(c, "onVideoData", "(Ljava/nio/ByteBuffer;IZJJ)V");
    g_lid.onAudioData = env->GetMethodID(c, "onAudioData", "(Ljava/nio/ByteBuffer;IIJJ)V");
    g_lid.onVolumeChanged = env->GetMethodID(c, "onVolumeChanged", "(F)V");
    g_lid.onVideoCodecChanged = env->GetMethodID(c, "onVideoCodecChanged", "(ZIIII)V");
    g_lid.onVideoPauseResume = env->GetMethodID(c, "onVideoPauseResume", "(Z)V");
    g_lid.onVideoReset = env->GetMethodID(c, "onVideoReset", "(I)V");
    g_listener_mid_valid =
        g_lid.onClientConnected && g_lid.onClientDisconnected && g_lid.onVideoData &&
        g_lid.onAudioData && g_lid.onVolumeChanged && g_lid.onVideoCodecChanged && g_lid.onVideoPauseResume && g_lid.onVideoReset;
    if (!g_listener_mid_valid) {
        LOGE("failed to resolve Listener method IDs");
    }
    env->DeleteLocalRef(c);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_uniq_airplay_AirPlayNative_nativeStartServer(JNIEnv *env, jobject thiz,
                                                      jstring jname, jbyteArray jmac, jstring jkeyfile)
{
    if (g_raop) {
        LOGW("server already running");
        return JNI_TRUE;
    }

    const char *name = env->GetStringUTFChars(jname, nullptr);
    const char *keyfile = env->GetStringUTFChars(jkeyfile, nullptr);

    jsize mlen = env->GetArrayLength(jmac);
    jbyte mac[6];
    if (mlen != 6) {
        LOGE("MAC must be 6 bytes, got %d", mlen);
        env->ReleaseStringUTFChars(jname, name);
        env->ReleaseStringUTFChars(jkeyfile, keyfile);
        return JNI_FALSE;
    }
    env->GetByteArrayRegion(jmac, 0, 6, mac);

    char mac_ascii[18];
    snprintf(mac_ascii, sizeof(mac_ascii), "%02x:%02x:%02x:%02x:%02x:%02x",
             mac[0] & 0xFF, mac[1] & 0xFF, mac[2] & 0xFF,
             mac[3] & 0xFF, mac[4] & 0xFF, mac[5] & 0xFF);
    LOGI("starting server name=%s mac=%s keyfile=%s", name, mac_ascii, keyfile ? keyfile : "(none)");

    raop_callbacks_t cbs;
    memset(&cbs, 0, sizeof(cbs));
    cbs.cls = nullptr;
    cbs.audio_process = cb_audio_process;
    cbs.video_process = cb_video_process;
    cbs.video_pause = cb_video_pause;
    cbs.video_resume = cb_video_resume;
    cbs.conn_feedback = cb_conn_feedback;
    cbs.conn_reset = cb_conn_reset;
    cbs.video_reset = cb_video_reset;
    cbs.video_set_codec_and_size = cb_video_set_codec_and_size;
    cbs.conn_init = cb_conn_init;
    cbs.conn_destroy = cb_conn_destroy;
    cbs.audio_flush = cb_audio_flush;
    cbs.video_flush = cb_video_flush;
    cbs.audio_set_client_volume = cb_audio_set_client_volume;
    cbs.audio_set_volume = cb_audio_set_volume;
    cbs.audio_set_metadata = cb_audio_set_metadata;
    cbs.audio_set_coverart = cb_audio_set_coverart;
    cbs.audio_stop_coverart_rendering = cb_audio_stop_coverart_rendering;
    cbs.audio_remote_control_id = cb_audio_remote_control_id;
    cbs.audio_set_progress = cb_audio_set_progress;
    cbs.audio_get_format = cb_audio_get_format;
    cbs.mirror_video_running = cb_mirror_video_running;
    cbs.report_client_request = cb_report_client_request;
    cbs.display_pin = cb_display_pin;
    cbs.register_client = cb_register_client;
    cbs.check_register = cb_check_register;
    cbs.passwd = cb_passwd;
    cbs.export_dacp = cb_export_dacp;
    cbs.on_video_play = cb_on_video_play;
    cbs.on_video_scrub = cb_on_video_scrub;
    cbs.on_video_rate = cb_on_video_rate;
    cbs.on_video_stop = cb_on_video_stop;
    cbs.on_video_acquire_playback_info = cb_on_video_acquire_playback_info;
    cbs.on_video_playlist_remove = cb_on_video_playlist_remove;
    cbs.get_custom_profile = cb_get_custom_profile;

    g_raop = raop_init(&cbs);
    if (!g_raop) {
        LOGE("raop_init failed");
        env->ReleaseStringUTFChars(jname, name);
        env->ReleaseStringUTFChars(jkeyfile, keyfile);
        return JNI_FALSE;
    }
    raop_set_log_callback(g_raop, log_cb, nullptr);
    if (raop_init2(g_raop, 0 /*nohold*/, mac_ascii, keyfile)) {
        LOGE("raop_init2 failed (keyfile problem?)");
        raop_destroy(g_raop);
        g_raop = nullptr;
        env->ReleaseStringUTFChars(jname, name);
        env->ReleaseStringUTFChars(jkeyfile, keyfile);
        return JNI_FALSE;
    }

    // Dynamic ports (0 = auto-assign); the RAOP port is reused for AirPlay,
    // matching UxPlay's default app behaviour.
    unsigned short tcp[2] = {0, 0};
    raop_set_tcp_ports(g_raop, tcp);
    unsigned short udp[3] = {0, 0, 0};
    raop_set_udp_ports(g_raop, udp);

    unsigned short port = raop_get_port(g_raop);
    ntp_global_init();
    // httpd_start: 0=already running, 1=started, <0=error (SPEC §5A).
    int httpd_ret = raop_start_httpd(g_raop, &port);
    if (httpd_ret < 0) {
        LOGE("raop_start_httpd failed ret=%d", httpd_ret);
        raop_destroy(g_raop);
        g_raop = nullptr;
        env->ReleaseStringUTFChars(jname, name);
        env->ReleaseStringUTFChars(jkeyfile, keyfile);
        return JNI_FALSE;
    }
    raop_set_port(g_raop, port);
    LOGI("RTSP/httpd listening on port %d", port);

    int err = 0;
    g_dnssd = dnssd_init(name, (int)strlen(name), (const char *)mac, 6, 0 /*no pin*/, &err);
    if (!g_dnssd) {
        // dnssd_error codes: 1=HWADDRLEN, 2=OUTOFMEM, 3=LIBNOTFOUND, <0=socket errno
        LOGE("dnssd_init failed err=%d", err);
        raop_stop_httpd(g_raop);
        raop_destroy(g_raop);
        g_raop = nullptr;
        env->ReleaseStringUTFChars(jname, name);
        env->ReleaseStringUTFChars(jkeyfile, keyfile);
        return JNI_FALSE;
    }
    if (dnssd_register_raop(g_dnssd, port) != DNSSD_ERROR_NOERROR) {
        LOGW("dnssd_register_raop failed (device may still be reachable via _airplay)");
    }
    if (dnssd_register_airplay(g_dnssd, port) != DNSSD_ERROR_NOERROR) {
        LOGW("dnssd_register_airplay failed");
    }
    raop_set_dnssd(g_raop, g_dnssd);

    env->ReleaseStringUTFChars(jname, name);
    env->ReleaseStringUTFChars(jkeyfile, keyfile);
    LOGI("server started (mDNS: %s / %s@..)", name, mac_ascii);
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_uniq_airplay_AirPlayNative_nativeStopServer(JNIEnv *env, jobject thiz)
{
    if (!g_raop && !g_dnssd) return;
    LOGI("stopping server");
    if (g_raop) {
        raop_stop_httpd(g_raop);
        raop_destroy(g_raop);
        g_raop = nullptr;
    }
    if (g_dnssd) {
        dnssd_destroy(g_dnssd); // unregisters + stops the mdnsd backend
        g_dnssd = nullptr;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_uniq_airplay_AirPlayNative_nativeGetVersion(JNIEnv *env, jobject thiz)
{
    return env->NewStringUTF("airplayjni 0.3 (UxPlay lib + mdnsd + FairPlay/playfair)");
}
