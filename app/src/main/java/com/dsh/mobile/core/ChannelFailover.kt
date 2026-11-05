package com.dsh.mobile.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.URI

/**
 * 通道自动选路：保命切换（必要时才动手）+ 优化建议（可见但克制）。
 *
 * ## 为什么要有这个文件
 *
 * 用户配好一台电脑后，手机其实有 4~5 条路能到它：家里 Wi-Fi 直连、IPv6 直连、
 * 国内 frp、Cloudflare / Relay 公网中转、Tailscale。但 App 过去只会「记住上次那条」：
 * 那条一挂，用户就只能看着白屏，或者自己去设置里猜该换哪条。用户的原话是
 * 「保命优先」—— 只要当前通道还能用，就不要动它（换通道会重载页面，等于把用户
 * 正在输入的东西冲掉）；但当前通道**完全不可用**时，必须自己换一条能用的过去。
 *
 * 同时还有一半需求是反向的：**不要**在正常使用时随便切。用户明确说了
 * 「切通道会重载页面」，所以「发现更好的通道」只能变成一条克制的提示，
 * 让用户自己决定，而且拒绝过一次之后本次运行 30 分钟内不许再弹。
 *
 * ## 分层（决定了本文件的可测试性）
 *
 * - [ChannelPolicy] —— 纯函数：谁比谁优先、什么算「国内直连」、什么算「国外中转」。
 * - [ChannelFailoverEngine] —— 纯状态机：连续失败计数、静默窗口、手动暂停。
 *   它不认识 OkHttp、不认识 Android，所以单测可以直接喂时钟和实测结果。
 * - [ChannelFailover] —— 唯一碰网络的部分：静默 HEAD 巡检 + 切换前的正式复验 + 主循环。
 *
 * 这样「保命触发的门槛」「30 分钟静默」「手动干预后暂停」这些**真正容易写错**的规则
 * 全部落在纯逻辑里，能一条条断言，而不需要真机、真网、真等待。
 */

/** 一条通道某一次探测的事实结论。只有事实，不含任何策略。 */
data class ChannelHealth(
    val profileId: String,
    val available: Boolean,
    /** 实测耗时（毫秒）。不可用时为 0。 */
    val elapsedMs: Long = 0,
    /** 给界面看的一句话：可用时是「入口 xx ms」，不可用时是失败原因。 */
    val detail: String = "",
)

/** 自动选路这一步的决定。调用方只管执行，不需要重新判断条件。 */
sealed interface ChannelDecision {
    /** 什么都不做：可能是当前正常、也可能是没有更好的选择。 */
    data object Idle : ChannelDecision

    /** 保命切换：当前通道连续实测不可用，且找到了可用的下一条。 */
    data class Failover(
        val target: ConnectionProfile,
        val from: ConnectionProfile,
        val reason: String,
    ) : ChannelDecision

    /**
     * 优化建议：当前走国外中转，而国内直连实测可用。
     *
     * 只是一个**建议**：界面必须让用户自己点，绝不自动执行 —— 用户最烦的就是
     * 明明在打字，页面突然自己刷新。
     */
    data class Suggest(
        val target: ConnectionProfile,
        val from: ConnectionProfile,
        val reason: String,
    ) : ChannelDecision

