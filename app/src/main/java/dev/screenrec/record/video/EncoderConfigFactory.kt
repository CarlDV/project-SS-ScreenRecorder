package dev.screenrec.record.video

import dev.screenrec.record.QualityPreset
import dev.screenrec.record.VideoFormatSpec

/**
 * Turns a display size and a quality preset into a size the encoder will actually accept.
 */
object EncoderConfigFactory {

    private const val STEP_PX = 16
    private const val MAX_ATTEMPTS = 32

    fun create(
        displayWidth: Int,
        displayHeight: Int,
        preset: QualityPreset,
        caps: EncoderCapabilities,
        frameRate: Int = 30
    ): VideoFormatSpec {
        val shortEdge = minOf(displayWidth, displayHeight)
        var targetShort = minOf(preset.shortEdge, shortEdge) // never upscale
        var size = sizeFor(displayWidth, displayHeight, targetShort, caps)
        var attempts = 0
        while (!caps.isSizeSupported(size.first, size.second) && attempts < MAX_ATTEMPTS) {
            targetShort -= STEP_PX
            if (targetShort < STEP_PX) break
            size = sizeFor(displayWidth, displayHeight, targetShort, caps)
            attempts++
        }
        return VideoFormatSpec(size.first, size.second, preset.bitrate, frameRate)
    }

    private fun sizeFor(
        displayWidth: Int,
        displayHeight: Int,
        targetShort: Int,
        caps: EncoderCapabilities
    ): Pair<Int, Int> {
        val shortEdge = minOf(displayWidth, displayHeight)
        val width = fit(
            scaled(displayWidth, targetShort, shortEdge),
            caps.supportedWidths,
            caps.widthAlignment
        )
        val heightRange = caps.supportedHeightsFor(width)
        val wantedHeight = scaled(displayHeight, width, displayWidth)
        val clampedHeight = wantedHeight.coerceIn(heightRange.first, heightRange.last)
        val height = fit(clampedHeight, heightRange, caps.heightAlignment)
        if (clampedHeight == wantedHeight) return width to height
        val correctedWidth = fit(
            scaled(displayWidth, height, displayHeight),
            caps.supportedWidths,
            caps.widthAlignment
        )
        return correctedWidth to height
    }

    private fun scaled(value: Int, numerator: Int, denominator: Int): Int =
        ((value.toLong() * numerator + denominator / 2) / denominator).toInt()

    private fun fit(value: Int, range: IntRange, alignment: Int): Int {
        val step = alignment.coerceAtLeast(1)
        val clamped = value.coerceIn(range.first, range.last)
        val alignedDown = clamped / step * step
        if (alignedDown >= range.first) return alignedDown
        val alignedUp = (range.first + step - 1) / step * step
        return alignedUp.coerceAtMost(range.last)
    }
}
