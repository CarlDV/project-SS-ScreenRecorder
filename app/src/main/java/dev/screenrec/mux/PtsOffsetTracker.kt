package dev.screenrec.mux

/**
 * Video-only. Turns the capture surface's raw timestamps into a stream a container can hold.
 *
 * Two corrections, both necessary:
 *
 * 1. **Rebase.** Surface timestamps come from CLOCK_MONOTONIC, so the first frame carries the
 *    device's uptime -- on a phone up for twelve hours, twelve hours. Written through
 *    unchanged that becomes the file's duration and desynchronises the audio, whose own
 *    timestamps start at zero. The first frame seen defines the origin.
 * 2. **Paused spans.** The clock keeps running while the virtual display's surface is
 *    detached, so those spans are subtracted or playback freezes for the pause duration.
 *
 * Audio needs neither: its timestamps come from a cumulative sample count that starts at zero
 * and stops advancing while paused.
 */
class PtsOffsetTracker {

    private var baseUs = UNSET
    private var pauseStartedAtUs = -1L
    private var lastEmittedUs = Long.MIN_VALUE

    var pausedTotalUs: Long = 0L
        private set

    val isPaused: Boolean
        get() = pauseStartedAtUs >= 0L

    fun pause(atUs: Long) {
        if (isPaused) return
        pauseStartedAtUs = atUs
    }

    fun resume(atUs: Long) {
        if (!isPaused) return
        pausedTotalUs += (atUs - pauseStartedAtUs).coerceAtLeast(0L)
        pauseStartedAtUs = -1L
    }

    /**
     * Frames already in flight when the surface detached still arrive, and the muxer rejects a
     * regressing timestamp, so the result is clamped to the last value emitted.
     */
    fun adjust(rawUs: Long): Long {
        if (baseUs == UNSET) baseUs = rawUs
        val shifted = (rawUs - baseUs - pausedTotalUs).coerceAtLeast(0L)
        val monotonic = if (lastEmittedUs == Long.MIN_VALUE) shifted else maxOf(shifted, lastEmittedUs)
        val result = if (isPaused && lastEmittedUs != Long.MIN_VALUE) lastEmittedUs else monotonic
        lastEmittedUs = result
        return result
    }

    private companion object {
        const val UNSET = Long.MIN_VALUE
    }
}
