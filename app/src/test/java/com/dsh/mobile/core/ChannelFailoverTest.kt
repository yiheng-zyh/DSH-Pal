package com.dsh.mobile.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通道自动选路的纯逻辑：优先级、保命触发、30 分钟静默、手动干预后暂停。
 *
 * 为什么不测 [ChannelFailover] 的网络部分：那需要真的连一台电脑。真正容易写错的
 * 是「什么时候才该动手」这类策略，全部落在 [ChannelPolicy] 与 [ChannelFailoverEngine]
 * 里，用假时钟就能把时间相关的规则一条条钉死。
 */
class ChannelFailoverTest {

    // ---- 测试夹具 ---------------------------------------------------------

    private fun lan(id: String = "lan-1") =
        ConnectionProfile(id, "家里 Wi-Fi", ConnectionProfile.Kind.LAN, "http://192.168.1.10:3080")

    private fun ipv6(id: String = "v6-1") =
        ConnectionProfile(id, "IPv6", ConnectionProfile.Kind.IPV6, "http://[2408:8000::1]:3080")

    private fun tunnel(id: String = "cf-1") =
        ConnectionProfile(id, "Cloudflare", ConnectionProfile.Kind.TUNNEL, "https://demo.trycloudflare.com")

    private fun relay(id: String = "relay-1") =
        ConnectionProfile(id, "Relay", ConnectionProfile.Kind.RELAY, "https://demo.dsh-market.com")

    private fun mesh(id: String = "ts-1") =
        ConnectionProfile(id, "Tailscale", ConnectionProfile.Kind.MESH, "http://100.100.1.2:3080")

    private fun custom(id: String = "frp-1") =
        ConnectionProfile(id, "国内 frp", ConnectionProfile.Kind.CUSTOM, "https://frp.example.cn")

    private fun ok(vararg ids: String): Map<String, ChannelHealth> =
        ids.associateWith { ChannelHealth(it, available = true, elapsedMs = 30, detail = "入口可用 · 30 ms") }

    private fun bad(vararg ids: String): Map<String, ChannelHealth> =
        ids.associateWith { ChannelHealth(it, available = false, detail = "连接超时") }

    private fun merge(vararg maps: Map<String, ChannelHealth>): Map<String, ChannelHealth> =
        maps.fold(emptyMap()) { acc, m -> acc + m }

    // ---- 优先级 -----------------------------------------------------------

    @Test
    fun defaultOrderIsLanThenIpv6ThenTunnelThenMesh() {
        // 用户明确确认过的顺序，改了就是违背需求。
        assertEquals(
            listOf(
                ConnectionProfile.Kind.LAN,
                ConnectionProfile.Kind.IPV6,
                ConnectionProfile.Kind.TUNNEL,
                ConnectionProfile.Kind.MESH,
            ),
            ChannelPolicy.defaultOrder().take(4),
        )
    }

    @Test
    fun parseOrderKeepsUserOrderAndDropsGarbage() {
        val parsed = ChannelPolicy.parseOrder("MESH, lan ,bogus,,MESH,CUSTOM")
        assertEquals(ConnectionProfile.Kind.MESH, parsed[0])
        assertEquals(ConnectionProfile.Kind.LAN, parsed[1])
        assertEquals(ConnectionProfile.Kind.CUSTOM, parsed[2])
        // 脏数据/重复被清掉后仍然是一个**全序**：每一种 Kind 都在里面，且无重复。
        assertEquals(ChannelPolicy.allKinds().toSet(), parsed.toSet())
        assertEquals(ChannelPolicy.allKinds().size, parsed.size)
    }

    @Test
    fun parseOrderFallsBackToDefaultOnBlankInput() {
        assertEquals(ChannelPolicy.defaultOrder(), ChannelPolicy.parseOrder(""))
    }

    @Test
    fun encodeRoundTripsThroughParse() {
        val custom = listOf(
            ConnectionProfile.Kind.IPV6,
            ConnectionProfile.Kind.LAN,
        ) + ChannelPolicy.allKinds().filterNot {
            it == ConnectionProfile.Kind.IPV6 || it == ConnectionProfile.Kind.LAN
        }
        assertEquals(custom, ChannelPolicy.parseOrder(ChannelPolicy.encodeOrder(custom)))
    }

