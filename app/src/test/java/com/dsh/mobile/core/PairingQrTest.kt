package com.dsh.mobile.core

import java.net.URLEncoder
import org.junit.Assert.*
import org.junit.Test

class PairingQrTest {
    @Test fun domesticHttpsPairsWithoutSelectingAProvider() {
        val url = "https://www.example.nyat.app:12451/pair-accept?pair=fixture"
        val entry = PairingQr.parse(url)
        assertEquals(ConnectionProfile.Kind.CUSTOM, entry.kind)
        assertEquals(url, entry.url)
    }
    @Test fun knownRemoteHostsKeepTheirRealTransport() {
        assertEquals(ConnectionProfile.Kind.RELAY, PairingQr.parse("https://example.dsh-market.com/pair-accept?pair=fixture").kind)
        assertEquals(ConnectionProfile.Kind.TUNNEL, PairingQr.parse("https://example.trycloudflare.com/pair-accept?pair=fixture").kind)
    }
    @Test fun wrappedQrPreservesTheEntireOriginalPairingLink() {
        val url = "https://example.test:12451/pair-accept?pair=fixture%2Bvalue&keep=a%2Fb"
        assertEquals(url, PairingQr.parse("dshmobile://open?url=" + URLEncoder.encode(url, "UTF-8")).url)
    }
    @Test fun localAndMeshEntriesAreIdentifiedWithoutRewritingTheirHost() {
        assertEquals(ConnectionProfile.Kind.LAN, PairingQr.parse("http://192.168.1.10:9000/pair-accept?pair=fixture").kind)
        assertEquals(ConnectionProfile.Kind.MESH, PairingQr.parse("https://example.ts.net/pair-accept?pair=fixture").kind)
    }
    @Test fun productionCannotUseAnImplicitUsbQr() {
        assertTrue(runCatching { PairingQr.parse("http://dsh-usb.localhost:18765/pair-accept?pair=fixture") }.isFailure)
        assertTrue(runCatching { PairingQr.parse("http://127.0.0.1:18765/pair-accept?pair=fixture", true) }.isFailure)
        assertEquals(ConnectionProfile.Kind.USB, PairingQr.parse("http://dsh-usb.localhost:18765/pair-accept?pair=fixture", true).kind)
    }
    @Test fun arbitraryOrAmbiguousQrDoesNotBecomeAConnection() {
        for (raw in listOf("https://example.test/", "javascript:alert(1)", "https://user:password@example.test/pair-accept?pair=fixture", "dshmobile://other?url=https%3A%2F%2Fexample.test%2F%3Fpair%3Dfixture", "dshmobile://open?url=a&url=b")) {
            assertTrue(raw.substringBefore('?'), runCatching { PairingQr.parse(raw) }.isFailure)
        }
    }
}
