package dev.screenrec.record.audio

/**
 * Derives AAC presentation timestamps from the cumulative frame count rather than the
 * clock. This is what makes pause correctness free.
 */
class AudioPts(
    private val sampleRate: Int,
    private val channelCount: Int,
    private val bytesPerSample: Int = 2
) {
    private val bytesPerFrame = channelCount * bytesPerSample

    var framesWritten: Long = 0L
        private set

    fun nextPtsUs(byteCount: Int): Long {
        val pts = framesWritten * 1_000_000L / sampleRate
        framesWritten += byteCount / bytesPerFrame
        return pts
    }

    fun reset() {
        framesWritten = 0L
    }
}
