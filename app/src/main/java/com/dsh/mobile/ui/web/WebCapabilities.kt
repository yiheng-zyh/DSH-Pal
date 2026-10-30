package com.dsh.mobile.ui.web

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.*
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.dsh.mobile.core.RunLog
import com.dsh.mobile.core.WebFilePolicy
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.OutputStream
import java.util.concurrent.TimeUnit

@Composable
fun rememberWebCapabilities(): WebCapabilities {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val helper = remember { WebCapabilities(context, scope) }
    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { helper.fileChosen(it.resultCode, it.data) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { helper.destinationChosen(it.resultCode, it.data?.data) }
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { helper.audioResult(it) }
    helper.launchChooser = { chooser.launch(it) }
    helper.launchSave = { save.launch(it) }
    helper.launchAudio = { microphone.launch(Manifest.permission.RECORD_AUDIO) }
    DisposableEffect(helper) { onDispose { helper.dispose() } }
    return helper
}

/** OS document pickers keep file access scoped to the files the user selects. */
class WebCapabilities(private val context: Context, private val scope: CoroutineScope) {
    var launchChooser: (Intent) -> Unit = {}
    var launchSave: (Intent) -> Unit = {}
    var launchAudio: () -> Unit = {}
    var view: WebView? = null
    var trustedOrigin: String = ""
    @Volatile var appearance: String = "dsh"
    val exportAuthority: String = java.util.UUID.randomUUID().toString()
    private val offerPending = java.util.concurrent.atomic.AtomicBoolean(false)
    @JavascriptInterface fun hasPendingTransfer(): Boolean = download != null || upload != null || audio != null
    @JavascriptInterface fun webLayoutMode(): String = com.dsh.mobile.core.SettingsStore(context).webLayoutMode.name
    @JavascriptInterface fun enhancedInteractions(): Boolean = com.dsh.mobile.core.SettingsStore(context).enhancedInteractions
    @JavascriptInterface fun largeWindow(): Boolean = context.resources.configuration.let { minOf(it.screenWidthDp,it.screenHeightDp)>=600 }
    @JavascriptInterface fun windowWidth(): Int = context.resources.configuration.screenWidthDp
    @JavascriptInterface fun multiWindow(): Boolean {
        var owner = context
        while (owner is android.content.ContextWrapper) {
            if (owner is Activity) return owner.isInMultiWindowMode
            val base = owner.baseContext
            if (base === owner) break
            owner = base
        }
        return false
    }
    @JavascriptInterface fun localAppearance(): String = appearance
    private val main = Handler(Looper.getMainLooper())
    private var upload: ValueCallback<Array<Uri>>? = null
    private var audio: PermissionRequest? = null
    private data class Download(val token: String?, val url: String?, val name: String, val size: Long = 0)
    @Volatile private var download: Download? = null
    private var output: OutputStream? = null
    private var written = 0L
    private var activeCall: okhttp3.Call? = null
    private var downloadJob: Job? = null
    private fun trusted() = view?.url?.let { WebFilePolicy.sameOrigin(it, trustedOrigin) } == true
    private fun notifyUser(text: String) { main.post { Toast.makeText(context, text, Toast.LENGTH_LONG).show() } }

    fun chooseFile(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean {
        upload?.onReceiveValue(null); upload = null
        if (!trusted()) { callback.onReceiveValue(null); return true }
        upload = callback
        runCatching { launchChooser(params.createIntent().addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE)) }
            .onFailure { upload = null; callback.onReceiveValue(null); notifyUser("无法打开系统文件选择器"); RunLog.warn("File", "picker unavailable") }
        RunLog.info("File", "document picker opened; multiple=${params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE}")
        return true
    }
    fun fileChosen(result: Int, intent: Intent?) {
        val callback = upload ?: run {
            RunLog.warn("File","picker result ignored; page callback no longer active result=$result")
            if(result==Activity.RESULT_OK)notifyUser("页面已重新载入，请重新选择文件")
            return
        }
        upload = null
        val clips=intent?.clipData?.let {clip->(0 until clip.itemCount).mapNotNull {clip.getItemAt(it).uri}}.orEmpty()
        val fallback=if(result==Activity.RESULT_OK)runCatching{WebChromeClient.FileChooserParams.parseResult(result,intent)}.getOrNull()?.toList().orEmpty()else emptyList()
        val selected=WebFilePolicy.chooserItems(result==Activity.RESULT_OK,intent?.data,clips,fallback)
        val uris=selected.takeIf {it.isNotEmpty()}?.toTypedArray()
        val originTrusted=trusted()
        val accepted = originTrusted && uris != null && WebFilePolicy.acceptedUris(uris.map { it.scheme })
        callback.onReceiveValue(if (accepted) uris else null)
        if(result==Activity.RESULT_OK&&!accepted)notifyUser(if(!originTrusted)"页面连接已变化，请重新选择文件" else "文件管理器未返回可读取文件，请重试或换用系统文件选择器")
        RunLog.info("File", "picker completed result=$result trusted=$originTrusted data=${intent?.data!=null} clips=${intent?.clipData?.itemCount?:0} schemes=${uris?.map{it.scheme}?.distinct()} accepted=$accepted count=${if(accepted) uris?.size else 0}")
    }
    fun requestPermission(request: PermissionRequest) {
        audio?.deny(); audio = null
        if (!trusted() || !WebFilePolicy.sameOrigin(request.origin.toString(), trustedOrigin) || PermissionRequest.RESOURCE_AUDIO_CAPTURE !in request.resources) { request.deny(); return }
        audio = request
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) audioResult(true)
        else launchAudio()
    }
    fun cancelPermission(request: PermissionRequest) { if (audio === request) audio = null }
    fun audioResult(granted: Boolean) {
        val request = audio ?: return
        audio = null
        if (granted && trusted() && WebFilePolicy.sameOrigin(request.origin.toString(), trustedOrigin)) request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) else request.deny()
        RunLog.info("Permission", "web microphone granted=$granted")
    }
    fun downloadUrl(url: String, userAgent: String?, disposition: String?, mime: String?) {
        if (!trusted() || !WebFilePolicy.sameOrigin(url, trustedOrigin)) { notifyUser("请在浏览器中打开外部下载链接"); return }
        offer(Download(null, url, WebFilePolicy.safeName(URLUtil.guessFileName(url, disposition, mime))), mime ?: "application/octet-stream")
    }
    private fun offer(next: Download, mime: String) {
        if (download != null) { notifyUser("已有文件正在保存，请稍后再试"); return }
        download = next
        written = 0
        RunLog.info("Download", "save destination requested; blob=${next.token != null}")
        val mediaType = mime.substringBefore(';').trim().takeIf { it.matches(Regex("[a-zA-Z0-9.+-]+/[a-zA-Z0-9.+-]+")) } ?: "application/octet-stream"
        runCatching { launchSave(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mediaType).putExtra(Intent.EXTRA_TITLE, next.name)) }
            .onFailure { finishDownload(false) }
    }
    @JavascriptInterface fun offerBlob(authority: String, token: String, name: String, mime: String, size: Long): Boolean {
        if (authority != exportAuthority || !WebFilePolicy.validBlobOffer(token, size) || download != null || !offerPending.compareAndSet(false, true)) return false
        main.post { try { if (trusted()) offer(Download(token, null, WebFilePolicy.safeName(name), size), mime) else releaseBlob(token) } finally { offerPending.set(false) } }
        return true
    }
    fun destinationChosen(result: Int, uri: Uri?) {
        val job = download ?: return
        if (result != Activity.RESULT_OK || uri?.scheme != "content") { finishDownload(false, cancelled = true); return }
        if (job.token != null) {
            scope.launch(Dispatchers.IO) {
                runCatching {
                    synchronized(this@WebCapabilities) { output = context.contentResolver.openOutputStream(uri, "w") ?: error("No destination"); written = 0 }
                }.onSuccess { withContext(Dispatchers.Main) { view?.evaluateJavascript("window.__DSH_FILE_SEND__&&window.__DSH_FILE_SEND__(${JSONObject.quote(job.token)})", null) } }
                    .onFailure { main.post { finishDownload(false) } }
            }
        } else {
            // Cookies go only to the selected origin. Redirects cannot leak them elsewhere.
            val cookie = CookieManager.getInstance().getCookie(job.url).orEmpty()
            downloadJob = scope.launch(Dispatchers.IO) {
                val success = runCatching {
                    val client = OkHttpClient.Builder().dns(com.dsh.mobile.core.DevelopmentDns).followRedirects(false).followSslRedirects(false).connectTimeout(15, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).build()
                    var targetUrl = job.url!!
                    var saved = false
                    for (hop in 0..5) {
                        val request = Request.Builder().url(targetUrl).apply { if (cookie.isNotBlank()) header("Cookie", cookie) }.build()
                        val call = client.newCall(request); activeCall = call
                        var redirect: String? = null
                        call.execute().use { response ->
                            RunLog.info("Download", "HTTP status=${response.code}; hop=$hop")
                            if (response.code in 300..399) {
                                redirect = WebFilePolicy.redirect(job.url, targetUrl, response.header("Location") ?: error("Missing redirect")) ?: error("External redirect refused")
                            } else {
                                check(response.isSuccessful)
                                context.contentResolver.openOutputStream(uri, "w")!!.use { target -> response.body!!.byteStream().use { written = it.copyTo(target) } }
                                saved = true
                            }
                        }
                        if (saved) break
                        targetUrl = redirect ?: error("Invalid redirect")
                    }
                    check(saved) { "Too many redirects" }
                }.isSuccess
                withContext(Dispatchers.Main) { finishDownload(success) }
            }
        }
    }
    @JavascriptInterface @Synchronized fun writeChunk(token: String, data: String): Boolean = runCatching {
        val job = download ?: return false
        if (token != job.token || data.length > 65536 || output == null) return false
        val bytes = android.util.Base64.decode(data, android.util.Base64.NO_WRAP)
        if (written + bytes.size > job.size) return false
        output!!.write(bytes); written += bytes.size
        true
    }.getOrDefault(false)
    @JavascriptInterface fun endBlob(token: String, success: Boolean) {
        if (token != download?.token) return
        val complete = synchronized(this) { success && written == download?.size }
        main.post { if (download?.token == token) finishDownload(complete) }
    }
    private fun releaseBlob(token: String) { view?.evaluateJavascript("window.__DSH_FILE_DROP__&&window.__DSH_FILE_DROP__(${JSONObject.quote(token)})", null) }
    private fun finishDownload(success: Boolean, cancelled: Boolean = false) {
        synchronized(this) { runCatching { output?.close() }; output = null }
        val job = download ?: return
        download = null
        job.token?.let { releaseBlob(it) }
        RunLog.info("Download", "save completed success=$success cancelled=$cancelled bytes=$written")
        if (!cancelled) notifyUser(if (success) "文件已保存到所选位置" else "保存失败，请重试；目标位置可能留有未完成文件")
    }
    fun pageNavigating() {
        upload?.onReceiveValue(null); upload = null; audio?.deny(); audio = null
        activeCall?.cancel(); activeCall = null; downloadJob?.cancel(); downloadJob = null
        finishDownload(false, cancelled = true)
    }
    fun dispose() { activeCall?.cancel(); downloadJob?.cancel(); pageNavigating(); view = null }
}