    @Test
    fun orderedPutsLanFirstEvenWhenProfilesAreShuffled() {
        val list = listOf(mesh(), tunnel(), lan(), ipv6())
        val ordered = ChannelPolicy.ordered(list, ChannelPolicy.defaultOrder())
        assertEquals(listOf("lan-1", "v6-1", "cf-1", "ts-1"), ordered.map { it.id })
    }

    @Test
    fun firstUsableSkipsUsbBecauseItNeedsACable() {
        val usb = ConnectionProfile("usb-1", "USB", ConnectionProfile.Kind.USB, "http://localhost:3080")
        val current = lan()
        val chosen = ChannelPolicy.firstUsable(
            current = current,
            profiles = listOf(usb, ipv6()),
            order = ChannelPolicy.defaultOrder(),
            health = ok("usb-1", "v6-1"),
        )
        assertEquals("自动切到 USB 必然是失败的（它需要数据线）", "v6-1", chosen?.id)
    }

    @Test
    fun firstUsableReturnsNullWhenNothingAnswers() {
        // 一条都不通时不能假装有目标 —— 否则用户会从「连不上」变成「连不上且页面被刷了」。
        assertNull(
            ChannelPolicy.firstUsable(
                current = lan(),
                profiles = listOf(ipv6(), tunnel()),
                order = ChannelPolicy.defaultOrder(),
                health = bad("v6-1", "cf-1"),
            )
        )
    }

    @Test
    fun firstUsableNeverReturnsTheCurrentChannel() {
        assertNull(
            ChannelPolicy.firstUsable(
                current = lan(),
                profiles = listOf(lan()),
                order = ChannelPolicy.defaultOrder(),
                health = ok("lan-1"),
            )
        )
    }

    // ---- 「国内直连 / 国外中转」判定 ---------------------------------------

    @Test
    fun domesticDirectMeansNoRelayHop() {
        assertTrue("局域网私有 IPv4 是直连", ChannelPolicy.isDomesticDirect(lan()))
        assertTrue("全局单播 IPv6 是直连", ChannelPolicy.isDomesticDirect(ipv6()))
    }

    @Test
    fun customWithLiteralPublicIpCountsAsDirectButDomainDoesNot() {
        // frp / 自建都在 CUSTOM 里：靠地址判类型，不能靠 kind 猜。
        val literal = ConnectionProfile("c1", "自有公网", ConnectionProfile.Kind.CUSTOM, "http://[2408:8000::9]:3080")
        val domain = ConnectionProfile("c2", "frp 域名", ConnectionProfile.Kind.CUSTOM, "https://frp.example.cn")
        assertTrue(ChannelPolicy.isDomesticDirect(literal))
        assertFalse("域名必然过 CDN/中转，不算直连", ChannelPolicy.isDomesticDirect(domain))
    }

    @Test
    fun cloudflareAndRelayAreForeignRelays() {
        assertTrue(ChannelPolicy.isForeignRelay(tunnel()))
        assertTrue(ChannelPolicy.isForeignRelay(relay()))
        assertFalse(ChannelPolicy.isForeignRelay(lan()))
        assertFalse(ChannelPolicy.isForeignRelay(mesh()))
    }

    // ---- 保命切换的触发条件 -----------------------------------------------

    private class Clock(var now: Long = 1_000_000L) {
        fun read(): Long = now
        fun advance(ms: Long) { now += ms }
    }

    private fun engine(clock: Clock) = ChannelFailoverEngine(clock = clock::read)

    @Test
    fun singleFailureIsNotEnoughToReloadThePage() {
        // 手机网络上单次探测失败太常见（切基站/丢包/Doze 刚醒）。
        // 一次就切会让页面在日常使用中反复重载 —— 这正是用户最反感的行为。
        val clock = Clock()
        val engine = engine(clock)
        val decision = engine.observe(
            current = lan(),
            profiles = listOf(lan(), ipv6()),
            order = ChannelPolicy.defaultOrder(),
            health = merge(bad("lan-1"), ok("v6-1")),
            autoSwitch = true,
        )
        assertEquals(ChannelDecision.Idle, decision)
        assertEquals(1, engine.consecutiveFailures)
    }

