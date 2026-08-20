package dev.screenrec.record.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioEncoderChunkTest {

    private val stereo16 = 4 // bytes per frame: 2 channels * 16-bit

    @Test
    fun neverExceedsTheBufferCapacity() {
        // The bug: 8192 bytes of PCM offered to a 4096-byte AAC input buffer.
        assertEquals(4_096, AudioEncoder.alignedChunk(available = 8_192, capacity = 4_096, bytesPerFrame = stereo16))
    }

    @Test
    fun takesEverythingWhenItFits() {
        assertEquals(2_048, AudioEncoder.alignedChunk(available = 2_048, capacity = 4_096, bytesPerFrame = stereo16))
    }

    @Test
    fun trimsToWholeFramesSoAFrameIsNeverSplit() {
        assertEquals(4_096, AudioEncoder.alignedChunk(available = 4_098, capacity = 4_098, bytesPerFrame = stereo16))
        assertEquals(4_092, AudioEncoder.alignedChunk(available = 4_095, capacity = 8_192, bytesPerFrame = stereo16))
    }

    @Test
    fun returnsZeroWhenNotEvenOneFrameFits() {
        assertEquals(0, AudioEncoder.alignedChunk(available = 3, capacity = 4_096, bytesPerFrame = stereo16))
        assertEquals(0, AudioEncoder.alignedChunk(available = 4_096, capacity = 2, bytesPerFrame = stereo16))
        assertEquals(0, AudioEncoder.alignedChunk(available = 0, capacity = 4_096, bytesPerFrame = stereo16))
    }

    @Test
    fun toleratesANonsenseFrameSize() {
        assertEquals(100, AudioEncoder.alignedChunk(available = 100, capacity = 4_096, bytesPerFrame = 0))
    }
}
