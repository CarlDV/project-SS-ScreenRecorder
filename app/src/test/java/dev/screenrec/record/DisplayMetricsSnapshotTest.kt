package dev.screenrec.record

import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayMetricsSnapshotTest {

    /**
     * Surface.ROTATION_* are plain int constants (0..3), so the mapping is JVM-testable even
     * though the surrounding display lookup is not.
     */
    @Test
    fun mapsEverySurfaceRotationToDegrees() {
        assertEquals(0, DisplayMetricsSnapshot.rotationDegrees(0))   // ROTATION_0
        assertEquals(90, DisplayMetricsSnapshot.rotationDegrees(1))  // ROTATION_90
        assertEquals(180, DisplayMetricsSnapshot.rotationDegrees(2)) // ROTATION_180
        assertEquals(270, DisplayMetricsSnapshot.rotationDegrees(3)) // ROTATION_270
    }

    @Test
    fun treatsAnUnknownRotationAsUpright() {
        assertEquals(0, DisplayMetricsSnapshot.rotationDegrees(-1))
        assertEquals(0, DisplayMetricsSnapshot.rotationDegrees(4))
    }

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
