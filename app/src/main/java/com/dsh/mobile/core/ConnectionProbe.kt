package com.dsh.mobile.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

enum class ProbeOutcome(val message: String) {
    DSH_FOUND("检测到 DSH 页面，可以继续配对"),
    AUTH_REQUIRED("入口可达，服务端要求认证；继续连接后确认"),
    REDIRECT("入口有响应，需确认重定向后的地址"),
    UNKNOWN_PAGE("入口有响应，但未确认是 DSH，请检查地址与端口"),
    HTTP_ERROR("入口返回错误，请检查电脑端服务与代理"),
    NETWORK_ERROR("无法访问入口，请检查网络、VPN、端口与电脑是否在线");
    companion object {
        fun classify(status: Int, fingerprint: Boolean): ProbeOutcome = when {
            status in setOf(401,403) -> AUTH_REQUIRED
            status in 300..399 -> REDIRECT
            status !in 200..299 -> HTTP_ERROR
            fingerprint -> DSH_FOUND
            else -> UNKNOWN_PAGE
        }
    }
}
data class ProbeResult(val outcome: ProbeOutcome, val status: Int? = null, val elapsedMs: Long = 0)

object ConnectionProbe {
    private val client = OkHttpClient.Builder().dns(DevelopmentDns).connectTimeout(3, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    suspend fun check(profile: ConnectionProfile): ProbeResult = withContext(Dispatchers.IO) {
        val start = System.nanoTime()
        RunLog.info("Probe", "begin kind=${profile.kind}; grant parameters excluded")
        val result = runCatching {
            // Probe only the origin root: never consume a one-shot pairing grant.
            client.newCall(Request.Builder().url(profile.baseUrl + "/").get().build()).execute().use { response ->
                val text = response.peekBody(4096).string()
                val fingerprint = text.contains("DeepSeek Harness", true) || text.contains("dsh-", true)
                ProbeResult(ProbeOutcome.classify(response.code, fingerprint), response.code)
            }
        }.getOrElse {
            RunLog.warn("Probe", "failed kind=${profile.kind}; reason=${it.javaClass.simpleName}")
            ProbeResult(ProbeOutcome.NETWORK_ERROR)
        }.copy(elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start))
        RunLog.info("Probe", "end kind=${profile.kind} result=${result.outcome} http=${result.status} elapsedMs=${result.elapsedMs}")
        result
    }

    /**
     * Is the tunnel actually carrying data, or only handshaking?
     *
     * A VPN interface existing and reporting `up` says the local process owns a
     * tun device — it says nothing about the far end. Measured on the tablet: tun0
     * had an assigned VPN address, the node was Online with a fresh handshake, and every
     * request to the peer still failed in ~0.4s while both sides sat on different
     * DERP relays. Without this check the app waits out its whole timeout before
     * telling the user anything, which reads as a freeze.
     *
     * A fast, explicit failure is the useful signal: it separates "tunnel has no
     * data path" from "slow network" so the UI can say which one it is.
     * @param origin - the address the tunnel is supposed to reach.
     * @returns true when the far end answered at all (any HTTP status counts).
     */
    suspend fun tunnelCarriesData(origin: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            // Any answer proves the path works; 401 is a perfectly good answer
            // here because it comes from the remote plugin, not from a timeout.
            client.newCall(Request.Builder().url(origin.trimEnd('/') + "/").head().build()).execute().use { true }
        }.getOrDefault(false)
    }
}
