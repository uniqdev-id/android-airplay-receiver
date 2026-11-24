package com.uniq.airplay

import android.util.Log
import java.nio.ByteBuffer

/**
 * JNI bridge to the UxPlay-based AirPlay core (libairplayjni.so).
 *
 * Events flow: UxPlay reader thread -> C++ callback -> [Listener] (set via
 * [setListener]). Keep listener callbacks cheap: video/audio paths run at
 * 60fps+ (SPEC §6.1) — queue synchronously, never block the reader thread.
 */
class AirPlayNative private constructor() {

    /** Event sink, implemented by [AirPlayService]. */
    interface Listener {
        fun onClientConnected(deviceId: String, name: String)
        fun onClientDisconnected(reason: Int)
        fun onVideoData(data: ByteBuffer, nalCount: Int, isH265: Boolean, ntpLocal: Long, ntpRemote: Long)
        fun onAudioData(data: ByteBuffer, codecTag: Int, seqnum: Int, ntpLocal: Long, ntpRemote: Long)
        fun onVolumeChanged(volume: Float)
        fun onVideoCodecChanged(isH265: Boolean, width: Int, height: Int, xOffset: Int, yOffset: Int)
        fun onVideoPauseResume(paused: Boolean)
        /** UxPlay reset_type_t (0=rtp_shutdown, 1=client_disconnect, 2=connection_lost), or -1 for flush. */
        fun onVideoReset(reason: Int)
    }

    init {
        try {
            System.loadLibrary("airplayjni")
        } catch (t: Throwable) {
            Log.w(TAG, "failed to load libairplayjni.so: ${t.message}")
        }
    }

    /** Starts RTSP + mDNS. [mac] is the stable 6-byte device identity. */
    external fun nativeStartServer(deviceName: String, mac: ByteArray, keyfilePath: String): Boolean

    external fun nativeStopServer()

    external fun nativeGetVersion(): String

    /** Registers the event sink (native global ref; null clears it). */
    external fun nativeSetListener(listener: Listener?)

    companion object {
        private const val TAG = "AirPlayNative"

        private val INSTANCE = AirPlayNative()

        /** Native library loaded and callable. */
        fun isAvailable(): Boolean =
            try {
                INSTANCE.nativeGetVersion()
                true
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "native core unavailable: ${e.message}")
                false
            }

        fun coreVersion(): String =
            try {
                INSTANCE.nativeGetVersion()
            } catch (e: UnsatisfiedLinkError) {
                "unavailable (native lib not loaded)"
            }

        fun setListener(listener: Listener?) {
            try {
                INSTANCE.nativeSetListener(listener)
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "setListener skipped: ${e.message}")
            }
        }

        fun startServer(deviceName: String, mac: ByteArray, keyfilePath: String): Boolean =
            try {
                INSTANCE.nativeStartServer(deviceName, mac, keyfilePath)
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "startServer failed: ${e.message}")
                false
            }

        fun stopServer() {
            try {
                INSTANCE.nativeStopServer()
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "stopServer skipped: ${e.message}")
            }
        }
    }
}
