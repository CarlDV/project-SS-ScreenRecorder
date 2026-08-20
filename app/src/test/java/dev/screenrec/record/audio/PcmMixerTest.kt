package dev.screenrec.record.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PcmMixerTest {

    /** Little-endian 16-bit PCM, the only format this pipeline uses. */
    private fun pcm(vararg samples: Int): ByteArray {
        val out = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, s ->
            out[i * 2] = (s and 0xFF).toByte()
            out[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun samplesOf(bytes: ByteArray, len: Int): IntArray =
        IntArray(len / 2) { i ->
            ((bytes[i * 2].toInt() and 0xFF) or (bytes[i * 2 + 1].toInt() shl 8)).toShort().toInt()
        }

    @Test
    fun sumsSamples() {
        val a = pcm(100, -200, 3000)
        val b = pcm(50, -50, -1000)
        val out = ByteArray(6)
        assertEquals(6, PcmMixer.mix(a, 6, b, 6, out))
        assertArrayEquals(intArrayOf(150, -250, 2000), samplesOf(out, 6))
    }

    @Test
    fun saturatesAtThePositiveRail() {
        val out = ByteArray(2)
        PcmMixer.mix(pcm(30_000), 2, pcm(30_000), 2, out)
        assertArrayEquals(intArrayOf(32_767), samplesOf(out, 2))
    }

    @Test
    fun saturatesAtTheNegativeRail() {
        val out = ByteArray(2)
        PcmMixer.mix(pcm(-30_000), 2, pcm(-30_000), 2, out)
        assertArrayEquals(intArrayOf(-32_768), samplesOf(out, 2))
    }

    @Test
    fun treatsTheShorterStreamAsSilencePastItsEnd() {
        val a = pcm(100, 200, 300, 400)
        val b = pcm(10, 20)
        val out = ByteArray(8)
        assertEquals(8, PcmMixer.mix(a, 8, b, 4, out))
        assertArrayEquals(intArrayOf(110, 220, 300, 400), samplesOf(out, 8))
    }

    @Test
    fun handlesTheLongerStreamInEitherPosition() {
        val out = ByteArray(8)
        assertEquals(8, PcmMixer.mix(pcm(10, 20), 4, pcm(100, 200, 300, 400), 8, out))
        assertArrayEquals(intArrayOf(110, 220, 300, 400), samplesOf(out, 8))
    }

    @Test
    fun mixingWithAnEmptyStreamCopiesTheOther() {
        val out = ByteArray(4)
        assertEquals(4, PcmMixer.mix(pcm(7, -7), 4, ByteArray(0), 0, out))
        assertArrayEquals(intArrayOf(7, -7), samplesOf(out, 4))
    }

    @Test
    fun ignoresAStrayOddTrailingByte() {
        val out = ByteArray(4)
        // 3 readable bytes is one whole sample plus a stray byte.
        assertEquals(2, PcmMixer.mix(pcm(5, 0), 3, pcm(5), 2, out))
        assertArrayEquals(intArrayOf(10), samplesOf(out, 2))
    }

    @Test
    fun upmixesMonoToStereoByDuplicatingEachSample() {
        val out = ByteArray(8)
        assertEquals(8, PcmMixer.upmixMonoToStereo(pcm(1_000, -2_000), 4, out))
        assertArrayEquals(intArrayOf(1_000, 1_000, -2_000, -2_000), samplesOf(out, 8))
    }

    @Test
    fun upmixOfEmptyInputWritesNothing() {
        assertEquals(0, PcmMixer.upmixMonoToStereo(ByteArray(0), 0, ByteArray(0)))
    }
}
