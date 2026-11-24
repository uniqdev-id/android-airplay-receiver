# airplay-receiver

Lightweight, ad-free **AirPlay mirroring receiver** for Android TV — mirror
your Mac / iPad screen using the built-in "Screen Mirroring" on Apple devices,
no companion app on the sender side.

See `SPEC.md` for the full technical specification and phased roadmap.

## Status (Phase 4 in progress — TV currently unreachable, pipeline built & queued for test)

| Phase | Work | State |
|---|---|---|
| 1 | Gradle/NDK scaffold, TV manifest, standby UI, foreground service + locks, JNI stub | done ✓ (installed, IP screen + black mirror surface confirmed) |
| 2 | mDNS discovery via UxPlay's `mdnsd` backend | done ✓ (`UNIQ TV Airplay` browsable on Mac via `dns-sd -B _airplay._tcp`; TXT values match SPEC §5B) |
| 3 | Vendored UxPlay `lib/` + libplist + OpenSSL 1.1.1q (NDK thirdparty prefab → jniLibs), JNI bridge (`libairplayjni.so`) | done ✓ (RTSP live, pairing keypair in filesDir) |
| 3b | **Client test** | done ✓ — MacBook Pro *and* iPad connected: FairPlay pairing, H.264 negotiated (1920×1080 / 1440×1080+240px offset), NAL frames flowed, clean teardowns |
| 4 | Hardware video pipeline (`VideoDecoder.kt`: drop-oldest ring → hw MediaCodec → SurfaceView, CSD from in-band SPS/PPS, low-latency param, callback-driven output) | built ✓ — first live run decoded but dropped ~100% of frames (blocking drain); redesigned event-driven + explicit hw-decoder pick; **retest pending TV back online** |
| 5 | Low-latency audio pipeline (AAC-ELD -> AudioTrack) | |
| 6 | Hardening, edge cases, debug HUD | |

## Deviations from SPEC.md (intentional)

- **mDNS**: keep UxPlay's `lib/mdnsd` instead of JmDNS — one less dependency,
  TXT values battle-tested by UxPlay; Kotlin only acquires the MulticastLock.
- **ABI filters**: `armeabi-v7a` + `arm64-v8a` (primary target MiTV-AXSO0 is
  32-bit only). `x86_64` omitted until emulator testing is needed.
- **Decoder keys**: `KEY_LOW_LATENCY`/`KEY_PRIORITY` are encoder parameters and
  are ignored by MediaCodec decoders — removed; latency control stays in §6
  (bounded queue + latest-frame drop + immediate `releaseOutputBuffer`).
- **Codec is dynamic**: `video/hevc` when UxPlay's `video_set_codec_and_size`
  reports H265, otherwise `video/avc`. Audio CSD is derived from
  `audio_get_format`, not hard-coded.
- **Manifest**: added `FOREGROUND_SERVICE_MEDIA_PLAYBACK` (required for the
  `mediaPlayback` FGS type on modern Android).
- **compileSdk 35** (androidx.core 1.15.0 requires it); targetSdk remains 34.
- **UI libs**: no AppCompat/Leanback libraries — two plain Activities;
  TV behaviour comes from manifest attributes + 320x180 banner.

## License

GPL-3.0 (UxPlay and playfair are GPL-3.0). Personal use only unless you
honour the license terms. See `LICENSE` and `NOTICE` (added in Phase 3).

## Build

Requires: Android SDK (platform 34, build-tools 35), NDK 28.2.13676358,
JDK 17+ (this machine pins Temurin 21 via `gradle.properties`).

```sh
./gradlew :app:assembleDebug
```

## Install & run on the TV

```sh
adb connect 192.168.1.139:5555
adb -s 192.168.1.139:5555 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s 192.168.1.139:5555 shell am start -n com.uniq.airplay/.MainActivity
```

## Discoverability check (Phase 2 checkpoint)

From a Mac on the same network:

```sh
dns-sd -B _airplay._tcp
dns-sd -L "<TV Name>" _airplay._tcp
```

Then open Screen Mirroring on the Mac/iPad and look for the TV.
