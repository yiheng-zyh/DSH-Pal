package com.dsh.mobile.ui.web

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewRenderProcessClient
import com.dsh.mobile.bridge.DshBridge
import com.dsh.mobile.bridge.BridgeScript

/**
 * Hosts the paired DSH web UI exactly as the browser would, and keeps it in sync
 * with the Compose tree.
 *
 * Why a WebView rather than a native re-implementation: the remote-web-ui plugin
 * already serves the *official* client and owns the pairing, the gated `/remote`
 * channel, and the mobile adaptation layer. Re-rendering that UI natively would
 * fork behaviour and drift on every upstream release. The app's value-add is the
 * native surface around it — island, notifications, motion, chrome.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DshWebView(
    url: String,
    appearance: String = "dsh",
    bridge: DshBridge,
    modifier: Modifier = Modifier,
    onWebViewReady: (WebView) -> Unit,
    onProgress: (Int) -> Unit,
    onLoadStarted: () -> Unit = {},
    /** Called with the failure text when the main document fails, or null on success. */
    onLoadSettled: (String?) -> Unit = {},
) {
    val context = LocalContext.current
    val loadScope=rememberCoroutineScope()
    val capabilities = rememberWebCapabilities()
    val pageReadyScript = remember { context.assets.open("page-ready.js").bufferedReader().use { it.readText() } }
    val fileScript = remember {
        context.assets.open("web-files.js").bufferedReader().use { it.readText() }.replace("__DSH_FILE_AUTHORITY__", capabilities.exportAuthority) + "\n" +
            context.assets.open("web-media.js").bufferedReader().use { it.readText() } + "\n" + context.assets.open("ego-stream-budget.js").bufferedReader().use { it.readText() } + "\n" + context.assets.open("business-health.js").bufferedReader().use { it.readText() }
    }
    WebView.setWebContentsDebuggingEnabled(com.dsh.mobile.BuildConfig.WEB_INSPECTION)

    val webView = remember {
        lateinit var instance: WebView
        instance = WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                @Suppress("DEPRECATION")
                allowFileAccess = false
                @Suppress("DEPRECATION")
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                useWideViewPort = true
                loadWithOverviewMode = true
                builtInZoomControls = true
                displayZoomControls = false
                mediaPlaybackRequiresUserGesture = true
                cacheMode = WebSettings.LOAD_DEFAULT
                userAgentString = "$userAgentString DshMobileShell/1.0"
            }
            isVerticalScrollBarEnabled = false
            overScrollMode = WebView.OVER_SCROLL_NEVER
            setBackgroundColor(android.graphics.Color.parseColor("#FF15161A"))

            // The JS half talks to native through `window.DshNative`. Without this
            // registration the bridge script runs, finds no API object, and every
            // event is dropped on the floor — the app looks connected but the strip
            // and island never update.
            addJavascriptInterface(bridge, BridgeScript.INTERFACE)
            addJavascriptInterface(capabilities, "DshFiles")
        }

        // The pairing cookie IS the credential; it must persist across restarts.
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(instance, true)
        }
        instance
    }
    val configuration=androidx.compose.ui.platform.LocalConfiguration.current
    androidx.compose.runtime.LaunchedEffect(configuration.screenWidthDp,configuration.screenHeightDp,webView) { webView.evaluateJavascript(WebViewport.script(),null) }
    capabilities.view = webView
    capabilities.trustedOrigin = url
    capabilities.appearance = appearance
    androidx.compose.runtime.LaunchedEffect(appearance, webView) { webView.evaluateJavascript(WebAppearance.script(), null) }

    DisposableEffect(webView) {
        onDispose {
            capabilities.dispose()
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
    }

    DisposableEffect(url) {
        val cacheNavigation = java.util.concurrent.atomic.AtomicLong(WebResourceCache.nextNavigation())
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        var probeCount = 0
        var focusPrepared = false
        val contentProbe = object : Runnable {
            override fun run() {
                webView.evaluateJavascript(pageReadyScript) { result ->
                    if(result == "\"auth-required\"") {
                        com.dsh.mobile.core.RunLog.warn("Connection","stage=pairing rejected; saved route retained; re-pair required")
                        // The VPN route is the confusing one: Tailscale login
                        // survives an app-data clear (it lives in the VPN service),
                        // so "VPN 已连接" coexists with a 401 forever. Naming the
                        // two layers separately stops the user from retrying a page
                        // that can never succeed without a device cookie.
                        onLoadSettled(
                            if (bridge.profileKind() == com.dsh.mobile.core.ConnectionProfile.Kind.MESH)
                                "VPN 已连通，但这个入口还没有配对凭据。VPN 是网络层，配对是应用层：请点“配对 / 换入口”用桌面配对码配对一次，之后即可直连"
                            else "配对凭据已失效，请为此入口重新配对"
                        )
                    } else if (result == "true") {
                        // Focus the page surface while the loading cover is still
                        // up, without focusing an editor or requesting the IME.
                        // Otherwise the first input tap also wakes every plugin's
                        // window-focus handler (including wallpaper effects).
                        if (!focusPrepared) {
                            focusPrepared = true
                            webView.requestFocus()
                        }
                        com.dsh.mobile.core.RunLog.info("WebView", "visible UI ready")
                        onLoadSettled(null)
                    } else {
                        probeCount++
                        if (probeCount % 20 == 0) com.dsh.mobile.core.RunLog.info("Load", "waiting for visible UI; checks=$probeCount")
                        main.postDelayed(this, if (probeCount < 180) 500 else 2_000)
                    }
                }
            }
        }

        com.dsh.mobile.core.RunLog.info("WebView", "preparing url=$url")
        com.dsh.mobile.core.RunLog.info("WebView", "resource transport=Chromium; native cache only for explicit USB resource experiment")

        // Inject BEFORE any page script runs. This is the only hook that reliably
        // beats the harness's own boot code: the page opens its WebSocket while
        // booting, so a bridge installed later (onPageFinished) misses the socket
        // constructor and no events are ever tee'd — which looks exactly like
        // "the island never updates".
        val scriptHandler = runCatching {
            WebViewCompat.addDocumentStartJavaScript(
                webView,
                WebViewport.script() + "\n" + BridgeScript.build(forwardEvents = true, forwardState = true) + "\n" + com.dsh.mobile.bridge.WebMotionScript.build() + "\n" + com.dsh.mobile.bridge.WebLayoutScript.build(context) + "\n" + fileScript + "\n" + WebAppearance.script(),
                setOf("*"),
            )
        }.onSuccess {
            com.dsh.mobile.core.RunLog.info("WebView", "document-start script registered")
        }.onFailure {
            com.dsh.mobile.core.RunLog.warn("WebView", "document-start unavailable ${it.javaClass.simpleName}")
        }.getOrNull()

        val client = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): android.webkit.WebResourceResponse? =
                WebResourceCache.intercept(context, url, request, cacheNavigation.get())

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                onLoadStarted()
                cacheNavigation.set(WebResourceCache.nextNavigation())
                main.removeCallbacks(contentProbe)
                capabilities.pageNavigating()
                bridge.resetBudget()
                com.dsh.mobile.core.RunLog.info("WebView", "onPageStarted ${url?.take(120)}")
                // Inject at document start. `addDocumentStartJavaScript` above is
                // the supported path, but on some HyperOS WebView builds it silently
                // does nothing, so this second, unconditional injection guarantees
                // the constructor wrapper is in place before the page boots its
                // socket. The script is idempotent.
                view?.let { bridge.install(it) }
                // Streaming/slow subresources can delay onPageFinished even after
                // the conversation is usable. Observe the DOM during loading too.
                probeCount = 0
                main.postDelayed(contentProbe, 500)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                androidx.core.view.ViewCompat.requestApplyInsets(view.rootView)
                com.dsh.mobile.core.RunLog.info("WebView", "onPageFinished ${url?.take(120)}")
                // Last resort for pages whose boot code runs after load.
                bridge.install(view)
                view.evaluateJavascript(fileScript, null)
                view.evaluateJavascript(WebAppearance.script(), null)
                // Prove the JS half is actually present. If DshNative is missing the
                // events can never arrive, so surface that instead of silently
                // showing "connected" forever.
                view.evaluateJavascript(
                    "(function(){ return (window.DshNative ? 'native-ok' : 'native-missing') + '/' + (window.__DSH_MOBILE_BRIDGE__ ? 'bridge-ok' : 'bridge-missing'); })()",
                ) { result ->
                    android.util.Log.i("DshWebView", "probe: $result")
                    com.dsh.mobile.core.RunLog.info("WebView", "probe $result")
                }
                main.removeCallbacks(contentProbe)
                probeCount = 0
                main.post(contentProbe)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: android.webkit.WebResourceError?,
            ) {
                super.onReceivedError(view, request, error)
                // Only the main document failing means "the page is blank"; a
                // subresource failing is normal on a flaky mobile link.
                if (request?.isForMainFrame == true) {
                    val description = error?.description?.toString().orEmpty()
                    com.dsh.mobile.core.RunLog.error(
                        "WebView",
                        "onReceivedError ${error?.errorCode} $description url=${request.url?.toString()?.take(120)}",
                    )
                    onLoadSettled(description.ifBlank { "加载失败" })
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: android.webkit.WebResourceResponse?,
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                if (request != null && !request.isForMainFrame) {
                    val path=request.url.path.orEmpty()
                    if(path.endsWith(".js") || path.endsWith(".css") || path.endsWith("/plugins/")) {
                        com.dsh.mobile.core.RunLog.warn("WebView", "resource HTTP ${errorResponse?.statusCode}; path=${path.take(140)}; query excluded")
                    }
                }
                if (request?.isForMainFrame == true) {
                    // Before the plugin's service worker installs, '/' still belongs
                    // to desktop browser authentication. The paired landing accepts
                    // the existing device cookie and never needs a fresh pair token.
                    if(errorResponse?.statusCode==401 && request.url.path=="/" && com.dsh.mobile.core.WebFilePolicy.sameOrigin(request.url.toString(),url)) {
                        com.dsh.mobile.core.RunLog.info("Connection","desktop root requires browser login; reopening paired device landing")
                        view?.loadUrl(com.dsh.mobile.core.ConnectionAddress.origin(url) + "/pair-app")
                        return
                    }
                    com.dsh.mobile.core.RunLog.error("WebView", "main HTTP ${errorResponse?.statusCode}")
                    onLoadSettled("HTTP ${errorResponse?.statusCode ?: 0}")
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                val target = request?.url?.toString() ?: return false
                // Never intercept sub-frames or sub-resources: the harness embeds
                // engine/asset pages (wallpaper, scene-live) that must load inside
                // the WebView. Intercepting those was pushing the app *out* of the
                // page it had just paired on.
                if (request.isForMainFrame.not()) return false

                val origin = originOf(url)
                // Same origin: let the WebView handle it (pair-accept -> pair-app ->
                // / redirects are all same-origin and must run in-place so the
                // pairing cookie is set on our own cookie jar).
                if (com.dsh.mobile.core.WebFilePolicy.sameOrigin(target, origin)) return false
                com.dsh.mobile.core.PairingNavigation.selectedOriginLanding(url,target,request.isRedirect)?.let { landing ->
                    if(view!=null) {
                        com.dsh.mobile.core.RunLog.info("Connection","fresh pairing redirect kept on selected origin; no route switch")
                        view.loadUrl(landing)
                        return true
                    }
                }
                if (target.startsWith("blob:") || target.startsWith("data:")) return false

                // Anything else is genuinely someone else's app (oauth, docs).
                return runCatching {
                    view?.context?.startActivity(
                        android.content.Intent(android.content.Intent.ACTION_VIEW, request.url),
                    )
                    true
                }.getOrDefault(false)
            }
        }

        val chrome = object : WebChromeClient() {
            override fun onShowFileChooser(view: WebView, callback: android.webkit.ValueCallback<Array<android.net.Uri>>, params: FileChooserParams): Boolean = capabilities.chooseFile(callback, params)
            override fun onPermissionRequest(request: android.webkit.PermissionRequest) = capabilities.requestPermission(request)
            override fun onPermissionRequestCanceled(request: android.webkit.PermissionRequest) = capabilities.cancelPermission(request)
            private var lastBucket = -1
            override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                val text = message.message()
                if (text.startsWith("dsh-answer:")) com.dsh.mobile.core.RunLog.info("Reply", text)
                else if (message.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR) {
                    com.dsh.mobile.core.RunLog.error("Console", text)
                }
                return true
            }
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                onProgress(newProgress)
                val bucket = newProgress / 20
                if (bucket != lastBucket) {
                    lastBucket = bucket
                    com.dsh.mobile.core.RunLog.info("Load", "progress=$newProgress%")
                }
            }
        }

        // A heavy harness page can get its renderer reclaimed under memory
        // pressure (HyperOS does this aggressively). Without this hook the page
        // simply goes white with no recovery and no explanation.
        runCatching {
            WebViewCompat.setWebViewRenderProcessClient(
                webView,
                java.util.concurrent.Executor { it.run() },
                object : WebViewRenderProcessClient() {
                    override fun onRenderProcessUnresponsive(
                        view: WebView,
                        renderProcess: androidx.webkit.WebViewRenderProcess?,
                    ) {
                        com.dsh.mobile.core.RunLog.warn("WebView", "renderer unresponsive; retaining page to protect draft")
                        onLoadSettled("页面暂时无响应，可继续等待或重试")
                    }

                    override fun onRenderProcessResponsive(
                        view: WebView,
                        renderProcess: androidx.webkit.WebViewRenderProcess?,
                    ) {
                        main.removeCallbacks(contentProbe)
                        main.post(contentProbe)
                    }
                },
            )
        }

        webView.webViewClient = client
        webView.webChromeClient = chrome
        webView.setDownloadListener { target, agent, disposition, mime, _ -> capabilities.downloadUrl(target, agent, disposition, mime) }
        val loadJob=loadScope.launch {
            // Snapshot the target. Retry only preparation, before loadUrl has
            // ever run, so a one-time pairing grant is never replayed.
            val settings=com.dsh.mobile.core.SettingsStore(context)
            val origin=com.dsh.mobile.core.ConnectionAddress.origin(url)
            val profile=settings.activeProfile?.takeIf {it.baseUrl==origin}
                ?: settings.profiles.firstOrNull {it.baseUrl==origin}
            while(true) {
                try {
                    if(profile!=null) com.dsh.mobile.vpn.VpnController.prepareNavigation(profile)
                    webView.loadUrl(url)
                    break
                } catch(error:kotlinx.coroutines.TimeoutCancellationException) {
                    onLoadSettled("仍在等待通道与电脑入口，稍后自动重试；可在设置中切换连接")
                    if(com.dsh.mobile.vpn.VpnController.state.value.active==null)break
                    com.dsh.mobile.core.RunLog.info("Connection","pre-navigation wait timed out; VPN active; retrying before first browser request")
                    kotlinx.coroutines.delay(3_000)
                } catch(error:kotlinx.coroutines.CancellationException) { throw error
                } catch(error:Exception) {
                    com.dsh.mobile.core.RunLog.warn("Connection","navigation preparation failed ${error.javaClass.simpleName}")
                    onLoadSettled("通道未就绪，请在设置中检查配置与 VPN 授权")
                    break
                }
            }
        }
        onWebViewReady(webView)

        onDispose {
            loadJob.cancel()
            main.removeCallbacksAndMessages(null)
            scriptHandler?.remove()
            webView.stopLoading()
            runCatching { WebViewCompat.setWebViewRenderProcessClient(webView, null) }
            webView.webViewClient = WebViewClient()
            webView.webChromeClient = null
        }
    }

    AndroidView(
        factory = { webView },
        modifier = modifier,
        update = { /* state is driven by the page itself */ },
    )
}

/** Scheme + authority prefix of [url], used for same-origin checks. */
private fun originOf(url: String): String {
    val schemeEnd = url.indexOf("://")
    if (schemeEnd <= 0) return url
    val afterScheme = schemeEnd + 3
    val pathStart = url.indexOf('/', afterScheme)
    return if (pathStart < 0) url else url.substring(0, pathStart)
}
