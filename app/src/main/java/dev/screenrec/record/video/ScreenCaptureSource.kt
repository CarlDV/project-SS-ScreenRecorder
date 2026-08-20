package dev.screenrec.record.video

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.view.Surface

/**
 * MediaProjection plus the VirtualDisplay that mirrors the screen into the encoder surface.
 *
 * Ordering rule from the platform: the callback must be registered before
 * createVirtualDisplay, and on API 34+ the foreground service must already be running with
 * type mediaProjection before the projection is obtained at all.
 */
class ScreenCaptureSource(private val projection: MediaProjection) {

    private val handler = Handler(Looper.getMainLooper())
    private var virtualDisplay: VirtualDisplay? = null
    private var callback: MediaProjection.Callback? = null

    fun start(
        width: Int,
        height: Int,
        densityDpi: Int,
        surface: Surface,
        onStopped: () -> Unit
    ) {
        val cb = object : MediaProjection.Callback() {
            override fun onStop() = onStopped()
        }
        projection.registerCallback(cb, handler)
        callback = cb
        virtualDisplay = projection.createVirtualDisplay(
            VIRTUAL_DISPLAY_NAME,
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            null,
            handler
        )
    }

    /** Detaching the surface stops frames without tearing the session down. */
    fun pause() {
        virtualDisplay?.setSurface(null)
    }

    fun resume(surface: Surface) {
        virtualDisplay?.setSurface(surface)
    }

    /**
     * Releases the virtual display and the callback but deliberately does NOT stop the
     * projection: RecorderService obtained the token and is the only thing that can guarantee
     * it is stopped on every exit path, including the ones where this class was never built.
     */
    fun release() {
        virtualDisplay?.release()
        virtualDisplay = null
        callback?.let { projection.unregisterCallback(it) }
        callback = null
    }

    private companion object {
        const val VIRTUAL_DISPLAY_NAME = "screenrec"
    }
}