    /**
     * 保命切换的「征询」：当前通道连续探测不通，但**先问用户**再动手。
     *
     * 为什么不能像以前那样直接切（用户 2026-11-05 明确要求）：
     * 「我现在就是想试试 VPN 的通道到底能不能走得通」，结果 App 自己把它切走了 ——
     * 用户正在测一条慢通道，App 却因为「探测超时」判定它不可用，把人拽回另一条路。
     * 对用户来说这不是帮忙，是「我的选择不被尊重，而且我永远测不出来」。
     *
     * 所以语义分成两层：
     *  - [Suggest]：还能用，只是有更快的 → 只提示，不动手（原有行为）
     *  - [ConfirmSwitch]：看着像不通了 → 提示 + 倒计时，**倒计时结束才切**；
     *    用户点「继续用当前通道」就保留，并把当前通道记进一段静默期，别再反复问。
     *
     * 倒计时必须存在而不是纯等待：通道真断了时用户也希望它自己恢复，
     * 不能因为「问了但没人回答」就一直卡在坏路上。
     */
    data class ConfirmSwitch(
        val target: ConnectionProfile,
        val from: ConnectionProfile,
        val reason: String,
        /** 倒计时秒数，到点自动执行切换。 */
        val countdownSeconds: Int = ChannelFailoverEngine.DEFAULT_COUNTDOWN_SECONDS,
    ) : ChannelDecision
}

/**
 * 通道种类之间的顺序关系（纯函数）。
 *
 * 用户确认过的默认顺序是 LAN → IPv6 → frp → Tailscale。在 App 的
 * [ConnectionProfile.Kind] 里 frp 归在 [ConnectionProfile.Kind.CUSTOM]（「自建固定域名」，
 * 2026-11-05 由「其他 HTTPS / frp」改名 —— 用户指出那个名字把主力通道说成了替补品），
 * 而公网中转归在 TUNNEL / RELAY。任务要求的默认是 LAN → IPV6 → TUNNEL → MESH，
 * 因此对外把「国内中转」这一档统一按 TUNNEL 处理，其余未点名的种类排在最后 ——
 * 这样即使用户把顺序改坏、或者将来加了新种类，也永远能算出**一个**确定的顺序，
 * 而不是「找不到排位就当可用」这种会乱切的兜底。
 */
object ChannelPolicy {

    /** 默认优先级：家用直连 → IPv6 直连 → 国内/公网中转 → Tailscale。 */
    val DEFAULT_ORDER: List<ConnectionProfile.Kind> = listOf(
        ConnectionProfile.Kind.LAN,
        ConnectionProfile.Kind.IPV6,
        ConnectionProfile.Kind.TUNNEL,
        ConnectionProfile.Kind.MESH,
    )

    /**
     * 默认顺序里没点名的种类，统一排在后面。
     *
     * 为什么 USB 排最后且不参与自动切换：它需要数据线 + 开发版构建，
     * 手机自己「切到 USB」必然是失败的 —— 自动切换到一条物理上不存在的路上，
     * 只会让用户从「连不上」变成「连不上且页面被刷了」。
     */
    val TAIL_ORDER: List<ConnectionProfile.Kind> = listOf(
        ConnectionProfile.Kind.RELAY,
        ConnectionProfile.Kind.CUSTOM,
        ConnectionProfile.Kind.USB,
    )

    fun allKinds(): List<ConnectionProfile.Kind> = DEFAULT_ORDER + TAIL_ORDER

    /** 默认顺序（含尾部的完整全序）。 */
    fun defaultOrder(): List<ConnectionProfile.Kind> = allKinds()

