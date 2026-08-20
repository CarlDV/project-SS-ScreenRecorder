package dev.screenrec.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PillMorphTest {

    @Test
    fun sizeInterpolatesBetweenTheTwoStates() {
        assertEquals(34f, PillMorph.size(34f, 174f, 0f), 0f)
        assertEquals(174f, PillMorph.size(34f, 174f, 1f), 0f)
        assertEquals(104f, PillMorph.size(34f, 174f, 0.5f), 0.001f)
    }

    /**
     * An interpolator can overshoot, and a size animator can be read one frame after it was
     * cancelled. Neither may produce a pill wider than it was measured for.
     */
    @Test
    fun sizeIsPinnedToTheEndsOutsideTheRange() {
        assertEquals(34f, PillMorph.size(34f, 174f, -0.4f), 0f)
        assertEquals(174f, PillMorph.size(34f, 174f, 1.3f), 0f)
    }

    @Test
    fun sizeShrinksJustAsWellAsItGrows() {
        assertEquals(174f, PillMorph.size(174f, 34f, 0f), 0f)
        assertEquals(34f, PillMorph.size(174f, 34f, 1f), 0f)
    }

    /** Nothing but the record dot is drawn while the pill is still too small to hold it. */
    @Test
    fun contentStaysHiddenUntilThePillHasRoomForIt() {
        assertEquals(0f, PillMorph.contentAlpha(0f), 0f)
        assertEquals(0f, PillMorph.contentAlpha(PillMorph.CONTENT_FADE_START), 0f)
        assertEquals(0f, PillMorph.contentAlpha(PillMorph.CONTENT_FADE_START - 0.01f), 0f)
    }

    @Test
    fun contentIsFullyOpaqueOnlyWhenTheMorphIsDone() {
        assertEquals(1f, PillMorph.contentAlpha(1f), 0f)
        assertTrue(PillMorph.contentAlpha(0.99f) < 1f)
    }

    @Test
    fun contentAlphaRisesMonotonicallyAndStaysInRange() {
        var previous = -1f
        var step = 0
        while (step <= 100) {
            val alpha = PillMorph.contentAlpha(step / 100f)
            assertTrue("alpha $alpha out of range at $step", alpha in 0f..1f)
            assertTrue("alpha fell at $step", alpha >= previous)
            previous = alpha
            step++
        }
    }

    @Test
    fun contentAlphaSaturatesOutsideTheRange() {
        assertEquals(0f, PillMorph.contentAlpha(-2f), 0f)
        assertEquals(1f, PillMorph.contentAlpha(2f), 0f)
    }
}
