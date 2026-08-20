package dev.screenrec.record.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioPtsTest {

    /** 44100 Hz stereo 16-bit: one frame is 4 bytes. */
    private fun stereo() = AudioPts(sampleRate = 44_100, channelCount = 2)

    @Test
    fun firstBufferStartsAtZero() {
        assertEquals(0L, stereo().nextPtsUs(4_096))
    }

    @Test
    fun advancesByBufferDuration() {
        val pts = stereo()
        assertEquals(0L, pts.nextPtsUs(4_410 * 4)) // 4410 frames = exactly 100ms
        assertEquals(100_000L, pts.nextPtsUs(4_410 * 4))
        assertEquals(200_000L, pts.nextPtsUs(4_410 * 4))
    }

    @Test
    fun countsFramesNotBytes() {
        val pts = stereo()
        pts.nextPtsUs(44_100 * 4) // one full second of stereo frames
        assertEquals(44_100L, pts.framesWritten)
        assertEquals(1_000_000L, pts.nextPtsUs(4))
    }

    @Test
    fun monoUsesTwoBytesPerFrame() {
        val pts = AudioPts(sampleRate = 44_100, channelCount = 1)
        pts.nextPtsUs(44_100 * 2)
        assertEquals(1_000_000L, pts.nextPtsUs(2))
    }

    @Test
    fun aPausedGapProducesNoDiscontinuity() {
        val pts = stereo()
        assertEquals(0L, pts.nextPtsUs(4_410 * 4))
        assertEquals(100_000L, pts.nextPtsUs(4_410 * 4))
        assertEquals(200_000L, pts.nextPtsUs(4_410 * 4))
    }

    @Test
    fun ignoresTrailingPartialFrameBytes() {
        val pts = stereo()
        pts.nextPtsUs(4_410 * 4 + 3) // 3 stray bytes are not a whole frame
        assertEquals(4_410L, pts.framesWritten)
    }

    @Test
    fun resetReturnsToZero() {
        val pts = stereo()
        pts.nextPtsUs(44_100 * 4)
        pts.reset()
        assertEquals(0L, pts.framesWritten)
        assertEquals(0L, pts.nextPtsUs(4))
    }

    @Test
    fun currentPtsUsDoesNotAdvanceOnItsOwn() {
        val pts = stereo()
        assertEquals(0L, pts.currentPtsUs())
        assertEquals(0L, pts.currentPtsUs())
        pts.advance(4_410 * 4)
        assertEquals(100_000L, pts.currentPtsUs())
    }

    @Test
    fun advancingInChunksMatchesAdvancingAllAtOnce() {
        // One AudioRecord read may be split across several encoder input buffers; the
        // timestamps must come out identical either way.
        val chunked = stereo()
        repeat(4) { chunked.advance(1_102 * 4) }
        val whole = stereo()
        whole.advance(4_408 * 4)
        assertEquals(whole.currentPtsUs(), chunked.currentPtsUs())
        assertEquals(whole.framesWritten, chunked.framesWritten)
    }
}
