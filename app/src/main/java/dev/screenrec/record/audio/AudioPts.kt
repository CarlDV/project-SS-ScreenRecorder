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

    /** Timestamp of the next byte to be written, without consuming anything. */
    fun currentPtsUs(): Long = framesWritten * 1_000_000L / sampleRate

    /** Accounts for bytes actually handed to the encoder. Partial frames are ignored. */
    fun advance(byteCount: Int) {
        framesWritten += byteCount / bytesPerFrame
    }

    /** Timestamp for the buffer about to be written; then accounts for it. */
    fun nextPtsUs(byteCount: Int): Long {
        val pts = currentPtsUs()
        advance(byteCount)
        return pts
    }

    fun reset() {
        framesWritten = 0L
    }
}
