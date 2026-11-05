package com.dsh.mobile.ui

import android.webkit.WebView
import com.dsh.mobile.ui.AppBackHandler as BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.dsh.mobile.bridge.DshBridge
import com.dsh.mobile.core.SettingsStore
import com.dsh.mobile.ui.motion.Motion
import com.dsh.mobile.ui.screen.SettingsScreen
import com.dsh.mobile.ui.screen.OnboardingScreen
import com.dsh.mobile.ui.screen.ShellScreen
import com.dsh.mobile.ui.theme.DshTheme

/**
 * Root of the Compose tree.
 *
 * The app has exactly two stable states, and the distinction matters:
 *
 *  - **Not configured** — no origin saved yet: show onboarding.
 *  - **Configured** — an origin is saved: load it in the WebView shell.
 *
 * A *pairing link* is a third, transient thing: it is handed to the WebView once
 * so the plugin can run its handshake, after which the saved origin is what gets
 * loaded. Treating the pairing link itself as the configured address is what
 * produced a black screen on relaunch (the token in it had expired).
 */
@Composable
fun DshMobileApp(
    settings: SettingsStore,
    settingsRevision: Int,
    onWebViewCreated: (WebView) -> Unit,
    onOpenSettings: () -> Unit,
    openWorkspaceRevision: Int = 0,
) {
    // Re-read stored state whenever the host reports a change (a deep link
    // arriving in onNewIntent, or unpairing). Compose cannot observe
    // SharedPreferences, so the revision counter is the trigger.
    //
    // A loopback origin is discarded rather than loaded: on a phone `127.0.0.1`
    // points at the phone itself, so it can only ever be a leftover from adb
    // debugging. Treating it as "configured" is what made the app launch straight
    // into a page that could not load, i.e. a black screen.
    var configuredOrigin by remember(settingsRevision) {
        mutableStateOf(if (!settings.autoConnectOnLaunch && settingsRevision == 0 && settings.pairingUrl.isBlank()) "" else settings.activeProfile?.takeIf { it.kind != com.dsh.mobile.core.ConnectionProfile.Kind.USB || com.dsh.mobile.BuildConfig.USB_DEVELOPMENT }?.baseUrl.orEmpty())
    }
    var pendingPairUrl by remember(settingsRevision) { mutableStateOf(settings.pairingUrl.takeIf {
        configuredOrigin.isNotBlank() && com.dsh.mobile.core.ConnectionAddress.origin(it) == configuredOrigin
    }.orEmpty()) }
    var shellLoadUrl by remember(settingsRevision) { mutableStateOf(settings.pairingUrl.ifBlank { com.dsh.mobile.core.LaunchRouting.resumeUrl(settings.baseUrl) }) }
    var shellLoading by remember { mutableStateOf(true) }
    var taskBusy by remember {mutableStateOf(false)}
    // 通道优化建议：由 ChannelFailoverHost 提出，交给 ShellScreen 的
    // 状态条"原位替换"第二行渲染（用户要求整合进状态栏，不要浮层）。
    var channelSuggestion by remember {
        mutableStateOf<Pair<com.dsh.mobile.core.ConnectionProfile, String>?>(null)
    }
    var showDiagnostics by remember { mutableStateOf(false) }
    var addingConnection by remember { mutableStateOf(false) }
    // 「配对 / 换入口」的落地页：设置的「设备与连接」。
    //
    // 用户 2026-11-04 原话：「他配对出现异常之后，他点那个配对，还有换入口，
    // 他进入的是这个界面。这个界面引导不太对吧，不应该是回去到选择连接途径的
    // 页面吗」。分布前两条路都是死胡同：非 MESH 走 addingConnection=true，
    // 那是「指定一个地址」的手填表单，用户此刻并不想新建连接；MESH 走
    // showVpnRepair=true，进的是「内置通道」页，只覆盖一条路。
    // 「设备与连接」才是「选择连接途径」的页面：当前连接、每一种已保存方式、
    // 局域网自动发现、以及通往内置通道和重新扫码的入口都在那里。
    var openConnectionList by remember { mutableStateOf(false) }
    var appearance by remember { mutableStateOf(settings.localAppearance.id) }
    var pendingNavigation by remember { mutableStateOf<(() -> Unit)?>(null) }
    var predictiveBack by remember {mutableStateOf(settings.predictiveBackEnabled)}
    androidx.compose.runtime.DisposableEffect(settings) {
        val stop=settings.observePredictiveBack {predictiveBack=it}
        onDispose {stop()}
    }
    val switchScope=rememberCoroutineScope()
    var switching by remember { mutableStateOf(false) }
    var switchError by remember { mutableStateOf<String?>(null) }
    var switchStage by remember { mutableStateOf("") }
    var switchSeconds by remember { mutableStateOf(0) }
    var switchJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    LaunchedEffect(switching) {switchSeconds=0;while(switching){kotlinx.coroutines.delay(1000);switchSeconds++}}
    fun switchConnection(target:com.dsh.mobile.core.ConnectionProfile, browserFirst:Boolean=false, commit:()->Unit) {
        if(switching)return
        val previous=settings.activeProfile
        switching=true
        switchStage="正在准备 ${target.kind.title} 连接"
        com.dsh.mobile.core.RunLog.info("Connection","switch begin from=${previous?.kind} to=${target.kind} target=${target.id.take(8)}")
        switchJob=switchScope.launch {
            try {
                com.dsh.mobile.core.ConnectionSwitch.run(target,previous,
                    prepare={com.dsh.mobile.vpn.VpnController.prepareNavigation(it){stage->if(stage!=switchStage){switchStage=stage;com.dsh.mobile.core.RunLog.info("Connection","stage=$stage")}}},
                    probe={switchStage="正在测试电脑 DSH 是否响应";com.dsh.mobile.core.ConnectionProbe.check(it)},
                    commit=commit, browserFirst=browserFirst,
                    progress={stage->if(stage!=switchStage){switchStage=stage;com.dsh.mobile.core.RunLog.info("Connection","stage=$stage")}})
                com.dsh.mobile.core.RunLog.info("Connection","switch committed kind=${target.kind} target=${target.id.take(8)}")
            } catch(error:kotlinx.coroutines.CancellationException) {
                if(error !is kotlinx.coroutines.TimeoutCancellationException)throw error
                switchError="停在：$switchStage。原来的页面还在，没有丢。${com.dsh.mobile.vpn.VpnController.state.value.let { vpn -> if(vpn.active!=null&&vpn.up) "当前 ${vpn.active?.title} 通道已恢复运行。" else "" }}请按上面的提示检查后重试，也可以点“取消切换”回来。"
                com.dsh.mobile.core.RunLog.warn("Connection","switch timeout; previous selection retained")
            } catch(error:Exception) {
                switchError="${error.message?.takeIf {error is IllegalStateException} ?: "请检查目标地址、VPN 授权及电脑端服务。"} 已保留原来的连接。"
                com.dsh.mobile.core.RunLog.warn("Connection","switch failed ${error.javaClass.simpleName}; previous selection retained")
            } finally { switching=false }
        }
    }

    // Recover into the method chooser; retain invalid legacy bytes for diagnosis.
    LaunchedEffect(settingsRevision) {
        if (settings.baseUrl.isNotBlank() && isLoopbackOrigin(settings.baseUrl)) {
            configuredOrigin = ""
        }
    }

    // ---- 启动时先探一次「当前连接」，别再硬加载一条已经失效的地址 ----------
    //
    // 用户真机反馈（2026-11-05）：「我之前用 vpn 的时候开了个网关，这个地址好像是一直
    // 在电脑里了。然后手机一开始直接的是这个，导致连不上，后来把那个网址禁用了就好了。」
    //
    // 改之前：L65-71 只看「有没有 activeProfile」就把 `<origin>/pair-app` 交给 WebView；
    // 地址失效时一路白屏，要等 ChannelFailover 的「2 次巡检失败 + 15 秒倒计时」才有出口
    // （实测 2026-11-05 本机复现：02:56:35 启动 → 02:57:04 才切换，约 29 秒）。
    //
    // 改之后：探测一次（[HealthProbe] 自带 3s/3s/4s 超时，异步 enqueue 不占主线程），
    // 不通就**不加载**那条地址，直接把「设备与连接」页推上来并说明原因。
    //
    // 为什么用 HealthProbe 而不是 ConnectionProbe：后者是 GET + withContext(IO) + 5s，
    // 而且会往 RunLog 打 begin/end，启动期多打日志会冲掉诊断环形缓冲。
    // 为什么只在 settingsRevision 变化时探一次：revision 变化=用户换了连接，
    // 这正是唯一需要重判的时机；平时不要多开一条探测循环去和 ChannelFailoverHost 抢。
    var launchStale by remember(settingsRevision) {
        mutableStateOf<com.dsh.mobile.core.LaunchHealth.Verdict.Unreachable?>(null)
    }
    var launchProbeRetry by remember(settingsRevision) { mutableStateOf(0) }
    LaunchedEffect(settingsRevision, launchProbeRetry) {
        val profile = settings.activeProfile
        val origin = profile?.baseUrl.orEmpty()
        if (!com.dsh.mobile.core.LaunchHealth.shouldProbe(
                autoConnectOnLaunch = settings.autoConnectOnLaunch,
                activeBaseUrl = origin,
                kind = profile?.kind,
                pairingUrl = settings.pairingUrl,
            )
        ) return@LaunchedEffect
        val probe = com.dsh.mobile.core.HealthProbe.check(origin)
        val verdict = com.dsh.mobile.core.LaunchHealth.verdict(origin, probe)
        if (verdict !is com.dsh.mobile.core.LaunchHealth.Verdict.Unreachable) {
            // 通了（含「重试这条」这次重探通了）：撤掉提示，并且**把页面接回来** ——
            // 用户点重试的期待是「让我用上」，不是「提示条消失了但还是停在设置页」。
            // 只在「之前确实判定过失败」时才补这一步，否则正常启动会被多走一次 applyConnection。
            if (launchStale != null) {
                com.dsh.mobile.core.RunLog.info("App", "launch probe recovered; loading the saved origin")
                launchStale = null
                openConnectionList = false
                // 等价于 applyConnection(settings.baseUrl)：把目标接回这条地址。
                // 这里不调用 applyConnection 是因为它是 L214 的局部函数，
                // 在 L157 的 LaunchedEffect 里属于前向引用（Kotlin 不允许）。
                val restored = com.dsh.mobile.core.LaunchRouting.resumeUrl(settings.baseUrl)
                configuredOrigin = settings.baseUrl
                pendingPairUrl = settings.pairingUrl
                shellLoadUrl = restored
            }
            return@LaunchedEffect
        }
        com.dsh.mobile.core.RunLog.warn(
            "App",
            "launch probe unreachable origin=${origin.take(60)} reason=${verdict.reason}",
        )
        // 这一步才是「不再硬加载」的实质：把已配置目标清掉，AnimatedContent 就不会
        // 进 RootScreen.Shell，WebView 也就不会去取 `<origin>/pair-app`。
        //
        // 为什么敢在这里动 configuredOrigin（而启动时不敢动别处）：此时页面**还没加载过**，
        // 没有草稿、没有 cookie 可丢；而且这是启动的一次性判定，不会在页面加载中途重建。
        // shellLoadUrl 一并清掉，避免 `key(configuredOrigin, shellLoadUrl)` 残留旧地址。
        launchStale = verdict
        configuredOrigin = ""
        shellLoadUrl = ""
        // 用户要的是「选择连接途径」，不是一张空白引导页：「设备与连接」里有当前连接、
        // 全部已保存方式和局域网自动发现，他自己挑一条就行 —— 不用再去禁用/删除那条坏地址。
        openConnectionList = true
    }

    var activeWebView by remember { mutableStateOf<WebView?>(null) }
    var handledWorkspaceRevision by remember {mutableStateOf(0)}
    LaunchedEffect(openWorkspaceRevision,activeWebView,shellLoading) {
        if(openWorkspaceRevision>handledWorkspaceRevision){
            showDiagnostics=false
            if(activeWebView!=null && !shellLoading){
                activeWebView?.evaluateJavascript("(function(){try{window.__DSH_MOBILE_MOTION__&&__DSH_MOBILE_MOTION__.closeLeft(true);window.__DSH_PHONE_RIGHT__&&__DSH_PHONE_RIGHT__.close(true)}catch(_){}return true})()",null)
                handledWorkspaceRevision=openWorkspaceRevision
            }
        }
    }
    var pendingReload by remember { mutableStateOf<WebView?>(null) }
    val reloadPage: () -> Unit = { com.dsh.mobile.ui.web.requestPageReload(activeWebView) { pendingReload = it } }
    val bridge = remember(configuredOrigin) { DshBridge(profileProvider = { settings.activeProfile }) { activeWebView } }
    LaunchedEffect(settingsRevision) { showDiagnostics = false; addingConnection = false; openConnectionList = false; pendingNavigation = null }
    fun applyConnection(input: String) {
        val target = com.dsh.mobile.core.LaunchRouting.apply(com.dsh.mobile.core.ConnectionTarget(settings.baseUrl, ""), input)
        // Keep the token here as well as in onPairingConsumed.
        //
        // A pairing link can arrive by two paths — the deep link / QR flow lands
        // here, while a page that pairs itself reports through onPairingConsumed —
        // and only the second was remembering the token. A fresh user who scanned
        // a Cloudflare code therefore reached the VPN route with nothing to replay
        // and got a bare /pair-app, which is a guaranteed 401: "登录 Tailscale 之后
        // 为什么还要我填链接". Remembering at both entry points is what makes the
        // VPN route pair silently afterwards.
        if (target.pairingUrl.isNotBlank()) settings.rememberPairingToken(target.pairingUrl)
        settings.baseUrl = target.baseUrl
        settings.pairingUrl = target.pairingUrl
        settings.onboarded = true
        pendingPairUrl = target.pairingUrl
        // 为什么 MESH 之外还要放 LAN / IPV6（2026-11-04 修）：
        // 原来这里只写 `kind == MESH`，于是 Wi-Fi 直连 / IPv6 这类"同一台 DSH、
        // 换了个入口地址"的路线拿不到 reusableMeshPairing，直接去加载裸
        // `/pair-app`。而那个 URL 在没有本 origin 的 device cookie 时，插件恒返回
        // 200 的「Pairing link invalid」页（routes.js 的 pairingFailurePage），
        // App 的 page-ready.js 靠 document.title 认出它并报 auth-lost，界面就卡在
        // 「需要配对」——这正是用户实测到的现象：自动发现扫到
        // <手机所在网段的网关地址>:18772（31ms 可达），点连接却永远要配对。
        // 实测证据：该 origin 的 /pair-app 返回 200 + title=Pairing link invalid，
        // 而 /api/pair/status 返回 requirePairingForLan=false —— 那个开关管不到
        // /pair-app，所以它从来不是这个问题的解。
        //
        // 令牌是 origin-blind 的（PairingService.accept 只查令牌表，不看 Host），
        // 所以同一次扫码得到的 token 可以为任意一个入口地址签出它自己的 cookie。
        //
        // 为什么**不**放宽到全部非 USB：公网路线（TUNNEL/RELAY/CUSTOM）多半已经有
        // 本 origin 的有效 cookie，此时裸 /pair-app 直接就是可用页面；改成强制走
        // accept URL 反而多压一次可能已过期的令牌，是退步。LAN/IPV6 是"同一台
        // DSH 的第二个地址"，天生没有 cookie，才需要这条重建路径。
        val reusableRoutePairing = if (target.pairingUrl.isBlank())
            com.dsh.mobile.core.ConnectionAddress.origin(settings.baseUrl)
                ?.takeIf {
                    settings.activeProfile?.kind in setOf(
                        com.dsh.mobile.core.ConnectionProfile.Kind.MESH,
                        com.dsh.mobile.core.ConnectionProfile.Kind.LAN,
                        com.dsh.mobile.core.ConnectionProfile.Kind.IPV6,
                    )
                }
                ?.let { settings.pairingUrlFor(it) }
        else null
        shellLoadUrl = target.pairingUrl.ifBlank { reusableRoutePairing ?: com.dsh.mobile.core.LaunchRouting.resumeUrl(settings.baseUrl) }
        pendingPairUrl = target.pairingUrl.ifBlank { reusableRoutePairing ?: "" }
        configuredOrigin = settings.baseUrl
        addingConnection = false
        openConnectionList = false
        showDiagnostics = false
        com.dsh.mobile.service.DshLiveService.current()?.clearTask()
    }
    fun requestConnection(url:String,label:String,kind:com.dsh.mobile.core.ConnectionProfile.Kind,provider:com.dsh.mobile.core.ConnectionProfile.MeshProvider?=null) {
        val target=com.dsh.mobile.core.ConnectionProfile.create(label,kind,url).copy(meshProvider=provider)
        // Remember the route *before* switching, without making it active.
        //
        // Saving only on a successful switch is what made the VPN route vanish
        // from the list: a first-time MESH connection is 401 until it is paired,
        // and entering through the VPN page (which pairs and then loads) never
        // went through this commit at all — so the profile never existed, and
        // "切回 VPN" had nothing to click. Routes are options, not trophies: a
        // failed attempt must still leave the option on the list so it can be
        // retried or picked later, which is exactly what finding the best network
        // path requires.
        runCatching { settings.rememberConnection(url,label,target.kind,provider) }
            .onFailure { com.dsh.mobile.core.RunLog.warn("Connection","route not remembered ${it.javaClass.simpleName}") }
        switchConnection(target,browserFirst=com.dsh.mobile.core.MeshPairing.token(url)!=null) {settings.saveConnection(url,label,target.kind,provider);applyConnection(url)}
    }
    fun navigate(action: () -> Unit) {
        val view = activeWebView
        if (view == null) action() else com.dsh.mobile.ui.web.hasUnsavedDraft(view) { occupied ->
            if (occupied) pendingNavigation = action else action()
        }
    }

    // Record the routing decision. "It is just black" reports are almost always
    // this: the app picked a screen the user did not expect, and without the
    // inputs to that choice there is nothing to debug.
    LaunchedEffect(configuredOrigin, pendingPairUrl, showDiagnostics) {
        com.dsh.mobile.core.RunLog.info(
            "App",
            "route origin=${configuredOrigin.take(60)} pair=${pendingPairUrl.isNotBlank()} diag=$showDiagnostics",
        )
    }

    // Diagnostics is an overlay, not an Activity, so the system back gesture would
    // otherwise finish MainActivity while the overlay is still composed — leaving
    // the user on a screen they cannot leave. Intercept here, above the shell, so
    // back always closes the overlay first.


    // Status-bar icon polarity has to follow the screen, not the system theme.
    //
    // The onboarding and splash surfaces are light (that is the app's shop window),
    // so on those the bar needs dark icons — otherwise the clock and battery are
    // white-on-white and effectively invisible. The paired shell hosts a dark WebView
    // page and needs the opposite. Driving this from the current screen rather than
    // from the theme is what keeps the system chrome readable in both cases.
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val showsLightSurfaces = if (appearance != "dsh") com.dsh.mobile.core.LocalAppearance.fromId(appearance).isLight else !systemDark && when {
        showDiagnostics || addingConnection || openConnectionList -> true
        configuredOrigin.isBlank() && pendingPairUrl.isBlank() -> true
        else -> shellLoading
    }

    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.SideEffect {
        val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
        androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = showsLightSurfaces
            isAppearanceLightNavigationBars = showsLightSurfaces
        }
    }

    DshTheme(appearance=com.dsh.mobile.core.LocalAppearance.fromId(appearance)) {
        BackNavigationHost(enabled=!showDiagnostics&&!openConnectionList&&!addingConnection&&!switching,
            predictive=predictiveBack,
            consumeBack={ result ->
                val web=activeWebView
                if(web==null||configuredOrigin.isBlank())result(false) else web.evaluateJavascript("""(function(){if(window.__DSH_SIDEBAR_NAV__&&__DSH_SIDEBAR_NAV__.dismissAccountMenu())return true;var f=document.querySelector('[data-dsh-mobile-frame]');if(!f)return false;var rail=f.querySelector('[data-dsh-mobile-right]');var p=window.__DSH_INTERACTION__;var r=p?p.rightClose(rail):rail&&rail.querySelector('button[data-sidebar-right-toggle],button[aria-label="收起右侧边栏"],button[aria-label="Close right sidebar"]');if(f.hasAttribute('data-dsh-mobile-right-open')&&r){r.click();return true;}var s=f.querySelector('[data-dsh-mobile-sidebar]');if(s&&!f.hasAttribute('data-sidebar-collapsed')&&!f.hasAttribute('data-dsh-left-closed')){if(window.__DSH_SIDEBAR_NAV__&&__DSH_SIDEBAR_NAV__.closeDrawer())return true;var b=p?p.leftToggle(s):s.querySelector('button[aria-label="收起侧边栏"],button[aria-label="Close sidebar"]');if(b){b.click();return true;}}return false;})()""") { result(it=="true") }
            }) {
    BackHandler(enabled = showDiagnostics || addingConnection || openConnectionList) {
        if (addingConnection) { addingConnection = false; return@BackHandler }
        // 「设备与连接」层的返回先交还给它自己：SettingsScreen 内部有页签栈，
        // 从「连接」页按返回应该回到「设置」首页，而不是直接把这层关掉。
        // 只有它已经在设置首页时，onBack 才会回调到这里，此时关整层。
        openConnectionList = false
        showDiagnostics = false
        configuredOrigin = settings.baseUrl
        pendingPairUrl = settings.pairingUrl
    }
        // Cross-fade between the three top-level states. Without it a state change
        // is a hard cut that reads as a flash, which is most of what "no animation"
        // meant in practice — the individual widgets were animated, the *screens*
        // were not.
        AnimatedContent(
            targetState = when {
                configuredOrigin.isNotBlank() || pendingPairUrl.isNotBlank() -> RootScreen.Shell
                else -> RootScreen.Onboarding
            },
            transitionSpec = {
                (fadeIn(Motion.enter()) + scaleIn(Motion.enter(), initialScale = 0.98f))
                    .togetherWith(fadeOut(Motion.exit()))
            },
            label = "root",
        ) { screen ->
            Box(Modifier.fillMaxSize().background(top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.background)) {
                when (screen) {
                    RootScreen.Shell -> key(configuredOrigin, shellLoadUrl) { ShellScreen(
                        settings = settings,
                        bridge = bridge,
                        loadUrl = shellLoadUrl,
                        appearance = appearance,
                        isPairingLink = pendingPairUrl.isNotBlank() && configuredOrigin.isBlank(),
                        onWebViewCreated = { activeWebView = it; onWebViewCreated(it) },
                        onOpenDiagnostics = {
                            activeWebView?.let { wv ->
                                (wv.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(wv.windowToken, 0)
                                wv.clearFocus()
                            }
                            showDiagnostics = true
                        },
                        onReloadPage = reloadPage,
                        onLoadingChanged = { shellLoading = it },
                        onTaskBusyChanged = {taskBusy=it},
                        // 通道建议从外部传进来（由 ChannelFailoverHost 提出），
                        // ShellScreen 只负责在状态条里渲染，不自己探测 ——
                        // 否则两条探测循环会各提一次建议。
                        externalChannelSuggestion = channelSuggestion,
                        onChannelSuggestionConsumed = { channelSuggestion = null },
                        // 状态条里接受通道建议时走这里：与手动切换同一条路径，
                        // 也就是会经过 navigate{} 的草稿确认，不会在用户打字时刷掉页面。
                        onSwitchChannel = { target ->
                            navigate {
                                switchConnection(target) {
                                    settings.activateProfile(target.id)
                                    applyConnection(settings.baseUrl)
                                }
                            }
                        },
                        onPairingConsumed = { origin ->
                            val canonical = com.dsh.mobile.core.ConnectionAddress.origin(origin)
                            if (canonical == settings.activeProfile?.baseUrl && canonical == configuredOrigin) {
                            android.webkit.CookieManager.getInstance().flush()
                            // Keep the token before the one-shot link is cleared:
                            // the plugin's device cookie is host-scoped, so this
                            // success does nothing for another origin of the same
                            // DSH (the VPN route most of all). The token is
                            // origin-blind, so remembering it is what lets the
                            // tunnel route pair without asking the user to scan a
                            // second code. Never logged.
                            settings.rememberPairingToken(settings.pairingUrl)
                            // The handshake finished: forget the one-shot link and
                            // keep the page where it is.
                            settings.consumePairingUrl()
                            pendingPairUrl = ""
                            configuredOrigin = settings.baseUrl
                            } else com.dsh.mobile.core.RunLog.info("Connection", "ignored pairing callback from previous route")
                        },
                        onUnpair = {
                            // 「配对 / 换入口」回到「选择连接途径」，不是落在一个表单里。
                            //
                            // 用户 2026-11-04 反馈：配对异常后点这里，进的是「内置通道」
                            // 页，而他要的是「回去到选择连接途径的页面」。之前的两个分支
                            // 都有这个问题，只是方向不同：
                            //   - 非 MESH → addingConnection = true，打开「手动输入地址」
                            //     表单。用户此刻并不想新建一条连接，他只想换条能走的路；
                            //     一个空地址输入框给不出任何选项。
                            //   - MESH → showVpnRepair = true，打开「内置通道」页。那条路
                            //     只修得好 MESH，公网用户被带进一个跟自己无关的通道页。
                            //
                            // 「设置 → 设备与连接」才是唯一同时满足两者的页面：当前连接、
                            // 按类型分组的全部已保存方式、局域网自动发现，以及通往内置
                            // 通道和重新扫码的入口都在那里，用户能自己挑一条。
                            showDiagnostics = false
                            addingConnection = false
                            openConnectionList = true
                        },
                    ) }

                    RootScreen.Onboarding -> OnboardingScreen(
                        settings = settings,
                        onPaired = { applyConnection(it) },
                        onConnectionRequested = {url,label,kind,provider->requestConnection(url,label,kind,provider)},
                    )
                }
            }
        }
        androidx.compose.animation.AnimatedVisibility(
            // 「配对 / 换入口」复用设置层显示「设备与连接」，而不是再开一个
            // 只有地址输入框的界面。两者共用同一个 SettingsScreen 实例，
            // 避免出现两套互相不知道对方存在的设置页面。
            visible = showDiagnostics || openConnectionList,
            enter = fadeIn(Motion.enter()) + androidx.compose.animation.slideInVertically(Motion.enter()) { it / 12 },
            exit = fadeOut(Motion.exit()),
        ) {
            BackGestureSurface {
            SettingsScreen(settings = settings, webView = activeWebView, startAtConnection = openConnectionList,
                // 启动探测发现「上次那条连不上」时，把结论交给设置页顶部渲染。
                // 只传数据、不在 DshMobileApp 里另开浮层：用户在「设备与连接」页上
                // 既要看提示，也要立刻能换一条，两件事必须在同一屏。
                launchStaleHint = launchStale?.let { verdict ->
                    "上次的连接现在连不上：${verdict.origin}。${verdict.reason}。"
                },
                onRetryLaunch = { launchProbeRetry++ },
                onClearLaunchHint = { launchStale = null },
                // 局域网自动发现的「连接」按钮走这里。
                //
                // 这里曾经把 kind 写死成 MESH，于是 `http://<手机所在网段的网关地址>:18772`
                // 这种纯局域网地址被存成「Tailscale / WireGuard」连接 —— 实测看到
                // 的后果就摆在「设备与连接」页上：
                //   当前连接  Tailscale / WireGuard
                //              Tailscale / WireGuard · http://<手机所在网段的网关地址>:18772
                //              内置通道：未开启
                // 三行互相矛盾：它根本不是组网地址，却挂着组网类型，还汇报组网通道
                // 未开启。更实际的问题是 prepareNavigation 会按 MESH 去起 VPN 隧道，
                // 明明局域网直连不需要任何隧道，白等一次 30 秒超时。
                // 类型应该由地址本身决定（ConnectionAddress.infer 认私有 IPv4 →
                // LAN，全局 IPv6 → IPV6，.ts.net → MESH），这也是连接列表分组的依据。
                onConnectAddress = { url, label ->
                    val kind = com.dsh.mobile.core.ConnectionAddress.infer(url)
                        ?: com.dsh.mobile.core.ConnectionProfile.Kind.LAN
                    navigate { requestConnection(url, label, kind) }
                }, onConnectMesh = { url, label, provider -> navigate { requestConnection(url,label,com.dsh.mobile.core.ConnectionProfile.Kind.MESH,provider) } }, onAppearanceChanged = { selected -> settings.localAppearance = selected; appearance = selected.id }, onAddConnection = { navigate { showDiagnostics = false; addingConnection = true } }, onSwitchConnection = { id ->
                navigate {
                    settings.profiles.firstOrNull {it.id==id}?.let {target->switchConnection(target) { if (settings.activateProfile(id)) applyConnection(settings.baseUrl) }}
                }
            }, onReloadPage = {
                showDiagnostics = false
                openConnectionList = false
                reloadPage()
            }, onDeleteConnection = { id ->
                // 删除连接的副作用要跟着当前连接走：
                // 删的不是当前那条 → 列表刷新即可，页面不用动；
                // 删的正是当前那条 → SettingsStore 已把 activeProfile 回退到
                // 最近用过的另一条（没有就清空），这里必须重新应用连接，
                // 否则页面还停在已删地址上，看起来像「删了没反应」。
                val wasActive = settings.activeProfile?.id == id
                if (settings.deleteProfile(id) && wasActive) {
                    configuredOrigin = settings.baseUrl
                    pendingPairUrl = settings.pairingUrl
                    if (settings.baseUrl.isNotBlank()) applyConnection(settings.baseUrl) else reloadPage()
                }
            }, onBack = {
                showDiagnostics = false
                openConnectionList = false
                configuredOrigin = settings.baseUrl
                pendingPairUrl = settings.pairingUrl
            })
            }
        }
        if (addingConnection) BackGestureSurface { OnboardingScreen(settings, onPaired = { applyConnection(it) }, onCancel = { addingConnection = false },onConnectionRequested={url,label,kind,provider->requestConnection(url,label,kind,provider)}) }
        if(switching) androidx.compose.ui.window.Dialog(onDismissRequest={switchJob?.cancel()}) {
            top.yukonga.miuix.kmp.basic.Card {
                androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().padding(24.dp),verticalArrangement=androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                    top.yukonga.miuix.kmp.basic.Text("正在切换连接 · ${switchSeconds}秒")
                    top.yukonga.miuix.kmp.basic.Text(switchStage)
                    top.yukonga.miuix.kmp.basic.Text("通道、电脑入口 → 配对 → 页面资源 → 实时连接")
                    top.yukonga.miuix.kmp.basic.Button(onClick={switchJob?.cancel()}) {top.yukonga.miuix.kmp.basic.Text("取消切换")}
                }
            }
        }
        ConfirmationDialog(show=switchError!=null,title="连接未切换",message=switchError.orEmpty(),cancelText="关闭",confirmText="知道了",onDismiss={switchError=null},onConfirm={switchError=null})
        UpdateNoticeHost(readyForNotice=!showDiagnostics&&!openConnectionList&&!addingConnection&&!switching&&!taskBusy&&pendingPairUrl.isBlank()&&configuredOrigin.isNotBlank()&&!shellLoading)
        // 通道自动选路的宿主：巡检当前通道，挂了就按优先级切到第一条能用的。
        //
        // 为什么切换要包一层 navigate{}：换通道会重载页面，用户正在输入的内容会没。
        // navigate{} 里已经有 hasUnsavedDraft 检查 —— 有草稿就先弹确认，没草稿才直接切，
        // 这样「自动切换」永远不会成为丢用户输入的原因。
        //
        // 为什么放在 DshTheme{} 内部：优化建议条用的是 MiuixTheme 的配色与字体，
        // 放到主题外面渲染会拿不到色板（MiuixTheme 在 DshTheme 里才被提供）。
        com.dsh.mobile.ui.screen.ChannelFailoverHost(
            settings = settings,
            enabled = settings.channelAutoSwitch,
            canProbe = { !switching && !taskBusy && !showDiagnostics && !openConnectionList && !addingConnection },
            onSwitch = { target ->
                navigate {
                    switchConnection(target) {
                        settings.activateProfile(target.id)
                        applyConnection(settings.baseUrl)
                    }
                }
            },
            // 建议交给 ShellScreen 的状态条渲染（用户要求「整合到状态栏上面」），
            // 本宿主因此不再弹 Popup。
            onSuggest = { target, reason -> channelSuggestion = target to reason },
        )
        com.dsh.mobile.ui.ConfirmationDialog(
            show = pendingNavigation != null, title = "切换连接？",
            message = "当前页面有未发送内容，切换连接后可能丢失，请先保存。", confirmText = "继续切换", cancelText = "保留草稿",
            onDismiss = { pendingNavigation = null },
            onConfirm = { val action = pendingNavigation; pendingNavigation = null; action?.invoke() },
        )
        com.dsh.mobile.ui.web.ReloadConfirmationDialog(
            show = pendingReload != null,
            onDismiss = { pendingReload = null },
            onConfirm = {
                val target = pendingReload
                pendingReload = null
                com.dsh.mobile.core.RunLog.info("Cache", "page reload confirmed")
                target?.reload()
            },
        )
        }
    }
}

