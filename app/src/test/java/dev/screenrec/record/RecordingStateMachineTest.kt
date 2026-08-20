package dev.screenrec.record

import dev.screenrec.record.RecordingState.COUNTDOWN
import dev.screenrec.record.RecordingState.IDLE
import dev.screenrec.record.RecordingState.PAUSED
import dev.screenrec.record.RecordingState.RECORDING
import dev.screenrec.record.RecordingState.STOPPING
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingStateMachineTest {

    @Test
    fun startsIdle() {
        assertEquals(IDLE, RecordingStateMachine().state)
    }

    @Test
    fun walksTheHappyPath() {
        val m = RecordingStateMachine()
        assertTrue(m.transitionTo(COUNTDOWN))
        assertTrue(m.transitionTo(RECORDING))
        assertTrue(m.transitionTo(PAUSED))
        assertTrue(m.transitionTo(RECORDING))
        assertTrue(m.transitionTo(STOPPING))
        assertTrue(m.transitionTo(IDLE))
        assertEquals(IDLE, m.state)
    }

    @Test
    fun countdownCanBeCancelledBackToIdle() {
        val m = RecordingStateMachine(COUNTDOWN)
        assertTrue(m.transitionTo(IDLE))
    }

    @Test
    fun pausedCanStopDirectly() {
        val m = RecordingStateMachine(PAUSED)
        assertTrue(m.transitionTo(STOPPING))
    }

    @Test
    fun rejectsIllegalTransitionsAndKeepsState() {
        val m = RecordingStateMachine()
        assertFalse(m.transitionTo(RECORDING)) // must count down first
        assertFalse(m.transitionTo(PAUSED))
        assertFalse(m.transitionTo(STOPPING))
        assertEquals(IDLE, m.state)
    }

    @Test
    fun rejectsPauseWhileAlreadyPausedAndResumeWhileRecording() {
        assertFalse(RecordingStateMachine(PAUSED).transitionTo(PAUSED))
        assertFalse(RecordingStateMachine(RECORDING).transitionTo(RECORDING))
    }

    @Test
    fun stoppingOnlyGoesIdle() {
        assertFalse(RecordingStateMachine(STOPPING).transitionTo(RECORDING))
        assertFalse(RecordingStateMachine(STOPPING).transitionTo(PAUSED))
        assertTrue(RecordingStateMachine(STOPPING).transitionTo(IDLE))
    }

    @Test
    fun reportsActiveOnlyWhileRecordingOrPaused() {
        assertFalse(RecordingStateMachine(IDLE).isActive)
        assertFalse(RecordingStateMachine(COUNTDOWN).isActive)
        assertTrue(RecordingStateMachine(RECORDING).isActive)
        assertTrue(RecordingStateMachine(PAUSED).isActive)
        assertFalse(RecordingStateMachine(STOPPING).isActive)
    }
}
