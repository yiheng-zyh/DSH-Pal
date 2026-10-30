package com.dsh.mobile.core

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

data class Reachability(val status: Int? = null, val elapsedMs: Long = 0, val error: String? = null) {
    val reachable: Boolean get() = status != null && status < 500 && status != 429
    val measured: Boolean get() = status != null || error !in setOf(null,"context-changed","browser-not-ready","probe-unavailable")
}
class HealthWindow(private val size: Int = 10) {
    private val samples = ArrayDeque<Boolean>()
    fun record(reachable: Boolean) { if (samples.size >= size) samples.removeFirst(); samples.addLast(reachable) }
    val count get() = samples.size
    val failurePercent get() = if (samples.size < 3) null else samples.count { !it } * 100 / samples.size
}
object RecoveryPolicy {
    fun retryablePageFailure(message:String?):Boolean = message != null && (
        Regex("^HTTP (429|5[0-9]{2})$").matches(message) ||
        message.contains("ERR_TIMED_OUT") || message.contains("ERR_CONNECTION_RESET") ||
        message.contains("ERR_CONNECTION_CLOSED") || message.contains("ERR_CONNECTION_REFUSED") ||
        message.contains("ERR_NETWORK_CHANGED") || message.startsWith("页面未能启动")
    )
    fun delayMs(failures: Int): Long = longArrayOf(1_000,2_000,4_000,8_000,15_000,30_000)[failures.coerceIn(0,5)]
    fun canReload(connected: Boolean, reachable: Boolean, authLost: Boolean, busy: Boolean, disconnectedMs: Long, sinceReloadMs: Long, attempts: Int = 0, loadingResources: Int = 0): Boolean =
        attempts < 2 && loadingResources == 0 && !connected && reachable && !authLost && !busy && disconnectedMs >= 20_000 && sinceReloadMs >= 60_000
}
object HealthProbe {
    fun resetConnections() { client.connectionPool.evictAll() }
    private val client = OkHttpClient.Builder().dns(DevelopmentDns).followRedirects(false).followSslRedirects(false)
        .connectTimeout(3,TimeUnit.SECONDS).readTimeout(3,TimeUnit.SECONDS).callTimeout(4,TimeUnit.SECONDS).build()
    suspend fun check(origin: String): Reachability = suspendCancellableCoroutine { continuation ->
        val start = System.nanoTime()
        val call = client.newCall(Request.Builder().url(origin.trimEnd('/') + "/").head().header("Cache-Control","no-cache").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if(continuation.isActive) continuation.resume(Reachability(elapsedMs=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start),error=e.javaClass.simpleName)) }
            override fun onResponse(call: Call, response: Response) { response.use { if(continuation.isActive) continuation.resume(Reachability(it.code,TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start))) } }
        })
    }
}


data class BusinessHealth(val sampleCount:Int=0,val failurePercent:Int=-1,val slowPending:Int=0,val resourceFailures:Int=0,val lastSuccessAgeMs:Long=-1,val lastLatencyMs:Long=0,val loadingResources:Int=0,val oldestResourceMs:Long=0,val restrictedPlugins:Int=0) {
    val impaired get() = slowPending>0 || resourceFailures>0 || failurePercent>=20 || lastLatencyMs>=5_000
    val recoveryNeeded get() = resourceFailures>0 || failurePercent>=20
    val verified get() = !impaired && loadingResources==0 && lastSuccessAgeMs in 0..60_000
}
enum class ConnectionQuality { DISCONNECTED, TRANSPORT_ONLY, DEGRADED, VERIFIED }
object ConnectionQualityPolicy {
    fun classify(socket:Boolean,endpoint:Boolean,probeFailures:Int?,business:BusinessHealth):ConnectionQuality = when {
        (probeFailures?:0)>=20 || business.impaired -> ConnectionQuality.DEGRADED
        !socket -> ConnectionQuality.DISCONNECTED
        !endpoint -> ConnectionQuality.DEGRADED
        !business.verified -> ConnectionQuality.TRANSPORT_ONLY
        else -> ConnectionQuality.VERIFIED
    }

    /** A single rendering path prevents a late socket callback from hiding a business failure. */
    fun describe(route:String,available:Boolean,captive:Boolean,authLost:Boolean,socket:Boolean,endpoint:Reachability?,probeFailures:Int?,business:BusinessHealth,resourceLoad:ResourceLoad?=null):String {
        val failed=probeFailures?.takeIf {it>0}?.let {" · 入口探测失败 $it%"}.orEmpty()
        // Only the compact "已加载 x/y" form rides along with the business state;
        // the strip already carries two lines and must not grow a third. The full
        // breakdown (完成/复用/失败 counts) stays on the boot screen's log box.
        val transfer=resourceLoad?.takeIf { it.busy || it.loadedBytes>0 }?.let { ResourceLoadText.compact(it) }
        val detail=when {
            !available -> "无可用网络 · 恢复后自动重连"
            captive -> "需完成网络登录 · 自动重试"
            authLost -> "配对授权失效 · 需要重新验证"
            endpoint?.status==530 -> "公网隧道不可用（HTTP 530）· 自动重试"
            endpoint?.status in 500..599 -> "服务端异常（HTTP ${endpoint?.status}）· 自动重试"
            endpoint?.status==429 -> "服务端限流（HTTP 429）· 稍后重试"
            business.slowPending>0 -> "功能受阻 · ${business.slowPending} 个接口等待过久"
            business.resourceFailures>0 -> "部分功能不可用 · ${business.resourceFailures} 项资源加载失败"
            business.impaired -> "功能受阻 · 业务接口响应缓慢或失败"
            (probeFailures?:0)>=20 -> "连接不稳定$failed · 功能可能不可用"
            // The loading branch is where byte counts belong: this is the state the
            // user stares at while multi-megabyte plugin bundles stream in, and it
            // used to say only "loading N resources".
            business.loadingResources>0 -> buildString {
                append("正在加载 ${business.loadingResources} 项界面资源")
                if (transfer != null) append(" · $transfer")
                else append(" · 最长等待 ${business.oldestResourceMs/1000} 秒")
            }
            socket && endpoint?.reachable==true && business.verified -> "已收到业务响应 · 入口 ${endpoint.elapsedMs} ms$failed" + if(business.restrictedPlugins>0) " · ${business.restrictedPlugins} 项插件接口受限" else ""
            socket -> "通道已建立 · " + (if(business.lastSuccessAgeMs>=0) "业务响应 ${business.lastSuccessAgeMs/1000} 秒前" else "业务待验证") + failed +
                if(business.restrictedPlugins>0) " · ${business.restrictedPlugins} 项插件接口受限" else ""
            transfer!=null -> "$transfer · 通道已建立"
            endpoint?.reachable==true -> "入口可达 · 正在恢复实时通道$failed"
            endpoint==null -> "正在检测连接…"
            else -> "入口暂不可达 · 自动重试$failed"
        }
        return "$route · $detail"
    }
}
