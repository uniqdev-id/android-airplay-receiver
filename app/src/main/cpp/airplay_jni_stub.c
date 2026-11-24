/*
 * Phase 1 JNI stub.
 *
 * Matches:  external fun nativeGetVersion(): String  in
 *           com.uniq.airplay.AirPlayNative (instance method -> jobject).
 *
 * Phase 3 replaces this file with airplay_jni.cpp, which wires the UxPlay
 * raop callbacks (video_process / audio_process / conn_* / volume) into the
 * Kotlin pipelines.
 */
#include <jni.h>

JNIEXPORT jstring JNICALL
Java_com_uniq_airplay_AirPlayNative_nativeGetVersion(JNIEnv *env, jobject /* this */)
{
    return (*env)->NewStringUTF(env, "airplayjni 0.1-stub (UxPlay core lands in Phase 3)");
}
