package com.dsh.mobile.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 启动探测的规则。
 *
 * 这些用例守的是三条会「反向伤害用户」的边界：
 *  - 401/403 必须算通（未配对不等于连不上，`ConnectionHealthTest` 已把它当契约）；
 *  - 没测到（context-changed 等）必须当「跳过」，不能当「失效」；
 *  - USB / 关了自动连接 / 正在配对，一律不探。
 */
class LaunchHealthTest {

    private fun reachable(status: Int) = Reachability(status = status, elapsedMs = 10)
    private fun failed(error: String = "SocketTimeoutException") = Reachability(elapsedMs = 10, error = error)

    // ---- shouldProbe ---------------------------------------------------------

    @Test
    fun probesAPlainSavedOriginOnLaunch() {
        assertTrue(
            LaunchHealth.shouldProbe(
                autoConnectOnLaunch = true,
                activeBaseUrl = "https://desk.example.net",
                kind = ConnectionProfile.Kind.CUSTOM,
                pairingUrl = "",
            ),
        )
    }

    @Test
    fun neverProbesWhenAutoConnectIsOff() {
        // 用户明确选了「启动时先选入口」，那就直接进选择页，不探测也不提示。
        assertFalse(
            LaunchHealth.shouldProbe(
                autoConnectOnLaunch = false,
                activeBaseUrl = "https://desk.example.net",
                kind = ConnectionProfile.Kind.CUSTOM,
                pairingUrl = "",
            ),
        )
    }

    @Test
    fun neverProbesUsbChannel() {
        // USB 走 adb reverse，普通 HTTP 探测必然误判。
        assertFalse(
            LaunchHealth.shouldProbe(
                autoConnectOnLaunch = true,
                activeBaseUrl = "http://dsh-usb.localhost:18765",
                kind = ConnectionProfile.Kind.USB,
                pairingUrl = "",
            ),
        )
    }

    @Test
    fun neverProbesWhileAPairingLinkIsPending() {
        // 一次性配对链接有有效期，不能当成「失效地址」。
        assertFalse(
            LaunchHealth.shouldProbe(
                autoConnectOnLaunch = true,
                activeBaseUrl = "https://desk.example.net",
                kind = ConnectionProfile.Kind.CUSTOM,
                pairingUrl = "https://desk.example.net/pair-accept?pair=abc",
            ),
        )
    }

    @Test
    fun neverProbesWithoutAnAddress() {
        assertFalse(
            LaunchHealth.shouldProbe(
                autoConnectOnLaunch = true,
                activeBaseUrl = "",
                kind = null,
                pairingUrl = "",
            ),
        )
    }

    @Test
    fun probesLanAddress() {
        // 用私网段里的占位地址（10/8），不写真实局域网拓扑。
        assertTrue(
            LaunchHealth.shouldProbe(
                autoConnectOnLaunch = true,
                activeBaseUrl = "http://10.0.0.1:18772",
                kind = ConnectionProfile.Kind.LAN,
                pairingUrl = "",
            ),
        )
    }

    // ---- verdict -------------------------------------------------------------

    @Test
    fun authRequiredCountsAsReachable() {
        // 401/403 = 网络通、只是没配对。当成「连不上」会打断首次使用流程。
        assertEquals(LaunchHealth.Verdict.Reachable, LaunchHealth.verdict("https://x.example.net", reachable(401)))
        assertEquals(LaunchHealth.Verdict.Reachable, LaunchHealth.verdict("https://x.example.net", reachable(403)))
    }

    @Test
    fun ordinarySuccessCountsAsReachable() {
        assertEquals(LaunchHealth.Verdict.Reachable, LaunchHealth.verdict("https://x.example.net", reachable(200)))
        assertEquals(LaunchHealth.Verdict.Reachable, LaunchHealth.verdict("https://x.example.net", reachable(302)))
    }

    @Test
    fun rateLimitIsUnreachable() {
        // 429 在 Reachability.reachable 里被明确排除（限流时不该继续压）。
        val verdict = LaunchHealth.verdict("https://x.example.net", reachable(429))
        assertTrue(verdict is LaunchHealth.Verdict.Unreachable)
        assertEquals("对方限流", (verdict as LaunchHealth.Verdict.Unreachable).reason)
    }

    @Test
    fun serverErrorIsUnreachable() {
        val verdict = LaunchHealth.verdict("https://x.example.net", reachable(502))
        assertTrue(verdict is LaunchHealth.Verdict.Unreachable)
        assertTrue((verdict as LaunchHealth.Verdict.Unreachable).reason.contains("502"))
    }

    @Test
    fun timeoutIsUnreachableWithAReadableReason() {
        val verdict = LaunchHealth.verdict("http://10.0.0.1:18772", failed("SocketTimeoutException"))
        assertTrue(verdict is LaunchHealth.Verdict.Unreachable)
        verdict as LaunchHealth.Verdict.Unreachable
        assertEquals("http://10.0.0.1:18772", verdict.origin)
        assertEquals("对方没有响应", verdict.reason)
    }

    @Test
    fun dnsFailureIsUnreachable() {
        val verdict = LaunchHealth.verdict("https://gone.example.net", failed("UnknownHostException"))
        assertEquals("地址解析不了", (verdict as LaunchHealth.Verdict.Unreachable).reason)
    }

    @Test
    fun unmeasuredProbeIsSkippedNotFailed() {
        // 冷启动那一瞬间的 context-changed 表示「这次没测到」，不是「测出来坏了」。
        // 用它判失效会误伤所有正常连接。
        assertEquals(
            LaunchHealth.Verdict.Skipped,
            LaunchHealth.verdict("https://x.example.net", Reachability(error = "context-changed")),
        )
        assertEquals(
            LaunchHealth.Verdict.Skipped,
            LaunchHealth.verdict("https://x.example.net", Reachability(error = "browser-not-ready")),
        )
        assertEquals(
            LaunchHealth.Verdict.Skipped,
            LaunchHealth.verdict("https://x.example.net", Reachability(error = "probe-unavailable")),
        )
    }
}
