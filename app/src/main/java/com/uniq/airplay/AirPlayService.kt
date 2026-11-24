package com.uniq.airplay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.uniq.airplay.util.DeviceIdentity
import com.uniq.airplay.video.VideoDecoder
import java.io.File
import java.nio.ByteBuffer

/**
 * Keeps the AirPlay receiver alive (SPEC §5E):
 *  - foreground service (mediaPlayback) so the TV OS doesn't suspend it,
 *  - MulticastLock so mDNS advertisement works over Wi-Fi,
 *  - WifiLock (full / low-latency) while a client is streaming.
 *
 * Phase 3: starts the UxPlay core (RTSP + mDNS + FairPlay) and receives its
 * events. Video/audio callbacks are counted + throttled-logged for now;
 * the real pipelines land in Phases 4/5.
 */
class AirPlayService : Service(), AirPlayNative.Listener {

    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    // Phase 4: hardware video pipeline (reader thread -> ring -> MediaCodec -> SurfaceView).
    val videoDecoder = VideoDecoder()

    // Throttled counters for Phase-3 byte-flow verification (SPEC §7 Phase 3 checkpoint).
    private val videoFrames = java.util.concurrent.atomic.AtomicInteger()
    private val audioFrames = java.util.concurrent.atomic.AtomicInteger()

    override fun onCreate() {
        super.onCreate()
        instance = this
        videoDecoder.onOutputFormat = { w, h ->
            MirroringActivity.instance?.resizeSurfaceFor(w, h)
        }
        startAsForeground()
        acquireMulticastLock()
        AirPlayNative.setListener(this)
        startAirPlayServer()
        Log.i(TAG, "service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        AirPlayNative.stopServer()
        AirPlayNative.setListener(null)
        videoDecoder.shutdown()
        instance = null
        releaseStreamWifiLock()
        releaseMulticastLock()
        Log.i(TAG, "service destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------------
    // AirPlay server
    // ------------------------------------------------------------------

    private fun startAirPlayServer() {
        val name = getSharedPreferences("com.uniq.airplay", MODE_PRIVATE)
            .getString("broadcast_name", "UNIQ TV Airplay") ?: "UNIQ TV Airplay"
        val macHex = DeviceIdentity.stableMac(this)
        val keyfile = File(filesDir, "uxplay.pem").absolutePath
        val ok = AirPlayNative.startServer(name, hexToBytes(macHex), keyfile)
        setServerRunning(ok)
        Log.i(TAG, "AirPlay server ${if (ok) "started" else "FAILED to start"}")
    }

    private fun setServerRunning(running: Boolean) {
        mainHandler.post {
            serverRunning = running
            // Standby screen picks this up on next resume.
        }
    }

    // ------------------------------------------------------------------
    // AirPlayNative.Listener (called on UxPlay reader threads — keep light!)
    // ------------------------------------------------------------------

    override fun onClientConnected(deviceId: String, name: String) {
        Log.i(TAG, "client connected: $name ($deviceId)")
        acquireStreamWifiLock()
        mainHandler.post {
            startActivity(
                Intent(this, MirroringActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    override fun onClientDisconnected(reason: Int) {
        Log.i(TAG, "client disconnected (reason=$reason)")
        releaseStreamWifiLock()
        // MirroringActivity stays up on the last frame until BACK (SPEC §5F.2).
        videoDecoder.clearQueue()
    }

    override fun onVideoData(data: ByteBuffer, nalCount: Int, isH265: Boolean, ntpLocal: Long, ntpRemote: Long) {
        if (data.isDirect && data.remaining() > 0) {
            val n = videoFrames.incrementAndGet()
            if (n % 300 == 1) {
                Log.d(TAG, "video: ~$n frames so far (${data.remaining()}B, h265=$isH265, nal=$nalCount)")
            }
            // Phase 4: hand a copy to the decoder pipeline (UxPlay reuses the
            // native buffer after this call returns).
            val copy = ByteArray(data.remaining())
            data.get(copy)
            videoDecoder.enqueue(copy)
        }
    }

    override fun onAudioData(data: ByteBuffer, codecTag: Int, seqnum: Int, ntpLocal: Long, ntpRemote: Long) {
        val n = audioFrames.incrementAndGet()
        if (n % 300 == 1) {
            Log.d(TAG, "audio: ~$n packets so far (ct=$codecTag seq=$seqnum)")
        }
        // Phase 5: feed AudioDecoder queue here.
    }

    override fun onVolumeChanged(volume: Float) {
        Log.i(TAG, "volume $volume")
        // Phase 5: AudioTrack.setVolume mapping.
    }

    override fun onVideoCodecChanged(isH265: Boolean, width: Int, height: Int, xOffset: Int, yOffset: Int) {
        Log.i(TAG, "video codec: h265=$isH265 ${width}x${height} off=$xOffset,$yOffset")
        // Phase 4: the offsets matter when a client mirrors a sub-window
        // (iPad split view) — letterboxing the decoded window lands in Phase 6.
        videoDecoder.onCodecChanged(isH265, width, height)
    }

    override fun onVideoPauseResume(paused: Boolean) {
        Log.i(TAG, "video ${if (paused) "paused" else "resumed"}")
        videoDecoder.setPaused(paused)
    }

    override fun onVideoReset(reason: Int) {
        Log.i(TAG, "video reset (reason=$reason)")
        videoDecoder.flush()
    }

    // ------------------------------------------------------------------
    // Locks
    // ------------------------------------------------------------------

    private fun acquireMulticastLock() {
        if (multicastLock != null) return
        val wm = getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wm.createMulticastLock("com.uniq.airplay.mdns").apply {
            setReferenceCounted(true)
            acquire()
        }
        Log.i(TAG, "MulticastLock acquired")
    }

    private fun releaseMulticastLock() {
        multicastLock?.let {
            if (it.isHeld) it.release()
            Log.i(TAG, "MulticastLock released")
        }
        multicastLock = null
    }

    fun acquireStreamWifiLock() {
        if (wifiLock != null) return
        val wm = getSystemService(Context.WIFI_SERVICE) as WifiManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = wm.createWifiLock(mode, "com.uniq.airplay.stream").also { it.acquire() }
        Log.i(TAG, "stream WifiLock acquired")
    }

    fun releaseStreamWifiLock() {
        wifiLock?.let {
            if (it.isHeld) it.release()
            Log.i(TAG, "stream WifiLock released")
        }
        wifiLock = null
    }

    private fun startAsForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(SERVICE_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(SERVICE_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("AirPlay receiver active")
            .setSmallIcon(R.drawable.ic_stat_airplay)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "AirPlayService"
        private const val CHANNEL_ID = "airplay_service"
        private const val SERVICE_ID = 1

        /** Updated from the service thread; read by the standby screen. */
        @Volatile
        var serverRunning: Boolean = false

        /** Set in onCreate / cleared in onDestroy; read by MirroringActivity. */
        @Volatile
        var instance: AirPlayService? = null

        private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

        fun start(context: Context) {
            context.startForegroundService(Intent(context, AirPlayService::class.java))
        }

        private fun hexToBytes(hex: String): ByteArray =
            ByteArray(hex.length / 2) {
                val b = (Character.digit(hex[it * 2], 16) shl 4) + Character.digit(hex[it * 2 + 1], 16)
                b.toByte()
            }
    }
}