/** The three top-level screens, named so [AnimatedContent] can transition on them. */
private enum class RootScreen { Onboarding, Shell }

/** Scheme + host + port of [url], or the input unchanged when it has no path. */
private fun originOf(url: String): String {
    val schemeEnd = url.indexOf("://")
    if (schemeEnd <= 0) return url.trimEnd('/')
    val afterScheme = schemeEnd + 3
    val pathStart = url.indexOf('/', afterScheme)
    val origin = if (pathStart < 0) url else url.substring(0, pathStart)
    return origin.trimEnd('/')
}

/**
 * Whether [url] points at the device itself.
 *
 * A phone cannot reach the paired desktop through loopback, so such an address is
 * always stale debugging state. Kept as a pure function so the rule is testable.
 */
internal fun isLoopbackOrigin(url: String): Boolean {
    if (com.dsh.mobile.BuildConfig.USB_DEVELOPMENT && com.dsh.mobile.core.DevelopmentTransport.isUsbOrigin(url)) return false
    // 用 URI 取 host：IPv6 字面量返回带方括号的 [::1]，交给 ConnectionAddress.isLoopback 剥括号判定。
    // 旧实现用 substringBefore(':') 手剥，对 [::1] 会得到 "["，导致回环 IPv6 永远拦不住。
    val host = runCatching { java.net.URI(url).host }.getOrNull() ?: run {
        val raw = url.substringAfter("://", url).substringBefore('/')
        if (raw.startsWith("[")) raw.substringBefore(']') + "]" else raw.substringBefore(':')
    }.lowercase()
    return com.dsh.mobile.core.ConnectionAddress.isLoopback(host)
}
