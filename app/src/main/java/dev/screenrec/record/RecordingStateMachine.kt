package dev.screenrec.record

enum class RecordingState { IDLE, COUNTDOWN, RECORDING, PAUSED, STOPPING }

/**
 * Guards the session lifecycle. Not thread-safe by itself; the service confines all
 * transitions to its main-thread Handler.
 */
class RecordingStateMachine(initial: RecordingState = RecordingState.IDLE) {

    var state: RecordingState = initial
        private set

    val isActive: Boolean
        get() = state == RecordingState.RECORDING || state == RecordingState.PAUSED

    fun transitionTo(target: RecordingState): Boolean {
        if (target !in legalTargets(state)) return false
        state = target
        return true
    }

    private fun legalTargets(from: RecordingState): Set<RecordingState> = when (from) {
        RecordingState.IDLE -> setOf(RecordingState.COUNTDOWN)
        // Countdown can be cancelled, or the user can revoke consent before frame one.
        RecordingState.COUNTDOWN -> setOf(RecordingState.RECORDING, RecordingState.IDLE)
        RecordingState.RECORDING -> setOf(RecordingState.PAUSED, RecordingState.STOPPING)
        RecordingState.PAUSED -> setOf(RecordingState.RECORDING, RecordingState.STOPPING)
        RecordingState.STOPPING -> setOf(RecordingState.IDLE)
    }
}
