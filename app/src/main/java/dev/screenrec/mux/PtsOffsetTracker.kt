package dev.screenrec.mux

/**
 * Video-only. The capture surface stamps frames from the system clock, which keeps
 * advancing while the virtual display's surface is detached, so paused spans must be
 * subtracted or playback freezes for the pause duration.
 */
class PtsOffsetTracker {

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

    fun adjust(rawUs: Long): Long {
        val shifted = rawUs - pausedTotalUs
        val monotonic = if (lastEmittedUs == Long.MIN_VALUE) shifted else maxOf(shifted, lastEmittedUs)
        val result = if (isPaused && lastEmittedUs != Long.MIN_VALUE) lastEmittedUs else monotonic
        lastEmittedUs = result
        return result
    }
}
