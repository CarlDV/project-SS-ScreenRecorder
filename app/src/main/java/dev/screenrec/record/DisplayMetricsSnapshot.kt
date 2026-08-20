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
    val rotationDegrees: Int
) {
    companion object {

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
                rotationDegrees = rotationDegrees(display.rotation)
            )
        }

        /** Surface.ROTATION_* to degrees; anything unrecognised is treated as upright. */
        fun rotationDegrees(surfaceRotation: Int): Int = when (surfaceRotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }
}
