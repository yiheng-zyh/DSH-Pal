package com.dsh.mobile.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 隧道（Sakura Frp 等中转通道）的真实状态。
 *
 * 为什么需要它：网关把隧道客户端的状态注入到 `/api/pair/status`，
 * 但 App 早先并不读 —— 用户只能看到「已收到业务响应」，看不出外部通道其实没工作。
 * 2026-11-04 真实故障中，隧道客户端被服务端拒绝（`[400] frpc 版本号无效`），
 * 手机侧表现为 `ERR_SSL_PROTOCOL_ERROR`，而所有本地状态都显示正常，排查只能靠命令行。
 *
 * 现在把这份状态搬到界面上：隧道没工作时直接说原因，而不是让用户对着超时猜。
 */
data class TunnelStatus(
    /** 网关报的客户端状态，如 running-unverified / client-rejected / waiting-to-retry。 */
    val state: String,
    /** 被拒绝或出错时的脱敏原因（可能为空）。 */
    val reason: String,
    /** 通道类型，如 sakura；空表示未知。 */
    val provider: String,
    /** 网关已处理的请求数，用于判断"有没有真的在转发"。 */
    val gatewayRequests: Long,
    /**
     * 网关**实测**的外部入口 TLS 可用性。
     * null = 还没探过（老网关没有该字段）；true/false 才是实测结论。
     *
     * 为什么需要它：frpc 报「隧道启动成功」只证明它连上了节点，
     * 不证明 `publicOrigin` 真能握手 —— `auto_https` 没生效时服务端只回明文 501，
     * 浏览器侧就是 `ERR_SSL_PROTOCOL_ERROR`（2026-11-04 实测复现两次）。
     */
    val publicOk: Boolean? = null,
    /** 实测失败时的原因（如 ERR_SSL_WRONG_VERSION_NUMBER）。 */
    val publicDetail: String = "",
) {
    /** 隧道是否在工作。client-rejected / waiting-to-retry / spawn-failed 等都不算。 */
    val working: Boolean get() = state in WORKING_STATES

    /**
     * 这份状态是否**真的拿到了**（而不是探测失败/老网关没该字段）。
     *
     * 调用方必须先用它挡一道：`known == false` 时**不能**把通道判成故障。
     * 理由（真机 2026-11-04 实测）：手机经 5G 走中转时，探测请求可能拿到
     * 非 TLS 的响应（`ID1ID2: actual 0x00001fef`），此时我们只是"不知道"隧道状态，
     * 但通道本身是能用的（WebView 主连接正常）。把它当故障会触发保命切换、
     * 把用户正在看的页面刷掉 —— 那是最不可接受的行为。
     */
    val known: Boolean get() = state.isNotBlank() && state != "unknown"

    /**
     * 真正可用 = frpc 认为在跑 **且** 入口 TLS 实测通过。
     * 老网关没有 publicOk（null）时退回只看 state，不误报故障。
     */
    val usable: Boolean get() = working && publicOk != false

    /** 给用户看的一句话。 */
    val summary: String
        get() = when {
            state == "client-rejected" ->
                "外部通道被服务端拒绝" + if (reason.isNotBlank()) "：$reason" else ""
            // frpc 说在跑、但入口 TLS 实测不通：这是最容易误导人的情形。
            publicOk == false ->
                "外部通道连接不正常（TLS 未建立" +
                    if (publicDetail.isNotBlank()) "：$publicDetail" else "" + "）"
            state == "waiting-to-retry" -> "外部通道已断开，正在重试"
            state == "spawn-failed" -> "外部通道启动失败"
            state == "tunnel-degraded" -> "外部通道不稳定" + if (reason.isNotBlank()) "：$reason" else ""
            state == "certificate-pending" -> "外部通道正在签发证书"
            state.startsWith("running") || state.startsWith("tunnel-started") -> "外部通道正常" +
                if (publicOk == true) "（入口已实测可达）" else ""
            state == "unknown" || state.isBlank() -> "未获取到通道状态"
            else -> "外部通道状态：$state"
        }

    companion object {
        /** 认为"能用"的状态。 */
        val WORKING_STATES = setOf("running-unverified", "tunnel-started-unverified")

        val UNKNOWN = TunnelStatus("unknown", "", "", 0)

        /**
         * 从 `/api/pair/status` 的响应里读出隧道状态。
         *
         * 兼容老网关（没有 tunnel 字段）：此时返回 [UNKNOWN]，
         * 界面显示"未获取到通道状态"，而不是假装正常。
         */
        fun fromPairStatus(body: String): TunnelStatus {
            val json = runCatching { JSONObject(body) }.getOrNull() ?: return UNKNOWN
            val tunnel = json.optJSONObject("tunnel") ?: return UNKNOWN
            return TunnelStatus(
                state = tunnel.optString("state").ifBlank { "unknown" },
                reason = tunnel.optString("reason"),
                provider = tunnel.optString("provider"),
                gatewayRequests = json.optJSONObject("gateway")?.optLong("requests") ?: 0L,
                // 老网关没有该字段 → keeps null（不误报故障）
                publicOk = if (tunnel.has("publicOk") && !tunnel.isNull("publicOk"))
                    tunnel.optBoolean("publicOk") else null,
                publicDetail = tunnel.optString("publicDetail"),
            )
        }
    }
}

object TunnelProbe {
    /**
     * 超时给得比"电脑本地探测"宽：这个请求要走手机网络 → 中转 → 电脑，
     * 5G 弱网或中转抖动时 6 秒会直接 IOException，而实际只是慢。
     * 真机实测（2026-11-04）：隧道正常、页面已加载，但 3/4/6 秒的三重超时
     * 仍报 IOException —— 探测本身比业务请求更保守，宁可慢也不要把"正常"误判成故障。
     */
    private val client = OkHttpClient.Builder()
        .dns(DevelopmentDns)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /**
     * 读取隧道状态。任何失败都返回 [TunnelStatus.UNKNOWN] 并把原因写日志，
     * 绝不在读不到时谎报"正常"。
     */
    suspend fun check(profile: ConnectionProfile): TunnelStatus = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(profile.baseUrl.trimEnd('/') + "/api/pair/status")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    RunLog.warn("Tunnel", "pair/status http=${response.code}")
                    return@runCatching TunnelStatus.UNKNOWN
                }
                val status = TunnelStatus.fromPairStatus(response.peekBody(16384).string())
                RunLog.info(
                    "Tunnel",
                    "state=${status.state} working=${status.working} requests=${status.gatewayRequests}",
                )
                status
            }
        }.getOrElse {
            // 只记 simpleName 会把原因抹平（IOException 可能是 DNS、TLS、连接重置、
            // 也可能是 HTTP 层被拦）。把 message 与 cause 一起记，才能定位到底卡在哪。
            val cause = generateSequence(it) { e -> e.cause }.take(3)
                .joinToString(" <- ") { e -> "${e.javaClass.simpleName}:${e.message?.take(80)}" }
            RunLog.warn("Tunnel", "probe failed $cause")
            TunnelStatus.UNKNOWN
        }
    }
}
