package dev.screenrec.record.video

import android.media.MediaCodecInfo

/**
 * The slice of MediaCodecInfo.VideoCapabilities the size negotiation needs, behind an
 * interface so the negotiation is JVM-testable without a device.
 */
interface EncoderCapabilities {
    val widthAlignment: Int
    val heightAlignment: Int
    val supportedWidths: IntRange
    fun supportedHeightsFor(width: Int): IntRange
    fun isSizeSupported(width: Int, height: Int): Boolean
}

/** Adapter over the real platform capabilities. */
class PlatformEncoderCapabilities(
    private val caps: MediaCodecInfo.VideoCapabilities
) : EncoderCapabilities {

    override val widthAlignment: Int get() = caps.widthAlignment
    override val heightAlignment: Int get() = caps.heightAlignment

    override val supportedWidths: IntRange
        get() = caps.supportedWidths.lower..caps.supportedWidths.upper

    override fun supportedHeightsFor(width: Int): IntRange =
        try {
            val r = caps.getSupportedHeightsFor(width)
            r.lower..r.upper
        } catch (e: IllegalArgumentException) {
            caps.supportedHeights.lower..caps.supportedHeights.upper
        }

    override fun isSizeSupported(width: Int, height: Int): Boolean =
        try {
            caps.isSizeSupported(width, height)
        } catch (e: IllegalArgumentException) {
            false
        }
}