    @Test
    fun secondConsecutiveFailureAsksBeforeSwitchingToFirstUsableInPriorityOrder() {
        val clock = Clock()
        val engine = engine(clock)
        val profiles = listOf(mesh(), tunnel(), lan(), ipv6())
        val health = merge(bad("lan-1"), ok("v6-1", "cf-1", "ts-1"))

        assertEquals(
            ChannelDecision.Idle,
            engine.observe(lan(), profiles, ChannelPolicy.defaultOrder(), health, autoSwitch = true),
        )
        val decision = engine.observe(lan(), profiles, ChannelPolicy.defaultOrder(), health, autoSwitch = true)
        // 2026-11-05 用户要求：不要直接切，先提示 + 倒计时，用户可以拒绝。
        // 原断言是 `is ChannelDecision.Failover`（直接切），已随行为一起改。
        assertTrue("第二次连续失败必须先征询: $decision", decision is ChannelDecision.ConfirmSwitch)
        decision as ChannelDecision.ConfirmSwitch
        assertEquals("优先级里 IPv6 排在 Cloudflare / Tailscale 之前", "v6-1", decision.target.id)
        assertEquals("lan-1", decision.from.id)
        assertTrue("原因要说人话: ${decision.reason}", decision.reason.contains("IPv6"))
        assertTrue("必须带倒计时，界面上要显示还剩几秒", decision.countdownSeconds > 0)
        // 计数清零，避免下一轮立刻再问一次。
        assertEquals(0, engine.consecutiveFailures)
    }

    @Test
    fun oneSuccessResetsTheFailureStreak() {
        val clock = Clock()
        val engine = engine(clock)
        val profiles = listOf(lan(), ipv6())
        engine.observe(lan(), profiles, ChannelPolicy.defaultOrder(), merge(bad("lan-1"), ok("v6-1")), true)
        assertEquals(1, engine.consecutiveFailures)
        engine.observe(lan(), profiles, ChannelPolicy.defaultOrder(), ok("lan-1", "v6-1"), true)
        assertEquals("当前通道恢复了就不能再记着旧账", 0, engine.consecutiveFailures)
        val again = engine.observe(lan(), profiles, ChannelPolicy.defaultOrder(), merge(bad("lan-1"), ok("v6-1")), true)
        assertEquals("恢复后重新从 1 计数", ChannelDecision.Idle, again)
    }

    @Test
    fun unmeasuredChannelIsNeverTreatedAsFailed() {
        // 本轮没测到的通道不能被当成「不可用」：那会把用户切到一条没验证过的路上。
        val clock = Clock()
        val engine = engine(clock)
        repeat(4) {
            val decision = engine.observe(
                current = lan(),
                profiles = listOf(lan(), ipv6()),
                order = ChannelPolicy.defaultOrder(),
                health = emptyMap(), // 一条都没测
                autoSwitch = true,
            )
            assertEquals(ChannelDecision.Idle, decision)
        }
        assertEquals("没测到不该累积失败次数", 0, engine.consecutiveFailures)
    }

    @Test
    fun autoSwitchOffKeepsFailuresFromDoingAnything() {
        val clock = Clock()
        val engine = engine(clock)
        val profiles = listOf(lan(), ipv6())
        val health = merge(bad("lan-1"), ok("v6-1"))
        repeat(5) {
            val decision = engine.observe(lan(), profiles, ChannelPolicy.defaultOrder(), health, autoSwitch = false)
            assertEquals("开关关掉就不许动: $decision", ChannelDecision.Idle, decision)
        }
    }

    @Test
    fun reEnablingDoesNotSwitchOnTheVeryFirstObservation() {
        // 关着的时候不能偷偷攒失败次数。
        // 否则用户重新打开开关的第一轮，计数已 >= 2，会立刻判"连续两次不通"切走 ——
        // 但他刚打开开关，还没看到任何证据。这等于把「连续 2 次」的保护绕过去了。
        val clock = Clock()
        val engine = engine(clock)
        val profiles = listOf(lan(), ipv6())
        val currentDown = merge(bad("lan-1"), ok("v6-1"))

        // 关着的时候连着失败 5 轮
        repeat(5) {
            engine.observe(lan(), profiles, ChannelPolicy.defaultOrder(), currentDown, autoSwitch = false)
        }
        assertEquals("关着时不该累积失败次数", 0, engine.consecutiveFailures)

        // 重新打开：第一轮必须仍然不动
        assertEquals(
            "刚打开开关不能立刻切",
            ChannelDecision.Idle,
            engine.observe(lan(), profiles, ChannelPolicy.defaultOrder(), currentDown, autoSwitch = true),
        )
        // 第二轮（连续两次真实观察）才允许动，且是**征询**而非直接切
        val decision = engine.observe(lan(), profiles, ChannelPolicy.defaultOrder(), currentDown, autoSwitch = true)
        assertTrue("观察满两轮后才征询: $decision", decision is ChannelDecision.ConfirmSwitch)
    }

