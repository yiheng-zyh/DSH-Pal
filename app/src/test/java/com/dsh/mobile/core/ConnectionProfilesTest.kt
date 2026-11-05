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

    // ---- IPv6 直连（新增通道）----

    @Test fun globalIpv6AddressesAreRecognizedWithAndWithoutBrackets() {
        for (host in listOf(
            "240a:42c6:e402:743:6504:f615:6326:cf3f",   // 实测的 PC 公网 IPv6
            "[240a:42c6:e402:743:6504:f615:6326:cf3f]",
            "2408:8000::1",
            "2606:4700::1",
            "2001:db7::1",                              // 紧邻文档段但不是文档段
            "2002::1",                                  // 6to4，仍可路由
        )) assertTrue(host, ConnectionAddress.isGlobalIpv6(host))
    }

    @Test fun nonRoutableIpv6ClassesAreNeverGlobal() {
        for (host in listOf(
            "::1", "::", "[::1]", "0:0:0:0:0:0:0:1",     // 回环与未指定
            "fe80::1", "fe9f::1", "febf:ffff::1",        // fe80::/10 链路本地全段（fe80–febf）
            "fc00::1", "fd00::1", "fd7a:115c:a1e0::c029:d60e",  // fc00::/7 ULA（含 Tailscale）
            "ff02::1", "ff12::1",                        // ff00::/8 组播
            "2001:db8::1", "2001:0db8:1234::9",          // 2001:db8::/32 文档
            "::ffff:127.0.0.1", "::ffff:192.168.1.1",    // IPv4 映射
        )) assertFalse(host, ConnectionAddress.isGlobalIpv6(host))
        // 非 IPv6 与非法写法一律不是「全局 IPv6」
        for (host in listOf("192.168.1.1", "example.org", "2408:8000:::1", "1:2:3:4:5:6:7", "1:2:3:4:5:6:7:8:9", "g408::1"))
            assertFalse(host, ConnectionAddress.isGlobalIpv6(host))
    }

    @Test fun localishFollowsIpv6SemanticsInsteadOfStringPrefixes() {
        for (host in listOf("::1", "::", "[::1]", "fe80::1", "fe9f::1", "fc00::1", "fd7a:115c:a1e0::1",
            "::ffff:127.0.0.1", "2001:db8::1", "ff02::1"))
            assertTrue(host, ConnectionAddress.isLocalish(host))
        for (host in listOf("240a:42c6:e402:743:6504:f615:6326:cf3f", "2408:8000::1", "[2408:8000::1]"))
            assertFalse(host, ConnectionAddress.isLocalish(host))
        // 原有 IPv4/域名行为不变
        for (host in listOf("localhost", "a.localhost", "127.1.2.3", "0.0.0.0", "10.0.0.1", "100.64.0.1",
            "169.254.1.1", "0x7f000001", "127.1", "2130706433"))
            assertTrue(host, ConnectionAddress.isLocalish(host))
        assertFalse(ConnectionAddress.isLocalish("example.org"))
    }

    @Test fun bareIpv6AuthorityGetsBracketedBeforeParsing() {
        // origin() 从不补 scheme（补 http:// 是 directInput 的职责）。带 scheme 的裸 IPv6
        // 会被补方括号；无 scheme 的裸串 URI 直接抛异常 → origin 返回 null。
        assertEquals("http://[2408:8000::1]", ConnectionAddress.origin("http://2408:8000::1"))
        assertNull(ConnectionAddress.origin("2408:8000::1"))
        assertEquals("http://[2408:8000::1]:8080", ConnectionAddress.origin("http://[2408:8000::1]:8080"))
        assertEquals("https://[240a:42c6:e402:743::1]", ConnectionAddress.origin("https://[240a:42c6:e402:743::1]:443/x?pair=s"))
        // 不该被误补括号：host:port、域名、默认端口
        assertEquals("http://192.168.1.4:8080", ConnectionAddress.origin("http://192.168.1.4:8080"))
        assertEquals("https://example.org", ConnectionAddress.origin("https://example.org"))
        assertEquals("http://example.org", ConnectionAddress.origin("http://example.org:80"))
        assertNull(ConnectionAddress.origin("http://user:pw@[2408:8000::1]:8080"))
        assertNull(ConnectionAddress.origin("http://[2408:8000::1]:99999"))
    }

    @Test fun ipv6RouteHasItsOwnKindAndNeverLeaksIntoLanOrMesh() {
        val url = "http://[240a:42c6:e402:743:6504:f615:6326:cf3f]:18772/pair-accept?pair=fixture"
        assertEquals(ConnectionProfile.Kind.IPV6, ConnectionAddress.infer(url))
        val profile = ConnectionProfile.create("", ConnectionProfile.Kind.IPV6, url)
        assertEquals(ConnectionProfile.Kind.IPV6, profile.kind)
        assertEquals("http://[240a:42c6:e402:743:6504:f615:6326:cf3f]:18772", profile.baseUrl)
        assertEquals(ConnectionProfile.Kind.IPV6.title, profile.label)
        // 显式选 LAN 会拒绝全局 IPv6（LAN 只认私有 IPv4）。
        assertThrows(IllegalArgumentException::class.java) { ConnectionProfile.create("", ConnectionProfile.Kind.LAN, url) }
        // 已知项 #10：MESH 分类过宽（任何含字母的非回环 host 都被接受），全局 IPv6 含十六进制字母，
        // 故显式选 MESH 仍会接受它。收紧会误伤 WireGuard 自定义域名，本轮不动；infer() 已优先判 IPV6，
        // 扫码/自动识别不会把全局 IPv6 落到 MESH。这里如实固化既有边界。
        assertEquals(ConnectionProfile.Kind.MESH, ConnectionProfile.create("", ConnectionProfile.Kind.MESH, url).kind)
        // 序列化往返保留类型
        assertEquals(profile, ConnectionProfiles.decode(ConnectionProfiles.encode(listOf(profile))).single())
        // 非全局 IPv6 一律存不进 IPV6 通道
        for (bad in listOf("http://[::1]:8080", "http://[fe80::1]:8080", "http://[fd7a:115c:a1e0::1]:8080", "http://[2001:db8::1]:8080"))
            assertThrows(IllegalArgumentException::class.java) { ConnectionProfile.create("", ConnectionProfile.Kind.IPV6, bad) }
    }

    @Test fun tailscaleUlaStaysMeshAndIsNotPromotedToIpv6() {
        // fd7a: 是 Tailscale 的 ULA，属于组网而非公网 IPv6 直连；顺序若颠倒会把组网入口显示成 IPv6。
        assertEquals(ConnectionProfile.Kind.MESH, ConnectionAddress.infer("http://[fd7a:115c:a1e0::c029:d60e]:8080"))
        assertEquals(ConnectionProfile.Kind.MESH, ConnectionAddress.infer("http://100.100.1.1:8080"))
        assertEquals(ConnectionProfile.Kind.LAN, ConnectionAddress.infer("http://192.168.1.10:8080"))
        assertNull(ConnectionAddress.publicIpv6Kind("http://[fd7a:115c:a1e0::1]:8080"))
        assertNotNull(ConnectionAddress.publicIpv6Kind("http://[2408:8000::1]:8080"))
    }

    @Test fun directInputAcceptsBothDirectKindsAndRejectsDomains() {
        assertEquals("http://192.168.1.4:8080" to ConnectionProfile.Kind.LAN, ConnectionAddress.directInput("192.168.1.4:8080"))
        assertEquals("http://10.0.0.7:8080" to ConnectionProfile.Kind.LAN, ConnectionAddress.directInput("http://10.0.0.7:8080"))
        // IPv6 带端口必须用方括号；无端口裸串可自动补 http:// 与方括号。
        assertEquals(
            "http://[240a:42c6:e402:743::1]:18772" to ConnectionProfile.Kind.IPV6,
            ConnectionAddress.directInput("[240a:42c6:e402:743::1]:18772"),
        )
        assertEquals(
            "http://[240a:42c6:e402:743::1]" to ConnectionProfile.Kind.IPV6,
            ConnectionAddress.directInput("240a:42c6:e402:743::1"),
        )
        // 裸 IPv6 带端口有歧义（:18772 是 5 位非法十六进制组），安全失败返回 null，不猜地址。
        assertNull(ConnectionAddress.directInput("240a:42c6:e402:743::1:18772"))
        // 域名与不可路由地址都不是「直连」
        for (bad in listOf("example.org:8080", "192.168.1.999:8080", "8.8.8.8:80", "[::1]:8080", "[fe80::1]:80", "[fd7a:115c:a1e0::1]:80"))
            assertNull(bad, ConnectionAddress.directInput(bad))
        // localInput 保持旧语义（只要 origin，且仍限局域网 IPv4）
        assertEquals("http://192.168.1.4:8080", ConnectionAddress.localInput("192.168.1.4:8080"))
        assertNull(ConnectionAddress.localInput("example.org:8080"))
        assertNull(ConnectionAddress.localInput("192.168.1.999:8080"))
    }

    @Test fun ipv6QrIsClassifiedWithoutRewritingItsHost() {
        val v6 = "http://[240a:42c6:e402:743:6504:f615:6326:cf3f]:18772/pair-accept?pair=fixture"
        assertEquals(ConnectionProfile.Kind.IPV6, PairingQr.parse(v6).kind)
        assertEquals(v6, PairingQr.parse(v6).url)
        // 深链包装同样识别
        val wrapped = "dshmobile://open?url=" + java.net.URLEncoder.encode(v6, "UTF-8")
        assertEquals(ConnectionProfile.Kind.IPV6, PairingQr.parse(wrapped).kind)
        // 原有通道不受影响
        assertEquals(ConnectionProfile.Kind.LAN, PairingQr.parse("http://192.168.1.10:9000/pair-accept?pair=fixture").kind)
        assertEquals(ConnectionProfile.Kind.MESH, PairingQr.parse("https://example.ts.net/pair-accept?pair=fixture").kind)
        // 回环 IPv6 无法归入任何通道：infer 返回 null，PairingQr 用 error() 抛 IllegalStateException。
        assertThrows(IllegalStateException::class.java) { PairingQr.parse("http://[::1]:8080/pair-accept?pair=fixture") }
    }

    @Test fun loopbackCoversIpv4AndIpv6Forms() {
        // 手机不可能经回环到达电脑；这类地址只可能是 adb 调试残留。
        for (host in listOf("127.0.0.1", "127.1.2.3", "localhost", "a.localhost", "::1", "[::1]", "::", "[::]"))
            assertTrue(host, ConnectionAddress.isLoopback(host))
        // 局域网与公网地址不是回环，保存后不能清掉
        for (host in listOf("192.168.1.4", "10.0.0.7", "172.31.144.190", "[2408:8000::1]", "2408:8000::1",
            "example.org", "[fd7a:115c:a1e0::1]"))
            assertFalse(host, ConnectionAddress.isLoopback(host))
        // 与 isLocalish 的分工：局域网地址是 localish 但不是 loopback
        assertTrue(ConnectionAddress.isLocalish("192.168.1.4"))
        assertFalse(ConnectionAddress.isLoopback("192.168.1.4"))
    }

    @Test fun ipv6TaskStreamIsAllowedAndPinnedToItsOwnOrigin() {
        val profile = ConnectionProfile.create("IPv6", ConnectionProfile.Kind.IPV6, "http://[2408:8000::1]:18772")
        assertTrue(HarnessSocketPolicy.isTaskStream("ws://[2408:8000::1]:18772/remote/api/remote.mux", profile))
        assertTrue(HarnessSocketPolicy.isTaskStream("ws://[2408:8000::1]:18772/api/remote.mux", profile))
        for (url in listOf(
            "ws://[2408:8000::2]:18772/api/remote.mux",       // 别的地址
            "ws://[2408:8000::1]:18773/api/remote.mux",       // 别的端口
            "wss://[2408:8000::1]:18772/api/remote.mux",      // 明文入口不接受 wss
            "ws://[2408:8000::1]:18772/ingest",               // 非任务流路径
        )) assertFalse(url, HarnessSocketPolicy.isTaskStream(url, profile))
        // 回环 IPv6 永远不放行（无 profile 重载）
        assertFalse(HarnessSocketPolicy.isTaskStream("ws://[::1]:18772/api/remote.mux", false))
    }
}
