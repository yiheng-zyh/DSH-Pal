package com.dsh.mobile.core

import org.junit.Assert.*
import org.junit.Test

class LaunchRoutingTest {
    @Test fun restartUsesThePairedLandingWithoutReplayingTokens() {
        assertEquals("https://example.com/pair-app",LaunchRouting.resumeUrl("https://example.com/pair-accept?pair=expired"))
        assertEquals("https://example.com/pair-app",LaunchRouting.resumeUrl("https://example.com/"))
    }
    private val current = ConnectionTarget("https://example.com", "")

    @Test fun notificationReturnPreservesConsumedPairingTarget() {
        assertSame(current, LaunchRouting.apply(current, null))
    }
    @Test fun sameOriginDoesNotRequirePageUpdate() {
        assertEquals(current, LaunchRouting.apply(current, "https://example.com/"))
    }
    @Test fun freshPairingIsAppliedOnce() {
        val link = "https://example.com/pair-accept?pair=sample"
        val paired = LaunchRouting.apply(current, link)
        assertEquals(link, paired.pairingUrl)
        assertEquals(current.baseUrl, paired.baseUrl)
        assertEquals(paired, LaunchRouting.apply(paired, link))
    }
    @Test fun ordinaryReturnRetainsHandshakeThatIsStillInProgress() {
        val pending = current.copy(pairingUrl = "https://example.com/pair-accept?pair=sample")
        assertSame(pending, LaunchRouting.apply(pending, null))
    }
    @Test fun changingServerDropsOldPendingToken() {
        val pending = current.copy(pairingUrl = "https://example.com/pair-accept?pair=sample")
        assertEquals(ConnectionTarget("https://other.example.com", ""), LaunchRouting.apply(pending, "https://other.example.com"))
    }
    @Test fun malformedAndNonWebLinksDoNotChangeThePage() {
        listOf("not a url", "javascript:alert(1)", "https://").forEach { assertSame(current, LaunchRouting.apply(current, it)) }
    }
}
