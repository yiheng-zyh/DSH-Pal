package com.dsh.mobile.update

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * 解析蓝奏云分享文件夹里某个文件的真实下载直链。
 *
 * 分两段：文件夹列表用 OkHttp 直接拉（filemoreajax 接口，参数内嵌在分享页），
 * 拿到目标文件的分享页 URL；后半段（文件页 → /tp/ 中转 → CDN「验证并下载」）带 JS
 * 验证码，纯 HTTP 不可靠，交给一个 1x1 隐藏 WebView 走真实浏览器环境，靠
 * [DownloadListener] 拦截最终下载请求，把直链和 Cookie 交还 OkHttp 做带进度条下载与校验。
 *
 * 只读分享页，不登录、不写用户数据；解析完即销毁 WebView。失败返回 null，调用方兜底 GitHub。
 */
object LanzouResolver {
    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true).build()
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

    suspend fun resolve(context: Context, folderUrl: String, password: String, apkName: String): Pair<String, String>? {
        val filePage = runCatching { findFilePage(folderUrl, password, apkName) }.getOrNull() ?: return null
        return withTimeoutOrNull(60_000) { suspendCancellableCoroutine { cont ->
            val done = AtomicBoolean(false)
            var webView: WebView? = null
            fun finish(result: Pair<String, String>?) {
                if (!done.compareAndSet(false, true)) return
                main.post { runCatching { webView?.destroy() } }
                if (cont.isActive) cont.resume(result) {}
            }
            main.post {
                runCatching {
                    val wv = WebView(context.applicationContext)
                    webView = wv
                    configure(wv)
                    wv.setDownloadListener(DownloadListener { url, _, _, _, _ ->
                        val cookie = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull().orEmpty()
                        finish(url to cookie)
                    })
                    wv.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                            !isLanzouHost(request.url.toString())
                        override fun onPageFinished(view: WebView, url: String) {
                            if (url.contains("/tp/")) clickAndVerify(view) else clickDownload(view)
                        }
                    }
                    wv.webChromeClient = object : WebChromeClient() {
                        override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean { result.confirm(); return true }
                        override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean { result.confirm(); return true }
                    }
                    wv.loadUrl(filePage)
                }.onFailure { finish(null) }
            }
            cont.invokeOnCancellation { main.post { runCatching { webView?.destroy() } } }
        } }
    }

    /** OkHttp 拉分享页 + filemoreajax 列表，返回目标 APK 的分享页绝对 URL。 */
    private fun findFilePage(folderUrl: String, password: String, apkName: String): String? {
        val u = java.net.URI(folderUrl)
        val base = "${u.scheme}://${u.host}"
        val page = get(folderUrl, folderUrl) ?: return null
        fun rx(p: String): String? = Regex(p).find(page)?.groupValues?.get(1)
        val fid = rx("filemoreajax\\.php\\?file=(\\d+)") ?: return null
        val uid = rx("'uid':'(\\d+)'") ?: return null
        val puid = rx("'puid':'([^']+)'") ?: return null
        val t = rx("ib2bwb\\s*=\\s*'(\\d+)'") ?: return null
        val k = rx("_h0ny4\\s*=\\s*'([^']+)'") ?: return null
        val form = FormBody.Builder()
            .add("lx", "2").add("fid", fid).add("uid", uid).add("puid", puid)
            .add("pg", "1").add("rep", "0").add("t", t).add("k", k)
            .add("up", "1").add("ls", "1").add("pwd", password).build()
        val req = Request.Builder().url("$base/filemoreajax.php?file=$fid")
            .header("User-Agent", UA).header("Referer", folderUrl).post(form).build()
        val json = http.newCall(req).execute().use { it.body?.string() } ?: return null
        val root = JSONObject(json)
        if (root.optString("zt") != "1") return null
        val arr = root.optJSONArray("text") ?: return null
        for (i in 0 until arr.length()) {
            val row = arr.optJSONObject(i) ?: continue
            if (row.optString("name_all") == apkName) {
                val id = row.optString("id")
                return if (id.startsWith("http")) id else "$base/$id"
            }
        }
        return null
    }

    private fun get(url: String, referer: String): String? = runCatching {
        http.newCall(Request.Builder().url(url).header("User-Agent", UA).header("Referer", referer).build())
            .execute().use { it.body?.string() }
    }.getOrNull()

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(wv: WebView) {
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.userAgentString = WebSettings.getDefaultUserAgent(wv.context)
        CookieManager.getInstance().setAcceptCookie(true)
        wv.layout(0, 0, 1, 1)
    }

    /** 文件页：把下载按钮的 href 设好并点击，进入 /tp/。 */
    private fun clickDownload(view: WebView) {
        view.evaluateJavascript("""
        (function(){
          var s=document.getElementById('ddown')||document.getElementById('submit');
          if(s){ if(s.onmousedown)s.onmousedown(); if(s.onclick)s.onclick(); s.click(); }
        })();
        """.trimIndent(), null)
    }

    /** /tp/ 中转页：直链 = vkjxld+hyggid(+lanosso)。直接导航触发下载拦截；若遇验证页再 down_r。 */
    private fun clickAndVerify(view: WebView) {
        view.evaluateJavascript("""
        (function(){
          var s=document.getElementById('submit');
          if(s){ if(s.onmousedown)s.onmousedown(); if(s.onclick)s.onclick(); }
          var href=(s&&s.href&&s.href.indexOf('#')<0)?s.href:((typeof vkjxld!=='undefined'&&typeof hyggid!=='undefined')?(vkjxld+hyggid+(typeof lanosso!=='undefined'?lanosso:'')):null);
          if(href){ location.href=href; return; }
          if(typeof down_r==='function'){ down_r(1); }
        })();
        """.trimIndent(), null)
    }

    private fun isLanzouHost(url: String): Boolean = runCatching {
        val h = java.net.URI(url).host ?: return false
        listOf("lanzoul.com", "lanzou.com", "lanzouc.com", "lanosso.com", "dmpdmp.com",
            "ilanzou.com", "woozooo.com", "bakstotre.com").any { h == it || h.endsWith(".$it") }
    }.getOrDefault(false)
}
