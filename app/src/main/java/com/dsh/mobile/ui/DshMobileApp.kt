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
    var showDiagnostics by remember { mutableStateOf(false) }
    var addingConnection by remember { mutableStateOf(false) }
    // Set when a MESH route needs its own pairing step; the VPN screen owns the
    // token-replay path that repairs such a route.
    var showVpnRepair by remember { mutableStateOf(false) }
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
    fun switchConnection(target:com.dsh.mobile.core.ConnectionProfile, commit:()->Unit) {
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
                    commit=commit,
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
    LaunchedEffect(settingsRevision) { showDiagnostics = false; addingConnection = false; pendingNavigation = null }
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
        // A stored MESH origin with no pending link is not loadable by itself.
        //
        // /pair-app is the already-paired landing, so opening it without a device
        // cookie for *that host* is a guaranteed 401 — and switching routes goes
        // straight to applyConnection without passing through the VPN screen, so
        // the auto-pairing code there never ran. Rebuilding the accept URL here is
        // what makes a route switch work: the remembered token is origin-blind, so
        // the new host mints its own cookie on the way in.
        val reusableMeshPairing = if (target.pairingUrl.isBlank())
            com.dsh.mobile.core.ConnectionAddress.origin(settings.baseUrl)
                ?.takeIf { settings.activeProfile?.kind == com.dsh.mobile.core.ConnectionProfile.Kind.MESH }
                ?.let { settings.pairingUrlFor(it) }
        else null
        shellLoadUrl = target.pairingUrl.ifBlank { reusableMeshPairing ?: com.dsh.mobile.core.LaunchRouting.resumeUrl(settings.baseUrl) }
        pendingPairUrl = target.pairingUrl.ifBlank { reusableMeshPairing ?: "" }
        configuredOrigin = settings.baseUrl
        addingConnection = false
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
        switchConnection(target) {settings.saveConnection(url,label,target.kind,provider);applyConnection(url)}
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
    val showsLightSurfaces = appearance=="dsh" && !systemDark && when {
        showDiagnostics || addingConnection -> true
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
        BackNavigationHost(enabled=!showDiagnostics&&!addingConnection&&!switching,
            predictive=predictiveBack,
            consumeBack={ result ->
                val web=activeWebView
                if(web==null||configuredOrigin.isBlank())result(false) else web.evaluateJavascript("""(function(){if(window.__DSH_SIDEBAR_NAV__&&__DSH_SIDEBAR_NAV__.dismissAccountMenu())return true;var f=document.querySelector('[data-dsh-mobile-frame]');if(!f)return false;var rail=f.querySelector('[data-dsh-mobile-right]');var p=window.__DSH_INTERACTION__;var r=p?p.rightClose(rail):rail&&rail.querySelector('button[data-sidebar-right-toggle],button[aria-label="收起右侧边栏"],button[aria-label="Close right sidebar"]');if(f.hasAttribute('data-dsh-mobile-right-open')&&r){r.click();return true;}var s=f.querySelector('[data-dsh-mobile-sidebar]');if(s&&!f.hasAttribute('data-sidebar-collapsed')&&!f.hasAttribute('data-dsh-left-closed')){if(window.__DSH_SIDEBAR_NAV__&&__DSH_SIDEBAR_NAV__.closeDrawer())return true;var b=p?p.leftToggle(s):s.querySelector('button[aria-label="收起侧边栏"],button[aria-label="Close sidebar"]');if(b){b.click();return true;}}return false;})()""") { result(it=="true") }
            }) {
    BackHandler(enabled = showDiagnostics || addingConnection) {
        if (addingConnection) { addingConnection = false; return@BackHandler }
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
                            // Repair an expired route without deleting other saved connections.
                            showDiagnostics = false
                            // The MESH route cannot be repaired from the generic add
                            // screen: its pairing link is minted for the public base
                            // and has to be replayed against the tunnel origin, which
                            // only the VPN screen knows how to do. Sending a VPN user
                            // there left them with the same dead page (measured on the
                            // tablet: VPN up, /pair-app 401, "重新配对" did nothing).
                            if (settings.activeProfile?.kind == com.dsh.mobile.core.ConnectionProfile.Kind.MESH) {
                                addingConnection = false
                                showVpnRepair = true
                            } else addingConnection = true
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
            visible = showDiagnostics,
            enter = fadeIn(Motion.enter()) + androidx.compose.animation.slideInVertically(Motion.enter()) { it / 12 },
            exit = fadeOut(Motion.exit()),
        ) {
            BackGestureSurface {
            SettingsScreen(settings = settings, webView = activeWebView, onConnectAddress = { url, label -> navigate { requestConnection(url,label,com.dsh.mobile.core.ConnectionProfile.Kind.MESH) } }, onConnectMesh = { url, label, provider -> navigate { requestConnection(url,label,com.dsh.mobile.core.ConnectionProfile.Kind.MESH,provider) } }, onAppearanceChanged = { selected -> settings.localAppearance = selected; appearance = selected.id }, onAddConnection = { navigate { showDiagnostics = false; addingConnection = true } }, onSwitchConnection = { id ->
                navigate {
                    settings.profiles.firstOrNull {it.id==id}?.let {target->switchConnection(target) { if (settings.activateProfile(id)) applyConnection(settings.baseUrl) }}
                }
            }, onReloadPage = {
                showDiagnostics = false
                reloadPage()
            }, onBack = {
                showDiagnostics = false
                configuredOrigin = settings.baseUrl
                pendingPairUrl = settings.pairingUrl
            })
            }
        }
        if (addingConnection) BackGestureSurface { OnboardingScreen(settings, onPaired = { applyConnection(it) }, onCancel = { addingConnection = false },onConnectionRequested={url,label,kind,provider->requestConnection(url,label,kind,provider)}) }
        // The VPN route's own repair screen: it accepts a pairing link minted for
        // any origin and replays the token against the saved tunnel entry, which
        // is the only way that origin can acquire a device cookie.
        if (showVpnRepair) BackGestureSurface { com.dsh.mobile.ui.screen.VpnSettingsScreen(
            onBack = { showVpnRepair = false },
            onSelectedServer = {},
            settings = settings,
            // Pre-fill the saved tunnel entry so the user only has to paste the
            // desktop pairing link; the token gets replayed onto this origin.
            initialAddress = settings.activeProfile?.takeIf {
                it.kind == com.dsh.mobile.core.ConnectionProfile.Kind.MESH
            }?.baseUrl.orEmpty(),
            onConnectRequested = { url, name, provider ->
                showVpnRepair = false
                requestConnection(url, name, com.dsh.mobile.core.ConnectionProfile.Kind.MESH,
                    com.dsh.mobile.core.ConnectionProfile.MeshProvider.valueOf(provider.name))
            },
        ) }
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
    val host = url.substringAfter("://", url).substringBefore('/').substringBefore(':').lowercase()
    return host == "127.0.0.1" || host == "localhost" || host.endsWith(".localhost") || host == "::1" || host == "[::1]"
}
