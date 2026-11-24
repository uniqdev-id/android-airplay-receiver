package com.uniq.airplay.video

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

/**
 * Hardware video pipeline (SPEC Phase 4 / §5C):
 *
 *   UxPlay reader thread ─enqueue(ByteArray)─► bounded ring (drop-oldest)
 *   this pipeline's HandlerThread ─► MediaCodec (hw) ─► SurfaceView
 *
 * Queue discipline (SPEC §5C): a fixed-depth ring that drops the OLDEST
 * frame on overflow. For mirroring, one frame of latency matters far more
 * than P-frame continuity; the hw decoder error-skips the gap.
 *
 * Input feeding is event-driven (non-blocking dequeue on each ring notify);
 * output rendering is callback-driven (`onOutputBufferAvailable` → release
 * with render=true), so no thread ever busy-waits on the hw pipeline.
 *
 * Built against the compileSdk-35 platform API: `createDecoderByType`,
 * CSD via format byte-array keys, surface auto-render via
 * `releaseOutputBuffer(idx, true)`.
 */
class VideoDecoder {

    private val hThread = HandlerThread("VideoFeed").also { it.start() }
    private val handler = Handler(hThread.looper)
    // Dedicated dispatch thread for codec callbacks (output render + control).
    // The feed loop occupies its own thread, so callbacks must not queue behind it.
    private val cbThread = HandlerThread("VideoCodecCb").also { it.start() }
    private val cbHandler = Handler(cbThread.looper)

    // ------------------------------------------------------------------
    // Producer side (called on UxPlay reader threads)
    // ------------------------------------------------------------------

    /** Copy of one Annex-B frame (SPS/PPS in-band for AVC; VPS/SPS/PPS for HEVC). */
    @Synchronized
    fun enqueue(data: ByteArray) {
        if (data.isEmpty() || !running) return
        // Drop-oldest: if full, overwrite the oldest unconsumed slot.
        if (ringSize == RING_CAP) {
            ring[head % RING_CAP] = data
            drops += 1
        } else {
            ring[tail % RING_CAP] = data
            ringSize += 1
        }
        tail += 1
        notifyDecoder()
    }

    /** Codec changed (H.264<->H.265) or geometry changed: reconfigure on next keyframe. */
    @Synchronized
    fun onCodecChanged(isH265: Boolean, width: Int, height: Int) {
        pendingH265 = isH265
        pendingWidth = width
        pendingHeight = height
        // A reconfigure means queued frames of the old stream are useless.
        ringSize = 0
        head = 0
        tail = 0
        drops = 0
        needReconfigure = true
        notifyDecoder()
        Log.i(TAG, "codec changed: ${if (isH265) "hevc" else "avc"} ${width}x${height} (reconfigure queued)")
    }

    fun setPaused(paused: Boolean) {
        this.paused = paused
        notifyDecoder()
    }

    /** Client left / RTP shutdown (SPEC §5F.2): drain the queue, hold last frame. */
    @Synchronized
    fun clearQueue() {
        ringSize = 0
        head = 0
        tail = 0
        drops = 0
    }

    /** UxPlay video_reset / video_flush: flush the decoder (SPEC §5C reset handling). */
    fun flush() {
        cbHandler.post {
            try {
                codec?.flush()
            } catch (e: Exception) {
                Log.w(TAG, "flush: ${e.message}")
                needReconfigure = true
                notifyDecoder()
            }
        }
        clearQueue()
        Log.i(TAG, "decoder flushed")
    }

    // ------------------------------------------------------------------
    // Surface side (main thread, from MirroringActivity)
    // ------------------------------------------------------------------

    @Synchronized
    fun attachSurface(surface: Surface?) {
        this.surface = surface
        if (surface != null) {
            needReconfigure = true
            notifyDecoder()
        }
    }

    /** Aspect-fit the SurfaceView for the new output geometry (main thread). */
    @Volatile
    var onOutputFormat: ((Int, Int) -> Unit)? = null

    // ------------------------------------------------------------------
    // Lifecycle (service destroy)
    // ------------------------------------------------------------------

    fun shutdown() {
        running = false
        notifyDecoder()
        // runLoop observes `running` (waitFrames bails within ~50ms) and exits;
        // join gives it a half-second to release the codec cleanly.
        try {
            hThread.join(500)
        } catch (_: InterruptedException) {}
        hThread.quitSafely()
        // Callback thread: pending messages may reference the released codec;
        // they're all guarded, so just drain and quit.
        cbThread.quitSafely()
    }

    // ------------------------------------------------------------------
    // Decoder thread
    // ------------------------------------------------------------------

    private val cv = java.lang.Object()
    private var running = true
    @Volatile private var paused = false
    @Volatile private var surface: Surface? = null
    @Volatile private var codec: MediaCodec? = null
    private var pendingH265 = false
    private var pendingWidth = 1920
    private var pendingHeight = 1080
    @Volatile private var needReconfigure = true
    private var lastDropLogMs = 0L

