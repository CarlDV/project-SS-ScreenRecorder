package dev.screenrec.mux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PtsOffsetTrackerTest {

    /**
     * The capture surface stamps frames with CLOCK_MONOTONIC -- time since boot -- so on a
     * device up for twelve hours the first frame arrives with a PTS of twelve hours. Written
     * to the container unchanged that becomes the file's duration, and it desyncs the audio,
     * whose own timestamps start at zero. The first frame must land on zero.
     */
    @Test
    fun rebasesTheFirstFrameToZero() {
        val t = PtsOffsetTracker()
        val uptimeUs = 43_374_000_000L // ~12h04m, the value that produced a 12:02:54 file
        assertEquals(0L, t.adjust(uptimeUs))
        assertEquals(33_000L, t.adjust(uptimeUs + 33_000L))
        assertEquals(66_000L, t.adjust(uptimeUs + 66_000L))
    }

    @Test
    fun passesTimestampsThroughUntouchedWhenNeverPaused() {
        val t = PtsOffsetTracker()
        assertEquals(0L, t.adjust(0L))
        assertEquals(33_000L, t.adjust(33_000L))
        assertEquals(66_000L, t.adjust(66_000L))
    }

    @Test
    fun subtractsASinglePausedSpan() {
        val t = PtsOffsetTracker()
        assertEquals(0L, t.adjust(100L)) // base
        t.pause(200L)
        t.resume(1_200L) // paused for 1000us
        assertEquals(200L, t.adjust(1_300L)) // 1300 - 100 base - 1000 paused
        assertEquals(1_000L, t.pausedTotalUs)
    }

    @Test
    fun accumulatesAcrossManyPauses() {
        val t = PtsOffsetTracker()
        assertEquals(0L, t.adjust(0L))
        t.pause(1_000L); t.resume(3_000L)   // +2000
        t.pause(5_000L); t.resume(5_500L)   // +500
        t.pause(9_000L); t.resume(19_000L)  // +10000
        assertEquals(12_500L, t.pausedTotalUs)
        assertEquals(7_500L, t.adjust(20_000L))
    }

    @Test
    fun clampsFramesThatArriveDuringAPause() {
        val t = PtsOffsetTracker()
        assertEquals(0L, t.adjust(500L))
        t.pause(600L)
        // A frame already in flight when the surface detached must not regress.
        assertEquals(0L, t.adjust(1_000L))
        assertEquals(0L, t.adjust(1_500L))
        t.resume(2_600L)
        assertEquals(200L, t.adjust(2_700L)) // 2700 - 500 base - 2000 paused
    }

    @Test
    fun neverRegressesUnderOutOfOrderInput() {
        val t = PtsOffsetTracker()
        assertEquals(0L, t.adjust(1_000L))
        assertEquals(0L, t.adjust(900L))
        assertEquals(100L, t.adjust(1_100L))
    }

    @Test
    fun neverEmitsANegativeTimestamp() {
        val t = PtsOffsetTracker()
        // Pause bookkeeping that precedes the first frame must not push it below zero.
        t.pause(1_000L)
        t.resume(5_000L)
        assertEquals(0L, t.adjust(6_000L))
    }

    @Test
    fun ignoresRedundantPauseAndResumeCalls() {
        val t = PtsOffsetTracker()
        t.pause(100L)
        t.pause(400L) // ignored; span still starts at 100
        assertTrue(t.isPaused)
        t.resume(600L)
        t.resume(900L) // ignored
        assertFalse(t.isPaused)
        assertEquals(500L, t.pausedTotalUs)
    }
}
