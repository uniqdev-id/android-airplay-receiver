You can save the document below as `SPEC.md` in a fresh project folder and hand it directly to an AI coding agent (such as Claude Code, Cursor, Windsurf, or Codex). It is structured with strict architectural constraints, exact protocol quirks, and phased milestones so the agent doesn't get stuck on common AirPlay/Android TV pitfalls.

---

# Technical Specification: Lightweight Android TV AirPlay Receiver

## 1. Project Overview & Objectives
Build a lightweight, ad-free, ultra-low-latency **AirPlay Mirroring Receiver** application for **Android TV** (compatible with macOS and iPadOS/iOS built-in "Screen Mirroring").

### Core Goals
* **Native AirPlay Compatibility:** Discoverable via macOS/iPadOS built-in Screen Mirroring without installing any companion app on the Apple devices.
* **Hardware-Accelerated & Lightweight:** Zero software video decoding. Use Android's hardware `MediaCodec` rendered directly to a `SurfaceView` (zero-copy) to minimize CPU load and heat on low-powered Android TV hardware.
* **High Stability:** Prevent background disconnects, Wi-Fi multicast packet drops, and memory leaks during long sessions.
* **Minimalist Android TV UI:** Clean standby screen showing TV name, IP address, and connection status; instant transition to fullscreen video upon connection.

### Non-Goals
* Do **NOT** use GStreamer or FFmpeg for video decoding.
* Do **NOT** implement DRM-protected AirPlay streaming (e.g., Netflix/Apple TV+ FairPlay HLS video casting); focus on **Screen Mirroring (H.264/HEVC + AAC-ELD audio)** and standard **AirPlay Audio (RAOP)**.

---

## 2. Target Environment & Tech Stack
* **Platform:** Android TV (`minSdk = 26`, `targetSdk = 34`, `compileSdk = 34`)
* **Languages:**
  * **Kotlin:** Android app lifecycle, Foreground Service, mDNS advertisement, `MediaCodec`, `AudioTrack`, UI.
  * **C / C++ (Android NDK r26+ & CMake):** AirPlay protocol engine, RTSP/HTTP server, FairPlay decryption, and JNI bridge.
* **Target ABIs:** `armeabi-v7a` (required — the primary test device, a MiTV-AXSO0 Xiaomi TV, runs Android 9 **32-bit only**: ABI list is `armeabi-v7a,armeabi`) and `arm64-v8a` (for modern TVs), plus `x86_64` optional for emulator testing.
* **Build System:** Gradle (Kotlin DSL) + CMake.

---

## 3. System Architecture

```text
[ macOS / iPadOS Device ]
       │
       ├── 1. mDNS Discovery (_airplay._tcp, _raop._tcp)
       ├── 2. RTSP / HTTP Control & FairPlay Handshake (TCP)
       ├── 3. Encrypted H.264/HEVC Video Stream (TCP)
       └── 4. Encrypted AAC-ELD / ALAC Audio Stream (UDP)
       │
       ▼
┌──────────────────────────────────────────────────────────────────┐
│                     Android TV Application                       │
│                                                                  │
│  ┌──────────────────────────┐    ┌────────────────────────────┐  │
│  │  Discovery Layer (Kotlin)│    │ Foreground Service (Kotlin)│  │
│  │  JmDNS / NsdManager      │    │ MulticastLock + WifiLock   │  │
│  │  Broadcasts TXT records  │    │ WakeLock + Server Lifecycle│  │
│  └────────────┬─────────────┘    └─────────────┬──────────────┘  │
│               │ (Ports, MAC, PubKey)           │                 │
│  ┌────────────▼────────────────────────────────▼──────────────┐  │
│  │             Native AirPlay Core (C/C++ via NDK)            │  │
│  │  Based on UxPlay `lib/` (raop, playfair, llhttp, crypto)   │  │
│  │  Dependencies: OpenSSL (libcrypto), libplist               │  │
│  │  Stripped of GStreamer; exposes JNI callbacks              │  │
│  └────────────┬────────────────────────────────┬──────────────┘  │
│               │ JNI: onVideoFrame(DirectBuf)   │ JNI: onAudioFrame
│               ▼                                ▼                 │
│  ┌──────────────────────────┐    ┌────────────────────────────┐  │
│  │ Video Pipeline (Kotlin)  │    │ Audio Pipeline (Kotlin)    │  │
│  │ AVCC -> Annex-B Converter│    │ MediaCodec (AAC-ELD)       │  │
│  │ MediaCodec (video/avc)   │    │ -> AudioTrack (Low Latency)│  │
│  │ Zero-Copy -> SurfaceView │    │                            │  │
│  └──────────────────────────┘    └────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────┘
```

