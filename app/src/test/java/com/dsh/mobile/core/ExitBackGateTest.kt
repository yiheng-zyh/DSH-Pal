package com.dsh.mobile.core

import org.junit.Assert.*
import org.junit.Test

class ExitBackGateTest {
    @Test fun requiresTwoCompletedRequestsWithinWindow() {
        val gate=ExitBackGate()
        assertFalse(gate.request(0)); assertTrue(gate.request(2499))
        assertFalse(gate.request(2500))
    }
    @Test fun timeoutOrBackgroundRequiresAnotherFirstRequest() {
        val gate=ExitBackGate()
        assertFalse(gate.request(100));assertFalse(gate.request(2601))
        gate.reset();assertFalse(gate.request(2700));assertTrue(gate.request(2800))
    }
    @Test fun backwardsClockCannotConfirm() {
        val gate=ExitBackGate()
        assertFalse(gate.request(100));assertFalse(gate.request(99))
    }
}