    @Test
    fun noUsableTargetMeansStayPutAndKeepProbing() {
        val clock = Clock()
        val engine = engine(clock)
        val profiles = listOf(lan(), ipv6(), tunnel())
        val health = bad("lan-1", "v6-1", "cf-1")
        repeat(3) {
            val decision = engine.observe(lan(), profiles, ChannelPolicy.defaultOrder(), health, true)
            assertEquals(ChannelDecision.Idle, decision)
        }
        assertTrue("没目标时失败计数继续涨（用来决定退避）", engine.consecutiveFailures >= 2)
    }

    // ---- 优化提示：只在「国外中转 + 国内直连可用」时 -----------------------

    @Test
    fun suggestsDomesticDirectWhenCurrentlyOnForeignRelay() {
        val clock = Clock()
        val engine = engine(clock)
        val current = tunnel()
        val profiles = listOf(current, lan())
        val decision = engine.observe(
            current, profiles, ChannelPolicy.defaultOrder(), merge(ok("cf-1"), ok("lan-1")), true,
        )
        assertTrue("走 Cloudflare 而家里直连可用，必须建议换回: $decision", decision is ChannelDecision.Suggest)
        decision as ChannelDecision.Suggest
        assertEquals("lan-1", decision.target.id)
        assertEquals("cf-1", decision.from.id)
    }

    @Test
    fun neverSuggestsWhileAlreadyOnDirectConnection() {
        // 已经在直连上，没有任何「更好的通道」可言，绝不能打扰。
        val clock = Clock()
        val engine = engine(clock)
        val current = ipv6()
        val decision = engine.observe(
            current, listOf(current, lan()), ChannelPolicy.defaultOrder(), ok("v6-1", "lan-1"), true,
        )
        assertEquals(ChannelDecision.Idle, decision)
    }

    @Test
    fun neverSuggestsAnotherRelayWhenDirectIsUnavailable() {
        // 从一条中转换到另一条中转并不解决用户的问题（还是要绕出国）。
        val clock = Clock()
        val engine = engine(clock)
        val current = tunnel()
        val decision = engine.observe(
            current,
            profiles = listOf(current, relay()),
            order = ChannelPolicy.defaultOrder(),
            health = merge(ok("cf-1"), ok("relay-1")),
            autoSwitch = true,
        )
        assertEquals(ChannelDecision.Idle, decision)
    }

    @Test
    fun neverSuggestsGoingDownInPriority() {
        // 用户可以把顺序调成「中转优先」；此时当前在排位更靠前的中转上，
        // 换到排位更后的直连属于倒退 —— 提示必须消失，否则就是在打自己的脸。
        val clock = Clock()
        val engine = engine(clock)
        val customOrder = listOf(
            ConnectionProfile.Kind.TUNNEL,
            ConnectionProfile.Kind.IPV6,
            ConnectionProfile.Kind.LAN,
        ) + ChannelPolicy.allKinds().filterNot {
            it == ConnectionProfile.Kind.TUNNEL ||
                it == ConnectionProfile.Kind.IPV6 ||
                it == ConnectionProfile.Kind.LAN
        }
        val current = tunnel()
        val decision = engine.observe(
            current, listOf(current, lan()), customOrder, merge(ok("cf-1"), ok("lan-1")), true,
        )
        assertEquals("直连排位更靠后时不该建议换过去: $decision", ChannelDecision.Idle, decision)
    }

    // ---- 30 分钟静默 ------------------------------------------------------

