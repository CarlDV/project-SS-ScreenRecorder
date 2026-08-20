package dev.screenrec.record

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display
import android.view.Surface

/**
 * The display facts a recording needs, captured once at start. Rotation is frozen here on
 * purpose: a live MediaCodec stream cannot be resized, so v1 records at the orientation it
 * began in.
 */
data class DisplayMetricsSnapshot(
    val widthPx: Int,
    val heightPx: Int,
    val densityDpi: Int,
    val rotationDegrees: Int,
    val frameRate: Int
) {
    companion object {

        /** Encoding 1080p above this on a mid-range SoC costs more than it returns. */
        private const val MAX_FRAME_RATE = 60
        private const val MIN_FRAME_RATE = 24
        private const val DEFAULT_FRAME_RATE = 30

        /**
         * Reads the display through DisplayManager rather than Context.getDisplay(). The
         * caller here is a Service, whose Context is CONTEXT_TYPE_NON_UI, and
         * Context.getDisplay() throws UnsupportedOperationException for any context that is
         * not visual -- Activity, window or display context. DisplayManager works from any
         * context, and getRealMetrics gives the true panel size rather than the app's window
         * bounds, which is what a screen mirror needs. Deprecated since API 31 but the
         * replacements are all window-based and so unavailable here.
         */
        fun from(context: Context): DisplayMetricsSnapshot {
            val display = context.getSystemService(DisplayManager::class.java)
                .getDisplay(Display.DEFAULT_DISPLAY)
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
            return DisplayMetricsSnapshot(
                widthPx = metrics.widthPixels,
                heightPx = metrics.heightPixels,
                densityDpi = metrics.densityDpi,
                rotationDegrees = rotationDegrees(display.rotation),
                frameRate = frameRateFor(display.refreshRate)
            )
        }

        /** Surface.ROTATION_* to degrees; anything unrecognised is treated as upright. */
        fun rotationDegrees(surfaceRotation: Int): Int = when (surfaceRotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }

        /**
         * A mirrored VirtualDisplay pushes a frame whenever the screen changes, up to the
         * panel's refresh rate -- so the encoder's frame-rate hint should describe that, not a
         * fixed 30. The hint does not throttle anything; it tells the rate controller how many
         * frames the bitrate has to cover, and setting it too low makes it budget for half the
         * frames it actually receives.
         */
        fun frameRateFor(refreshRateHz: Float): Int {
            if (refreshRateHz.isNaN() || refreshRateHz <= 0f) return DEFAULT_FRAME_RATE
            return Math.round(refreshRateHz).coerceIn(MIN_FRAME_RATE, MAX_FRAME_RATE)
        }
    }
}