---

## 4. Core Dependencies & Native Integration Strategy

### A. Native AirPlay Protocol Core (`UxPlay`)
Do **not** implement the AirPlay cryptographic handshake or FairPlay decryption from scratch.
* **Source:** Vendor the `lib/` directory from the open-source **UxPlay** repository (`github.com/FDH2/UxPlay`).
* **Modules to keep:** `raop.c`, `raop_rtp.c`, `raop_rtp_mirror.c`, `raop_buffer.c`, `httpd.c`, `fairplay_playfair.c`, `mirror_buffer.c`, `netutils.c`, `utils.c`, `lib/playfair/*`, `lib/llhttp/*`, `lib/crypto/*`, `lib/ed25519/*`.
* **Modules to strip/replace:**
  * Remove all GStreamer (top-level `renderers/` + `uxplay.cpp` are simply not vendored; only `lib/` is imported).
  * **Keep `lib/mdnsd/`** (UxPlay's own minimal mDNS responder, builds as target `dnssd`). It registers both `_airplay._tcp` and `_raop._tcp` with the correct TXT values natively — no JmDNS and no JNI discovery bridge needed. Kotlin only acquires the `MulticastLock` (§5B.1) and re-triggers registration on network change.

### B. Native C Dependencies (Cross-compiled for Android NDK)
UxPlay's `lib/` requires two external C libraries:
1. **OpenSSL (`libcrypto`):** Use prebuilt Android binaries (e.g., `com.android.ndk.thirdparty:openssl` via Prefab in Gradle, or build OpenSSL 3.x static libs for `arm64-v8a` and `armeabi-v7a`).
2. **`libplist`:** Cross-compile `libplist` (C only, no C++ or Python bindings needed) as a static library via CMake `FetchContent` or git submodule.

### C. Kotlin/Java Dependencies
* **mDNS:** none — `lib/mdnsd` (UxPlay) handles both service registrations in C. (Originally JmDNS was planned to replace `dnssd.c`; superseded — verified against UxPlay's current source: `dnssd_mdnsd.c` builds the full TXT records itself.)
* **Coroutines:** `kotlinx-coroutines-android` for managing service lifecycles and decoding loops.
* **UI:** Standard plain Activities (TV behaviour comes from manifest attributes + 320x180 banner; no AppCompat/Leanback libraries for a two-activity app — keep dependencies minimal).

---

## 5. Detailed Component Specifications

### A. JNI Bridge Contract
Expose a clean JNI interface (`AirPlayNative.kt` <-> `airplay_jni.cpp`):
* **Methods (Kotlin -> C++):**
  * `startServer(deviceName: String, hwAddress: ByteArray, raopPort: Int, airplayPort: Int, width: Int, height: Int, fps: Int): Boolean`
  * `stopServer()`
  * `getPublicKeyHex(): String` (needed for mDNS `pk` TXT record)
* **Callbacks (C++ -> Kotlin):**
  * `onClientConnected(clientName: String)`
  * `onClientDisconnected()`
  * `onVideoData(buffer: ByteBuffer, length: Int, payloadType: Int, timestampUs: Long)`
  * `onAudioData(buffer: ByteBuffer, length: Int, codecType: Int, timestampUs: Long)`
  * `onVolumeChanged(volume: Float)`

> **Performance Rule:** Inside C++ callbacks, wrap the native payload pointer using `env->NewDirectByteBuffer(data, length)` and process/queue it synchronously before returning. Avoid allocating new `ByteArray` objects per frame at 60fps to prevent GC pauses.

### B. Network Discovery (mDNS / Bonjour)
> **Verified against UxPlay `lib/mdnsd/dnssd_mdnsd.c` + `lib/dnssdint.h` (Nov 2026):**
> UxPlay registers both services itself; values below are UxPlay's built-in defaults.
> Kotlin's only job is acquiring the `MulticastLock` before the native server starts.
1. Acquire `WifiManager.MulticastLock` **before** starting the native AirPlay server.
2. Generate or persist a stable 6-byte MAC address (e.g., stored in `SharedPreferences`, since Android 11+ restricts reading the real hardware MAC). UxPlay takes it as `device_id` in `raop_init2()`; note UxPlay advertises it as lowercase `deviceid` in the TXT record — that is the working convention, keep it.
3. Service records UxPlay registers (for reference / debug greps):
   * **`_airplay._tcp.local.`** (Name: `<TV Name>`, Port: `7000`):
     * `deviceid`: `<stable hex MAC>`
     * `features`: `0x5A7FFEE6,0x0` (UxPlay default; bit 27 = "supports legacy pairing", required for mirroring. iMirror instead advertises `0x5A7FFFF7,0x1E` — either works, but don't mix-and-match without re-verifying discovery.)
     * `flags`: `0x4`
     * `model`: `AppleTV3,2` (`lib/global.h` `GLOBAL_MODEL`)
     * `pk`: `<base64 DER RSA public key from UxPlay's keyfile>`
     * `pi`: `2e388006-13ba-4041-9a67-25dd4a43d536` (UxPlay `AIRPLAY_PI`)
     * `srcvers`: `220.68` (`GLOBAL_VERSION`)
     * `vv`: `2`
   * **`_raop._tcp.local.`** (Name: `<AABBCCDDEEFF>@<TV Name>`, Port: `5000`):
     * `am`: `AppleTV3,2`
     * `ch`: `2`
     * `cn`: `0,1,2,3`
     * `da`: `true`
     * `et`: `0,3,5`
     * `ft`: `0x5A7FFEE6,0x0` (must match the AirPlay record; UxPlay default)
     * `md`: `0,1,2`
     * `pk`: `<same public key>`
     * `sf`: `0x4`
     * `sr`: `44100`
     * `ss`: `16`
     * `tp`: `UDP`
     * `vn`: `65537`
     * `vs`: `220.68`
     * `vv`: `2`
4. Listen for network connectivity changes (`ConnectivityManager.NetworkCallback`). If the IP changes, tear down and re-register the UxPlay `mdnsd` services immediately.

### C. Hardware Video Decoder (`MediaCodec`) — CRITICAL DETAILS
AirPlay mirroring sends H.264 NAL units with specific framing quirks that **must** be handled properly:

1. **Payload Types:**
   * `payloadType == 1`: Contains **SPS and PPS** codec configuration data (`AVCDecoderConfigurationRecord` format).
   * `payloadType == 0`: Contains **Video Frames** in **AVCC format** (4-byte big-endian length prefix before each NAL unit).
2. **AVCC to Annex-B Conversion (Mandatory):**
   * Android's `MediaCodec` requires **Annex-B** start codes (`0x00 0x00 0x00 0x01`).
   * For `payloadType == 1`: Parse the SPS and PPS lengths/bytes from the `AVCDecoderConfigurationRecord`, prepend `0x00 0x00 0x00 0x01` to both SPS and PPS, and feed them to `MediaCodec` with `MediaCodec.BUFFER_FLAG_CODEC_CONFIG` (or set `csd-0` / `csd-1` in `MediaFormat`).
   * For `payloadType == 0`: Walk through the buffer replacing every 4-byte length prefix with `0x00 0x00 0x00 0x01` in-place before copying into the `MediaCodec` input buffer.
3. **Low-Latency Configuration:**
   * MIME type: `MediaFormat.MIMETYPE_VIDEO_AVC` (`video/avc`) for H.264 sessions, `video/hevc` when UxPlay's `video_set_codec_and_size` reports `VIDEO_CODEC_H265` (newer macOS sends HEVC mirror — codec must be chosen dynamically, not hard-coded).
   * Output directly to `SurfaceView.holder.surface`.
   * (Do NOT set `KEY_LOW_LATENCY` / `KEY_PRIORITY` on the decoder format — those are encoder-only parameters and are ignored by MediaCodec decoders. Latency control is purely the queue discipline in §6: bounded queue, latest-frame drop, immediate `releaseOutputBuffer`.)
4. **Dynamic Resolution & Rotation (iPad Support):**
   * When an iPad rotates between portrait and landscape, it sends a new `payloadType == 1` packet with new SPS/PPS dimensions.
   * Handle `MediaCodec.INFO_OUTPUT_FORMAT_CHANGED` to read updated `KEY_WIDTH` / `KEY_HEIGHT` and dynamically adjust the `SurfaceView` aspect ratio (letterboxing/pillarboxing) without stretching the video.
   * If the decoder throws an error or stalls on format change, cleanly flush/reconfigure `MediaCodec` and wait for the next IDR keyframe.

### D. Low-Latency Audio Pipeline
AirPlay Mirroring transmits audio encoded in **AAC-ELD** (44.1 kHz, Stereo, 480 samples per frame).
1. Configure a `MediaCodec` audio decoder for `MediaFormat.MIMETYPE_AUDIO_AAC` (`audio/mp4a-latm`):
   * Derive `csd-0` from the session: UxPlay's `audio_get_format` callback returns the AudioSpecificConfig as a `uint64_t audioFormat` — pack its bytes into `csd-0` instead of hard-coding (the hard-coded `0xF8 0xE8 0x50 0x00` is the AAC-ELD 44.1 kHz stereo variant, used only as a sanity-check fallback if the callback is absent).
2. Output decoded PCM 16-bit audio directly to an Android **`AudioTrack`**:
   * Mode: `AudioTrack.MODE_STREAM`
   * Performance Mode (API 26+): `AudioTrack.PERFORMANCE_MODE_LOW_LATENCY`
   * Usage: `AudioAttributes.USAGE_MEDIA`, Content Type: `AudioAttributes.CONTENT_TYPE_MOVIE`
   * Buffer size: `AudioTrack.getMinBufferSize(...) * 2` (small enough for low latency, large enough to avoid crackling).
3. Support volume adjustments from the Mac/iPad via `AudioTrack.setVolume(...)` mapped from AirPlay's dB scale (`-30.0` to `0.0`, where `-144.0` is mute).

### E. Stability, Background Execution & Permissions
To prevent Android TV OS from killing the receiver or dropping Wi-Fi packets:
* Run the AirPlay server and `JmDNS` inside a **Foreground Service** (`foregroundServiceType="mediaPlayback"` or `"connectedDevice"`).
* Hold these locks while the service is active:
  * `WifiManager.MulticastLock` (always while discoverable).
  * `WifiManager.WifiLock` (`WIFI_MODE_FULL_LOW_LATENCY` on API 29+, `WIFI_MODE_FULL_HIGH_PERF` on older APIs) while a client is connected.
  * `WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON` on the mirroring Activity so the TV screensaver never triggers during mirroring.
* **Required Manifest Permissions:**
  * `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`, `WAKE_LOCK`, `FOREGROUND_SERVICE`, and `FOREGROUND_SERVICE_MEDIA_PLAYBACK` (the last one is required on Android 10+ to allow `foregroundServiceType="mediaPlayback"`; ignored on the Android 9 target TV).
* **Android TV Compatibility:**
  * Declare `<uses-feature android:name="android.software.leanback" android:required="false" />` (keeps it runnable on both Android TV and standard phones/emulators for testing).
  * Declare `<uses-feature android:name="android.hardware.touchscreen" android:required="false" />`.
  * Include `CATEGORY_LEANBACK_LAUNCHER` in the main Activity intent filter and provide a 320x180dp TV banner drawable.

### F. User Interface (Minimalist)
1. **Standby Screen:**
   * Dark background (OLED/TV friendly).
   * Displays: App Title, Broadcast Name (editable via simple settings dialog), Local IP address, Network type (Ethernet / Wi-Fi), and Server Status ("Ready for AirPlay").
   * Full D-Pad remote navigation support.
2. **Mirroring Screen:**
   * Edge-to-edge black background with aspect-ratio-locked `SurfaceView`.
   * Pressing `BACK` on the TV remote disconnects the active session and returns to the standby screen.
   * Optional tiny debug HUD (toggleable via `MENU` or `INFO` button on remote) showing current resolution, FPS, and audio status.

---

## 6. Performance & Reliability Rules (For the AI Agent)
1. **Thread Isolation:** Never run network I/O, `JmDNS`, or `MediaCodec` dequeue loops on Android's Main (UI) thread. Use dedicated threads/Dispatchers:
   * Native sockets run on UxPlay's internal pthreads.
   * Video `MediaCodec` input/output runs on a dedicated high-priority thread (`Process.THREAD_PRIORITY_URGENT_DISPLAY`).
   * Audio `MediaCodec` + `AudioTrack` runs on a dedicated `Process.THREAD_PRIORITY_AUDIO` thread.
2. **Backpressure / Latency Control:** If the video queue backs up (e.g., brief Wi-Fi jitter), drop queued non-keyframe frames rather than letting latency accumulate to multiple seconds.
3. **Clean Teardown:** On client disconnect (or unexpected socket drop), cleanly stop/release `MediaCodec` and `AudioTrack`, clear the `SurfaceView` to black, and keep the AirPlay server + mDNS alive so the user can reconnect immediately without restarting the app.
4. **No Memory Leaks:** Properly detach JNI threads (`DetachCurrentThread`) when native threads exit, and delete local JNI references (`DeleteLocalRef`) inside hot video/audio callbacks to prevent JNI local reference table overflow!

---

## 7. Phased Implementation Roadmap

Execute and verify each milestone in order before moving to the next:

### Phase 1: Project Scaffolding & Android TV Setup
* Initialize Kotlin Gradle project with CMake/NDK enabled.
* Configure `AndroidManifest.xml` for Android TV (Leanback launcher, permissions, no touchscreen required).
* Create Standby UI + Foreground Service skeleton acquiring `MulticastLock` and `WifiLock`.
* **Checkpoint:** App builds, installs on Android TV / emulator, and shows local IP address.

### Phase 2: mDNS Discovery Verification
* No `MdnsAdvertiser.kt` needed — UxPlay's `lib/mdnsd` registers both services; the Kotlin side owns the `MulticastLock` + `NetworkCallback` re-registration (SPEC §5B).
* **Checkpoint:** Verify from a Mac on the same network using `dns-sd -B _airplay._tcp` and confirm the TV appears in macOS/iPadOS "Screen Mirroring" menu.

### Phase 3: Native Build (OpenSSL + libplist + UxPlay Core)
* Integrate OpenSSL (NDK thirdparty prefab) and `libplist` in `CMakeLists.txt`.
* Import UxPlay `lib/` sources (raop, mirror_buffer, httpd, playfair, fairplay_v2, llhttp, mdnsd, crypto, netutils, utils, sockets, pairing, srp, threads...); keep `lib/mdnsd` for service registration; write `airplay_jni.cpp` implementing `raop_callbacks_t` (video_process / audio_process / conn_* / volume / codec+size / flush / reset).
* Wire up `AirPlayNative.kt` so the standby screen shows live status; `pk`/device id are generated by UxPlay (keyfile) — surface them for debug.
* **Checkpoint:** Select the TV from a Mac/iPad. Verify via `adb logcat` that FairPlay handshake succeeds and `onVideoData` / `onAudioData` callbacks are receiving bytes.

### Phase 4: Hardware Video Pipeline
* Implement `VideoDecoder.kt`: AVCC -> Annex-B conversion, `MediaCodec` initialization, and `SurfaceView` rendering.
* Implement dynamic aspect ratio resizing on `INFO_OUTPUT_FORMAT_CHANGED` (test with iPad rotation).
* **Checkpoint:** Smooth 1080p60 screen mirroring visible on TV with minimal CPU usage.

### Phase 5: Audio Pipeline
* Implement `AudioDecoder.kt`: AAC-ELD `MediaCodec` with `csd-0` (`0xF8, 0xE8, 0x50, 0x00`) feeding `AudioTrack`.
* Wire up remote volume control callback.
* **Checkpoint:** Clear, synchronized audio playing from Mac/iPad through TV speakers.

### Phase 6: Hardening & Edge Cases
* Test rapid connect/disconnect cycles, iPad rotation, Wi-Fi reconnects, and long-running (1+ hour) sessions.
* Verify zero JNI local ref leaks (`adb logcat` check for JNI warnings) and smooth D-Pad exit behavior.

---

## 8. Debugging & Verification Commands (Reference)
* **Check mDNS advertisement from Mac:**
  ```bash
  dns-sd -B _airplay._tcp
  dns-sd -L "<TV Name>" _airplay._tcp
  ```
* **Inspect Android TV hardware codecs & logs:**
  ```bash
  adb logcat -s AirPlayNative VideoDecoder AudioDecoder MdnsAdvertiser
  ```
* **Check CPU/Memory footprint during 1080p mirroring:**
  ```bash
  adb shell top -p $(adb shell pidof <your.package.name>)
  ```

---

### Tip before handing this to your AI Agent
When you give this document to your AI agent, also tell it:
1. Whether you have an **Android TV device connected via ADB** or want to test on an emulator first (note: Android emulators have isolated virtual networking, so testing mDNS/AirPlay from a real Mac/iPad requires a real Android TV or real Android phone on the same Wi-Fi network).
2. To **clone/inspect `github.com/FDH2/UxPlay`** first so it sees the exact current file layout of `UxPlay/lib/` before writing `CMakeLists.txt`.