    @Test
    fun declinedPromptSilencesForThirtyMinutes() {
        val clock = Clock()
        val engine = engine(clock)
        val current = tunnel()
        val profiles = listOf(current, lan())
        val health = merge(ok("cf-1"), ok("lan-1"))

        val first = engine.observe(current, profiles, ChannelPolicy.defaultOrder(), health, true)
        assertTrue(first is ChannelDecision.Suggest)

        clock.advance(60_000) // 用户此时点了「不用」
        engine.onPromptDeclined()

        clock.advance(1_000)
        assertEquals(
            "被拒绝后 1 秒内绝不能再弹",
            ChannelDecision.Idle,
            engine.observe(current, profiles, ChannelPolicy.defaultOrder(), health, true),
        )

        clock.advance(29 * 60_000L)
        assertEquals(
            "29 分钟时仍在静默期",
            ChannelDecision.Idle,
            engine.observe(current, profiles, ChannelPolicy.defaultOrder(), health, true),
        )

        clock.advance(61_000L) // 越过 30 分钟
        val after = engine.observe(current, profiles, ChannelPolicy.defaultOrder(), health, true)
        assertTrue("静默期过后可以再问一次: $after", after is ChannelDecision.Suggest)
    }

    @Test
    fun promptingOnceOccupiesTheSilenceWindowBeforeTheUserAnswers() {
        // 弹之前就先占上静默窗口：界面被别的事情吞掉时，不会变成每 20 秒弹一次。
        val clock = Clock()
        val engine = engine(clock)
        val current = tunnel()
        val profiles = listOf(current, lan())
        val health = merge(ok("cf-1"), ok("lan-1"))
        assertTrue(engine.observe(current, profiles, ChannelPolicy.defaultOrder(), health, true) is ChannelDecision.Suggest)
        assertEquals(
            ChannelDecision.Idle,
            engine.observe(current, profiles, ChannelPolicy.defaultOrder(), health, true),
        )
    }

    @Test
    fun restoredSilenceSurvivesAppRestart() {
        // 用户拒绝后 App 被系统杀掉重启，立刻又弹同一个提示 = 「它记不住我说了不」。
        val clock = Clock()
        val engine = engine(clock)
        engine.restore(manualHoldUntil = 0L, promptSilencedUntil = clock.now + 10 * 60_000L)
        val current = tunnel()
        val decision = engine.observe(
            current, listOf(current, lan()), ChannelPolicy.defaultOrder(), merge(ok("cf-1"), ok("lan-1")), true,
        )
        assertEquals(ChannelDecision.Idle, decision)
    }

    // ---- 手动干预后暂停 ---------------------------------------------------

    @Test
    fun manualSwitchPausesBothFailoverAndPromptForThirtyMinutes() {
        val clock = Clock()
        val engine = engine(clock)
        val current = tunnel()
        val profiles = listOf(current, lan(), ipv6())
        val healthy = merge(ok("cf-1"), ok("lan-1", "v6-1"))
        val currentDown = merge(bad("cf-1"), ok("lan-1", "v6-1"))

        engine.onManualSwitch()

        // 立刻：既不许建议，也不许把他拽走。
        assertEquals(ChannelDecision.Idle, engine.observe(current, profiles, ChannelPolicy.defaultOrder(), healthy, true))
        repeat(5) {
            assertEquals(
                "手动切过之后不许自动切",
                ChannelDecision.Idle,
                engine.observe(current, profiles, ChannelPolicy.defaultOrder(), currentDown, true),
            )
        }

        // 累计推进到 29 分钟：仍在暂停期内
        clock.advance(29 * 60_000L)
        repeat(3) {
            assertEquals(
                "暂停期内仍不许动手",
                ChannelDecision.Idle,
                engine.observe(current, profiles, ChannelPolicy.defaultOrder(), currentDown, true),
            )
        }

        // 再推 2 分钟 → 累计 31 分钟，越过 30 分钟暂停窗口。
        clock.advance(2 * 60_000L)
        // 与 autoSwitch 关闭再打开同一个道理：暂停期内不能偷偷攒失败次数，
        // 否则解除暂停的**第一轮**就以「连续多次不通」为由立刻切走 —— 那时用户
        // 才刚恢复自动选路，还没看到任何证据。
        assertEquals(
            "刚解除暂停不能立刻切",
            ChannelDecision.Idle,
            engine.observe(current, profiles, ChannelPolicy.defaultOrder(), currentDown, true),
        )
        val decision = engine.observe(current, profiles, ChannelPolicy.defaultOrder(), currentDown, true)
        assertTrue("观察满两轮后必须恢复保命能力（征询）: $decision", decision is ChannelDecision.ConfirmSwitch)
    }

