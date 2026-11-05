package com.dsh.mobile.core

/**
 * 启动时先探一次「当前连接」还要不要硬加载。
 *
 * 背景（用户真机反馈，2026-11-05）：
 *
 * > 「一开始我是用局域网链接的，那个成功了。但是有个小问题就是我之前用 vpn 的时候
 * > 开了个网关，这个地址好像是一直在电脑里了。然后手机一开始直接的是这个，
 * > 导致连不上，后来把那个网址禁用了就好了。」
 *
 * 启动路径原来只看「有没有 activeProfile」，[com.dsh.mobile.ui.DshMobileApp] 就把它
 * 直接拼成 `<origin>/pair-app` 交给 WebView 加载。地址已经失效时用户会看到白屏，
 * 而自动选路要等「两次巡检失败 + 15 秒倒计时」才动手（[ChannelFailover] 的
 * `FAILURES_TO_SWITCH = 2` 和 `DEFAULT_COUNTDOWN_SECONDS = 15`，巡检间隔
 * [RecoveryBackoff.HEALTHY_INTERVAL_MS] = 20 秒），实测约 29 秒才有出口。
 *
 * 这里只做纯判定，不做 IO、不持有状态，方便单测（项目的 UI 层没有测试基建，
 * 所以规则必须落在这个包里）。
 */
object LaunchHealth {

    /** 探测结果。`Skipped` 与 `Reachable` 的区别对调用方有意义：跳过时不该显示任何提示。 */
    sealed interface Verdict {
        /** 不该探测：没有连接、USB、用户关掉了「启动时自动连接」、或正在握手。 */
        data object Skipped : Verdict
        /** 通（含 401/403 —— 未配对但网络是通的）。 */
        data object Reachable : Verdict
        /** 连不上。带上原地址和原因，供提示文案直接显示。 */
        data class Unreachable(val origin: String, val reason: String) : Verdict
    }

    /**
     * 要不要在启动时探测这条地址。
     *
     * 四个「不要」缺一不可，漏掉任何一个都会破坏既有场景：
     *
     *  - 没有 activeProfile / 地址为空 —— 新用户走引导，探测只会添乱；
     *  - `autoConnectOnLaunch == false` —— 用户明确选了「先选入口」，直接进选择页；
     *  - `Kind.USB` —— 走 adb reverse，普通 HTTP 探测必然误判（见 `DshMobileApp` 里
     *    那个 `takeIf { it.kind != Kind.USB || USB_DEVELOPMENT }`）；
     *  - 正在配对（`pairingUrl` 非空）—— 一次性链接有有效期，不能当成「失效地址」。
     */
    fun shouldProbe(
        autoConnectOnLaunch: Boolean,
        activeBaseUrl: String,
        kind: ConnectionProfile.Kind?,
        pairingUrl: String,
    ): Boolean {
        if (!autoConnectOnLaunch) return false
        if (activeBaseUrl.isBlank()) return false
        if (kind == ConnectionProfile.Kind.USB) return false
        if (pairingUrl.isNotBlank()) return false
        // 地址本身得是 App 认得的形状，否则探测只会得到一个无意义的失败。
        if (ConnectionAddress.origin(activeBaseUrl) == null) return false
        if (kind != null && !ConnectionAddress.allowed(activeBaseUrl, kind)) return false
        return true
    }

    /**
     * 把一次探测结果翻译成结论。
     *
     * 判据严格复用 [Reachability.reachable]（`status != null && status < 500 && status != 429`），
     * 不自己写一套：401/403 是「网络通、只是没配对」，把它当失效会把正常首次使用流程
     * 打断，而且 `ConnectionHealthTest` 已经把这条当成契约在测。
     *
     * 另一个必须放过的情况是 `measured == false`：`context-changed` /
     * `browser-not-ready` / `probe-unavailable` 表示**这次没测到**，不是**测出来坏了**。
     * 用「没测到」去判定失效，会在冷启动那一瞬间误伤所有正常连接。
     */
    fun verdict(origin: String, reachability: Reachability): Verdict {
        if (!reachability.measured) return Verdict.Skipped
        if (reachability.reachable) return Verdict.Reachable
        return Verdict.Unreachable(origin, describeReason(reachability))
    }

    /**
     * 给用户看的原因。只写他能拿去做判断的话，不暴露异常类名。
     *
     * 面向的是不熟悉网络的用户。所以这里避免
     * `SocketTimeoutException` 这类词，只保留「没响应 / 被拒绝 / 地址解析不了」三档。
     */
    fun describeReason(reachability: Reachability): String = when {
        reachability.status == 429 -> "对方限流"
        reachability.status != null && reachability.status >= 500 -> "对方服务端出错（HTTP ${reachability.status}）"
        reachability.status != null -> "返回了 HTTP ${reachability.status}"
        reachability.error == "UnknownHostException" -> "地址解析不了"
        reachability.error == "ConnectException" -> "对方没接受连接"
        reachability.error == "SocketTimeoutException" -> "对方没有响应"
        reachability.error != null -> "连不上"
        else -> "连不上"
    }
}
