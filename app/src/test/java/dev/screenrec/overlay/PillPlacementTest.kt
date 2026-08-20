package dev.screenrec.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PillPlacementTest {

    @Test
    fun freeTravelIsWhatIsLeftAfterTheViewAndBothMargins() {
        assertEquals(1080 - 200 - 16, PillPlacement.free(1080, 200, 8))
    }

    @Test
    fun clampKeepsThePillOnScreen() {
        assertEquals(0, PillPlacement.clamp(-40, 500))
        assertEquals(500, PillPlacement.clamp(9_000, 500))
        assertEquals(120, PillPlacement.clamp(120, 500))
    }

    /**
     * A pill wider than the space it has -- a narrow display, a large font scale -- leaves no
     * travel at all. Everything downstream has to survive that rather than divide by it.
     */
    @Test
    fun degenerateFreeSpacePinsToTheOrigin() {
        assertEquals(0, PillPlacement.clamp(300, 0))
        assertEquals(0, PillPlacement.clamp(300, -50))
        assertEquals(0f, PillPlacement.fractionOf(300, 0), 0f)
        assertEquals(0, PillPlacement.positionFor(0.5f, 0))
        assertEquals(0, PillPlacement.positionFor(0.5f, -50))
    }

    @Test
    fun fractionAndPositionRoundTrip() {
        val free = 880
        for (x in intArrayOf(0, 1, 137, 440, 879, 880)) {
            assertEquals(x, PillPlacement.positionFor(PillPlacement.fractionOf(x, free), free))
        }
    }

    @Test
    fun fractionsSaturateRatherThanEscape() {
        assertEquals(0f, PillPlacement.fractionOf(-10, 500), 0f)
        assertEquals(1f, PillPlacement.fractionOf(700, 500), 0f)
        assertEquals(0, PillPlacement.positionFor(-1f, 500))
        assertEquals(500, PillPlacement.positionFor(2f, 500))
    }

    /**
     * Rotation is the whole reason the position is stored as a fraction. A pill three quarters
     * of the way down a tall display belongs three quarters of the way down a short one, not
     * 1700px off the bottom of it.
     */
    @Test
    fun aRememberedPositionSurvivesRotation() {
        val portraitFree = PillPlacement.free(2340, 40, 8)
        val landscapeFree = PillPlacement.free(1080, 40, 8)
        val fraction = PillPlacement.fractionOf(Math.round(portraitFree * 0.75f), portraitFree)

        val landscapeY = PillPlacement.positionFor(fraction, landscapeFree)

        assertEquals(Math.round(landscapeFree * 0.75f), landscapeY)
        assertTrue(landscapeY <= landscapeFree)
    }

    @Test
    fun snapsToWhicheverSideIsNearer() {
        val free = 900
        assertEquals(0, PillPlacement.snapToNearerSide(0, free))
        assertEquals(0, PillPlacement.snapToNearerSide(449, free))
        assertEquals(free, PillPlacement.snapToNearerSide(451, free))
        assertEquals(free, PillPlacement.snapToNearerSide(900, free))
    }

    /** Dead centre goes left, deterministically, rather than depending on rounding. */
    @Test
    fun snapsLeftFromExactlyHalfway() {
        assertEquals(0, PillPlacement.snapToNearerSide(450, 900))
    }

    @Test
    fun snapSurvivesNoTravel() {
        assertEquals(0, PillPlacement.snapToNearerSide(0, 0))
        assertEquals(0, PillPlacement.snapToNearerSide(10, -20))
    }

    /** A tap that wobbles must still count as a tap, or Stop needs a perfectly still finger. */
    @Test
    fun onlyMovementBeyondTheSlopCountsAsADrag() {
        assertFalse(PillPlacement.isDrag(0f, 0f, 24))
        assertFalse(PillPlacement.isDrag(10f, 10f, 24))
        assertFalse(PillPlacement.isDrag(-24f, 0f, 24))
        assertTrue(PillPlacement.isDrag(25f, 0f, 24))
        assertTrue(PillPlacement.isDrag(0f, -30f, 24))
        assertTrue(PillPlacement.isDrag(-20f, 20f, 24))
    }
}
