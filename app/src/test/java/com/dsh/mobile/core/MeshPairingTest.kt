package com.dsh.mobile.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The VPN route reused a saved origin and navigated straight to `/pair-app`,
 * which is the already-paired landing: after app data is cleared the tunnel
 * still comes up (Tailscale login is a separate layer) and every load ends in
 * 401 with no way forward. These cases pin the token-replay behaviour that lets
 * the VPN origin pair from a link minted for another origin.
 */
class MeshPairingTest {
    private fun acceptUrl(token: String) = "$RELAY/pair-accept?pair=$token"

    @Test
    fun `token is taken from the plugin accept link`() {
        assertEquals("t0k3n", MeshPairing.token(acceptUrl("t0k3n")))
    }

    @Test
    fun `token is taken from a deep link that wraps the pairing url`() {
        val nested = java.net.URLEncoder.encode(acceptUrl("t0k3n"), "UTF-8")
        assertEquals("t0k3n", MeshPairing.token("dshmobile://open?url=$nested"))
    }

    @Test
    fun `bare origin carries no token`() {
        assertNull(MeshPairing.token("https://example-node.example-tailnet.ts.net"))
        assertNull(MeshPairing.token(""))
        assertNull(MeshPairing.token("   "))
    }

    @Test
    fun `deep link url parameter is never mistaken for the token`() {
        // `url` is the wrapper's own parameter; reading it as a token would replay
        // the whole address bar line against the plugin.
        val nested = java.net.URLEncoder.encode("$RELAY/pair-accept", "UTF-8")
        assertNull(MeshPairing.token("dshmobile://open?url=$nested"))
    }

    @Test
    fun `empty token parameter is rejected`() {
        assertNull(MeshPairing.token("$RELAY/pair-accept?pair="))
        assertNull(MeshPairing.token("$RELAY/pair-accept?pair"))
    }

    @Test
    fun `pairing url is rebuilt on the mesh origin keeping the token`() {
        val url = MeshPairing.pairingUrlFor(acceptUrl("t0k3n"), "https://example-node.example-tailnet.ts.net")
        assertEquals("https://example-node.example-tailnet.ts.net/pair-accept?pair=t0k3n", url)
    }

    @Test
    fun `rebuilt pairing url canonicalises an origin that carries a path`() {
        val url = MeshPairing.pairingUrlFor(acceptUrl("t0k3n"), "https://example-node.example-tailnet.ts.net/some/path")
        assertEquals("https://example-node.example-tailnet.ts.net/pair-accept?pair=t0k3n", url)
    }

    @Test
    fun `pairing url needs both a token and a valid origin`() {
        assertNull(MeshPairing.pairingUrlFor("https://example-node.example-tailnet.ts.net", "https://example-node.example-tailnet.ts.net"))
        assertNull(MeshPairing.pairingUrlFor(acceptUrl("t0k3n"), "not a url"))
    }

    @Test
    fun `a saved route without a pending link is not openable`() {
        // Exactly the tablet state: profile restored from settings, no grant.
        assertFalse(MeshPairing.canOpenSavedRoute("https://example-node.example-tailnet.ts.net", ""))
    }

    @Test
    fun `a saved route with its pairing link is openable`() {
        assertTrue(MeshPairing.canOpenSavedRoute("https://example-node.example-tailnet.ts.net", acceptUrl("t0k3n")))
    }

    @Test
    fun `an unusable origin is never openable`() {
        assertFalse(MeshPairing.canOpenSavedRoute("", acceptUrl("t0k3n")))
    }

    @Test
    fun `a remembered token rebuilds a pairing url for another origin`() {
        // The VPN route pairs from a token first minted for the public base; the
        // plugin's accept() never inspects the host, so this is the whole reason
        // no second scan is needed.
        val remembered = MeshPairing.token(acceptUrl("t0k3n"))!!
        val rebuilt = MeshPairing.pairingUrlFor("https://x/pair-accept?pair=$remembered", MESH)
        assertEquals("$MESH/pair-accept?pair=t0k3n", rebuilt)
    }

    @Test
    fun `the vpn origin is recognised as its own kind so a public link is never treated as mesh`() {
        assertEquals(
            ConnectionProfile.Kind.MESH,
            ConnectionAddress.recognizedRemoteKind(MESH),
        )
    }

    private companion object {
        const val RELAY = "https://example-instance.dsh-market.com"
        const val MESH = "https://example-node.example-tailnet.ts.net"
    }
}
