package com.dsh.mobile.core

import org.junit.Assert.*
import org.junit.Test

class ConnectionProfilesTest {
    @Test fun knownPublicRoutesUseExactSuffixesAndOtherHostsStayCustom() {
        assertEquals(ConnectionProfile.Kind.TUNNEL,ConnectionAddress.infer("https://fixture.trycloudflare.com/pair-accept?pair=secret"))
        assertEquals(ConnectionProfile.Kind.RELAY,ConnectionAddress.infer("https://fixture.dsh-market.com"))
        for(url in listOf("https://fixture.trycloudflare.com.evil.test","https://fixture.dsh-market.com.evil.test","https://my-domain.example.org"))
            assertEquals(ConnectionProfile.Kind.CUSTOM,ConnectionAddress.infer(url))
    }
    @Test fun legacyPublicRecordsSplitWithoutLosingIdentityUsageOrSelection() {
        for((url,kind) in listOf("https://fixture.dsh-market.com" to ConnectionProfile.Kind.RELAY,
            "https://fixture.trycloudflare.com" to ConnectionProfile.Kind.TUNNEL,"https://unknown.example.org" to ConnectionProfile.Kind.CUSTOM)) {
            val legacy="""[{"label":"Cloudflare / Relay","kind":"TUNNEL","baseUrl":"$url","lastUsedAt":123}]"""
            val before=ConnectionProfile.create("",ConnectionProfile.Kind.TUNNEL,url)
            val after=ConnectionProfiles.decode(legacy).single()
            assertEquals(before.id,after.id);assertEquals(url,after.baseUrl);assertEquals(123L,after.lastUsedAt)
            assertEquals(kind,after.kind);assertEquals(kind.title,after.label)
            assertEquals(after,ConnectionProfiles.restore(listOf(after),before.id,url))
            assertEquals(after,ConnectionProfiles.decode(ConnectionProfiles.encode(listOf(after))).single())
        }
        assertEquals("我的固定入口",ConnectionProfiles.decode("""[{"label":"我的固定入口","kind":"TUNNEL","baseUrl":"https://fixture.dsh-market.com"}]""").single().label)
    }
    @Test fun namedTunnelAndSelfHostedRelayKeepExplicitChoicesAfterSerialization() {
        for(kind in listOf(ConnectionProfile.Kind.TUNNEL,ConnectionProfile.Kind.RELAY,ConnectionProfile.Kind.CUSTOM)) {
            val profile=ConnectionProfile.create("自有域名",kind,"https://owned.example.org")
            assertEquals(profile,ConnectionProfiles.decode(ConnectionProfiles.encode(listOf(profile))).single())
        }
    }
    @Test fun qrFromAnotherKnownRouteIsClassifiedByItsActualHost() {
        assertEquals(ConnectionProfile.Kind.RELAY,ConnectionProfile.create("",ConnectionProfile.Kind.TUNNEL,"https://fixture.dsh-market.com/pair-accept?pair=secret").kind)
        assertEquals(ConnectionProfile.Kind.TUNNEL,ConnectionProfile.create("",ConnectionProfile.Kind.RELAY,"https://fixture.trycloudflare.com").kind)
        for(kind in listOf(ConnectionProfile.Kind.TUNNEL,ConnectionProfile.Kind.RELAY,ConnectionProfile.Kind.CUSTOM))
            assertThrows(IllegalArgumentException::class.java) {ConnectionProfile.create("",kind,"http://127.0.0.1:1234")}
        assertTrue(ConnectionProfiles.decode("""[{"kind":"TUNNEL","baseUrl":"http://192.168.1.2:8080"}]""").isEmpty())
    }
    @Test fun savedMeshRouteKeepsItsProviderAcrossRestarts() {
        val route=ConnectionProfile.create("WG",ConnectionProfile.Kind.MESH,"https://desktop.example.org")
            .copy(meshProvider=ConnectionProfile.MeshProvider.WIREGUARD)
        assertEquals(route,ConnectionProfiles.decode(ConnectionProfiles.encode(listOf(route))).single())
        val external=route.copy(meshProvider=ConnectionProfile.MeshProvider.EXTERNAL)
        assertEquals(external,ConnectionProfiles.decode(ConnectionProfiles.encode(listOf(external))).single())
        assertNull(ConnectionProfiles.decode("""[{"label":"Legacy","kind":"MESH","baseUrl":"https://desktop.example.ts.net"}]""").single().meshProvider)
    }
    @Test fun oldTailscaleTunnelRecordRepairsKindWithoutLosingIdentity() {
        val correct=ConnectionProfile.create("电脑",ConnectionProfile.Kind.MESH,"https://desktop.example.ts.net")
        val old="""[{"label":"电脑","kind":"TUNNEL","baseUrl":"https://desktop.example.ts.net","lastUsedAt":123}]"""
        val repaired=ConnectionProfiles.decode(old).single()
        assertEquals(correct.id,repaired.id)
        assertEquals(correct.baseUrl,repaired.baseUrl)
        assertEquals(ConnectionProfile.Kind.MESH,repaired.kind)
        assertEquals(123L,repaired.lastUsedAt)
        assertEquals("Tailscale",ConnectionProfile.create(ConnectionProfile.Kind.TUNNEL.title,ConnectionProfile.Kind.MESH,correct.baseUrl).label)
        assertEquals(ConnectionProfile.Kind.MESH,ConnectionProfiles.migrate(correct.baseUrl,false).single().kind)
        val public=ConnectionProfile.create("公网",ConnectionProfile.Kind.TUNNEL,"https://public.example.org")
        val both=ConnectionProfiles.upsert(listOf(repaired),public)
        repeat(10) {
            assertEquals(repaired,ConnectionProfiles.select(both,repaired.id))
            assertEquals(public,ConnectionProfiles.select(both,public.id))
        }
    }
    @Test fun tailscaleDeepLinksKeepTheMeshRoute() {
        assertEquals(ConnectionProfile.Kind.MESH,ConnectionAddress.infer("https://desktop.example.ts.net/pair-accept?pair=fixture"))
        assertEquals(ConnectionProfile.Kind.CUSTOM,ConnectionAddress.infer("https://ts.net.example.org"))
    }
    @Test fun startupRecoversOnlyKnownSelectionOrLegacyOrigin() {
        val a = ConnectionProfile.create("A", ConnectionProfile.Kind.TUNNEL, "https://example.org")
        val b = ConnectionProfile.create("B", ConnectionProfile.Kind.LAN, "http://192.168.1.2:8080")
        assertEquals(b, ConnectionProfiles.restore(listOf(a,b), b.id, a.baseUrl))
        assertEquals(a, ConnectionProfiles.restore(listOf(a,b), "lost-id", a.baseUrl))
        assertNull(ConnectionProfiles.restore(listOf(a,b), null, ""))
        assertNull(ConnectionProfiles.restore(emptyList(), a.id, a.baseUrl))
        assertNull(ConnectionProfiles.restore(listOf(a,b), null, "javascript:alert(1)"))
    }
    @Test fun migrateKeepsOriginAndStableIdentity() {
        val a = ConnectionProfiles.migrate("https://example.org/", false).single()
        assertEquals("https://example.org", a.baseUrl)
        assertEquals("默认连接", a.label)
        assertEquals(ConnectionProfile.Kind.CUSTOM, a.kind)
        assertEquals(a.id, ConnectionProfiles.migrate("https://example.org", false).single().id)
    }
    @Test fun usbMigrationIsIndependent() {
        assertEquals(ConnectionProfile.Kind.USB, ConnectionProfiles.migrate("http://dsh-usb.localhost:18765", true).single().kind)
        assertTrue(ConnectionProfiles.migrate("http://dsh-usb.localhost:18765", false).isEmpty())
    }
    @Test fun emptyAndStaleLegacyDoNotInventTrustedProfiles() {
        for (s in listOf("", "broken", "http://127.0.0.1:80", "file:///private")) assertTrue(ConnectionProfiles.migrate(s, false).isEmpty())
    }
    @Test fun serializationHasNoPairingGrant() {
        val p = ConnectionProfile.create("Home", ConnectionProfile.Kind.TUNNEL, "https://example.org/pair-app?pair=private-grant").copy(lastUsedAt=123L)
        val json = ConnectionProfiles.encode(listOf(p))
        assertFalse(json.contains("private-grant"))
        assertEquals(listOf(p), ConnectionProfiles.decode(json))
    }
    @Test fun switchPreservesBothProfilesAndUsage() {
        val a = ConnectionProfile.create("CF", ConnectionProfile.Kind.TUNNEL, "https://example.org").copy(lastUsedAt=5)
        val b = ConnectionProfile.create("WiFi", ConnectionProfile.Kind.LAN, "http://192.168.1.2:8080")
        val all = ConnectionProfiles.upsert(listOf(a), b)
        assertEquals(a, ConnectionProfiles.select(all, a.id))
        assertEquals(b, ConnectionProfiles.select(all, b.id))
        assertNull(ConnectionProfiles.select(all, "unknown"))
        assertEquals(all, ConnectionProfiles.upsert(all, b))
    }
    @Test fun invalidStoredEntryCannotGrantLanTrust() {
        val json = """[{"label":"bad","kind":"LAN","baseUrl":"http://127.0.0.1:88"}]"""
        assertTrue(ConnectionProfiles.decode(json).isEmpty())
    }
    @Test fun localInputAddsSchemeButRequiresPrivateAddress() {
        assertEquals("http://192.168.1.4:8080", ConnectionAddress.localInput("192.168.1.4:8080"))
        assertNull(ConnectionAddress.localInput("example.org:8080"))
        assertNull(ConnectionAddress.localInput("192.168.1.999:8080"))
    }
    @Test fun originRejectsCredentialsAndNormalizesPorts() {
        assertNull(ConnectionAddress.origin("https://user:secret@example.org"))
        assertEquals("https://example.org", ConnectionAddress.origin("https://EXAMPLE.org:443/path?pair=secret"))
        assertNull(ConnectionAddress.origin("http://example.org:99999"))
    }
    @Test fun privateAndCgnatBoundaries() {
        for (host in listOf("10.0.0.1","192.168.1.1","172.16.0.1","172.31.255.254")) assertTrue(ConnectionAddress.isPrivateIpv4(host))
        for (host in listOf("172.15.0.1","172.32.0.1","192.169.1.1","10.999.0.1","010.0.0.1")) assertFalse(ConnectionAddress.isPrivateIpv4(host))
        for (host in listOf("100.64.0.1","100.127.255.254","fd7a:115c:a1e0::1")) assertTrue(ConnectionAddress.isCgnatOrPrivate(host))
        for (host in listOf("100.63.0.1","100.128.0.1","8.8.8.8")) assertFalse(ConnectionAddress.isCgnatOrPrivate(host))
    }
    @Test fun localishIncludesAllRestrictedRanges() {
        for (host in listOf("localhost","a.localhost","127.1.2.3","[::1]","0.0.0.0","10.0.0.1","100.64.0.1","169.254.1.1","::ffff:127.0.0.1","0x7f000001","127.1","2130706433")) assertTrue(host, ConnectionAddress.isLocalish(host))
    }
    @Test fun profilesScopeTheSocketToExactOriginAndTransport() {
        val lan = ConnectionProfile.create("LAN", ConnectionProfile.Kind.LAN, "http://192.168.1.3:8080")
        assertTrue(HarnessSocketPolicy.isTaskStream("ws://192.168.1.3:8080/remote/api/remote.mux", lan))
        for (url in listOf("ws://192.168.1.4:8080/remote/api/remote.mux","ws://192.168.1.3:8081/remote/api/remote.mux","wss://192.168.1.3:8080/remote/api/remote.mux","ws://192.168.1.3:8080/ingest")) assertFalse(HarnessSocketPolicy.isTaskStream(url, lan))
        assertFalse(HarnessSocketPolicy.isTaskStream("ws://192.168.1.3:8080/remote/api/remote.mux", null))
    }
    @Test fun meshAndUsbRequireTheirExplicitProfiles() {
        val mesh = ConnectionProfile.create("TS", ConnectionProfile.Kind.MESH, "http://100.100.1.1:8080")
        assertTrue(HarnessSocketPolicy.isTaskStream("ws://100.100.1.1:8080/api/remote.mux", mesh))
        val usb = ConnectionProfile.create("USB", ConnectionProfile.Kind.USB, "http://dsh-usb.localhost:18765")
        assertFalse(HarnessSocketPolicy.isTaskStream("ws://dsh-usb.localhost:18765/api/remote.mux", usb, false))
        assertTrue(HarnessSocketPolicy.isTaskStream("ws://dsh-usb.localhost:18765/api/remote.mux", usb, true))
    }
    @Test fun legacyOverloadRejectsPrivateWithoutSelection() {
        assertFalse(HarnessSocketPolicy.isTaskStream("ws://192.168.1.3:8080/api/remote.mux", false))
    }
}
