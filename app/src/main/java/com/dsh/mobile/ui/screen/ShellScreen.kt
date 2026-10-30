package com.dsh.mobile.ui.screen

import android.webkit.WebView
import com.dsh.mobile.ui.AppBackHandler as BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import com.dsh.mobile.bridge.DshBridge
import com.dsh.mobile.core.SettingsStore
import com.dsh.mobile.core.TaskState
import com.dsh.mobile.service.DshLiveService
import com.dsh.mobile.ui.motion.Motion
import com.dsh.mobile.ui.motion.pressClickable
import com.dsh.mobile.ui.theme.LocalDshAccents
import com.dsh.mobile.ui.theme.LocalShellSurface
import com.dsh.mobile.ui.web.DshWebView
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Text

/**
 * How long the shell waits before declaring the load dead.
 *
 * The bar is deliberately generous: the harness pulls its whole plugin bundle on
 * first paint, and over a public tunnel that regularly exceeds half a minute. A
 * short bar produced a full-screen "load failed" overlay on top of a page that was
 * still loading perfectly well — worse than no watchdog at all, because it hides
 * the very progress the user is waiting for.
 *
 * Liveness is tracked separately (see `pageLoaded`), so this only fires when
 * nothing at all came back.
 */
private const val LOAD_TIMEOUT_MS = 90_000L

/**
 * The paired shell: a full-bleed WebView running the official DSH UI, with a
 * native status strip above it that mirrors what the island is showing.
 *
 * The strip is always visible on purpose. It is the only native chrome above the
 * page, so it doubles as the always-reachable way back into diagnostics — which
 * is where a user must go when the page will not load.
 */
/**
 * What to tell the user while the harness boots.
 *
 * Ordered by how much we actually know: a real percentage beats a phase guess,
 * and the socket being up is the strongest signal short of the DOM painting.
 */
private fun bootStatus(progress: Int, socketConnected: Boolean): String = when {
    socketConnected -> "已连接，正在加载界面…"
    progress in 1..30 -> "正在连接工作台… $progress%"
    progress in 31..80 -> "正在下载插件资源… $progress%"
    progress > 80 -> "正在渲染界面… $progress%"
    else -> "正在连接你的工作台…"
}

/**
 * The shell: native status strip + the official DSH web UI in a WebView.
 *
 * The native layer deliberately does not reimplement any of the chat UI; it adds
 * only what a web page cannot do (task state in the status bar, the island, the
 * foreground service) and otherwise stays out of the way.
 */
