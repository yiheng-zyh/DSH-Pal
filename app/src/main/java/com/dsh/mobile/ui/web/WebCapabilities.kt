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
    @JavascriptInterface fun originalDshPage(): Boolean =
        com.dsh.mobile.core.SettingsStore(context).webCompatibility(trustedOrigin).originalPage
    @JavascriptInterface fun pausedWebPlugins(): String = org.json.JSONArray(
        com.dsh.mobile.core.SettingsStore(context).webCompatibility(trustedOrigin).pausedPlugins,
    ).toString()
    // AUTO 模式据此决定桌面/手机布局。必须看「当前窗口宽度」而不是设备短边：
    // 平板竖屏短边 720dp≥600 会恒判桌面，把 720dp 的屏强行按 1024 CSS 宽排版，
    // 整体缩到 0.70×，触摸目标与正文随之缩小（实测 69/69 目标 <40dp、正文 11.3dp）。
    // 改为按当前宽度判定后：竖屏 720dp 走手机布局（1:1 不缩放），横屏 1152dp 仍走桌面。
    @JavascriptInterface fun largeWindow(): Boolean = context.resources.configuration.screenWidthDp >= 900
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
    @JavascriptInterface fun localAppearancePalette(): String {
        val p = com.dsh.mobile.core.LocalAppearance.fromId(appearance)
        return JSONObject().put("background",p.background).put("surface",p.surface)
            .put("accent",p.accent).put("foreground",p.foreground)
            .put("muted",p.muted).put("onAccent",p.onAccent).put("light",p.isLight).toString()
    }
    private val main = Handler(Looper.getMainLooper())
    private var upload: ValueCallback<Array<Uri>>? = null
    private var audio: PermissionRequest? = null
    private val downloads = com.dsh.mobile.core.DownloadStore.get(context)
    @JavascriptInterface fun downloadOpenAfter(): Boolean = com.dsh.mobile.core.SettingsStore(context).downloadOpenAfter
    @JavascriptInterface fun setDownloadOpenAfter(authority: String, enabled: Boolean) {
        if(authority == exportAuthority) main.post { if(trusted()) com.dsh.mobile.core.SettingsStore(context).downloadOpenAfter=enabled }
    }
    private data class Download(val token: String?, val url: String?, val name: String, val size: Long = 0,
        val session: String = "", val conversation: String = "", val openAfter: Boolean = true,
        var mime: String = "application/octet-stream", var file: com.dsh.mobile.core.DownloadStore.File? = null)
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
        offer(Download(null, url, WebFilePolicy.safeName(URLUtil.guessFileName(url, disposition, mime)), openAfter = downloadOpenAfter()), mime ?: "application/octet-stream")
    }
    private fun offer(next: Download, mime: String) {
        if (download != null) { notifyUser("已有文件正在保存，请稍后再试"); return }
        next.mime = downloads.mime(next.name, mime)
        download = next
        written = 0
        if(android.os.Build.VERSION.SDK_INT >= 29) {
            downloadJob = scope.launch(Dispatchers.IO) {
                val created = runCatching { downloads.begin(next.name,next.mime,next.session,next.conversation) }
                withContext(NonCancellable + Dispatchers.Main) {
                    created.onSuccess { file ->
                        if(download !== next) downloads.abort(file)
                        else { next.file=file; destinationChosen(Activity.RESULT_OK, Uri.parse(file.uri)) }
                    }.onFailure { if(download === next) finishDownload(false) }
                }
            }
        } else runCatching {
            launchSave(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(next.mime)
                .putExtra(Intent.EXTRA_TITLE,next.name)
                .putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload")))
        }.onFailure { finishDownload(false) }
    }
    @JavascriptInterface fun offerBlob(authority: String, token: String, name: String, mime: String, size: Long): Boolean =
        offerFile(authority,token,name,mime,size,"{}")
    @JavascriptInterface fun offerFile(authority: String, token: String, name: String, mime: String, size: Long, metadata: String): Boolean {
        if (authority != exportAuthority || metadata.length > 8192 || !WebFilePolicy.validBlobOffer(token, size) || download != null || !offerPending.compareAndSet(false, true)) return false
        val meta=runCatching { JSONObject(metadata) }.getOrNull()
        main.post { try {
            if (trusted()) offer(Download(token,null,WebFilePolicy.safeName(name),size,
                meta?.optString("session").orEmpty().take(256),meta?.optString("conversation").orEmpty().take(512),downloadOpenAfter()),mime)
            else releaseBlob(token)
        } finally { offerPending.set(false) } }
        return true
    }
    fun destinationChosen(result: Int, uri: Uri?) {
        val job = download ?: return
        if (result != Activity.RESULT_OK || uri?.scheme != "content") { finishDownload(false, cancelled = true); return }
        if(job.file == null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            job.file = runCatching { downloads.trackDestination(uri,job.name,job.mime,job.session,job.conversation) }.getOrElse { finishDownload(false); return }
        }
        notifyUser("正在保存文件，请保持页面连接")
        if (job.token != null) {
            downloadJob = scope.launch(Dispatchers.IO) {
                runCatching {
                    synchronized(this@WebCapabilities) { check(download === job); output = context.contentResolver.openOutputStream(uri, "w") ?: error("No destination"); written = 0 }
                }.onSuccess { withContext(Dispatchers.Main) { if(download === job) view?.evaluateJavascript("window.__DSH_FILE_SEND__&&window.__DSH_FILE_SEND__(${JSONObject.quote(job.token)})", null) } }
                    .onFailure { main.post { if(download === job) finishDownload(false) } }
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
                withContext(Dispatchers.Main) { if(download === job) finishDownload(success) }
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
        val closed = synchronized(this) { runCatching { output?.close() }.isSuccess.also { output=null } }
        val job = download ?: return
        download = null
        job.token?.let { releaseBlob(it) }
        val bytes=written
        // Completion must survive disposal of the WebView and its composition scope.
        CoroutineScope(Dispatchers.IO).launch {
            val file=job.file
            val saved=if(success && closed && file!=null) runCatching { downloads.complete(file,bytes) }.getOrNull() else null
            if(saved==null && file!=null) downloads.abort(file)
            withContext(Dispatchers.Main) {
                if(saved!=null) {
                    notifyUser("下载完成，可在设置 → 已下载文件中查看")
                    if(job.openAfter) runCatching { downloads.open(saved) }.onFailure { notifyUser("文件已下载，但没有可用的打开应用，请在已下载文件中重试") }
                } else if(!cancelled) notifyUser("下载失败，请保持连接后重试")
            }
        }
        RunLog.info("Download", "transfer finished success=$success cancelled=$cancelled bytes=$bytes")
    }
    fun pageNavigating() {
        upload?.onReceiveValue(null); upload = null; audio?.deny(); audio = null
        activeCall?.cancel(); activeCall = null; downloadJob?.cancel(); downloadJob = null
        finishDownload(false, cancelled = true)
    }
    fun dispose() { activeCall?.cancel(); downloadJob?.cancel(); pageNavigating(); view = null }
}
