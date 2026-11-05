package com.dsh.mobile.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 隧道状态解析与呈现。
 *
 * 这些用例锁定 2026-11-04 真实故障的教训：隧道客户端被服务端拒绝时，
 * 界面必须明确说出来，绝不能因为字段缺失或状态未知就显示成"正常"。
 */
class TunnelStatusTest {

    @Test
    fun readsTunnelBlockFromPairStatus() {
        val body = """
            {"ok":true,"phase":"connected",
             "tunnel":{"state":"running-unverified","reason":"","provider":"sakura"},
             "gateway":{"requests":234,"directRequests":0}}
        """.trimIndent()
        val status = TunnelStatus.fromPairStatus(body)
        assertEquals("running-unverified", status.state)
        assertEquals("sakura", status.provider)
        assertEquals(234L, status.gatewayRequests)
        assertTrue(status.working)
        assertEquals("外部通道正常", status.summary)
    }

    @Test
    fun rejectedTunnelIsNotWorkingAndExplainsWhy() {
        // 真机实测：clientState=client-rejected / clientReason=[400] frpc 版本号无效
        val body = """
            {"ok":true,"phase":"connected",
             "tunnel":{"state":"client-rejected","reason":"[400] frpc 版本号无效","provider":"sakura"}}
        """.trimIndent()
        val status = TunnelStatus.fromPairStatus(body)
        assertFalse("被服务端拒绝绝不能算正常", status.working)
        assertTrue(status.summary.contains("被服务端拒绝"))
        assertTrue(status.summary.contains("frpc 版本号无效"))
    }

    @Test
    fun missingTunnelBlockFallsBackToUnknownNotOk() {
        // 老网关没有 tunnel 字段：必须报"未获取到"，不能假装正常。
        val status = TunnelStatus.fromPairStatus("""{"ok":true,"phase":"connected"}""")
        assertEquals("unknown", status.state)
        assertFalse(status.working)
        assertEquals("未获取到通道状态", status.summary)
    }

    @Test
    fun malformedBodyDoesNotCrashOrPretendHealthy() {
        for (bad in listOf("", "not json", "{}", "null")) {
            val status = TunnelStatus.fromPairStatus(bad)
            assertEquals("input=$bad", TunnelStatus.UNKNOWN.state, status.state)
            assertFalse(status.working)
        }
    }

    @Test
    fun retryAndSpawnFailureAreReported() {
        val retry = TunnelStatus("waiting-to-retry", "", "sakura", 0)
        assertFalse(retry.working)
        assertTrue(retry.summary.contains("正在重试"))

        val spawned = TunnelStatus("spawn-failed", "", "sakura", 0)
        assertFalse(spawned.working)
        assertTrue(spawned.summary.contains("启动失败"))
    }

    @Test
    fun describePutsTunnelFailureAheadOfBusinessSuccess() {
        // 这正是故障当时的问题：业务层说"已收到响应"，但外部通道已被拒绝。
        // 隧道异常必须优先显示，否则用户被"看起来正常"误导。
        val text = ConnectionQualityPolicy.describe(
            route = "Wi-Fi",
            available = true,
            captive = false,
            authLost = false,
            socket = true,
            // status=200 → reachable/measured 由计算属性得出
            endpoint = Reachability(status = 200, elapsedMs = 109),
            probeFailures = 0,
            // lastSuccessAgeMs 在 0..60_000 才 verified
            business = BusinessHealth(lastSuccessAgeMs = 1_000),
            resourceLoad = null,
            tunnel = TunnelStatus("client-rejected", "[400] frpc 版本号无效", "sakura", 0),
        )
        assertTrue("必须提到被拒绝: $text", text.contains("被服务端拒绝"))
        assertFalse("不能被'已收到业务响应'盖住: $text", text.contains("已收到业务响应"))
    }

    @Test
    fun describeKeepsNormalPathWhenTunnelHealthy() {
        val text = ConnectionQualityPolicy.describe(
            route = "Wi-Fi",
            available = true,
            captive = false,
            authLost = false,
            socket = true,
            endpoint = Reachability(status = 200, elapsedMs = 109),
            probeFailures = 0,
            business = BusinessHealth(lastSuccessAgeMs = 1_000),
            resourceLoad = null,
            tunnel = TunnelStatus("running-unverified", "", "sakura", 12),
        )
        assertTrue("健康时应保留原提示: $text", text.contains("已收到业务响应"))
    }

    @Test
    fun unknownStateIsNotKnownSoItCannotBeReportedAsAFault() {
        // 契约：known == false 表示「没拿到状态」，调用方绝不能据此判通道故障。
        //
        // 背景（真机 2026-11-04）：手机经 5G 走中转时，探测可能拿到非 TLS 响应
        // （ID1ID2: actual 0x00001fef —— 0x1fef 是 UTF-8 中文字节，说明是明文 JSON）。
        // 这时隧道其实可用（WebView 主连接正常），若把「探测失败」当成「通道坏了」，
        // 自动选路会立刻切走并重载页面，把用户正在输入的内容刷掉。
        assertFalse("空白 state 不算已知", TunnelStatus("", "", "", 0).known)
        assertFalse("unknown 不算已知", TunnelStatus("unknown", "", "", 0).known)
        assertFalse("探测失败产出的 UNKNOWN 不算已知", TunnelStatus.UNKNOWN.known)

        // 反过来：所有真实状态都必须是 known，否则真正的故障会被静默吞掉。
        assertTrue(TunnelStatus("client-rejected", "[400] frpc 版本号无效", "sakura", 0).known)
        assertTrue(TunnelStatus("waiting-to-retry", "", "sakura", 0).known)
        assertTrue(TunnelStatus("running-unverified", "", "sakura", 12).known)
    }

    @Test
    fun knownIsFalseExactlyWhenSummarySaysStatusUnavailable() {
        // 两个口径必须一致：known 说"不知道"时，summary 也必须说"未获取到"，
        // 这样界面文案和判定逻辑不会互相打脸。
        val unknown = TunnelStatus.UNKNOWN
        assertFalse(unknown.known)
        assertEquals("未获取到通道状态", unknown.summary)
    }
}