    /**
     * 解析用户保存的顺序。容错是刻意的：
     * 未知名字忽略、重复只留第一次、缺失的种类补在尾部 —— 任何脏数据都能得到一个全序。
     */
    fun parseOrder(raw: String): List<ConnectionProfile.Kind> {
        val picked = ArrayList<ConnectionProfile.Kind>(allKinds().size)
        raw.split(',', ';', ' ', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { name ->
                val kind = runCatching { ConnectionProfile.Kind.valueOf(name.uppercase()) }.getOrNull()
                if (kind != null && kind !in picked) picked += kind
            }
        allKinds().filterNot { it in picked }.forEach { picked += it }
        return picked
    }

    fun encodeOrder(order: List<ConnectionProfile.Kind>): String =
        (order.filter { it in allKinds() }.distinct() + allKinds().filterNot { it in order })
            .joinToString(",") { it.name }

    /** 排位越小越优先。永远返回一个有效下标。 */
    fun rank(order: List<ConnectionProfile.Kind>, kind: ConnectionProfile.Kind): Int {
        val index = order.indexOf(kind)
        return if (index >= 0) index else order.size + allKinds().indexOf(kind).coerceAtLeast(0)
    }

    /** 按优先级给地址簿排序（同档保持原有相对顺序，界面读起来才稳定）。 */
    fun ordered(
        profiles: List<ConnectionProfile>,
        order: List<ConnectionProfile.Kind>,
    ): List<ConnectionProfile> = profiles.sortedBy { rank(order, it.kind) }

    /**
     * 国外中转 = 当前流量要绕出国的中转入口。
     *
     * 只有这种情况才值得提示「换回国内直连」：用户明确的规则是
     * 「只在用国外中转（Cloudflare / Relay）而国内直连可用时才提示」。
     */
    fun isForeignRelay(profile: ConnectionProfile): Boolean =
        profile.kind == ConnectionProfile.Kind.TUNNEL || profile.kind == ConnectionProfile.Kind.RELAY

    /**
     * 国内直连 = 不需要任何中转就能到达电脑。
     *
     * 判据是**地址**而不只是 kind：LAN 私有 IPv4、全局单播 IPv6 都是直连；
     * 而 `Kind.CUSTOM` 里既有 frp（中转）也有「自己的公网 IP」（直连），
     * 所以对字面量 IP 再看一眼地址类型，域名一律不算直连（那必然过 CDN/中转）。
     */
    fun isDomesticDirect(profile: ConnectionProfile): Boolean {
        if (profile.kind == ConnectionProfile.Kind.LAN || profile.kind == ConnectionProfile.Kind.IPV6) return true
        val host = runCatching { URI(ConnectionAddress.origin(profile.baseUrl) ?: profile.baseUrl).host }.getOrNull()
            ?: return false
        return ConnectionAddress.isGlobalIpv6(host) || ConnectionAddress.isPrivateIpv4(host)
    }

    /** 能不能被自动切过去。USB 需要数据线，只能是用户自己选。 */
    fun autoEligible(profile: ConnectionProfile): Boolean =
        profile.kind != ConnectionProfile.Kind.USB

    /**
     * 保命切换的目标：按优先级取**第一个实测可用**的入口。
     *
     * 注意返回值可能是 null —— 一条都不通的时候不能假装有目标，
     * 调用方应该继续按退避探测，而不是把用户丢到另一个坏页面上。
     */
    fun firstUsable(
        current: ConnectionProfile?,
        profiles: List<ConnectionProfile>,
        order: List<ConnectionProfile.Kind>,
        health: Map<String, ChannelHealth>,
    ): ConnectionProfile? = ordered(profiles, order).firstOrNull { candidate ->
        candidate.id != current?.id &&
            autoEligible(candidate) &&
            health[candidate.id]?.available == true
    }

    /**
     * 优化建议的候选：**排位比当前更靠前**且**是国内直连**且实测可用。
     *
     * 三个条件缺一不可。只比「更靠前」不够 —— 从 Cloudflare 换到另一条中转并不
     * 解决用户的问题（还是要绕出去）；只比「是直连」也不够 —— 换到一条排位更差的
     * 直连属于倒退。所以必须同时满足，才配得上弹一次提示。
     */
    fun betterDomestic(
        current: ConnectionProfile,
        profiles: List<ConnectionProfile>,
        order: List<ConnectionProfile.Kind>,
        health: Map<String, ChannelHealth>,
    ): ConnectionProfile? {
        val currentRank = rank(order, current.kind)
        return ordered(profiles, order).firstOrNull { candidate ->
            candidate.id != current.id &&
                autoEligible(candidate) &&
                isDomesticDirect(candidate) &&
                rank(order, candidate.kind) < currentRank &&
                health[candidate.id]?.available == true
        }
    }
}

/**
 * 恢复探测的退避节奏：5s → 10s → 30s → 60s，之后一直 60s。
 *
 * 为什么是「失败立即切、恢复探测才退避」这一对：保命切换慢一步用户就多看一眼白屏，
 * 所以探测到失败就立刻切；但**反复去敲一条已知挂掉的路**没有意义，越敲越费电，
 * 所以恢复探测才拉起退避，60 秒封顶保证「它好了我能比较快发现」。
 */
object RecoveryBackoff {
    private val STEPS = longArrayOf(5_000L, 10_000L, 30_000L, 60_000L)

