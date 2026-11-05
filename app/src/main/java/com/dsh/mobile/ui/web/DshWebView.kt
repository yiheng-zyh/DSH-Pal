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
    // 离线屏蔽必须在 document-start 注入：DSH 页面 boot 时就建立 WebSocket 与
    // 首轮 fetch，晚一步注入就抓不到构造函数（与桥接脚本同一个理由）。
    val offlineGuardScript = remember { context.assets.open("offline-guard.js").bufferedReader().use { it.readText() } }
    // Page-side error collector (window.onerror / unhandledrejection / script
    // load failures). A missing asset must degrade to "no extra evidence", never
    // to a crash, so the load itself is the guarded inject site.
    val jsErrorScript = remember {
        runCatching { context.assets.open("dsh-js-errors.js").bufferedReader().use { it.readText() } }
            .getOrElse {
                com.dsh.mobile.core.RunLog.warn("Inject", "dsh-js-errors failed ${it.javaClass.simpleName}")
                com.dsh.mobile.core.DiagFacts.recordInjectFailed("dsh-js-errors", it.javaClass.simpleName)
                ""
            }
    }
    val resourceProbeScript = remember { com.dsh.mobile.core.PageProbeScript.resourceProbe() }
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
    // Latched once per navigation so a page that spams console errors still only
    // moves the stage machine a single time.
    val consoleErrorSeen = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
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
        // DOM / mount snapshot. `progress=100%` proves the bytes arrived; only the
        // DOM proves whether the app mounted. Sampled on the two moments that
        // matter (first 100%, and every 20th "no visible UI" check) so a blank
        // screen stops being a guess.
        fun captureDomSnapshot(tag: String) = snapshotDom(webView, tag)
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
                        // The strongest evidence available: the page's own script
                        // says the composer exists.
                        com.dsh.mobile.core.NavStages.machine.reach(com.dsh.mobile.core.NavStage.READY)
                        onLoadSettled(null)
                        // 实验功能：对话本地缓存。开关关闭时 sync() 内部直接返回，
                        // 不注册定时器、不建目录，所以这里无条件调用也不会改变默认行为。
                        ChatArchiveCollector.attach(context) { bridge }
                        ChatArchiveCollector.markPageAlive()
                        ChatArchiveCollector.sync(active = true)
                        // 记录页面自己调用的 /api 接口 —— 用于找到"完整消息"接口。
                        // projections 只给摘要（turnOutline 被截断），全文接口必须
                        // 从页面实际调用里发现，而不是猜路径。
                        webView?.let { v -> ChatArchiveCollector.installApiSpy(v) }
                    } else {
                        probeCount++
                        if (probeCount % 20 == 0) {
                            com.dsh.mobile.core.RunLog.info("Load", "waiting for visible UI; checks=$probeCount")
                            // Same cadence as the retry log: by now the page has had
                            // ten seconds to mount, so a snapshot is due.
                            captureDomSnapshot("checks=$probeCount")
                        }
                        main.postDelayed(this, if (probeCount < 180) 500 else 2_000)
                    }
                }
            }
        }

        com.dsh.mobile.core.RunLog.info("WebView", "preparing url=$url")
        com.dsh.mobile.core.RunLog.info("WebView", "resource transport=Chromium; native cache only for explicit USB resource experiment")
        // A new main-document navigation invalidates every page-side observation
        // made so far, so the console-error latch restarts with it.
        consoleErrorSeen.set(false)
        val probeSource = jsErrorScript + "\n" + resourceProbeScript

        /**
         * Evaluate [script] on [view] without ever disturbing the navigation.
         *
         * An injection that throws is itself a finding: "we registered it" does
         * not prove "it ran", so the failure is recorded under `Inject` — the tag
         * the contract reserves for exactly this — instead of dying in a bare
         * `runCatching`.
         */
        fun injectJs(view: WebView?, script: String, name: String, onResult: ((String?) -> Unit)? = null) {
            val target = view ?: return
            runCatching {
                if (onResult == null) target.evaluateJavascript(script, null)
                else target.evaluateJavascript(script) { raw -> runCatching { onResult(raw) } }
            }.onFailure {
                val reason = it.javaClass.simpleName
                com.dsh.mobile.core.RunLog.warn("Inject", "$name failed $reason")
                com.dsh.mobile.core.DiagFacts.recordInjectFailed(name, reason)
            }
        }

        // Inject BEFORE any page script runs. This is the only hook that reliably
        // beats the harness's own boot code: the page opens its WebSocket while
        // booting, so a bridge installed later (onPageFinished) misses the socket
        // constructor and no events are ever tee'd — which looks exactly like
        // "the island never updates".
        val scriptHandler = runCatching {
            WebViewCompat.addDocumentStartJavaScript(
                webView,
                WebViewport.script() + "\n" + offlineGuardScript + "\n" + BridgeScript.build(forwardEvents = true, forwardState = true) + "\n" + com.dsh.mobile.bridge.WebMotionScript.build() + "\n" + com.dsh.mobile.bridge.WebLayoutScript.build(context) + "\n" + fileScript + "\n" + WebAppearance.script() + "\n" + probeSource,
                setOf("*"),
            )
        }.onSuccess {
            com.dsh.mobile.core.RunLog.info("WebView", "document-start script registered")
        }.onFailure {
            com.dsh.mobile.core.RunLog.warn("WebView", "document-start unavailable ${it.javaClass.simpleName}")
            // Same finding under the tag the contract reserves for injection
            // failures, so the diagnostic summary can say "the probe never ran"
            // instead of "the page produced no errors".
            val reason = it.javaClass.simpleName
            com.dsh.mobile.core.RunLog.warn("Inject", "document-start failed $reason")
            com.dsh.mobile.core.DiagFacts.recordInjectFailed("document-start", reason)
        }.getOrNull()

        val client = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): android.webkit.WebResourceResponse? {
                // 实验功能：离线屏蔽。断网时实时通道与业务 API 注定失败，放任它们去打网络
                // 会让页面白等超时、刷满失败日志，还会被 DSH 的重连逻辑误判成故障而抖动。
                // 只挡这一类；静态资源与主文档必须放行，离线页要靠缓存的外壳才能画出来。
                val decision = WebResourceCache.offlineDecision(context, request)
                if (decision != com.dsh.mobile.core.OfflineRequestPolicy.Decision.ALLOW) {
                    com.dsh.mobile.core.RunLog.info("Offline", "blocked ${if (decision == com.dsh.mobile.core.OfflineRequestPolicy.Decision.BLOCK_REALTIME) "realtime" else "api"} path=${request.url?.encodedPath?.take(60)}")
                    return WebResourceCache.offlineResponse(decision)
                }
                return WebResourceCache.intercept(context, url, request, cacheNavigation.get())
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                onLoadStarted()
                cacheNavigation.set(WebResourceCache.nextNavigation())
                main.removeCallbacks(contentProbe)
                capabilities.pageNavigating()
                bridge.resetBudget()
                com.dsh.mobile.core.RunLog.info("WebView", "onPageStarted ${url?.take(120)}")
                // The document itself started arriving: the parser now has bytes.
                // This is the single most useful transition for case A, where the
                // page sat at progress=10% and this callback never fired at all.
                com.dsh.mobile.core.NavStages.machine.reach(com.dsh.mobile.core.NavStage.HTML, "progress=${webView.progress}")
                // Inject at document start. `addDocumentStartJavaScript` above is
                // the supported path, but on some HyperOS WebView builds it silently
                // does nothing, so this second, unconditional injection guarantees
                // the constructor wrapper is in place before the page boots its
                // socket. The script is idempotent.
                view?.let { bridge.install(it) }
                // 双保险注入（与桥接同一理由：部分 HyperOS WebView 上
                // addDocumentStartJavaScript 静默失效）。offline-guard 自身幂等。
                injectJs(view, offlineGuardScript + ";window.__DSH_SET_OFFLINE__ && window.__DSH_SET_OFFLINE__(false)", "offline-guard")
                // Same double-injection for the page-side error collector: if
                // addDocumentStartJavaScript was a no-op on this WebView build, an
                // error raised before onPageFinished would otherwise never be seen.
                // The script is idempotent (guards on window.__DSH_JS_ERRORS__).
                if (probeSource.isNotBlank()) injectJs(view, probeSource, "dsh-js-errors")
                // Streaming/slow subresources can delay onPageFinished even after
                // the conversation is usable. Observe the DOM during loading too.
                probeCount = 0
                main.postDelayed(contentProbe, 500)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                androidx.core.view.ViewCompat.requestApplyInsets(view.rootView)
                com.dsh.mobile.core.RunLog.info("WebView", "onPageFinished ${url?.take(120)}")
                // Scripts have had their turn by now; a syntax error would already
                // have surfaced through onConsoleMessage.
                com.dsh.mobile.core.NavStages.machine.reach(com.dsh.mobile.core.NavStage.SCRIPT_EVAL, "progress=${view.progress}")
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
                // Registration success is not execution success: check the actual
                // footholds too, and name each missing one. Without this a silent
                // document-start failure looks like a healthy page with no events.
                injectJs(view, com.dsh.mobile.core.PageProbeScript.injectCheck(), "inject-check") { result ->
                    // injectJs guards this callback body, so a non-JSON reply
                    // (or a null one) is simply recorded as "no finding".
                    val missing = org.json.JSONObject(result.orEmpty()).optJSONArray("missing") ?: return@injectJs
                    for (index in 0 until missing.length()) {
                        val name = missing.optString(index)
                        if (name.isBlank()) continue
                        com.dsh.mobile.core.RunLog.warn("Inject", "missing $name")
                        com.dsh.mobile.core.DiagFacts.recordInjectMissing(name)
                    }
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
                    // The contract wants the failure named in the navigation stream
                    // too, not only in the WebView dump: `Nav:` is the tag the issue
                    // summary reads when it asks "why did this attempt end?".
                    val reason = navFailureReason(error?.errorCode)
                    com.dsh.mobile.core.RunLog.error(
                        "Nav",
                        "failed reason=$reason code=${error?.errorCode} url=${request.url?.toString()?.take(120)}",
                    )
                    com.dsh.mobile.core.NavStages.machine.fail(reason, description.take(80))
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
                    // Same rule as onReceivedError: name the failure in the
                    // navigation stream with the contract's fixed token.
                    com.dsh.mobile.core.RunLog.error("Nav", "failed reason=http-error status=${errorResponse?.statusCode ?: 0} path=${request.url?.path.orEmpty().take(80)}")
                    com.dsh.mobile.core.NavStages.machine.fail("http-error", "HTTP ${errorResponse?.statusCode ?: 0}")
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
                if (text.startsWith("dsh-answer:")) {
                    com.dsh.mobile.core.RunLog.info("Reply", text)
                    return true
                }
                // The source is the whole point: an `Uncaught SyntaxError` with no
                // file name cannot be acted on. Chromium gives the absolute URL and
                // the line, so keep the file name (not the path) and the line.
                val source = message.sourceId().orEmpty()
                    .substringAfterLast('/').substringBefore('?').substringBefore('#').take(60)
                val where = "[${source.ifBlank { "-" }}:${message.lineNumber()}]"
                val level = message.messageLevel()
                // Chromium prefixes every mixed-content message with the literal
                // "Mixed Content:", so matching on that alone is both complete and
                // safe. A bare "was blocked" test also matched CORS and MIME
                // refusals, which are a different finding and must not be logged
                // under this tag.
                val mixed = text.contains("Mixed Content", ignoreCase = true)
                when {
                    level == android.webkit.ConsoleMessage.MessageLevel.ERROR -> com.dsh.mobile.core.RunLog.error("Console", "$where $text")
                    level == android.webkit.ConsoleMessage.MessageLevel.WARNING -> com.dsh.mobile.core.RunLog.warn("Console", "$where $text")
                    // Informational chatter is what fills the ring buffer; only keep
                    // it when it carries protocol evidence.
                    mixed -> com.dsh.mobile.core.RunLog.info("Console", "$where $text")
                }
                // Only real errors become "evidence" on the bus. Chromium prints
                // plenty of warnings (deprecations, autoplay policy) that are not
                // findings, and DiagSummary would otherwise quote one as the cause.
                if (level == android.webkit.ConsoleMessage.MessageLevel.ERROR) {
                    com.dsh.mobile.core.DiagFacts.recordJsError(
                        source.ifBlank { "console" },
                        message.lineNumber(),
                        0,
                        text,
                    )
                }
                // A syntax error is the first proof that scripts are being evaluated
                // and failing, which is exactly what SCRIPT_EVAL means.
                if (level == android.webkit.ConsoleMessage.MessageLevel.ERROR &&
                    consoleErrorSeen.compareAndSet(false, true)
                ) {
                    com.dsh.mobile.core.NavStages.machine.reach(
                        com.dsh.mobile.core.NavStage.SCRIPT_EVAL,
                        "console-error",
                    )
                }
                if (mixed) {
                    // Protocol-sensitive: this line is only useful with both schemes
                    // intact (page https, blocked http). The raw Chromium sentence is
                    // hundreds of characters of boilerplate, so the contract's compact
                    // form is assembled from the two parts that matter.
                    //
                    // Extraction rule: a mixed-content message *always* quotes the
                    // document url under its secure scheme and the blocked resource
                    // under the insecure one, so the first `http://` token is the
                    // blocked url — this holds across every Chromium wording
                    // ("insecure script", "insecure XMLHttpRequest endpoint",
                    // "insecure element", "targets an insecure form action", ...),
                    // which enumerating the nouns never did.
                    val blocked = Regex("http://[^\\s'\"<>]+").find(text)?.value.orEmpty()
                    val page = Regex("The page at '([^']+)'").find(text)?.groupValues?.getOrNull(1)
                        .orEmpty()
                    // ConsoleMessage carries no document url, so prefer the url the
                    // browser quoted in the sentence and fall back to the navigation
                    // origin, which is the same host for every same-origin page.
                    val source = page.ifBlank { originOf(url) }
                    val line = if (blocked.isNotBlank()) {
                        "blocked $blocked (页面来源 $source)"
                    } else {
                        // "Blocked" for some other reason (CORS, MIME): keep enough of
                        // the sentence to tell which, without the boilerplate.
                        "blocked <no url> (页面来源 $source) ${text.take(120)}"
                    }
                    com.dsh.mobile.core.RunLog.warn("MixedContent", line)
                    com.dsh.mobile.core.DiagFacts.recordMixedContent(line)
                }
                return true
            }
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                onProgress(newProgress)
                val bucket = newProgress / 20
                if (bucket != lastBucket) {
                    lastBucket = bucket
                    com.dsh.mobile.core.RunLog.info("Load", "progress=$newProgress%")
                    if (newProgress >= 100) {
                        // Resources are in; from here on only the DOM can explain a
                        // screen that stays blank (case B: 100% + rootChildren=0).
                        com.dsh.mobile.core.NavStages.machine.reach(com.dsh.mobile.core.NavStage.HTML, "progress=100")
                        captureDomSnapshot("progress=100")
                    }
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
            // One timeline per navigation: clear the stage machine and the evidence
            // bus before the first request leaves, so nothing from the previous
            // attempt can be read as this attempt's cause.
            com.dsh.mobile.core.NavStages.machine.begin()
            com.dsh.mobile.core.DiagFacts.reset()
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
            // 页面销毁即停止采集：不加这行，定时器会在没有 WebView 时继续空转。
            ChatArchiveCollector.detachPage()
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

/**
 * Map a main-document `WebResourceError` code onto the stage machine's fixed
 * failure vocabulary.
 *
 * The tokens are a contract: [DiagSummary] matches on them to pick its advice, so
 * they must stay stable and stay short. `ERROR_HOST_LOOKUP` is the one that
 * matters most — it is the difference between "the internet is down" and "this
 * one hostname does not resolve", which is exactly case A.
 */
private fun navFailureReason(errorCode: Int?): String = when (errorCode) {
    android.webkit.WebViewClient.ERROR_HOST_LOOKUP -> "dns"
    android.webkit.WebViewClient.ERROR_CONNECT -> "tcp-refused"
    android.webkit.WebViewClient.ERROR_TIMEOUT -> "no-response-headers"
    android.webkit.WebViewClient.ERROR_FAILED_SSL_HANDSHAKE -> "tls-handshake"
    android.webkit.WebViewClient.ERROR_BAD_URL,
    android.webkit.WebViewClient.ERROR_UNSUPPORTED_SCHEME -> "html-parse"
    else -> "unknown"
}

/**
 * Evaluate the DOM/mount probe and record the result into the run log and the
 * evidence bus.
 *
 * Top-level and `internal` on purpose: the WebView's own checkpoints (first
 * `progress=100`, every 20th "no visible UI" retry) and the ShellScreen timeout
 * path both need the same parse, and duplicating the JSON handling is how the two
 * sites drift until one of them silently reports -1.
 *
 * `rootChildren > 0` is the only evidence that the app shell actually mounted, so
 * it also moves the stage machine to APP_MOUNT.
 *
 * [tag] says *why* the sample was taken (`progress=100`, `checks=20`, a manual
 * shell prompt). It stays out of the `Dom:` line — the contract fixes that
 * format — and goes to logcat only, where it helps correlate a sample with the
 * checkpoint that asked for it.
 */
internal fun snapshotDom(webView: android.webkit.WebView, tag: String) {
    android.util.Log.d("DshWebView", "dom snapshot requested tag=$tag")
    runCatching {
        webView.evaluateJavascript(com.dsh.mobile.core.PageProbeScript.domSnapshot()) { raw ->
            runCatching {
                val obj = org.json.JSONObject(raw)
                val failedArray = obj.optJSONArray("failedScripts")
                val failed = (0 until (failedArray?.length() ?: 0))
                    .mapNotNull { index -> failedArray?.optString(index)?.takeIf { it.isNotBlank() } }
                val page = com.dsh.mobile.core.PageSnapshot(
                    readyState = obj.optString("readyState"),
                    title = obj.optString("title"),
                    path = obj.optString("path"),
                    rootChildren = obj.optInt("rootChildren", -1),
                    bodyLen = obj.optInt("bodyLen", -1),
                    editors = obj.optInt("editors", -1),
                    scripts = obj.optInt("scripts", -1),
                    failedScripts = failed,
                )
                com.dsh.mobile.core.RunLog.info(
                    "Dom",
                    "readyState=${page.readyState} title=\"${page.title}\" path=${page.path} " +
                        "rootChildren=${page.rootChildren} bodyLen=${page.bodyLen} " +
                        "editors=${page.editors} scripts=${page.scripts} " +
                        "failed=[${failed.joinToString(",")}]",
                )
                com.dsh.mobile.core.DiagFacts.recordPage(page)
                if (page.rootChildren > 0) {
                    com.dsh.mobile.core.NavStages.machine.reach(
                        com.dsh.mobile.core.NavStage.APP_MOUNT,
                        "rootChildren=${page.rootChildren}",
                    )
                }
            }
        }
    }
}
