package com.dsh.mobile.core

import org.json.JSONObject

/**
 * 网关（电脑侧）自报的通道清单，来自 `/api/pair/status` 响应里的 `channels` 数组。
 *
 * ## 为什么需要它，又为什么不能只信它
 *
 * 网关是从**电脑那一侧**看通道的：它自己绑上 IPv6 / LAN 监听了就报 `available=true`，
 * 隧道客户端在跑就报 ok。这回答的是「电脑准备好接客了吗」。
 *
 * 而 App 要判断的是完全不同的问题：「**手机现在这个网络**能不能到电脑」。
 * 两者会背离 —— 网关报 LAN ok，但手机此刻在 4G 上，那条 192.168 的地址毫无意义；
 * 网关报 tunnel 被拒（`provider-flaky`），手机走 Cloudflare 也确实不通。
 *
 * 所以本对象只提供**解释性信息**，不参与「可用/不可用」的判定：
 * - [category] 决定给用户的措辞 —— 把「通道侧故障」和「你自己电脑配错了」分开说，
 *   这是用户明确要求过的（「查实是隧道波动就报告，别让我去查自己的电脑」）；
 * - 真正的可用性一律由手机侧实测（[ChannelFailover.measure]）说了算。
 *   用网关自报去替代实测，等于把「手机在这个网络到不了、但电脑自报 ok」
 *   误判成可用，正好是选路最不该犯的错。
 */
data class GatewayChannel(
    /** 网关侧的通道标识，如 tunnel / ipv6 / lan。 */
    val id: String,
    /** 通道类型，与 [id] 通常相同。 */
    val kind: String,
    /** 该入口的 origin，可能与 App 已保存的地址完全一致，也可能从未保存过。 */
    val origin: String,
    /** 网关视角：这条入口是否已就绪。**注意不等于手机可达**。 */
    val available: Boolean,
    /** ok / provider-flaky / config-broken / unknown。 */
    val category: String,
    /** 脱敏后的补充说明（失败原因等）。 */
    val detail: String,
) {
    /**
     * 这条故障该怪谁。
     *
     * `provider-flaky` = 中转服务侧的问题（frpc 被拒、节点抖动），用户什么都不用改；
     * `config-broken` / `unknown` = 需要看电脑端配置。
     * 界面据此决定是「等它自己好」还是「去查电脑」，不让用户为服务侧的锅折腾自己。
     */
    val providerSideFault: Boolean get() = category == "provider-flaky"

    /** 给用户看的一句话。空 detail 时不显示多余标点。 */
    val summary: String
        get() = when (category) {
            "ok" -> "入口已就绪"
            "provider-flaky" -> "中转服务侧波动" + if (detail.isNotBlank()) "：$detail" else "，通常等一会自己好"
            "config-broken" -> "入口配置不可用" + if (detail.isNotBlank()) "：$detail" else ""
            else -> "状态未知" + if (detail.isNotBlank()) "：$detail" else ""
        }

    companion object {
        /**
         * 从 `/api/pair/status` 的响应体里读出通道清单。
         *
         * 兼容没有该字段的老网关：返回空列表，界面按「没这条信息」处理，
         * 不因为没有清单就谎报某条通道可用或不可用。
         */
        fun parsePairStatus(body: String): List<GatewayChannel> {
            val json = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
            val array = json.optJSONArray("channels") ?: return emptyList()
            return (0 until array.length()).mapNotNull { index ->
                val item = runCatching { array.getJSONObject(index) }.getOrNull() ?: return@mapNotNull null
                val id = item.optString("id").ifBlank { item.optString("kind") }
                if (id.isBlank()) return@mapNotNull null
                GatewayChannel(
                    id = id,
                    kind = item.optString("kind").ifBlank { id },
                    origin = item.optString("origin"),
                    available = item.optBoolean("available", false),
                    category = item.optString("category").ifBlank { "unknown" },
                    detail = item.optString("detail"),
                )
            }
        }

        /**
         * 从网关读取通道清单。
         *
         * 失败时返回空列表而不是抛异常：这份信息是**补充说明**，拿不到就退化成
         * 「只显示手机侧实测结论」，绝不能让设置页因此报错或空白。
         * 复用 [TunnelProbe] 的 client（同一个 DevelopmentDns 配置、同样的短超时）。
         */
        suspend fun fetch(profile: ConnectionProfile): List<GatewayChannel> =
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val request = okhttp3.Request.Builder()
                        .url(profile.baseUrl.trimEnd('/') + "/api/pair/status")
                        .get()
                        .build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@runCatching emptyList()
                        parsePairStatus(response.peekBody(32768).string())
                    }
                }.getOrElse {
                    RunLog.warn("Channel", "gateway channels unavailable reason=${it.javaClass.simpleName}")
                    emptyList()
                }
            }

        /** 与 [TunnelProbe] 同一套配置：同样的短超时，避免设置页卡在等待上。 */
        private val client = okhttp3.OkHttpClient.Builder()
            .dns(DevelopmentDns)
            .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        /**
         * 把网关清单与 App 已保存的 profile 对上号。
         *
         * 按 origin 匹配（而不是按 kind）：同一种通道用户可能存了两条
         * （家里两条家宽、两个不同的 Tailscale 节点），按 kind 匹配会把结论安到错的那条上。
         */
        fun match(
            channels: List<GatewayChannel>,
            profiles: List<ConnectionProfile>,
        ): Map<String, GatewayChannel> {
            val byOrigin = channels.associateBy { normalizeOrigin(it.origin) }
            return profiles.mapNotNull { profile ->
                byOrigin[normalizeOrigin(profile.baseUrl)]?.let { profile.id to it }
            }.toMap()
        }

        /** 规范化 origin 后再比：`HTTP://Host:443/` 与 `https://host` 必须认成同一条。 */
        private fun normalizeOrigin(raw: String): String =
            ConnectionAddress.origin(raw.trim()).orEmpty().ifBlank { raw.trim().trimEnd('/') }
    }
}