    fun delayMs(attempt: Int): Long = STEPS[attempt.coerceIn(0, STEPS.lastIndex)]

    /** 一切正常时的巡检间隔。比 5s 稀疏得多：正常时不需要抢资源。 */
    const val HEALTHY_INTERVAL_MS = 20_000L
}

/**
 * 自动选路的状态机（纯逻辑，可单测）。
 *
 * 所有时间都来自 [clock]，因此「30 分钟静默」「手动暂停」这些规则可以在毫秒内
 * 用假时钟验证，而不需要真的等半小时。
 */
class ChannelFailoverEngine(
    private val clock: () -> Long = { System.currentTimeMillis() },
    /** 连续多少次实测失败才算「完全不可用」。 */
    private val failuresToSwitch: Int = FAILURES_TO_SWITCH,
    val manualHoldMs: Long = MANUAL_HOLD_MS,
    val promptSilenceMs: Long = PROMPT_SILENCE_MS,
) {
    /** 当前通道连续失败的次数。一次失败不算数，原因见 [FAILURES_TO_SWITCH]。 */
    var consecutiveFailures: Int = 0
        private set

    /** 手动干预后的静默截止时间：在此之前不自动切，也不弹提示。 */
    var manualHoldUntil: Long = 0L
        private set

    /** 提示静默截止时间：拒绝（或已经弹过一次）之后 30 分钟内不再弹。 */
    var promptSilencedUntil: Long = 0L
        private set

    /**
     * 最近一次「用户同意切换」的时间，供界面/日志展示。
     *
     * 语义随 2026-11-05 的行为变化而改变：以前是「引擎自己切了」，现在是
     * 「征询之后用户点了立即切换（或倒计时走完）」—— 由界面在真正执行切换时
     * 通过 [noteFailoverCommitted] 记下来，引擎本身不再偷偷动手。
     */
    var lastFailoverAt: Long = 0L
        private set

    /** 记录一次真正发生的保命切换（由界面在确认后调用）。 */
    fun noteFailoverCommitted(at: Long = clock()) {
        lastFailoverAt = at
    }

    /** 从设置恢复持久化的两个时间戳（App 重启后仍然生效）。 */
    fun restore(manualHoldUntil: Long, promptSilencedUntil: Long) {
        this.manualHoldUntil = manualHoldUntil.coerceAtLeast(0L)
        this.promptSilencedUntil = promptSilencedUntil.coerceAtLeast(0L)
    }

    /**
     * 用户自己切了通道 → 暂停自动 30 分钟。
     *
     * 为什么连保命切换也暂停：用户刚做完一个明确的选择，如果 App 立刻把他拽走，
     * 他会觉得「这软件根本不听我的」。30 分钟足够他完成手上的事，也足够发现
     * 自己选错了。
     */
    fun onManualSwitch() {
        manualHoldUntil = clock() + manualHoldMs
        consecutiveFailures = 0
    }

    /** 用户拒绝了优化提示 → 本次运行 30 分钟内不再弹。 */
    fun onPromptDeclined() {
        promptSilencedUntil = clock() + promptSilenceMs
    }

    /** 用户接受了优化提示（或提示被别的路径消化掉），同样静默一轮，避免连着骚扰。 */
    fun onPromptAccepted() {
        promptSilencedUntil = clock() + promptSilenceMs
    }

    /**
     * 喂一轮实测结果，得到一个决定。
     *
     * @param current 当前正在用的地址；null 表示还没配对，什么都不做。
     * @param health 本轮实测结果（按 profileId 索引）。**没测到的通道不算失败**，
     *   否则「还没探测」会被误判成「不可用」，把用户切到一条没验证过的路上。
     */
    fun observe(
        current: ConnectionProfile?,
        profiles: List<ConnectionProfile>,
        order: List<ConnectionProfile.Kind>,
        health: Map<String, ChannelHealth>,
        autoSwitch: Boolean,
    ): ChannelDecision {
        if (current == null) return ChannelDecision.Idle
        val now = clock()
        val currentHealth = health[current.id]

        if (currentHealth == null) return ChannelDecision.Idle // 本轮没测到 → 保持原状

        if (currentHealth.available) {
            consecutiveFailures = 0
            return maybeSuggest(current, profiles, order, health, autoSwitch, now)
        }

        // 开关关掉 / 手动暂停期间都不计失败次数。
        //
        // 为什么要清零而不是照常累积：如果暂停期内存着证据，解除的那一刻第一轮就以
        // 「连续多次不通」为由立刻切走 —— 但用户刚恢复自动选路，还没看到任何证据。
        // 那等于把「连续 2 次」的保护绕过去了，观感上就是「它一恢复就抢我页面」。
        // 正确做法：恢复后必须**重新观察满 2 轮**才允许动手。
        if (!autoSwitch || now < manualHoldUntil) {
            consecutiveFailures = 0
            return ChannelDecision.Idle
        }

        consecutiveFailures++
        if (consecutiveFailures < failuresToSwitch) return ChannelDecision.Idle

        val target = ChannelPolicy.firstUsable(current, profiles, order, health) ?: return ChannelDecision.Idle
        val failures = consecutiveFailures
        consecutiveFailures = 0
        // 不直接切，改成「征询 + 倒计时」。
        //
        // 用户 2026-11-05 的原话：「我现在这种情况，我就是想试试 VPN 的通道到底
        // 能不能走得通」。以前这里直接 onSwitch，于是他正在测的慢通道被 App 自己
        // 换掉了 —— 看上去像「连不上」，实际是自动化把证据毁掉了。
        // 现在改为弹提示并倒计时，用户可以选择「继续用当前通道」，那条选择会被
        // 记进 manualHoldUntil（见 noteManualChannelSwitch），30 分钟内不再打扰。
        return ChannelDecision.ConfirmSwitch(
            target = target,
            from = current,
            reason = "当前通道连续 $failures 次探测不通。可以继续用它重试，或切换到${target.kind.title}",
        )
    }

    private fun maybeSuggest(
        current: ConnectionProfile,
        profiles: List<ConnectionProfile>,
        order: List<ConnectionProfile.Kind>,
        health: Map<String, ChannelHealth>,
        autoSwitch: Boolean,
        now: Long,
    ): ChannelDecision {
        // 自动选路关掉时一并闭嘴：关掉这个开关的用户要的是「别管我」。
        if (!autoSwitch) return ChannelDecision.Idle
        if (now < manualHoldUntil) return ChannelDecision.Idle
        if (now < promptSilencedUntil) return ChannelDecision.Idle
        // 只有「当前绕出国」才值得提示换国内直连，其余情况一律不打扰。
        if (!ChannelPolicy.isForeignRelay(current)) return ChannelDecision.Idle
        val better = ChannelPolicy.betterDomestic(current, profiles, order, health) ?: return ChannelDecision.Idle
        // 弹之前就把静默窗口占上：界面被别的事情吞掉时不会变成每 20 秒弹一次的骚扰。
        promptSilencedUntil = now + promptSilenceMs
        return ChannelDecision.Suggest(
            target = better,
            from = current,
            reason = "当前走国外中转，${better.kind.title} 直连实测可用",
        )
    }

    companion object {
        /**
         * 连续 2 次才切。
         *
         * 一次探测失败在手机网络上很常见（切基站、丢包、Doze 刚醒），
         * 单次失败就切会让页面在日常使用中反复重载 —— 这正是用户最反感的行为。
         * 两次失败的最坏代价是「多等一个退避周期」，而误切要重载页面、丢草稿。
         */
        const val FAILURES_TO_SWITCH = 2

        /** 手动干预后暂停自动 30 分钟。用户明确要求。 */
        const val MANUAL_HOLD_MS = 30 * 60_000L

        /** 提示静默 30 分钟。用户明确要求。 */
        const val PROMPT_SILENCE_MS = 30 * 60_000L

        /**
         * 保命切换的征询倒计时（秒）。
         *
         * 15 秒是权衡：太短用户来不及读完「可以继续用它重试」，太长则通道真断了
         * 时页面白白多卡一段。用户明确要求「要有一个倒计时，倒计时结束之后再自动
         * 去切换」，所以必须有一个确定的默认值，不能无限等。
         */
        const val DEFAULT_COUNTDOWN_SECONDS = 15
    }
}

/**
 * 唯一碰网络的地方：巡检、切换前复验、主循环。
 *
 * 探测复用已有的两套探针，不另建 OkHttpClient：
 * - 常规巡检用 [HealthProbe]（静默 HEAD，3s 连接 / 3s 读 / 4s 调用）——
 *   它不写日志，20 秒一轮也不会把诊断日志的环形缓冲冲掉；
 * - 真要切之前用 [ConnectionProbe] 再做一次，因为切换路径本身用的就是它的分类
 *   （DSH_FOUND / AUTH_REQUIRED / REDIRECT 才算可用），这样「探测说能切」和
 *   「切换说能连」永远是同一个判据，不会出现探测放行、切换又判失败的来回拉扯。
 */
object ChannelFailover {

