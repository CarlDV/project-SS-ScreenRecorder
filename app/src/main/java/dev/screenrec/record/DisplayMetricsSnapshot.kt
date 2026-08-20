package dev.screenrec.record

import android.content.Context
import android.view.Surface
import android.view.WindowManager

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
        fun from(context: Context): DisplayMetricsSnapshot {
            val wm = context.getSystemService(WindowManager::class.java)
            val bounds = wm.currentWindowMetrics.bounds
            val rotation = when (context.display?.rotation ?: Surface.ROTATION_0) {
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }
            return DisplayMetricsSnapshot(
                widthPx = bounds.width(),
                heightPx = bounds.height(),
                densityDpi = context.resources.configuration.densityDpi,
                rotationDegrees = rotation
            )
        }
    }
}
