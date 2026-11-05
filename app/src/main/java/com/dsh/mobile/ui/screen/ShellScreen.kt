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
import androidx.compose.foundation.layout.Arrangement
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
// Navigation-stage line renderers (contract lines 4/5/6). Extension functions on
// ProbeSegments, kept in the core so the wording is unit-testable off-device.
import com.dsh.mobile.core.dnsFailLine
import com.dsh.mobile.core.probeLine
import com.dsh.mobile.core.tlsFailLine
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
    onTaskBusyChanged: (Boolean) -> Unit = {},
    /**
     * 执行通道切换（已带草稿确认）。建议条被接受时调用。
     * 默认空实现，方便测试与不需要该能力的调用点。
     */
    onSwitchChannel: (com.dsh.mobile.core.ConnectionProfile) -> Unit = {},
    /**
     * 外部提出的通道建议（由 ChannelFailoverHost 探测得到）。
     *
     * 为什么从外部传：探测与自动切换都在 DshMobileApp 那一层，
     * 本文件只负责渲染状态条 —— 两边各自探测会各提一次建议。
     */
    externalChannelSuggestion: Pair<com.dsh.mobile.core.ConnectionProfile, String>? = null,
    /** 建议被处理（接受 / 拒绝 / 超时）后通知外部清空。 */
    onChannelSuggestionConsumed: () -> Unit = {},
) {
    var progress by remember { mutableStateOf(0) }
    var task by remember { mutableStateOf(TaskState()) }
    LaunchedEffect(task.isLive){onTaskBusyChanged(task.isLive)}
    var loadError by remember { mutableStateOf<String?>(null) }
    // 通道建议来自外部（ChannelFailoverHost 探测），本文件只渲染不探测。
    // 这样避免两条探测循环各提一次建议。
    val channelSuggestion = externalChannelSuggestion
    // 实验功能：对话本地缓存。只在「开关开启 + 本地确实有归档」时露出离线入口，
    // 避免没开功能的用户看到一个点了是空页的按钮。
    var showOfflineChat by remember { mutableStateOf(false) }
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
    var confirmStartupRestart by remember(loadUrl) { mutableStateOf(false) }
    var service by remember { mutableStateOf<DshLiveService?>(null) }
    val network by com.dsh.mobile.core.NetworkConnectivity.state.collectAsState()
    val appVisible by com.dsh.mobile.core.NetworkConnectivity.visible.collectAsState()
    var endpoint by remember(loadUrl) { mutableStateOf<com.dsh.mobile.core.Reachability?>(null) }
    var business by remember(loadUrl) { mutableStateOf(com.dsh.mobile.core.BusinessHealth()) }
    // 隧道（外部中转）的真实状态。null=还没取到；UNKNOWN=网关没提供该字段（老版本）。
    var tunnelStatus by remember(loadUrl) { mutableStateOf<com.dsh.mobile.core.TunnelStatus?>(null) }
    var probeFailures by remember(loadUrl, network.revision) { mutableStateOf<Int?>(null) }
    var authLost by remember { mutableStateOf(false) }
    var disconnectedAt by remember { mutableStateOf(android.os.SystemClock.elapsedRealtime()) }
    var lastAutoReload by remember { mutableStateOf(-60_000L) }
    var autoReloadAttempts by remember(loadUrl) { mutableStateOf(0) }
    var lastNudge by remember { mutableStateOf(-30_000L) }
    val healthWindow = remember(loadUrl, network.revision) { com.dsh.mobile.core.HealthWindow() }

    // ---- 日志可观测性接线：阶段机 sink / Nav 看门狗 / 网络环境快照 ----
    // The stage machine is the backbone of the new log format. Every `Stage:` line
    // is emitted here, and the last one is what tells a reader where a stuck load
    // died — no ADB, no guessing between DNS / TCP / TLS / headers.
    val logContext = androidx.compose.ui.platform.LocalContext.current
    DisposableEffect(Unit) {
        com.dsh.mobile.core.NavStages.machine.install(object : com.dsh.mobile.core.NavStageMachine.Listener {
            override fun onStage(stage: com.dsh.mobile.core.NavStage, sinceStartMs: Long, detail: String) {
                com.dsh.mobile.core.RunLog.info("Stage", "${stage.label} +${sinceStartMs}ms $detail")
                com.dsh.mobile.core.DiagFacts.recordStage(
                    com.dsh.mobile.core.NavStages.machine.describe(),
                    stage.label,
                    com.dsh.mobile.core.NavStages.machine.failureReason(),
                )
            }

            override fun onFailed(reason: String, sinceStartMs: Long, detail: String) {
                com.dsh.mobile.core.RunLog.warn("Stage", "FAILED($reason) +${sinceStartMs}ms $detail")
                com.dsh.mobile.core.DiagFacts.recordStage(
                    com.dsh.mobile.core.NavStages.machine.describe(),
                    com.dsh.mobile.core.NavStages.machine.lastProgressStage().label,
                    reason,
                )
            }
        })
        onDispose { com.dsh.mobile.core.NavStages.machine.install(null) }
    }
    // Transport / proxy / private-DNS / WebView version, sampled once so the export
    // header can answer "was this even the same network?" without a second export.
    LaunchedEffect(Unit) {
        runCatching {
            com.dsh.mobile.core.NetEnvSnapshot.annotate(com.dsh.mobile.core.NetEnvSnapshot.collect(logContext))
        }
    }
    // Nav watchdog. While the navigation has not settled, say every 2s which stage
    // it is stuck on (state-unchanged lines are coalesced by LogPolicy). If it is
    // still before response headers after 15s, stop the load and run the
    // three-target comparison — that is what converts "10% forever" into a named
    // hop. A one-time pairing token can only be redeemed once, so this path stops
    // the load and never replays it.
    LaunchedEffect(loadUrl, loadAttempt) {
        var diagnosed = false
        while (true) {
            kotlinx.coroutines.delay(2_000)
            val stage = com.dsh.mobile.core.NavStages.machine.current()
            if (stage == com.dsh.mobile.core.NavStage.READY || stage == com.dsh.mobile.core.NavStage.FAILED) return@LaunchedEffect
            if (stage == com.dsh.mobile.core.NavStage.IDLE) continue
            if (pageLoaded && socketConnected) return@LaunchedEffect
            val view = webViewRef
            com.dsh.mobile.core.RunLog.warn(
                "Nav",
                "pending +${com.dsh.mobile.core.NavStages.machine.stalledFor()}ms stage=${stage.label} " +
                    "progress=$progress viewUrl=${view?.url?.let { viewUrlForLog(it) } ?: "-"} " +
                    "attached=${view?.isAttachedToWindow ?: false} netBlocked=${!network.available}",
            )
            if (!diagnosed && com.dsh.mobile.core.NavStages.machine.isStuckAtOrBelow(com.dsh.mobile.core.NavStage.TLS, 15_000L)) {
                diagnosed = true
                runCatching { view?.stopLoading() }
                com.dsh.mobile.core.RunLog.warn("Nav", "stalled before response headers; load stopped; no automatic replay")
                com.dsh.mobile.core.NavStages.machine.fail("no-response-headers", "stalled at ${stage.label}")
                val results = runCatching { com.dsh.mobile.core.DiagProbe.run(settings.baseUrl) }
                    .getOrDefault(emptyList())
                results.forEach { result ->
                    com.dsh.mobile.core.RunLog.warn(
                        "Diag",
                        "target=${result.target} result=${result.result} ${result.elapsedMs}ms",
                    )
                }
                runCatching {
                    com.dsh.mobile.core.NetEnvSnapshot.annotate(com.dsh.mobile.core.NetEnvSnapshot.collect(logContext))
                }
            }
        }
    }
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
    val pendingStartupResources = !mainDocumentFailed && (business.loadingResources > 0 || resourceLoad.busy)
    val restartStartup: () -> Unit = {
        loadError = null
        pageLoaded = false
        mainDocumentFailed = false
        autoReloadAttempts = 0
        loadAttempt++
        com.dsh.mobile.core.RunLog.info("Connection", "manual retry attempt=$loadAttempt")
        webViewRef?.let { com.dsh.mobile.ui.web.reloadPairedPage(it) }
    }
    val retryStartup: () -> Unit = {
        if (pendingStartupResources) {
            loadError = "界面资源仍在下载，已保留当前进度；可继续等待或选择重新下载"
            com.dsh.mobile.core.RunLog.info("Connection", "retry deferred; preserving pending resource downloads")
            webViewRef?.context?.let {
                android.widget.Toast.makeText(it, "资源仍在下载，已保留当前进度", android.widget.Toast.LENGTH_SHORT).show()
            }
        } else {
            restartStartup()
        }
    }
    // 定期读取隧道状态：隧道故障（被服务端拒绝等）时用户必须能看见，
    // 而不是只看到超时/白屏。10 秒一次足够，且这个端点很轻（只读 JSON）。
    LaunchedEffect(loadUrl, network.available) {
        if (!network.available) return@LaunchedEffect
        val profile = settings.activeProfile ?: return@LaunchedEffect
        while (true) {
            val status = com.dsh.mobile.core.TunnelProbe.check(profile)
            tunnelStatus = status
            delay(10_000)
        }
    }

    val networkStatus = com.dsh.mobile.core.ConnectionQualityPolicy.describe(        if(usbRoute) "USB" else network.transport, network.available || usbRoute,
        network.captive && !usbRoute, authLost, socketConnected, endpoint, probeFailures, business, resourceLoad,
        // 外部中转通道的真实状态：被服务端拒绝/断开时优先显示，避免"看起来正常其实不通"。
        tunnelStatus) + if(settings.usbStaticPreview) " · 资源 USB" else ""


    LaunchedEffect(loadUrl, network.revision, appVisible) {
        if (!appVisible) return@LaunchedEffect
        var failures = 0
        var reconnectAttempts = 0
        // 心跳降噪：状态不变就不再逐轮写一行。案例 A 里有 8 条一模一样的
        // `context-changed / samples=0`，既没信息量又把 400 条环形缓冲挤满。
        // 现在只在「状态变化」或「满 60 秒」时写一条，重复次数用 x N 表示。
        var lastHealthSignature = ""
        var lastHealthAt = 0L
        var healthRepeats = 0
        var lastNativeProbeAt = 0L
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
            // 心跳降噪：同状态合并，变化立即写，60 秒兜底写一条带计数的。
            val healthSignature = "reachable=${result.reachable} http=${result.status} error=${result.error} measured=${result.measured}"
            val healthNow = android.os.SystemClock.elapsedRealtime()
            val healthDetail = "browser health reachable=${result.reachable} http=${result.status} error=${result.error} rttMs=${result.elapsedMs} socket=$socketConnected samples=${healthWindow.count} failurePercent=${healthWindow.failurePercent}"
            if (healthSignature != lastHealthSignature) {
                com.dsh.mobile.core.RunLog.info(
                    "Network",
                    if (healthRepeats > 0) "$healthDetail (previous state x${healthRepeats + 1})" else healthDetail,
                )
                lastHealthSignature = healthSignature
                lastHealthAt = healthNow
                healthRepeats = 0
            } else if (healthNow - lastHealthAt >= 60_000L) {
                com.dsh.mobile.core.RunLog.info("Network", "$healthDetail (unchanged x${healthRepeats + 1})")
                lastHealthAt = healthNow
                healthRepeats = 0
            } else {
                healthRepeats++
            }
            // 浏览器探测看不见网络分层：`context-changed` 只说明页面还没就绪，
            // 案例 A 里它连打 8 条、samples 恒 0，等于「我不知道」。这里补一次
            // 原生 HEAD，把 DNS / TCP / TLS / 响应头分段写成 `Probe:` 行 ——
            // 这是整份日志里唯一能回答「到底断在哪一跳」的证据。
            // 只在浏览器探测拿不到结论时做，且 60 秒最多一次，避免自己刷屏。
            if (!result.measured && healthNow - lastNativeProbeAt >= 60_000L) {
                lastNativeProbeAt = healthNow
                val native = runCatching { com.dsh.mobile.core.HealthProbe.checkDetailed(settings.baseUrl) }.getOrNull()
                val segments = native?.segments
                if (segments != null) {
                    segments.probeLine()?.let { com.dsh.mobile.core.RunLog.info("Probe", it.removePrefix("Probe: ")) }
                    segments.dnsFailLine()?.let { com.dsh.mobile.core.RunLog.warn("Probe", it.removePrefix("Probe: ")) }
                    segments.tlsFailLine()?.let { com.dsh.mobile.core.RunLog.warn("Probe", it.removePrefix("Probe: ")) }
                }
            }
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
                // 实验功能：对话本地缓存。事件流带显式角色（user/message、
                // assistant/message、tool/call），比从 DOM 猜结构可靠得多；
                // 开关关闭时内部直接返回，不影响既有路径。
                com.dsh.mobile.ui.web.ChatArchiveCollector.onEvent(raw)
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
    // 实验功能：断网时若开关开启且本地确有归档，失败提示条上多一个「离线看历史」。
    // 依赖 network.available，联网后自动消失，不需要额外监听。
    val offlineAvailable = remember(network.available, settings.chatArchiveEnabled, loadError) {
        settings.chatArchiveEnabled && runCatching {
            com.dsh.mobile.ui.web.ChatArchiveCollector.list(context).isNotEmpty()
        }.getOrDefault(false)
    }
    // 把"开关开启 + 无网络"实时下发给页面内的 offline-guard。
    // 页面里挡 fetch/XHR/WebSocket 才是真正有效的离线屏蔽（shouldInterceptRequest
    // 只覆盖资源加载，看不到这些请求）。网络一变就推送，不等到下次导航。
    LaunchedEffect(network.available, settings.chatArchiveEnabled) {
        val offline = settings.chatArchiveEnabled && !network.available
        webViewRef?.evaluateJavascript(
            "window.__DSH_SET_OFFLINE__ && window.__DSH_SET_OFFLINE__($offline)",
            null,
        )
    }
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

    // A deadline explains a slow startup; only WebView's actual error callbacks
    // establish main-document failure. A slow plugin download must keep running.
    LaunchedEffect(loadUrl, loadAttempt) {
        delay(LOAD_TIMEOUT_MS)
        if (loadError == null && !pageLoaded) {
            val downloading = !mainDocumentFailed && (business.loadingResources > 0 || com.dsh.mobile.ui.web.WebResourceCache.load.snapshot().busy)
            loadError = if (downloading)
                "界面资源下载较慢，正在保留当前进度；可继续等待或查看日志"
            else "页面等待已超过 ${LOAD_TIMEOUT_MS / 1000} 秒，可查看日志或重试"
            com.dsh.mobile.core.RunLog.warn("Connection", "startup wait exceeded; progress=$progress socket=$socketConnected resources=${business.loadingResources}")
            // 90 秒兜底不再只是一句文案：把阶段机钉成失败、补采环境，并写一行
            // 结构化证据，导出时的「诊断摘要」就是据此生成结论的。
            com.dsh.mobile.core.NavStages.machine.fail("timeout-90s", "progress=$progress")
            // 最后一刻的 DOM 快照：案例 B 这种「资源 100% 但没挂载」只有 DOM 能证明。
            webViewRef?.let { view -> com.dsh.mobile.ui.web.snapshotDom(view, "timeout-90s") }
            runCatching {
                com.dsh.mobile.core.NetEnvSnapshot.annotate(com.dsh.mobile.core.NetEnvSnapshot.collect(logContext))
            }
            val evidence = com.dsh.mobile.core.DiagFacts.snapshot()
            com.dsh.mobile.core.RunLog.warn(
                "Diag",
                "timeout stage=${evidence.progressStage.ifBlank { "-" }} " +
                    "failure=${evidence.failureReason ?: "-"} " +
                    "rootChildren=${evidence.page?.rootChildren ?: -1} " +
                    "resources=${evidence.resources.ok}/${evidence.resources.total} " +
                    "failedScripts=${evidence.page?.failedScripts?.size ?: 0} " +
                    "jsErrors=${evidence.jsErrors.size}",
            )
        }
    }

    val nativeColors = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
    com.dsh.mobile.ui.ConfirmationDialog(
        show = confirmStartupRestart,
        title = "重新下载界面资源？",
        message = "当前下载将被中断，未缓存的内容需要重新下载。继续等待会保留当前进度。",
        cancelText = "继续等待", confirmText = "重新下载",
        onDismiss = { confirmStartupRestart = false },
        onConfirm = { confirmStartupRestart = false; if (!pageLoaded) restartStartup() },
    )
    val chrome = if (!pageLoaded) com.dsh.mobile.ui.theme.ShellSurfaceColors(nativeColors.background, nativeColors.surface, nativeColors.onBackground, nativeColors.onBackgroundVariant) else LocalShellSurface.current
    // 实验功能：断网时的离线历史页。整页覆盖，不叠加在主界面上，
    // 避免用户误以为那还是实时对话；退出后回到原界面继续等网络。
    if (showOfflineChat) {
        OfflineChatScreen { showOfflineChat = false }
        return
    }
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
                pageReady = pageLoaded,
                unifiedBackground = appearance != "dsh",
                onOpenDiagnostics = onOpenDiagnostics,
                onReload = onReloadPage,
                showActions = pageLoaded,
                // 通道建议直接进状态条（用户要求原位替换，不新加浮层）。
                channelSuggestion = channelSuggestion?.let { pair ->
                    "可切到 ${pair.first.kind.title} · ${pair.second}" to pair.second
                },
                onAcceptChannel = {
                    val target = channelSuggestion?.first
                    onChannelSuggestionConsumed()
                    if (target != null) {
                        settings.silenceChannelPrompt()
                        onSwitchChannel(target)
                    }
                },
                onDeclineChannel = {
                    settings.silenceChannelPrompt()
                    onChannelSuggestionConsumed()
                },
                onTimeoutChannel = {
                    // 倒计时走完、用户没操作 → 保持不变，只收掉提示。
                    // 不调用 silenceChannelPrompt：用户没表达意见，
                    // 下次条件满足时仍可再提示（只是不强制他做决定）。
                    com.dsh.mobile.core.RunLog.info("Channel", "suggestion timed out; keeping current channel")
                    onChannelSuggestionConsumed()
                },
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
                        if (error == null) {
                            pageLoaded = true
                            // 实验功能：页面每次就绪（含断网恢复后的重载）都重新对齐采集器状态。
                            // 只在 DshWebView 里挂一次不够：断网期间页面重载失败会停掉采集，
                            // 联网恢复后若不再触发，缓存就永远停在旧内容上。
                            runCatching { com.dsh.mobile.ui.web.ChatArchiveCollector.sync(context, active = true) }
                            // 缓存要持续更新：每次页面就绪都检查一遍网页上有哪些对话、
                            // 哪些还没缓存，把数字摆到界面上（不偷偷切换用户会话）。
                            runCatching { com.dsh.mobile.ui.web.ChatArchiveCollector.checkForUpdates(context) }
                        }
                    },
                )

                // The page itself paints nothing until the harness bundle lands, so
                // without this the user stares at a flat dark rectangle for the whole
                // first load. Cover it with the boot layer and fade that out the
                // moment an actual visible editor is ready; a bridge callback or
                // onPageFinished alone does not prove the workspace is usable.
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
                        onReload = retryStartup,
                        browserResources = business,
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
                                text = if (authLost) "需要配对" else if (mainDocumentFailed) "连接暂不可用" else "加载较慢",
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
                        // 2026-11-04 用户反馈：这几个选项「太丑了，都没有好好适配好
                        // 那些文字的排版，随便塞进去的一样」。
                        //
                        // 原实现的问题：
                        //   1. 四个 Text 直接用 Spacer(4.dp) 硬拼，宽度靠文字自然长度，
                        //      有的字多有的字少，视觉上完全不齐；
                        //   2. 长文案（「配对 / 换入口」）在窄屏会挤出屏幕；
                        //   3. 点击区只有 padding，没有边界感，不知道哪里能点。
                        //
                        // 改成等分布局：主操作（重试/重新下载）与次操作各占一份，
                        // 用 FlowRow 保证窄屏自动换行而不是溢出；每个按钮有统一的
                        // 最小高度与内边距，点击区一致、基线一致。
                        androidx.compose.foundation.layout.FlowRow(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            // 主操作：实心强调色，视觉上最重。
                            Text(
                                text = if (pendingStartupResources) "重新下载" else "重试",
                                color = LocalDshAccents.current.accent,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .pressClickable {
                                        if (pendingStartupResources) confirmStartupRestart = true else retryStartup()
                                    }
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                            )
                            Text(
                                text = "继续等待",
                                color = LocalDshAccents.current.waiting,
                                fontSize = 13.sp,
                                // The page is very likely still pulling assets;
                                // let the user (or a sleeping user's phone) simply
                                // keep waiting instead of being forced to act.
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .pressClickable { loadError = null }
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                            )
                            Text(
                                // 打开「设置 → 设备与连接」，即「选择连接途径」那一页。
                                //
                                // 这里曾直接进「添加连接」表单，用户 2026-11-04 反馈：
                                // 「他点那个配对，还有换入口，他进入的是这个界面。这个
                                // 界面引导不太对吧，不应该是回去到选择连接途径的页面
                                // 吗」。此刻他要的是换一条路，不是一个空地址输入框；
                                // 「设备与连接」同时给出当前连接、全部已保存方式、
                                // 局域网自动发现和重新扫码的入口。
                                // "重新配对" 这个名字也不成立：MESH 入口需要的是它
                                // 自己的 cookie，标签因此改为 "配对 / 换入口"。
                                text = if (authLost) "配对 / 换入口" else "换入口",
                                color = LocalShellSurface.current.onBackgroundMuted,
                                fontSize = 13.sp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .pressClickable { onUnpair() }
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                            )
                            // 实验功能：断网且开关开启、本地有缓存时，直接给一条进入离线历史的入口。
                            // 不放在设置深处：用户断网时正好停在这个提示条上，让他在原地就能回看。
                            if (offlineAvailable) {
                                Text(
                                    text = "离线看历史",
                                    color = LocalDshAccents.current.accent,
                                    fontSize = 13.sp,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .pressClickable { showOfflineChat = true }
                                        .padding(horizontal = 14.dp, vertical = 8.dp),
                                )
                            }
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
    pageReady: Boolean,
    unifiedBackground: Boolean = false,
    onOpenDiagnostics: () -> Unit,
    onReload: () -> Unit,
    showActions:Boolean,
    // 通道建议：null 表示没有建议，第二行照常显示 networkStatus。
    // 2026-11-04 用户要求：「整合到状态栏上面…需要提醒的时候就切换提醒界面，
    // 然后有倒计时，倒计时结束用户没有作出操作自动默认不变」。
    channelSuggestion: Pair<String, String>? = null,
    onAcceptChannel: () -> Unit = {},
    onDeclineChannel: () -> Unit = {},
    // 倒计时走完、用户什么都没点：默认保持不变，只是把提示收掉。
    onTimeoutChannel: () -> Unit = onDeclineChannel,
) {
    val accents = LocalDshAccents.current
    val surface = LocalShellSurface.current
    val stripFill = if (unifiedBackground) surface.background else surface.raised
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
        // Streaming plugins may leave Chromium below 100% after the UI is usable.
        if (!pageReady && loadFraction > 0.001f && loadFraction < 1f) {
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
                .background(stripFill)
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
                        !pageReady && progress in 1..99 -> "加载页面 $progress%"
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
        // 第二行：平时是 networkStatus；有通道建议时**原位替换**成建议内容。
        //
        // 为什么原位替换而不是新加一行：用户要求「整合到状态栏上面」「缩小界面」。
        // 新加行会把下面的网页顶下去，而且高度变化会让整个页面抖动一次 ——
        // 那正是他想避免的「太大个」。这里保持同一行、同一高度，只是内容切换。
        val suggestion = channelSuggestion
        if (suggestion != null) {
            // 建议态：一行内放「说明 + 倒计时 + 切换 + 不用」。
            // 倒计时用 remember 绑 suggestion.first（通道名），换建议即重置。
            val seconds = 20
            var remaining by remember(suggestion.first) { mutableStateOf(seconds) }
            LaunchedEffect(suggestion.first) {
                remaining = seconds
                while (remaining > 0) {
                    kotlinx.coroutines.delay(1_000)
                    remaining -= 1
                }
                // 倒计时结束用户没操作 → 默认保持不变，只是把提示收掉。
                onTimeoutChannel()
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(stripFill)
                    .heightIn(min = 26.dp)
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = suggestion.first,
                    color = accent,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "${remaining}s",
                    color = surface.onBackgroundMuted,
                    fontSize = 10.sp,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "切换",
                    color = accent,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .pressClickable(onClick = onAcceptChannel)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
                Text(
                    text = "不用",
                    color = surface.onBackgroundMuted,
                    fontSize = 10.5.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .pressClickable(onClick = onDeclineChannel)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        } else if(!compact) {
            Text(text = networkStatus, color = surface.onBackgroundMuted, fontSize = 10.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().background(stripFill).clickable(onClick = onOpenDiagnostics).padding(horizontal = 12.dp, vertical = 3.dp))
        }
    }
}

/**
 * WebView URL reduced to `scheme://host/path`, for the Nav watchdog line.
 *
 * The URL passed to `loadUrl` can still carry a one-time pairing token in its
 * query. The sanitizer would mask the value, but the safest thing is to never
 * hand the token to the log path at all — the path alone is what identifies the
 * navigation step.
 */
private fun viewUrlForLog(raw: String): String = runCatching {
    val uri = android.net.Uri.parse(raw)
    val scheme = uri.scheme.orEmpty()
    val host = uri.host.orEmpty()
    val path = uri.path.orEmpty().take(80)
    when {
        scheme.isBlank() || host.isBlank() -> "-"
        else -> "$scheme://$host$path"
    }
}.getOrDefault("-")