    /** ConnectionProbe 认为「电脑确实应答了」的结论集合，与 ConnectionSwitch 保持一致。 */
    val USABLE = setOf(ProbeOutcome.DSH_FOUND, ProbeOutcome.AUTH_REQUIRED, ProbeOutcome.REDIRECT)

    /** 切过去之后给页面重载/配对留的时间，这段时间内不再探测，避免自己打自己。 */
    private const val AFTER_SWITCH_MS = 15_000L

    /** 一次巡检最多看几条候选：地址簿很长时不值得把每一轮都拖满。 */
    private const val MAX_CANDIDATES = 4

    /**
     * 实测一条通道。
     *
     * @param confirm true = 真要切过去的正式复验（走 ConnectionProbe 分类 + 公网中转再看隧道状态）；
     *   false = 日常巡检（静默 HEAD，便宜）。
     */
    suspend fun measure(profile: ConnectionProfile, confirm: Boolean = false): ChannelHealth {
        val reachability = HealthProbe.check(profile.baseUrl)
        if (!reachability.reachable) {
            return ChannelHealth(
                profileId = profile.id,
                available = false,
                detail = reachability.error ?: "入口无响应",
            )
        }
        if (!confirm) {
            return ChannelHealth(
                profileId = profile.id,
                available = true,
                elapsedMs = reachability.elapsedMs,
                detail = "入口可用 · ${reachability.elapsedMs} ms",
            )
        }
        val probe = ConnectionProbe.check(profile)
        if (probe.outcome !in USABLE) {
            return ChannelHealth(profileId = profile.id, available = false, detail = probe.outcome.message)
        }
        // 公网中转还要看网关自报的隧道状态：frpc 说「启动成功」不等于入口 TLS 通
        // （2026-11-04 故障：本地全绿、入口只回明文 501、手机侧 ERR_SSL_PROTOCOL_ERROR）。
        if (profile.kind.publicEntry) {
            val tunnel = TunnelProbe.check(profile)
            // 用 TunnelStatus.known 挡一道，而不是自己比字符串。
            //
            // 为什么必须挡：探测失败时要表达的是「不知道隧道状态」，不是「隧道坏了」。
            // 真机实测（2026-11-04）手机经 5G 走中转时探测请求可能拿到非 TLS 响应
            // （`ID1ID2: actual 0x00001fef`，0x1fef 是 UTF-8 中文字节 = 明文 JSON），
            // 此时通道本身是能用的（WebView 主连接正常）。把它当故障会触发保命切换、
            // 把用户正在看的页面刷掉 —— 那正是最不可接受的行为。
            if (tunnel.known && !tunnel.usable) {
                return ChannelHealth(profileId = profile.id, available = false, detail = tunnel.summary)
            }
        }
        return ChannelHealth(
            profileId = profile.id,
            available = true,
            elapsedMs = probe.elapsedMs,
            detail = "入口可用 · ${probe.elapsedMs} ms",
        )
    }

