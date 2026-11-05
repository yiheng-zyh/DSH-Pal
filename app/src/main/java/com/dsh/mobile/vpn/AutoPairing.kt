package com.dsh.mobile.vpn

import com.dsh.mobile.core.ConnectionAddress
import com.dsh.mobile.core.RunLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 登录 Tailscale 之后自动完成配对，不再要求用户扫码。
 *
 * 用户 2026-11-04 原话：「我这边用户在手机这边登录好账号之后，下一步不就是应该
 * 直接连接了，而不是还要有其他操作。」
 *
 * ## 为什么不去直接调插件的 /api/pair/issue
 *
 * 试过了，走不通。插件的签发路由内部有一道硬栅栏（routes.js:781）：
 * ```js
 * const loopbackFence = (req) => isLoopbackClient(req) && isTrustedApiRequest(req, []);
 * // handleIssue: if (!loopbackFence(req)) → 403 {ok:false,code:'forbidden'}
 * ```
 * 它要求「真回环 + 无转发头」，经 Tailscale Serve 转发的请求永远不满足。
 * 实测重启后经 ts.net 请求仍是 403，正是这个原因。
 *
 * ## 现在的链路
 *
 * 请求电脑端本 App 自己的端点 `/dsh-mobile/autopair`：
 * ```
 * 手机 ──Serve(注入 tailscale-user-login / x-forwarded-for)──▶ /dsh-mobile/autopair
 *        │  电脑端校验来源 IP 是否在本 tailnet 设备表里（tailscaled 维护，
 *        │  经 WireGuard 密钥验证，伪造不了）
 *        ▼  校验通过 → 电脑端**从回环**代发 POST /api/pair/issue
 *        ▼  把令牌交回手机
 * ```
 * 令牌仍由第三方插件自己按原策略签发，我们只是把请求从回环转发了一次 ——
 * 没有绕开它的任何限制。手机拿到令牌后走 `/pair-accept?pair=…`，与扫码落到
 * 的是同一个端点、同一套校验。
 *
 * 拿不到令牌（没配对过、不在 tailnet、电脑端没装插件）就返回 null，调用方退回
 * 原来的扫码流程，行为不会比现在更差。
 */
object AutoPairing {

    /** 电脑端本 App 注册的自动配对端点（pc-plugin/autopair.mjs 的 AUTOPAIR_PATH）。 */
    private const val AUTOPAIR_PATH = "/dsh-mobile/autopair"

    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        // Serve 走公网中继时域名解析与 TLS 握手都可能偏慢，留够余量但不无限等。
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    /** 取到的结果。token 只用于构造 accept URL，不写日志。 */
    data class Result(val acceptUrl: String?, val reason: String)

    /**
     * 请电脑端代签一个一次性配对令牌，并拼成 `/pair-accept?pair=` 地址。
     *
     * @param origin 电脑入口的 origin，例如 `https://<host>.ts.net`。
     * @return 失败时 `acceptUrl` 为 null，`reason` 说明卡在哪一步（用于日志）。
     */
    suspend fun requestAcceptUrl(origin: String): Result = withContext(Dispatchers.IO) {
        val base = ConnectionAddress.origin(origin)
            ?: return@withContext Result(null, "bad-origin")
        val issued = runCatching {
            val request = Request.Builder()
                .url(base + AUTOPAIR_PATH)
                .post("{}".toRequestBody(jsonMedia))
                .build()
            client.newCall(request).execute().use { response ->
                response.code to response.peekBody(8192).string()
            }
        }.getOrElse { error ->
            RunLog.info("AutoPair", "request failed ${error.javaClass.simpleName}")
            return@withContext Result(null, "request-error")
        }

        val (code, body) = issued
        // 403 = 电脑端判定「这台设备不被自动信任」（不在 tailnet、账号不符、
        // 插件没装）。这不是错误，退回扫码即可。
        if (code != 200) return@withContext Result(null, "http-$code")

        val token = runCatching { JSONObject(body).optString("token") }.getOrNull().orEmpty()
        // 与插件签发端的正则一致：base64url 风格，长度 16..256。
        if (token.length !in 16..256 || !token.all { it.isLetterOrDigit() || it == '_' || it == '-' }) {
            RunLog.info("AutoPair", "response carried no usable token")
            return@withContext Result(null, "bad-token")
        }

        // 只记结论，不记令牌。
        RunLog.info("AutoPair", "desktop issued a pairing token; token excluded")
        val encoded = java.net.URLEncoder.encode(token, "UTF-8")
        Result("$base/pair-accept?pair=$encoded", "issued")
    }
}