    @Test
    fun suggestComesBackAfterThePauseExpires() {
        val clock = Clock()
        val engine = engine(clock)
        val current = tunnel()
        val profiles = listOf(current, lan())
        val health = merge(ok("cf-1"), ok("lan-1"))
        engine.onManualSwitch()
        assertEquals(ChannelDecision.Idle, engine.observe(current, profiles, ChannelPolicy.defaultOrder(), health, true))
        clock.advance(31 * 60_000L)
        assertTrue(
            "暂停结束后提示可以恢复（但仍有 30 分钟静默要各自计）",
            engine.observe(current, profiles, ChannelPolicy.defaultOrder(), health, true) is ChannelDecision.Suggest,
        )
    }

    // ---- 退避 -------------------------------------------------------------

    @Test
    fun recoveryProbeBackoffGrowsThenCapsAtSixtySeconds() {
        // 失败立即切（由 observe 负责），恢复探测才退避；60 秒封顶保证
        // 「它好了我能比较快发现」，同时不会一直敲一条挂掉的路。
        assertEquals(5_000L, RecoveryBackoff.delayMs(0))
        assertEquals(10_000L, RecoveryBackoff.delayMs(1))
        assertEquals(30_000L, RecoveryBackoff.delayMs(2))
        assertEquals(60_000L, RecoveryBackoff.delayMs(3))
        assertEquals(60_000L, RecoveryBackoff.delayMs(4))
        assertEquals(60_000L, RecoveryBackoff.delayMs(99))
        assertEquals("负数不能炸", 5_000L, RecoveryBackoff.delayMs(-3))
        assertTrue(
            "正常巡检必须比恢复探测稀疏得多",
            RecoveryBackoff.HEALTHY_INTERVAL_MS > RecoveryBackoff.delayMs(0),
        )
    }

    @Test
    fun firstRetryAfterAFailureWaitsTheShortestStep() {
        // 锁定主循环的退避取值方式：连续第 N 次失败应等 delayMs(N-1)。
        //
        // 为什么单独钉这一条：主循环里 attempt 是「先自增再取延时」，
        // 若直接用自增后的值，**第一次**失败会等 10s 而不是 5s —— 而第一次重试
        // 恰恰是最该快的那次（用户多半刚发现问题，通道也可能刚恢复）。
        fun waitAfter(consecutiveFailures: Int): Long =
            RecoveryBackoff.delayMs(consecutiveFailures - 1)

        assertEquals("第一次失败后等 5 秒", 5_000L, waitAfter(1))
        assertEquals("第二次 10 秒", 10_000L, waitAfter(2))
        assertEquals("第三次 30 秒", 30_000L, waitAfter(3))
        assertEquals("第四次起封顶 60 秒", 60_000L, waitAfter(4))
        assertEquals("再久也还是 60 秒", 60_000L, waitAfter(20))
    }

    // ---- 决定本身的语义 ---------------------------------------------------

    @Test
    fun confirmSwitchCarriesEverythingTheCallerNeeds() {
        val clock = Clock()
        val engine = engine(clock)
        val profiles = listOf(lan(), ipv6())
        val health = merge(bad("lan-1"), ok("v6-1"))
        engine.observe(lan(), profiles, ChannelPolicy.defaultOrder(), health, true)
        val decision = engine.observe(lan(), profiles, ChannelPolicy.defaultOrder(), health, true)
        assertNotNull(decision)
        decision as ChannelDecision.ConfirmSwitch
        // 调用方要拿 target 去切、拿 from 告诉用户「现在在用什么」、
        // 拿 reason 说明为什么问、拿 countdownSeconds 驱动倒计时。
        assertEquals("v6-1", decision.target.id)
        assertEquals("lan-1", decision.from.id)
        assertTrue(decision.reason.isNotBlank())
        assertTrue("倒计时必须为正，否则界面没法显示剩余秒数", decision.countdownSeconds > 0)
    }
}
