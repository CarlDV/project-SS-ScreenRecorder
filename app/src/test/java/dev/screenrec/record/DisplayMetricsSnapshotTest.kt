package dev.screenrec.record

import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayMetricsSnapshotTest {

    /**
     * The encoder's frame-rate hint should describe what the compositor will actually deliver.
     * A mirrored display pushes frames as fast as it refreshes, so a 30 hint on a 90Hz panel
     * makes the rate controller budget bitrate for half the frames it receives.
     */
    @Test
    fun tracksThePanelRefreshRateUpToTheEncodingCap() {
        assertEquals(60, DisplayMetricsSnapshot.frameRateFor(60f))
        assertEquals(60, DisplayMetricsSnapshot.frameRateFor(59.94f))
        assertEquals(30, DisplayMetricsSnapshot.frameRateFor(30f))
        assertEquals(48, DisplayMetricsSnapshot.frameRateFor(48f))
    }

    @Test
    fun capsAtSixtySoAMidRangeEncoderKeepsUp() {
        assertEquals(60, DisplayMetricsSnapshot.frameRateFor(90f))
        assertEquals(60, DisplayMetricsSnapshot.frameRateFor(120f))
        assertEquals(60, DisplayMetricsSnapshot.frameRateFor(144f))
    }

    @Test
    fun fallsBackToThirtyWhenTheDisplayReportsNonsense() {
        assertEquals(30, DisplayMetricsSnapshot.frameRateFor(0f))
        assertEquals(30, DisplayMetricsSnapshot.frameRateFor(-1f))
        assertEquals(30, DisplayMetricsSnapshot.frameRateFor(Float.NaN))
        // Nothing below 24 is worth calling a video.
        assertEquals(24, DisplayMetricsSnapshot.frameRateFor(10f))
    }
}
