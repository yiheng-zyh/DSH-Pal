package com.dsh.mobile.ui.web

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.dsh.mobile.core.RunLog
import com.dsh.mobile.core.StaticResourceCache
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** Shared across WebViews and navigations, on WebView's interception worker thread. */
object WebResourceCache {
    @Volatile private var cache: StaticResourceCache? = null
    private val navigationIds = AtomicLong()
    private val hits = AtomicLong()
    private val downloads = AtomicLong()
    private val validations = AtomicLong()
    private val savedBytes = AtomicLong()

    /**
     * Live byte accounting for the current navigation.
     *
     * The boot screen and the status strip read this so "loading" can show real
     * megabytes instead of a navigation percentage that stalls while a multi-
     * megabyte plugin bundle streams in.
     */
    val load = com.dsh.mobile.core.ResourceLoadTracker()

    /** Per-resource running totals, so `receiving` lines become deltas not sums. */
    private val reported = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Bumped by the UI so a new navigation starts from zero. */
    fun beginNavigation() {
        reported.clear()
        load.reset()
    }

    fun summary(): String = "页面资源默认由浏览器缓存管理。USB资源实验统计：本地复用 ${hits.get()} 次，校验复用 ${validations.get()} 次，下载 ${downloads.get()} 次，复用资源体积 %.1f MB".format(savedBytes.get() / 1048576.0)
    private fun record(message: String) {
        val bytes = Regex("bytes=(\\d+)").find(message)?.groupValues?.get(1)?.toLongOrNull() ?: 0
        // The id identifies one transfer; `receiving` repeats a running total.
        val id = Regex("id=([0-9a-fA-F]+)").find(message)?.groupValues?.get(1).orEmpty()
        when {
            message.startsWith("hit ") -> {
                hits.incrementAndGet(); savedBytes.addAndGet(bytes); load.onReused(bytes)
            }
            message.startsWith("validated ") -> {
                validations.incrementAndGet(); savedBytes.addAndGet(bytes); load.onReused(bytes)
            }
            message.startsWith("receiving ") -> {
                val previous = reported[id] ?: 0L
                reported[id] = bytes
                // The interceptor advertises the resource's Content-Length once the
                // body is open; feeding it in lets the UI show a real fraction.
                Regex("total=(\\d+)").find(message)?.groupValues?.get(1)?.toLongOrNull()?.let { total ->
                    load.onDeclaredSize(total)
                }
                if (previous == 0L) load.onStart() else load.onProgress(previous, bytes)
            }
            message.startsWith("download ") -> {
                downloads.incrementAndGet(); load.onFinished(bytes, reported.remove(id) ?: 0L)
            }
            message.startsWith("no-store ") -> load.onFinished(bytes, reported.remove(id) ?: 0L)
            message.startsWith("bypass ") -> {
                load.onFinished(0, reported.remove(id) ?: 0L, failed = true)
            }
            message.startsWith("fallback ") -> {
                load.onFinished(0, reported.remove(id) ?: 0L, failed = true)
            }
        }
        RunLog.info("Cache", message)
    }
    fun nextNavigation(): Long = navigationIds.incrementAndGet()
    fun clearResources(context:Context) {
        val store=cache?:synchronized(this){cache?:StaticResourceCache(File(context.applicationContext.cacheDir,"web-static-v1"),client=client(context.applicationContext),log=::record).also{cache=it}}
        store.clear()
        hits.set(0);downloads.set(0);validations.set(0);savedBytes.set(0)
        RunLog.info("Cache","resource cache cleared; cookies and saved connections retained")
    }
    private fun client(context:Context)=okhttp3.OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(10,java.util.concurrent.TimeUnit.SECONDS).readTimeout(20,java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(120,java.util.concurrent.TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request=chain.request()
            val settings=com.dsh.mobile.core.SettingsStore(context)
            if(settings.usbStaticPreview && StaticResourceCache.eligible(settings.baseUrl,request.url.toString(),request.method,false,request.headers.toMultimap().mapValues {it.value.joinToString(", ")})) {
                val original=request.url
                val local=original.newBuilder().scheme("http").host("127.0.0.1").port(18768).build()
                val builder=request.newBuilder().url(local)
                for(name in listOf("Origin","Referer")) {
                    val value=request.header(name)?:continue
                    val remoteOrigin="${original.scheme}://${original.host}"+(if(original.port==443) "" else ":${original.port}")
                    if(value==remoteOrigin||value.startsWith(remoteOrigin+"/"))builder.header(name,"http://127.0.0.1:18768"+value.removePrefix(remoteOrigin))
                }
                RunLog.info("Cache","development USB static transfer; API and realtime transport unchanged")
                chain.proceed(builder.build())
            } else chain.proceed(request)
        }.build()

    fun intercept(context: Context, origin: String, request: WebResourceRequest, navigation: Long): WebResourceResponse? {
        // Production navigation must use Chromium end-to-end. A synchronous
        // OkHttp cache miss used to hold WebView's request for up to 120 seconds,
        // then repeat it in Chromium on failure, with different TLS/cookie/SW state.
        // Keep the alternate transport only for the explicit USB asset experiment.
        if (!com.dsh.mobile.core.SettingsStore(context).usbStaticPreview) return null
        if (com.dsh.mobile.core.DevelopmentTransport.isUsbOrigin(origin)) return null
        val url = request.url.toString()
        if (!StaticResourceCache.eligible(origin, url, request.method, request.isForMainFrame, request.requestHeaders)) return null
        return runCatching {
            val store = cache ?: synchronized(this) {
                cache ?: StaticResourceCache(File(context.applicationContext.cacheDir, "web-static-v1"), client=client(context.applicationContext), log = ::record).also { cache = it }
            }
            val headers = request.requestHeaders.toMutableMap()
            if (headers.keys.none { it.equals("Cookie", true) }) {
                CookieManager.getInstance().getCookie(url)?.let { headers["Cookie"] = it }
            }
            val resource = store.load(url, headers, navigation) ?: return null
            WebResourceResponse(resource.mime, resource.encoding, 200, "OK", resource.headers, ByteArrayInputStream(resource.bytes))
        }.getOrElse {
            RunLog.warn("Cache", "browser fallback ${it.javaClass.simpleName}")
            null
        }
    }
}
