package com.dsh.mobile.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网关自报的通道清单解析。
 *
 * 这些用例锁定一条边界：**网关说 ok 不等于手机到得了**。解析层只负责解释
 * 「这条故障该怪谁」，绝不允许自己被当成可用性判据。
 */
class GatewayChannelsTest {

    private val body = """
        {"ok":true,"phase":"connected",
         "tunnel":{"state":"client-rejected","reason":"[400] frpc 版本号无效","provider":"sakura"},
         "channels":[
           {"id":"tunnel","kind":"tunnel","origin":"https://demo.trycloudflare.com","available":true,"category":"ok","detail":""},
           {"id":"tunnel","kind":"tunnel","origin":"https://frp.example.cn","available":false,"category":"provider-flaky","detail":"[400] frpc 版本号无效"},
           {"id":"ipv6","kind":"ipv6","origin":"http://[2408:8000::1]:18772","available":true,"category":"ok","detail":""},
           {"id":"lan","kind":"lan","origin":"http://192.168.1.10:18772","available":true,"category":"ok","detail":""}
         ]}
    """.trimIndent()

    @Test
    fun readsEveryChannelWithItsCategory() {
        val channels = GatewayChannel.parsePairStatus(body)
        assertEquals(4, channels.size)
        assertEquals(listOf("tunnel", "tunnel", "ipv6", "lan"), channels.map { it.id })
        assertEquals("ok", channels[0].category)
        assertEquals("provider-flaky", channels[1].category)
        assertTrue(channels[1].detail.contains("frpc"))
    }

    @Test
    fun missingChannelsBlockIsNotAnError() {
        // 老网关没有 channels 字段：返回空列表，不因为没有清单就谎报可用/不可用。
        assertEquals(emptyList<GatewayChannel>(), GatewayChannel.parsePairStatus("""{"ok":true}"""))
        assertEquals(emptyList<GatewayChannel>(), GatewayChannel.parsePairStatus(""))
        assertEquals(emptyList<GatewayChannel>(), GatewayChannel.parsePairStatus("not json"))
    }

    @Test
    fun providerSideFaultIsSeparatedFromUserMisconfiguration() {
        // 用户明确要求过：查实是中转服务波动就报告，不要让他去查自己的电脑。
        val flaky = GatewayChannel("tunnel", "tunnel", "https://x", false, "provider-flaky", "[400] 版本号无效")
        val broken = GatewayChannel("lan", "lan", "http://1.2.3.4", false, "config-broken", "端口未监听")
        val ok = GatewayChannel("ipv6", "ipv6", "http://[2408::1]", true, "ok", "")

        assertTrue("中转侧故障不该让用户改自己电脑", flaky.providerSideFault)
        assertFalse(broken.providerSideFault)
        assertFalse(ok.providerSideFault)
        assertTrue("要说出结论: ${flaky.summary}", flaky.summary.contains("中转服务侧"))
        assertTrue(broken.summary.contains("配置"))
    }

    @Test
    fun matchesSavedProfilesByOriginNotByKind() {
        // 同一种通道可能存了两条（两个不同的 frp 节点 / 两条家宽），
        // 按 kind 匹配会把结论安到错的那条上。
        val cf = ConnectionProfile.create("CF", ConnectionProfile.Kind.TUNNEL, "https://demo.trycloudflare.com")
        val frp = ConnectionProfile.create("frp", ConnectionProfile.Kind.TUNNEL, "https://frp.example.cn")
        val matched = GatewayChannel.match(GatewayChannel.parsePairStatus(body), listOf(cf, frp))
        assertEquals("两条 tunnel 都必须各自对上自己那条", 2, matched.size)
        assertEquals("ok", matched[cf.id]?.category)
        assertEquals("provider-flaky", matched[frp.id]?.category)
    }

    @Test
    fun profileWithoutMatchingChannelIsSimplyAbsent() {
        // 没出现在网关清单里 = 没这条信息，不能当成"不可用"。
        val unknown = ConnectionProfile.create("mesh", ConnectionProfile.Kind.MESH, "http://100.100.1.2:3080")
        val matched = GatewayChannel.match(GatewayChannel.parsePairStatus(body), listOf(unknown))
        assertEquals(emptyMap<String, GatewayChannel>(), matched)
    }

    @Test
    fun originNormalizationAcceptsDifferentSpellings() {
        // 网关可能给 `http://host:80`，App 存的是 `http://host` —— 必须是同一条。
        val channels = listOf(
            GatewayChannel("lan", "lan", "http://10.1.2.3:80/", available = true, category = "ok", detail = ""),
        )
        val profile = ConnectionProfile.create("LAN", ConnectionProfile.Kind.LAN, "http://10.1.2.3")
        val matched = GatewayChannel.match(channels, listOf(profile))
        assertEquals(1, matched.size)
    }
}