    /** 批量巡检（设置页展示用）：顺序执行，避免几条通道同时抢带宽把结论测歪。 */
    suspend fun measureAll(profiles: List<ConnectionProfile>): List<ChannelHealth> =
        profiles.map { runCatching { measure(it) }.getOrElse { error ->
            ChannelHealth(it.id, false, detail = error.javaClass.simpleName)
        } }

    /**
     * 启动自动选路主循环。调用方持有 Job，页面销毁时取消即可。
     *
     * 所有动作都通过回调交回给调用方，本对象不直接改设置、不直接重载页面 ——
     * 这样「切之前要存草稿」这条硬约束由调用方用自己的确认流程保证，
     * 不会因为这里偷偷换地址而丢用户输入。
     *
     * @param canProbe 现在能不能探测/切换（页面不在前台、正在切、有任务在跑时返回 false）。
     * @param onSwitch 执行切换。**调用方必须先确认没有未发送草稿**。
     * @param onSuggest 优化建议。调用方负责用「不影响观感」的方式问用户。
     * @param onConfirmSwitch 保命切换的征询：界面负责显示倒计时并询问用户，
     *   倒计时结束或用户点「立即切换」后才真正调用 [onSwitch]。
     */
    fun start(
        scope: CoroutineScope,
        settings: SettingsStore,
        canProbe: () -> Boolean,
        onSwitch: suspend (ConnectionProfile, String) -> Unit,
        onSuggest: suspend (ConnectionProfile, String) -> Unit,
        onConfirmSwitch: suspend (ConnectionProfile, String, Int) -> Unit = { target, reason, _ ->
            // 未提供征询回调时退回旧行为（直接切），保证既有调用方语义不变。
            onSwitch(target, reason)
        },
        onHealth: (List<ChannelHealth>) -> Unit = {},
    ): Job = scope.launch {
        val engine = ChannelFailoverEngine()
        engine.restore(settings.channelManualHoldUntil, settings.channelPromptSilencedUntil)
        var attempt = 0
        while (isActive) {
            if (!canProbe()) {
                // 页面不在前台/正在切换：不探测，也不消耗退避计数。
                delay(RecoveryBackoff.HEALTHY_INTERVAL_MS)
                continue
            }
            val current = settings.activeProfile
            if (current == null) {
                delay(RecoveryBackoff.HEALTHY_INTERVAL_MS)
                continue
            }
            // 设置是唯一事实来源：用户刚在设置里手动切过，这里立刻就能看到。
            engine.restore(settings.channelManualHoldUntil, settings.channelPromptSilencedUntil)
            val order = settings.channelPriority
            val profiles = settings.profiles
            val currentHealth = runCatching { measure(current) }.getOrElse {
                ChannelHealth(current.id, false, detail = it.javaClass.simpleName)
            }
            val health = LinkedHashMap<String, ChannelHealth>()
            health[current.id] = currentHealth

            // 只测「这一轮真的可能用到」的候选，避免每 20 秒把整个地址簿敲一遍。
            val needCandidates = !currentHealth.available || ChannelPolicy.isForeignRelay(current)
            if (needCandidates) {
                ChannelPolicy.ordered(profiles, order)
                    .filter { it.id != current.id && ChannelPolicy.autoEligible(it) }
                    .filter { !currentHealth.available || ChannelPolicy.isDomesticDirect(it) }
                    .take(MAX_CANDIDATES)
                    .forEach { candidate ->
                        health[candidate.id] = runCatching { measure(candidate) }.getOrElse {
                            ChannelHealth(candidate.id, false, detail = it.javaClass.simpleName)
                        }
                    }
            }
            onHealth(health.values.toList())

            when (val decision = engine.observe(
                current = current,
                profiles = profiles,
                order = order,
                health = health,
                autoSwitch = settings.channelAutoSwitch,
            )) {
                is ChannelDecision.ConfirmSwitch -> {
                    // 切之前正式复验一次：巡检是便宜的静默 HEAD，真正切过去要求更严。
                    val verified = runCatching { measure(decision.target, confirm = true) }.getOrNull()
                    if (verified?.available == true) {
                        // 只把「有更好的路」这件事交给界面，由界面带着倒计时问用户；
                        // 真正的切换在倒计时结束、或用户点「立即切换」时才发生。
                        RunLog.info(
                            "Channel",
                            "confirm-switch ${decision.from.kind}->${decision.target.kind} " +
                                "id=${decision.target.id.take(8)} countdown=${decision.countdownSeconds}s",
                        )
                        onConfirmSwitch(decision.target, decision.reason, decision.countdownSeconds)
                    } else {
                        RunLog.warn("Channel", "failover candidate went away before commit; retrying")
                    }
                }
                is ChannelDecision.Failover -> {
                    // 保留旧类型的分支：外部若还有地方产出 Failover，行为不变。
                    val verified = runCatching { measure(decision.target, confirm = true) }.getOrNull()
                    if (verified?.available == true) {
                        RunLog.info(
                            "Channel",
                            "failover ${decision.from.kind}->${decision.target.kind} id=${decision.target.id.take(8)}",
                        )
                        onSwitch(decision.target, decision.reason)
                        // 页面要重载/重配，这段时间内别再探测，否则会把自己刚切的通道判成不稳定。
                        delay(AFTER_SWITCH_MS)
                        attempt = 0
                        continue
                    }
                    RunLog.warn("Channel", "failover candidate went away before commit; retrying")
                }
                is ChannelDecision.Suggest -> {
                    RunLog.info(
                        "Channel",
                        "suggest ${decision.from.kind}->${decision.target.kind} id=${decision.target.id.take(8)}",
                    )
                    onSuggest(decision.target, decision.reason)
                }
                ChannelDecision.Idle -> Unit
            }

            // 静默窗口是在 engine 里推进的，这里负责落盘，App 重启后仍然生效。
            // 两个时间戳都要落：只存提示静默的话，重启后「用户手动切过」就忘了，
            // 会在用户刚选完通道的下一秒把他拽走。
            if (engine.promptSilencedUntil != settings.channelPromptSilencedUntil) {
                settings.channelPromptSilencedUntil = engine.promptSilencedUntil
            }
            if (engine.manualHoldUntil != settings.channelManualHoldUntil) {
                settings.channelManualHoldUntil = engine.manualHoldUntil
            }

            // 退避节奏：本轮通了 → 回到常规巡检间隔；本轮不通 → 5s/10s/30s/60s 递增。
            //
            // 注意这里刻意用 `attempt - 1`：`attempt` 在下面已经自增过，而第一次失败
            // 应当等最短的 5s（用户最可能马上手动重试，早一点发现恢复才好）。
            // 若直接用自增后的值，第一次会等 10s，把最该快的那一次拖慢了。
            attempt = if (currentHealth.available) 0 else (attempt + 1).coerceAtMost(Int.MAX_VALUE - 1)
            delay(
                if (currentHealth.available) RecoveryBackoff.HEALTHY_INTERVAL_MS
                else RecoveryBackoff.delayMs(attempt - 1),
            )
        }
    }
}
