package com.dsh.mobile.core

import org.junit.Assert.*
import org.junit.Test

class VpnSessionGateTest {
    @Test fun serviceOnlyProcessCannotRestoreVpn() {
        assertNull(VpnSessionGate().ticket())
    }
    @Test fun returningHomeDoesNotCloseTheSession() {
        val gate=VpnSessionGate();gate.enter()
        val ticket=gate.ticket()!!
        gate.enter() // Activity resumes without the task being removed.
        assertTrue(gate.permits(ticket))
    }
    @Test fun clearingTaskInvalidatesQueuedStartsEvenAfterReopen() {
        val gate=VpnSessionGate();gate.enter()
        val old=gate.ticket()!!
        gate.leave()
        assertNull(gate.ticket());assertFalse(gate.permits(old))
        gate.enter()
        assertFalse(gate.permits(old));assertTrue(gate.permits(gate.ticket()!!))
    }
}
