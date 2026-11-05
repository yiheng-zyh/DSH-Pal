package com.dsh.mobile.core

import org.junit.Assert.*
import org.junit.Test

class PairingNavigationTest {
    private val source="https://fixture.trycloudflare.com/pair-accept?pair=one-use"
    @Test fun defaultRelayRedirectKeepsFreshGrantOnSelectedTunnel() {
        assertEquals("https://fixture.trycloudflare.com/pair-app?grant=fresh%2Bgrant",
            PairingNavigation.selectedOriginLanding(source,"https://fixture.dsh-market.com/pair-app?grant=fresh%2Bgrant",true))
    }
    @Test fun normalLinksAndOtherCredentialFormsAreNotRewritten() {
        for(target in listOf("https://fixture.dsh-market.com/docs","https://fixture.dsh-market.com/pair-app",
            "https://fixture.dsh-market.com/pair-app?deviceId=private","https://fixture.dsh-market.com/pair-app?grant=",
            "https://fixture.dsh-market.com/pair-app?grant=fresh&next=other","https://external.example.org/pair-app?grant=fresh",
            "https://fixture.dsh-market.com/pair-app?grant=fresh#fragment"))
            assertNull(PairingNavigation.selectedOriginLanding(source,target,true))
        assertNull(PairingNavigation.selectedOriginLanding(source,"https://fixture.dsh-market.com/pair-app?grant=fresh",false))
        assertNull(PairingNavigation.selectedOriginLanding("https://fixture.trycloudflare.com/chat","https://fixture.dsh-market.com/pair-app?grant=fresh",true))
    }
}
