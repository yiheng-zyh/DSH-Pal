package com.dsh.mobile.core
import org.junit.Assert.*
import org.junit.Test
class ProbeOutcomeTest {
    @Test fun reachableIsNotTheSameAsAuthenticatedDsh() {
        assertEquals(ProbeOutcome.UNKNOWN_PAGE, ProbeOutcome.classify(200,false))
        assertEquals(ProbeOutcome.DSH_FOUND, ProbeOutcome.classify(200,true))
        assertEquals(ProbeOutcome.AUTH_REQUIRED, ProbeOutcome.classify(401,true))
        assertEquals(ProbeOutcome.AUTH_REQUIRED, ProbeOutcome.classify(403,false))
        assertEquals(ProbeOutcome.REDIRECT, ProbeOutcome.classify(302,false))
        assertEquals(ProbeOutcome.HTTP_ERROR, ProbeOutcome.classify(502,true))
    }
}
