package dev.screenrec.mux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PtsOffsetTrackerTest {

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
        assertEquals(100L, t.adjust(100L))
        t.pause(200L)
        t.resume(1_200L) // paused for 1000us
        assertEquals(300L, t.adjust(1_300L))
        assertEquals(1_000L, t.pausedTotalUs)
    }

    @Test
    fun accumulatesAcrossManyPauses() {
        val t = PtsOffsetTracker()
        t.pause(1_000L); t.resume(3_000L)   // +2000
        t.pause(5_000L); t.resume(5_500L)   // +500
        t.pause(9_000L); t.resume(19_000L)  // +10000
        assertEquals(12_500L, t.pausedTotalUs)
        assertEquals(7_500L, t.adjust(20_000L))
    }

    @Test
    fun clampsFramesThatArriveDuringAPause() {
        val t = PtsOffsetTracker()
        assertEquals(500L, t.adjust(500L))
        t.pause(600L)
        // A frame already in flight when the surface detached must not regress.
        assertEquals(500L, t.adjust(1_000L))
        assertEquals(500L, t.adjust(1_500L))
        t.resume(2_600L)
        assertEquals(700L, t.adjust(2_700L))
    }

    @Test
    fun neverRegressesUnderOutOfOrderInput() {
        val t = PtsOffsetTracker()
        assertEquals(1_000L, t.adjust(1_000L))
        assertEquals(1_000L, t.adjust(900L))
        assertEquals(1_100L, t.adjust(1_100L))
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
