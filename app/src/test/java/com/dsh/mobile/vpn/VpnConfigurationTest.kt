package com.dsh.mobile.vpn

import com.wireguard.crypto.KeyPair
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VpnConfigurationTest {
    @Test fun wireGuardNeedsARecentRealHandshake() {
        val now=1_000_000L
        assertFalse(WireGuardReadiness.hasRecentHandshake(0,now))
        assertFalse(WireGuardReadiness.hasRecentHandshake(now+1,now))
        assertFalse(WireGuardReadiness.hasRecentHandshake(now-180_001,now))
        assertTrue(WireGuardReadiness.hasRecentHandshake(now-180_000,now))
        assertTrue(WireGuardReadiness.hasRecentHandshake(now,now))
    }
    @Test fun authorizationOnlyOpensRealOfficialHttpsLinks() {
        assertEquals("",TailscaleEngine.authorizationUrl("null"))
        assertEquals("",TailscaleEngine.authorizationUrl(""))
        assertEquals("",TailscaleEngine.authorizationUrl("https://login.tailscale.com.evil.test/a"))
        assertEquals("",TailscaleEngine.authorizationUrl("http://login.tailscale.com/a"))
        assertEquals("https://login.tailscale.com/a/fixture",TailscaleEngine.authorizationUrl("https://login.tailscale.com/a/fixture"))
    }
    @Test fun discoveryOnlyUsesOnlineAuthorizedPeersAndBoundsWork() {
        val status=JSONObject("""{"Peer":{"a":{"Online":true,"DNSName":"desktop.example.ts.net.","TailscaleIPs":["100.70.0.2","127.0.0.1","8.8.8.8"]},"b":{"Online":false,"DNSName":"offline.example.ts.net","TailscaleIPs":["100.70.0.3"]}}}""")
        val targets=MeshDiscovery.candidates(status).map {it.origin}
        assertTrue(targets.contains("https://desktop.example.ts.net"))
        assertTrue(targets.any {it.contains("100.70.0.2")})
        assertFalse(targets.any {it.contains("127.0.0.1")||it.contains("8.8.8.8")||it.contains("offline")})
        assertTrue(targets.size<=60)
    }
    @Test fun wireGuardConfigurationCannotRouteOtherApplications() {
        val privateKey=KeyPair().privateKey.toBase64()
        val publicKey=KeyPair().publicKey.toBase64()
        val config=VpnController.appOnlyConfig("""
            [Interface]
            PrivateKey = $privateKey
            Address = 10.7.0.2/32
            DNS = 1.1.1.1
            [Peer]
            PublicKey = $publicKey
            AllowedIPs = 0.0.0.0/0, ::/0
            Endpoint = 127.0.0.1:51820
        """.trimIndent(),"com.example.dsh")
        assertEquals(setOf("com.example.dsh"),config.`interface`.includedApplications)
        assertTrue(config.`interface`.excludedApplications.isEmpty())
        assertEquals(1,config.peers.size)
        assertEquals(2,config.peers[0].allowedIps.size)
    }
    @Test fun malformedWireGuardConfigurationIsRejectedBeforeConnecting() {
        assertThrows(Exception::class.java) {VpnController.appOnlyConfig("not a configuration","com.example.dsh")}
    }
}