    // Diagnostics: rendered-frame counter (bumped in the output callback).
    private val rendered = AtomicInteger()

    // Ring buffer (~1.5s @ 60fps 1080p)
    private val ring = Array(RING_CAP) { null as ByteArray? }
    private var head = 0
    private var tail = 0
    private var ringSize = 0   // guarded by `this`
    private var drops = 0      // guarded by `this`

    init {
        handler.post { runLoop() }
    }

    private fun notifyDecoder() {
        synchronized(cv) { cv.notify() }
    }

    private fun runLoop() {
        while (true) {
            waitFrames()
            if (!running) break
            if (needReconfigure || codec == null) {
                val hadSurface = reconfigure()
                if (hadSurface) needReconfigure = false
            }
            if (codec == null || paused) continue
            feedOnly()
        }
        releaseCodec()
        Log.i(TAG, "decoder thread exiting")
    }

    private fun waitFrames() {
        while (running) {
            synchronized(this) {
                if (ringSize > 0) return
            }
            synchronized(cv) {
                try { cv.wait(50L) } catch (_: InterruptedException) {}
            }
        }
    }

    /** @return true when a codec is live (false = no surface yet, retry soon). */
    private fun reconfigure(): Boolean {
        releaseCodec()
        val s = surface ?: return false
        val mime = if (pendingH265) "video/hevc" else "video/avc"
        rendered.set(0)

        // CSD derived from the oldest queued keyframe (in-band SPS/PPS/VPS).
        // Peek only — the frame stays in the ring for feeding.
        val csd = peekFirstFrame()?.let { peekCsd(it, pendingH265) }

        val format = MediaFormat.createVideoFormat(mime, pendingWidth, pendingHeight).apply {
            csd?.forEach { (key, bytes) -> setByteBuffer(key, ByteBuffer.wrap(bytes)) }
        }

        val c = try {
            pickDecoder(mime)
        } catch (e: IOException) {
            Log.e(TAG, "no hw decoder for $mime: ${e.message}")
            return false
        }

        val cb = object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                // Input is driven manually from the ring (feedOnly); no-op here.
            }

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                val render = info.size > 0 &&
                    (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                try {
                    codec.releaseOutputBuffer(index, render)
                } catch (e: IllegalStateException) {
                    // Codec released between callback dispatch and execution.
                    return
                }
                if (render) {
                    val n = rendered.incrementAndGet()
                    if (n == 1) {
                        Log.i(TAG, "first frame rendered to surface")
                    } else if (n % 300 == 1) {
                        Log.d(TAG, "rendered ~$n frames")
                    }
                }
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                val w = format.getInteger(MediaFormat.KEY_WIDTH)
                val h = format.getInteger(MediaFormat.KEY_HEIGHT)
                Log.i(TAG, "output format: ${w}x$h")
                if (w > 0 && h > 0) {
                    onOutputFormat?.let { outer ->
                        Handler(Looper.getMainLooper()).post { outer(w, h) }
                    }
                }
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                Log.e(TAG, "codec error: ${e.message}")
                needReconfigure = true
                notifyDecoder()
            }
        }
        c.setCallback(cb, cbHandler)

        try {
            c.configure(format, s, null, 0)
            c.start()
        } catch (e: Exception) {
            Log.e(TAG, "configure $mime failed: ${e.message}")
            try { c.release() } catch (_: Exception) {}
            return false
        }
        codec = c
        Log.i(TAG, "$mime ${pendingWidth}x${pendingHeight} started" +
            (csd?.get("csd-0")?.let { " (csd-0 ${it.size}B)" } ?: ""))

        // Best-effort low-latency mode (experimental API; not supported on all codecs).
        try {
            c.setParameters(android.os.Bundle().apply {
                putBoolean(MediaFormat.KEY_LOW_LATENCY, true)
            })
            Log.i(TAG, "low-latency mode enabled")
        } catch (e: Exception) {
            Log.w(TAG, "low-latency unsupported: ${e.message}")
        }

        // Runs on the main thread: resizes the SurfaceView for aspect-fit.
        onOutputFormat?.let { cb2 ->
            Handler(Looper.getMainLooper()).post { cb2(pendingWidth, pendingHeight) }
        }
        return true
    }

    /**
     * Pick a hardware-accelerated decoder for [mime], preferring the
     * platform's preferred choice; fall back to any supporting decoder.
     * A software fallback would ruin mirroring latency, so prefer hw hard.
     */
    private fun pickDecoder(mime: String): MediaCodec {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val preferred = list.findDecoderForFormat(
            MediaFormat.createVideoFormat(mime, pendingWidth, pendingHeight)
        )
        val infos = list.getCodecInfos()
        fun supports(info: android.media.MediaCodecInfo) =
            info.getSupportedTypes()?.contains(mime) == true
        val hw = infos.filter { info -> info.isHardwareAccelerated() && supports(info) }
        val pick =
            (hw.firstOrNull { info -> info.getName() == preferred || info.getCanonicalName() == preferred }
                ?: hw.firstOrNull()
                ?: infos.firstOrNull { supports(it) }
                ?: throw IOException("no decoder for $mime"))
        Log.i(TAG, "decoder picked: ${pick.getName()} (hw=${pick.isHardwareAccelerated()}, preferred=$preferred)")
        return MediaCodec.createByCodecName(pick.getName())
    }

    /** Non-blocking: enqueue whatever input buffers + ring frames are available now. */
    private fun feedOnly() {
        val c = codec ?: return
        while (true) {
            val inIdx = c.dequeueInputBuffer(0)
            if (inIdx < 0) break
            val frame = takeFrame() ?: break
            val buf = c.getInputBuffer(inIdx) ?: continue
            buf.clear()
            buf.put(frame)
            c.queueInputBuffer(inIdx, 0, frame.size, System.nanoTime() / 1000, 0)
        }
        if (drops > 0 && System.currentTimeMillis() - lastDropLogMs > 5000) {
            lastDropLogMs = System.currentTimeMillis()
            Log.d(TAG, "queue drops since last log: $drops (rendered=${rendered.get()})")
            drops = 0
        }
    }

    private fun releaseCodec() {
        try {
            codec?.let {
                try { it.stop() } catch (_: Exception) {}
                it.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "codec release: ${e.message}")
        }
        codec = null
    }

    @Synchronized
    private fun takeFrame(): ByteArray? {
        if (ringSize == 0) return null
        val f = ring[head % RING_CAP]
        head += 1
        ringSize -= 1
        ring[(head - 1) % RING_CAP] = null
        return f
    }

    /** Peek (don't consume) the oldest frame, or null. */
    @Synchronized
    private fun peekFirstFrame(): ByteArray? =
        if (ringSize == 0) null else ring[head % RING_CAP]

    /**
     * Pull parameter-set NALs out of an Annex-B frame and map them onto CSD
     * format keys. UxPlay delivers SPS/PPS in-band on keyframes, so this is
     * a best-effort optimization for codec config robustness.
     *  H.264: "csd-0" = SPS+PPS · H.265: "csd-0" = VPS, "csd-1" = SPS, "csd-2" = PPS
     * A NAL payload ends at the next start code (00 00 01 / 00 00 00 01),
     * not at the first zero byte — SPS payloads routinely contain zeros.
     */
    private fun peekCsd(annexB: ByteArray?, h265: Boolean): Map<String, ByteArray>? {
        if (annexB == null) return null
        val avc = ByteArrayOutputStream()
        val hevcVps = ByteArrayOutputStream()
        val hevcSps = ByteArrayOutputStream()
        val hevcPps = ByteArrayOutputStream()
        var i = 0
        while (i + 2 < annexB.size) {
            if (annexB[i] != 0.toByte() || annexB[i + 1] != 0.toByte()) {
                i++
                continue
            }
            // Zero run found; the payload starts after it (00 00 01 / 00 00 00 01).
            var zeros = i
            while (zeros < annexB.size && annexB[zeros] == 0.toByte()) zeros++
            val payloadStart = zeros
            if (payloadStart >= annexB.size) break
            val b0 = annexB[payloadStart].toInt() and 0xFF
            val nalType = if (h265) (b0 shr 2) and 0x3F else b0 and 0x1F
            val keep: ByteArrayOutputStream? = when (nalType) {
                7, 8 -> avc
                32 -> hevcVps
                33 -> hevcSps
                34 -> hevcPps
                else -> null
            }
            if (keep != null) {
                // NAL payload ends at the next start code, not the first zero.
                var p = payloadStart + 1
                while (p + 1 < annexB.size &&
                    !(annexB[p] == 0.toByte() && annexB[p + 1] == 0.toByte())) p++
                val payloadEnd = p
                keep.write(0); keep.write(0); keep.write(1)
                keep.write(annexB, payloadStart, payloadEnd - payloadStart)
            }
            i = payloadStart + 1
        }
        val result = LinkedHashMap<String, ByteArray>()
        if (h265) {
            if (hevcVps.size() > 0) result["csd-0"] = hevcVps.toByteArray()
            if (hevcSps.size() > 0) result["csd-1"] = hevcSps.toByteArray()
            if (hevcPps.size() > 0) result["csd-2"] = hevcPps.toByteArray()
        } else {
            if (avc.size() > 0) result["csd-0"] = avc.toByteArray()
        }
        return result.ifEmpty { null }
    }

    companion object {
        private const val TAG = "VideoDecoder"
        private const val RING_CAP = 90
    }
}
