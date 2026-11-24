package com.uniq.airplay

import android.app.Activity
import android.graphics.PixelFormat
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * Fullscreen mirroring surface (SPEC §5F.2): edge-to-edge black background,
 * aspect-ratio-fitted [SurfaceView], screensaver disabled, BACK returns to
 * the standby screen.
 *
 * Phase 4: the [SurfaceView]'s Surface is handed to the service's
 * [com.uniq.airplay.video.VideoDecoder]; frames arrive zero-copy from the
 * hardware MediaCodec.
 */
class MirroringActivity : Activity() {

    private lateinit var surfaceView: SurfaceView

    /** Live decoder output surface, or null when not attached. */
    var currentSurface: Surface? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_mirroring)

        surfaceView = findViewById(R.id.mirror_surface)
        // Pixel format for MediaCodec output (opaque, no alpha).
        surfaceView.holder.setFormat(PixelFormat.RGBX_8888)
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                currentSurface = holder.surface
                AirPlayService.instance?.videoDecoder?.attachSurface(holder.surface)
                Log.i(TAG, "mirror surface attached")
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                Log.d(TAG, "mirror surface ${width}x${height}")
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                // Detach before clearing: the decoder releases its reference to
                // the Surface, then reconfigures for the next client session.
                AirPlayService.instance?.videoDecoder?.attachSurface(null)
                currentSurface = null
                Log.i(TAG, "mirror surface detached")
            }
        })
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /**
     * Aspect-fit the surface for the decoded geometry (main thread, called by
     * the decoder pipeline on codec geometry change / INFO_OUTPUT_FORMAT_CHANGED).
     */
    fun resizeSurfaceFor(contentW: Int, contentH: Int) {
        if (contentW <= 0 || contentH <= 0) return
        val dm = resources.displayMetrics
        val scale = minOf(
            dm.widthPixels.toFloat() / contentW,
            dm.heightPixels.toFloat() / contentH
        )
        val w = (contentW * scale).toInt()
        val h = (contentH * scale).toInt()
        val lp = surfaceView.layoutParams as? android.view.ViewGroup.LayoutParams
            ?: android.view.ViewGroup.MarginLayoutParams(w, h)
        lp.width = w
        lp.height = h
        (lp as? FrameLayout.LayoutParams)?.gravity = Gravity.CENTER
        surfaceView.layoutParams = lp
        surfaceView.requestLayout()
        Log.d(TAG, "surface fit ${contentW}x${contentH} -> ${w}x${h}")
    }

    companion object {
        private const val TAG = "MirroringActivity"

        @Volatile
        var instance: MirroringActivity? = null
    }
}
