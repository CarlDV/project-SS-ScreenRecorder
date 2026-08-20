package dev.screenrec.record.video

import dev.screenrec.record.QualityPreset
import org.junit.Assert.assertEquals
import org.junit.Test

class EncoderConfigFactoryTest {

    /** Hand-written stand-in for MediaCodecInfo.VideoCapabilities. */
    private class FakeCaps(
        override val widthAlignment: Int = 2,
        override val heightAlignment: Int = 2,
        override val supportedWidths: IntRange = 2..4096,
        private val heights: IntRange = 2..4096,
        private val supported: (Int, Int) -> Boolean = { _, _ -> true }
    ) : EncoderCapabilities {
        override fun supportedHeightsFor(width: Int): IntRange = heights
        override fun isSizeSupported(width: Int, height: Int): Boolean = supported(width, height)
    }

    // The A17's panel.
    private val dw = 1080
    private val dh = 2340

    @Test
    fun keepsNativeSizeAtTheMatchingPreset() {
        val spec = EncoderConfigFactory.create(dw, dh, QualityPreset.P1080, FakeCaps())
        assertEquals(1080, spec.width)
        assertEquals(2340, spec.height)
        assertEquals(12_000_000, spec.bitrate)
        assertEquals(30, spec.frameRate)
        assertEquals(1, spec.iFrameIntervalSeconds)
    }

    @Test
    fun scalesShortEdgeToPresetPreservingAspect() {
        val spec = EncoderConfigFactory.create(dw, dh, QualityPreset.P720, FakeCaps())
        assertEquals(720, spec.width)
        assertEquals(1560, spec.height)
        assertEquals(8_000_000, spec.bitrate)
    }

    @Test
    fun scalesTheShortEdgeInLandscapeToo() {
        val spec = EncoderConfigFactory.create(2340, 1080, QualityPreset.P720, FakeCaps())
        assertEquals(1560, spec.width)
        assertEquals(720, spec.height)
    }

    @Test
    fun neverUpscalesASmallerDisplay() {
        val spec = EncoderConfigFactory.create(720, 1560, QualityPreset.P1080, FakeCaps())
        assertEquals(720, spec.width)
        assertEquals(1560, spec.height)
        assertEquals(12_000_000, spec.bitrate)
    }

    @Test
    fun honoursTheAlignmentTheEncoderReports() {
        val spec = EncoderConfigFactory.create(dw, dh, QualityPreset.P1080, FakeCaps(16, 16))
        assertEquals(1072, spec.width) // 1080 -> largest multiple of 16 below
        assertEquals(2320, spec.height)
    }

    @Test
    fun clampsToTheEncodersMaximumWidthAndRecomputesHeight() {
        val spec = EncoderConfigFactory.create(
            dw, dh, QualityPreset.P1080, FakeCaps(supportedWidths = 2..1024)
        )
        assertEquals(1024, spec.width)
        assertEquals(2218, spec.height)
    }

    @Test
    fun clampsToTheEncodersMaximumHeightAndPullsWidthBackToMatch() {
        val spec = EncoderConfigFactory.create(
            dw, dh, QualityPreset.P1080, FakeCaps(heights = 2..2000)
        )
        assertEquals(922, spec.width)
        assertEquals(2000, spec.height)
    }

    @Test
    fun clampsUpToTheEncodersMinimumWidth() {
        val spec = EncoderConfigFactory.create(
            320, 640, QualityPreset.P480, FakeCaps(64, 64, supportedWidths = 640..4096)
        )
        assertEquals(640, spec.width)
        assertEquals(1280, spec.height)
    }

    @Test
    fun stepsDownUntilTheEncoderAcceptsTheSize() {
        val spec = EncoderConfigFactory.create(
            dw, dh, QualityPreset.P1080,
            FakeCaps(supported = { w, _ -> w <= 1000 })
        )
        assertEquals(1000, spec.width)
        assertEquals(2166, spec.height)
    }

    @Test
    fun mapsEveryPresetToItsBitrate() {
        val caps = FakeCaps()
        assertEquals(12_000_000, EncoderConfigFactory.create(dw, dh, QualityPreset.P1080, caps).bitrate)
        assertEquals(8_000_000, EncoderConfigFactory.create(dw, dh, QualityPreset.P720, caps).bitrate)
        assertEquals(4_000_000, EncoderConfigFactory.create(dw, dh, QualityPreset.P480, caps).bitrate)
    }

    @Test
    fun givesUpGracefullyWhenNothingIsSupported() {
        // Never throws: the controller retries at a lower preset and then reports an error.
        val spec = EncoderConfigFactory.create(
            dw, dh, QualityPreset.P480, FakeCaps(supported = { _, _ -> false })
        )
        assertEquals(4_000_000, spec.bitrate)
        assertEquals(0, spec.width % 2)
        assertEquals(0, spec.height % 2)
    }
}
