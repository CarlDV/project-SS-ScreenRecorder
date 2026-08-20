package dev.screenrec.service

import org.junit.Assert.assertEquals
import org.junit.Test

class ElapsedTextTest {

    /**
     * The Android 16 status bar chip shows a short static string, not a live Chronometer, so
     * the text is formatted and re-posted once a second. Format follows One UI: no leading
     * zero on the first unit, hours only once they exist.
     */
    @Test
    fun formatsSecondsUnderAMinute() {
        assertEquals("0:00", ElapsedText.of(0L))
        assertEquals("0:07", ElapsedText.of(7_000L))
        assertEquals("0:59", ElapsedText.of(59_999L))
    }

    @Test
    fun formatsMinutesAndSeconds() {
        assertEquals("1:00", ElapsedText.of(60_000L))
        assertEquals("1:23", ElapsedText.of(83_400L))
        assertEquals("59:59", ElapsedText.of(3_599_000L))
    }

    @Test
    fun addsHoursOnlyOnceTheyExist() {
        assertEquals("1:00:00", ElapsedText.of(3_600_000L))
        assertEquals("1:02:03", ElapsedText.of(3_723_000L))
        assertEquals("12:00:00", ElapsedText.of(43_200_000L))
    }

    @Test
    fun truncatesRatherThanRounds() {
        // A counter that reads 0:01 when 1.9s have passed is right; 0:02 is a lie.
        assertEquals("0:01", ElapsedText.of(1_999L))
    }

    @Test
    fun treatsNegativeElapsedAsZero() {
        assertEquals("0:00", ElapsedText.of(-5_000L))
    }
}
