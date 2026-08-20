package dev.screenrec.record.audio

/**
 * Software mixing for "Media and mic". Saturating rather than wrapping.
 */
object PcmMixer {

    fun mix(a: ByteArray, aLen: Int, b: ByteArray, bLen: Int, out: ByteArray): Int {
        val samples = maxOf(aLen, bLen) / 2
        for (i in 0 until samples) {
            val sum = sampleAt(a, aLen, i) + sampleAt(b, bLen, i)
            writeSample(out, i, sum.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()))
        }
        return samples * 2
    }

    /** The mic often refuses a stereo mask, so it is captured mono and upmixed. */
    fun upmixMonoToStereo(src: ByteArray, srcLen: Int, out: ByteArray): Int {
        val samples = srcLen / 2
        for (i in 0 until samples) {
            val s = sampleAt(src, srcLen, i)
            writeSample(out, i * 2, s)
            writeSample(out, i * 2 + 1, s)
        }
        return samples * 4
    }

    /** Past the end of a stream, silence. */
    private fun sampleAt(buf: ByteArray, len: Int, index: Int): Int {
        val lo = index * 2
        if (lo + 1 >= len) return 0
        return ((buf[lo].toInt() and 0xFF) or (buf[lo + 1].toInt() shl 8)).toShort().toInt()
    }

    private fun writeSample(out: ByteArray, index: Int, value: Int) {
        out[index * 2] = (value and 0xFF).toByte()
        out[index * 2 + 1] = ((value shr 8) and 0xFF).toByte()
    }
}