@Composable
fun ShellScreen(
    settings: SettingsStore,
    bridge: DshBridge,
    /** What to load right now: a one-shot pairing link, or the saved origin. */
    loadUrl: String,
    appearance: String = "dsh",
    /** True while [loadUrl] is a pairing link that still needs its handshake. */
    isPairingLink: Boolean,
    onWebViewCreated: (WebView) -> Unit,
    onOpenDiagnostics: () -> Unit,
    onReloadPage: () -> Unit,
    onLoadingChanged: (Boolean) -> Unit,
    onPairingConsumed: (String) -> Unit,
    onUnpair: () -> Unit,
) {
    var progress by remember { mutableStateOf(0) }
    var task by remember { mutableStateOf(TaskState()) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    // Whether the page's own WebSocket is open. Distinct from "page loaded": the
    // DOM can be up while the socket is still connecting.
    var socketConnected by remember { mutableStateOf(false) }
    // Set when the main document finished loading. This — not the progress
    // percentage — is what the timeout watchdog checks, because redirect targets
    // such as the pairing handshake page do not always report 100%.
    var pageLoaded by remember(loadUrl) { mutableStateOf(false) }
    var mainDocumentFailed by remember(loadUrl) { mutableStateOf(false) }
    var loadAttempt by remember { mutableStateOf(0) }
    var service by remember { mutableStateOf<DshLiveService?>(null) }
    val network by com.dsh.mobile.core.NetworkConnectivity.state.collectAsState()
    val appVisible by com.dsh.mobile.core.NetworkConnectivity.visible.collectAsState()
    var endpoint by remember(loadUrl) { mutableStateOf<com.dsh.mobile.core.Reachability?>(null) }
    var business by remember(loadUrl) { mutableStateOf(com.dsh.mobile.core.BusinessHealth()) }
    var probeFailures by remember(loadUrl, network.revision) { mutableStateOf<Int?>(null) }
    var authLost by remember { mutableStateOf(false) }
    var disconnectedAt by remember { mutableStateOf(android.os.SystemClock.elapsedRealtime()) }
    var lastAutoReload by remember { mutableStateOf(-60_000L) }
    var autoReloadAttempts by remember(loadUrl) { mutableStateOf(0) }
    var lastNudge by remember { mutableStateOf(-30_000L) }
    val healthWindow = remember(loadUrl, network.revision) { com.dsh.mobile.core.HealthWindow() }
    val usbRoute = settings.activeProfile?.kind == com.dsh.mobile.core.ConnectionProfile.Kind.USB
    // Byte counters move on the cache's worker thread while the page streams, and
    // neither the socket nor the probe state changes during that phase — so this
    // needs its own 1s tick rather than piggy-backing a `remember` key. One tick a
    // second is finer than the strip can be read at, and it is the only timer
    // added here.
    var resourceTick by remember { mutableStateOf(0) }
    LaunchedEffect(loadUrl) {
        while (true) { kotlinx.coroutines.delay(1000); resourceTick++ }
    }
    val resourceLoad = remember(loadUrl, resourceTick) {
        com.dsh.mobile.ui.web.WebResourceCache.load.snapshot()
    }
    val networkStatus = com.dsh.mobile.core.ConnectionQualityPolicy.describe(
        if(usbRoute) "USB" else network.transport, network.available || usbRoute,
        network.captive && !usbRoute, authLost, socketConnected, endpoint, probeFailures, business, resourceLoad) + if(settings.usbStaticPreview) " · 资源 USB" else ""


    LaunchedEffect(loadUrl, network.revision, appVisible) {
        if (!appVisible) return@LaunchedEffect
        var failures = 0
        var reconnectAttempts = 0
        val usb = settings.activeProfile?.kind == com.dsh.mobile.core.ConnectionProfile.Kind.USB
        while (true) {
            if (!network.available && !usb) {
                socketConnected = false
                delay(1_000); continue
            }
            if(webViewRef==null){delay(500);continue}
            val result = bridge.checkPageReachability()
            if(result.measured) endpoint = result
            // A separate native HEAD timeout must not overrule live browser traffic.
            val browserHealthy = pageLoaded && socketConnected && business.verified
            if (browserHealthy) autoReloadAttempts = 0
            if(result.measured || browserHealthy) healthWindow.record(result.reachable || browserHealthy)
            probeFailures = healthWindow.failurePercent
            failures = if (result.reachable || browserHealthy) 0 else if(result.measured) failures + 1 else failures
            val route = if (usb) "USB" else network.transport
            val now = android.os.SystemClock.elapsedRealtime()
            if (socketConnected) { disconnectedAt = 0; if (result.reachable) { loadError = null; authLost = false } }
            else if (disconnectedAt == 0L) disconnectedAt = now
            com.dsh.mobile.core.RunLog.info("Network", "browser health reachable=${result.reachable} http=${result.status} error=${result.error} rttMs=${result.elapsedMs} socket=$socketConnected samples=${healthWindow.count} failurePercent=${healthWindow.failurePercent}")
            // Optional/API faults must not tear down a live task channel or visible chat.
            val needsRecovery = !socketConnected
            if (needsRecovery && disconnectedAt == 0L) disconnectedAt = now
            val failedPageCanRetry = mainDocumentFailed && network.available && com.dsh.mobile.core.RecoveryPolicy.retryablePageFailure(loadError)
            if (settings.autoReconnect && needsRecovery && (result.reachable || failedPageCanRetry) && !authLost) {
                if (!socketConnected && now - lastNudge >= 10_000) {
                    lastNudge = now
                    webViewRef?.evaluateJavascript("window.__DSH_MOBILE_RECONNECT__ && window.__DSH_MOBILE_RECONNECT__()", null)
                    com.dsh.mobile.core.RunLog.info("Connection", "requested page-owned channel reconnect; no task replay")
                }
                if (com.dsh.mobile.core.RecoveryPolicy.canReload(!needsRecovery, true, authLost, task.isLive, now-disconnectedAt, now-lastAutoReload, autoReloadAttempts, business.loadingResources) && (pageLoaded || mainDocumentFailed)) {
                    val view = webViewRef
                    if (view != null) com.dsh.mobile.ui.web.hasUnsavedDraft(view) { draft ->
                        view.evaluateJavascript("!!(window.__DSH_NATIVE_ACTIVITY__ && window.__DSH_NATIVE_ACTIVITY__.busy()) || !!(window.DshFiles && DshFiles.hasPendingTransfer()) || !!(window.__DSH_PENDING_WRITES__ && window.__DSH_PENDING_WRITES__()) || Array.from(document.querySelectorAll('input[type=file]')).some(x=>x.files && x.files.length)") { busy ->
                            if (!draft && busy == "false" && !task.isLive && business.loadingResources == 0 && autoReloadAttempts < 2 && !socketConnected && settings.autoReconnect && appVisible && view.isAttachedToWindow && com.dsh.mobile.core.WebFilePolicy.sameOrigin(view.url.orEmpty(), settings.baseUrl)) {
                                autoReloadAttempts++
                                lastAutoReload = android.os.SystemClock.elapsedRealtime()
                                mainDocumentFailed = false; loadError = null; loadAttempt++
                                com.dsh.mobile.core.RunLog.info("Connection", "safe automatic reload; no draft or transfer")
                                com.dsh.mobile.ui.web.reloadPairedPage(view)
                            } else com.dsh.mobile.core.RunLog.info("Connection", "automatic reload deferred; preserving input or active transfer")
                        }
                    }
                }
            }
            if (autoReloadAttempts >= 2 && !pageLoaded && loadError == null && mainDocumentFailed) {
                loadError = "自动恢复未成功，已暂停刷新；可重试或在设置中检查连接日志"
            }
            if (socketConnected) reconnectAttempts = 0 else reconnectAttempts++
            delay(if (socketConnected && result.reachable) 12_000 else com.dsh.mobile.core.RecoveryPolicy.delayMs(maxOf(failures,reconnectAttempts)))
        }
    }

    LaunchedEffect(pageLoaded) { onLoadingChanged(!pageLoaded) }

    // Back inside the web app: walk its own history before letting the system
    // finish the Activity. Without this, one back press from a nested DSH view
    // drops the user straight out of the app.
    BackHandler(enabled = webViewRef?.canGoBack() == true && android.net.Uri.parse(webViewRef?.url.orEmpty()).path.orEmpty() !in listOf("", "/", "/pair-app", "/pair-accept")) {
        webViewRef?.goBack()
    }

    // Bridge -> service -> state. The service is the single owner of task state,
    // so the on-screen strip and the island can never disagree.
    DisposableEffect(bridge) {
        bridge.listener = object : DshBridge.Listener {
            override fun onPairingEstablished() {
                if(settings.pairingUrl.isBlank()) com.dsh.mobile.core.RunLog.info("Pair","paired landing confirmed; existing device credential accepted")
                if(settings.pairingUrl.isNotBlank()) {
                    com.dsh.mobile.core.RunLog.info("Pair","paired landing confirmed; one-time link consumed before resource loading")
                    onPairingConsumed(settings.baseUrl)
                }
            }
            override fun onBusinessHealth(health: com.dsh.mobile.core.BusinessHealth) {
                business = health

            }
            override fun onDismissKeyboard() {
                webViewRef?.let { view ->
                    (view.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(view.windowToken, 0)
                    view.clearFocus()
                }
            }
            override fun onSessionTitle(title: String) {
                DshLiveService.current()?.updateTitle(title)
            }

            override fun onEvent(raw: String) {
                DshLiveService.current()?.onRawEvent(raw)
            }

            override fun onPageReady(href: String) {
                // The page's own script just announced itself, which is proof the
                // document is up and running our code. This arrives earlier and more
                // reliably than onPageFinished on a page that keeps streaming
                // resources, so it also counts as "loaded" for the watchdog.
                // Bridge installation proves injection, not that the official UI painted.
                business = com.dsh.mobile.core.BusinessHealth()
                com.dsh.mobile.core.RunLog.info("Connection", "bridge installed; waiting for visible UI and business responses")
            }

            override fun onSocketState(connected: Boolean) {
                if (connected) settings.markConnectionUsed()
                com.dsh.mobile.core.RunLog.info("Connection", "task channel connected=$connected")
                socketConnected = connected
                if (connected) {
                    loadError = null; authLost = false; if(!business.recoveryNeeded) disconnectedAt = 0
                }
                else if (disconnectedAt == 0L) disconnectedAt = android.os.SystemClock.elapsedRealtime()
            }

            override fun onPageError(message: String) {
                if (!pageLoaded) loadError = "页面运行出错，可打开设置查看日志"
            }

            override fun onNetwork(online: Boolean) {
                if (!online && settings.activeProfile?.kind != com.dsh.mobile.core.ConnectionProfile.Kind.USB) socketConnected = false
                if (online && socketConnected) loadError = null
            }

            override fun onAuthLost() {
                com.dsh.mobile.core.RunLog.warn("Connection", "task authentication lost")
                if (!socketConnected) { authLost = true; loadError = "配对需要重新验证，请重新配对" }
            }
        }
        onDispose { bridge.listener = null }
    }

    // Mirror the service's state into this screen.
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        runCatching { DshLiveService.start(context) }.onFailure {
            com.dsh.mobile.core.RunLog.error("Service", "start failed ${it.javaClass.simpleName}")
        }
        DshLiveService.instances.collectLatest { svc ->
            service = svc
            svc?.state?.collect { task = it }
        }
    }

    DisposableEffect(bridge,service) {
        val owner=service
        owner?.companionBridge=bridge
        onDispose {if(owner?.companionBridge===bridge)owner.companionBridge=null}
    }

    // Answers typed in the notification shade. The service forwards them as an
    // effect because only this screen owns the WebView, and therefore the only
    // authenticated path back to the harness.
    LaunchedEffect(bridge, service) {
        service?.answers?.collect { effect ->
            when (effect) {
                is DshLiveService.Effect.AnswerSubmitted -> {
                    if (socketConnected && com.dsh.mobile.core.ReplyPolicy.canSubmit(service?.state?.value ?: TaskState(), effect.approvalId)) {
                        bridge.submitAnswer(effect.text)
                    } else {
                        com.dsh.mobile.core.RunLog.warn("Reply", "not sent; connection or approval changed")
                        android.widget.Toast.makeText(context, "回复未发送，请打开当前任务检查", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
                else -> Unit
            }
        }
    }

    // Once the pairing handshake has completed, remember the origin so the next
    // launch does not depend on an expired token. The page itself continues to the
    // app root — we deliberately do NOT reload here, because reloading mid-handshake
    // would throw away the cookie the handshake just set.
    LaunchedEffect(loadUrl, socketConnected) {
        if (settings.pairingUrl.isNotBlank() && socketConnected) {
            com.dsh.mobile.core.RunLog.info("Pair", "socket connected; pairing consumed")
            onPairingConsumed(settings.baseUrl)
        }
    }

    // Load watchdog. A page that neither finishes nor reports an error (a hung
    // connection, or a host that silently drops packets) leaves the WebView blank
    // forever, which is indistinguishable from a frozen app. Turning that into an
    // explicit failure guarantees the user always gets the retry/re-pair buttons.
    //
    // The liveness signal is `pageLoaded`, NOT the progress percentage: the
    // handshake lands on a redirect target (`/pair-app?grant=…`) whose
    // `onProgressChanged` does not necessarily reach 100, so a progress-based test
    // falsely declared a perfectly good page dead. `onPageFinished` is the real
    // "the document is up" signal.
    LaunchedEffect(loadUrl, loadAttempt) {
        delay(LOAD_TIMEOUT_MS)
        if (loadError == null && !pageLoaded) {
            mainDocumentFailed = true
            loadError = "页面未能启动（${LOAD_TIMEOUT_MS / 1000} 秒），请检查连接日志或重试"
            com.dsh.mobile.core.RunLog.warn("Connection", "load timeout; progress=$progress socket=$socketConnected")
        }
    }

    val nativeColors = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
    val chrome = if (!pageLoaded) com.dsh.mobile.ui.theme.ShellSurfaceColors(nativeColors.background, nativeColors.surface, nativeColors.onBackground, nativeColors.onBackgroundVariant) else LocalShellSurface.current
    androidx.compose.runtime.CompositionLocalProvider(LocalShellSurface provides chrome) {
    Box(
        Modifier
            .fillMaxSize()
            .background(LocalShellSurface.current.background)
            .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.systemBars.union(androidx.compose.foundation.layout.WindowInsets.displayCutout)),
    ) {
        Column(Modifier.fillMaxSize()) {
            TaskStrip(
                connectionName = settings.activeProfile?.kind?.title ?: "DSH",
                networkStatus = networkStatus,
                task = task,
                connected = socketConnected,
                progress = progress,
                onOpenDiagnostics = onOpenDiagnostics,
                onReload = onReloadPage,
                showActions = pageLoaded,
            )

            // `weight(1f)`, NOT `fillMaxSize()`: inside a Column the latter asks for
            // the whole parent height in addition to the strip, which pushed the
            // WebView past the bottom edge and left a blank screen.
            Box(Modifier.fillMaxWidth().weight(1f).clipToBounds()) {
                DshWebView(
                    url = loadUrl,
                    appearance = appearance,
                    bridge = bridge,
                    modifier = Modifier.fillMaxSize(),
                    onWebViewReady = { wv ->
                        webViewRef = wv
                        onWebViewCreated(wv)
                    },
                    onProgress = { progress = it },
                    // Some WebView versions report main HTTP failure before onPageStarted.
                    // Do not erase that failure and leave the loading cover spinning.
                    onLoadStarted = { pageLoaded=false },
                    onLoadSettled = { error ->
                        loadError = error
                        if(error?.startsWith("配对凭据已失效")==true)authLost=true
                        mainDocumentFailed = error != null
                        if (error == null) pageLoaded = true
                    },
                )

                // The page itself paints nothing until the harness bundle lands, so
                // without this the user stares at a flat dark rectangle for the whole
                // first load. Cover it with the boot layer and fade that out the
                // moment the document is up: `pageLoaded` flips on onPageFinished or
                // on bridge `ready`, whichever arrives first.
                androidx.compose.animation.AnimatedVisibility(
                    visible = !pageLoaded,
                    enter = fadeIn(tween(0)),
                    exit = fadeOut(Motion.exit()),
                ) {
                    // Loading and native controls share the selected local palette.
                    BootScreen(
                        status = bootStatus(progress, socketConnected),
                        progress = progress,
                        onRepair = onUnpair,
                        onDiagnostics = onOpenDiagnostics,
                        onReload = onReloadPage,
                    )
                }

            }
                // A blank page is indistinguishable from a hung app, so a failed
                // load needs an explanation. It is rendered as a bottom sheet rather
                // than a centred overlay on purpose: the harness can finish loading
                // long after this fires, and a full-screen panel would hide the very
                // page the user is waiting for. Anything that arrived late would look
                // like "it never loaded".
                val err = loadError
                if (err != null && (authLost || !pageLoaded)) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(LocalShellSurface.current.raised)
                            .padding(horizontal = 18.dp, vertical = 12.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (authLost) "需要配对" else "正在自动恢复",
                                color = LocalShellSurface.current.onBackground,
                                fontWeight = FontWeight.Medium,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = err,
                                color = LocalShellSurface.current.onBackgroundMuted,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Row {
                            Text(
                                text = "重试",
                                color = LocalDshAccents.current.accent,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .pressClickable {
                                        loadError = null
                                        // Reset the liveness flag so the watchdog
                                        // can fire again if this attempt also hangs.
                                        pageLoaded = false
                                        mainDocumentFailed = false
                                        autoReloadAttempts = 0
                                        loadAttempt++
                                        com.dsh.mobile.core.RunLog.info("Connection", "retry attempt=$loadAttempt")
                                        webViewRef?.let { com.dsh.mobile.ui.web.reloadPairedPage(it) }
                                    }
                                    .padding(horizontal = 14.dp, vertical = 6.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "继续等待",
                                color = LocalDshAccents.current.waiting,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    // The page is very likely still pulling assets;
                                    // let the user (or a sleeping user's phone) simply
                                    // keep waiting instead of being forced to act.
                                    .pressClickable { loadError = null }
                                    .padding(horizontal = 14.dp, vertical = 6.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                // Opens the add-connection flow (onUnpair maps to it),
                                // which is where a pairing link is entered. "重新配对"
                                // promised a repair that did not happen here: for the
                                // VPN route the origin needs a cookie of its own, so
                                // the honest label is "配对 / 换入口".
                                text = if (authLost) "配对 / 换入口" else "换入口",
                                color = LocalShellSurface.current.onBackgroundMuted,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .pressClickable { onUnpair() }
                                    .padding(horizontal = 14.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
        }
    }
    }
}

/**
 * The native status strip.
 *
 * Always rendered (never collapsed to zero height) because it is the only entry to
 * diagnostics. It animates its accent colour and pulse with the task lifecycle
 * instead of appearing/disappearing, so the layout below it never shifts.
 */
@Composable
private fun TaskStrip(
    connectionName: String,
    networkStatus: String,
    task: TaskState,
    connected: Boolean,
    progress: Int,
    onOpenDiagnostics: () -> Unit,
    onReload: () -> Unit,
    showActions:Boolean,
) {
    val accents = LocalDshAccents.current
    val surface = LocalShellSurface.current
    val compact=androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp<480

    val accent by androidx.compose.animation.animateColorAsState(
        targetValue = if (!connected) accents.error else accents.forPhase(task.phase),
        animationSpec = Motion.standard(),
        label = "accent",
    )

    Column(Modifier.zIndex(2f)) {
        // Deterministic page-load progress (0..100 from WebChromeClient). Animated,
        // not snapped, so a fast load still reads as movement rather than a flash.
        val loadFraction by animateFloatAsState(
            targetValue = (progress.coerceIn(0, 100)) / 100f,
            animationSpec = tween(durationMillis = Motion.PROGRESS_MS),
            label = "pageLoad",
        )
        if (loadFraction > 0.001f && loadFraction < 1f) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(surface.raised),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(loadFraction)
                        .height(2.dp)
                        .background(accent),
                )
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .background(surface.raised)
                .heightIn(min = 48.dp)
                .padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // While work is live the dot breathes; when idle it is static. A
            // constant pulse during a long run is what makes the strip feel alive
            // rather than frozen at "执行中".
            val pulse = rememberInfiniteTransition(label = "pulse")
            val dotAlpha by pulse.animateFloat(
                initialValue = 1f,
                targetValue = if (task.isLive) 0.35f else 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 900, easing = Motion.Gentle),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dotAlpha",
            )
            val dotScale by pulse.animateFloat(
                initialValue = 1f,
                targetValue = if (task.isLive) 1.25f else 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 900, easing = Motion.Gentle),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dotScale",
            )

            Box(
                Modifier
                    .size(8.dp)
                    .graphicsLayer { alpha = dotAlpha; scaleX = dotScale; scaleY = dotScale }
                    .clip(CircleShape)
                    .background(accent),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = when {
                        task.headline.isNotBlank() -> task.headline
                        progress in 1..99 -> "加载页面 $progress%"
                        !connected -> "正在连接…"
                        else -> connectionName
                    },
                    color = surface.onBackground,
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if(compact)Text(networkStatus,color=surface.onBackgroundMuted,fontSize=10.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                if (!compact && task.detail.isNotBlank() && task.detail != task.headline) {
                    Text(text = task.detail, color = surface.onBackgroundMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (task.stepTotal > 0) {
                Text(text = "${task.step}/${task.stepTotal}", color = surface.onBackgroundMuted)
            }
            Spacer(Modifier.width(12.dp))
            if(showActions) {
                com.dsh.mobile.ui.AppIconButton(com.dsh.mobile.ui.AppActionIcon.Refresh,"刷新",onReload,tint=surface.onBackground)
                Spacer(Modifier.width(2.dp))
                com.dsh.mobile.ui.AppIconButton(com.dsh.mobile.ui.AppActionIcon.Settings,"设置",onOpenDiagnostics,tint=surface.onBackground)
            }
        }
        if(!compact)Text(text = networkStatus, color = surface.onBackgroundMuted, fontSize = 10.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().background(surface.raised).clickable(onClick = onOpenDiagnostics).padding(horizontal = 12.dp, vertical = 3.dp))
    }
}
