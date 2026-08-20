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
}
