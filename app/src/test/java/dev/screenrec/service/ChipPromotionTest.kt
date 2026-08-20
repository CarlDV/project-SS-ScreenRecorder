package dev.screenrec.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChipPromotionTest {

    /**
     * Android 16 has two mutually exclusive routes to the status bar chip, chosen by a
     * platform flag we cannot read: with ui_rich_ongoing on, a promoted notification must NOT
     * be colorized; with it off, colorization is exactly what qualifies it. So the app posts
     * the modern way, reads back whether the system promoted it, and switches once if not.
     */
    @Test
    fun startsWithTheModernNonColorizedForm() {
        assertFalse(ChipPromotion().colorized)
    }

    @Test
    fun switchesToColorizedWhenTheSystemDeclinesToPromote() {
        val p = ChipPromotion()
        assertTrue(p.onPostResult(promoted = false))
        assertTrue(p.colorized)
    }

    @Test
    fun staysPutWhenThePromotionSucceeded() {
        val p = ChipPromotion()
        assertFalse(p.onPostResult(promoted = true))
        assertFalse(p.colorized)
    }

    @Test
    fun givesUpAfterOneSwitchRatherThanOscillating() {
        val p = ChipPromotion()
        assertTrue(p.onPostResult(promoted = false))
        // Still not promoted on the colorized form: nothing left to try.
        assertFalse(p.onPostResult(promoted = false))
        assertFalse(p.onPostResult(promoted = false))
        assertTrue(p.colorized)
    }

    @Test
    fun keepsTheColorizedFormOnceItWorks() {
        val p = ChipPromotion()
        p.onPostResult(promoted = false)
        assertFalse(p.onPostResult(promoted = true))
        assertTrue(p.colorized)
    }
}
