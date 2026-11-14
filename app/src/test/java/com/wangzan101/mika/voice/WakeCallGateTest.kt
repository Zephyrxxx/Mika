package com.wangzan101.mika.voice

import org.junit.Assert.*
import org.junit.Test

class WakeCallGateTest {
    @Test fun nothingCanStartUntilIdleWasObserved() {
        val gate = WakeCallGate()
        assertNull(gate.token())
        gate.update(false)
        assertTrue(gate.allows(requireNotNull(gate.token())))
    }

    @Test fun callInvalidatesListeningAndCloudResultsEvenAfterItEnds() {
        val gate = WakeCallGate()
        gate.update(false)
        val listening = requireNotNull(gate.token())
        val transcribing = listening
        gate.update(true)
        assertFalse(gate.allows(listening))
        assertNull(gate.token())
        gate.update(false)
        assertFalse(gate.allows(transcribing))
        assertTrue(gate.allows(requireNotNull(gate.token())))
    }

    @Test fun unchangedPollingKeepsCurrentAttemptValid() {
        val gate = WakeCallGate()
        gate.update(false)
        val token = requireNotNull(gate.token())
        repeat(10) { assertFalse(gate.update(false)) }
        assertTrue(gate.allows(token))
    }

    @Test fun userStopCannotResumeFromAnOldResult() {
        val gate = WakeCallGate()
        gate.update(false)
        val token = requireNotNull(gate.token())
        gate.update(true) // requested=false or service destruction
        repeat(10) { gate.update(true) }
        assertNull(gate.token())
        assertFalse(gate.allows(token))
    }

    @Test fun stockDialerAndAudioModeAreIndependentGuards() {
        assertTrue(wakeCallBlocked(true, false, false))
        assertTrue(wakeCallBlocked(false, false, true))
        assertTrue(wakeCallBlocked(false, true, null))
        assertFalse(wakeCallBlocked(false, false, false))
        assertFalse(wakeCallBlocked(false, false, null))
    }
}
