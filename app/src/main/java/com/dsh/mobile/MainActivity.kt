package com.dsh.mobile

import android.annotation.SuppressLint
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.dsh.mobile.core.ConnectionTarget
import com.dsh.mobile.core.LaunchRouting
import com.dsh.mobile.core.RunLog
import com.dsh.mobile.core.SettingsStore
import com.dsh.mobile.service.DshLiveService
import com.dsh.mobile.ui.DshMobileApp

/**
 * The single activity. It hosts a Compose shell in which the paired DSH web UI
 * lives as a full-bleed WebView, plus the native chrome the shell draws around
 * it (island status, navigation, settings).
 *
 * Keeping one activity (and `singleTask`) is deliberate: the island's action
 * buttons deep-link back into a running task, and a second activity instance
 * would give us two WebViews whose sockets both report events.
 */
class MainActivity : ComponentActivity() {

    private lateinit var settings: SettingsStore

    /** Hoisted so the Compose shell and the activity can both reach it. */
    private var webViewRef by mutableStateOf<WebView?>(null)
    private var keyboardInsetsBridge: com.dsh.mobile.ui.web.KeyboardInsetsBridge? = null

    /**
     * Bumped whenever something outside Compose changes [SettingsStore].
     *
     * Compose cannot observe SharedPreferences through a plain property read, so
     * a deep link arriving in [onNewIntent] would otherwise be saved but never
     * reflected: the UI would sit on the onboarding screen while the settings
     * already held a valid base URL. The shell keys its state on this counter.
     */
    private var settingsRevision by mutableStateOf(0)
    private var workspaceRevision by mutableStateOf(0)

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Log.i(TAG, "POST_NOTIFICATIONS granted=$granted")
            com.dsh.mobile.core.RunLog.info("Permission", "notifications granted=$granted")
            if (granted) {
                // The tracker can only show its island/notification once allowed.
                maybeStartTracker(startService = true)
            }
        }

    override fun onResume() {
        super.onResume()
        DshLiveService.current()?.refreshCompanion()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Swap the launch theme for the normal one before anything is drawn: the
        // launch theme only exists to stop the platform from stamping its own
        // splash icon over the in-app boot screen. Leaving it applied would keep
        // the window in splash mode for the whole session.
        setTheme(R.style.Theme_DshMobile)

        super.onCreate(savedInstanceState)
        com.dsh.mobile.vpn.VpnController.onTaskOpened(this)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        keyboardInsetsBridge = com.dsh.mobile.ui.web.KeyboardInsetsBridge(window) { webViewRef }

        settings = SettingsStore(this)
        if(intent.action=="com.dsh.mobile.OPEN_WORKSPACE")workspaceRevision++
        handleStaticPreviewIntent(intent)
        RunLog.info("Activity", "created instance=${System.identityHashCode(this)} restored=${savedInstanceState != null}")
        handleDeepLink(intent)
        handleDebugIslandIntent(intent)
        ensureNotificationPermission()

        setContent {
            DshMobileApp(
                settings = settings,
                settingsRevision = settingsRevision,
                openWorkspaceRevision = workspaceRevision,
                onWebViewCreated = { webViewRef = it },
                onOpenSettings = { /* handled inside the Compose shell */ },
            )
        }
        acceptOverlayDraft(intent)
    }

    private fun acceptOverlayDraft(intent:Intent) {
        if(!intent.getBooleanExtra("companion_draft_pending",false))return
        val owner=DshLiveService.current() ?: return
        val text=owner.pendingOverlayDraft ?: return
        owner.pendingOverlayDraft=null
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            val route=DshLiveService.current()?.companionBridge
            if(route==null)android.widget.Toast.makeText(this,"对话尚未就绪，挂件草稿仍保留",android.widget.Toast.LENGTH_LONG).show()
            else route.stageQuickDraft(text){result->
                RunLog.info("OverlayReply","main editor staging result=$result")
                android.widget.Toast.makeText(this,if(result=="staged")"已填入当前对话，请核对后发送" else "未覆盖主对话草稿，挂件草稿仍保留",android.widget.Toast.LENGTH_LONG).show()
            }
        },400)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleStaticPreviewIntent(intent)
        setIntent(intent)
        if(intent.action == "com.dsh.mobile.OPEN_WORKSPACE") workspaceRevision++
        val targetChanged = handleDeepLink(intent)
        handleDebugIslandIntent(intent)
        // A media/notification tap only resumes this Activity. Re-keying Compose
        // here would replace a consumed pairing URL with the origin and reload.
        if (targetChanged) settingsRevision++
        acceptOverlayDraft(intent)
        RunLog.info("Activity", "new intent targetChanged=$targetChanged page=${webViewRef?.let { System.identityHashCode(it) }}")
    }

    override fun onStart() {
        super.onStart()
        com.dsh.mobile.vpn.VpnController.onTaskOpened(this)
        com.dsh.mobile.core.NetworkConnectivity.visible.value = true
        com.dsh.mobile.vpn.VpnController.restore(this)
        if (settings.baseUrl.isNotBlank()) runCatching { DshLiveService.start(this) }
    }

    override fun onStop() {
        RunLog.info("Activity", "background page=${webViewRef?.let { System.identityHashCode(it) }}")
        com.dsh.mobile.core.NetworkConnectivity.visible.value = false
        super.onStop()
    }

    override fun onDestroy() {
        RunLog.info("Activity", "destroyed finishing=$isFinishing changingConfig=$isChangingConfigurations")
        // The WebView lives in Compose; detach it so it does not leak the activity.
        webViewRef?.let { wv ->
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.destroy()
        }
        webViewRef = null
        super.onDestroy()
    }

    /**
     * Android 13+ requires a runtime grant for POST_NOTIFICATIONS. Without it the
     * app's notification importance is forced to NONE and every `notify()` —
     * including the island's — is silently dropped, which looks exactly like a
     * hung feature. Ask once, on first launch.
     */
    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Log.i(TAG, "requesting POST_NOTIFICATIONS")
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** Start the live tracker once we are paired and allowed to post. */
    private fun maybeStartTracker(startService: Boolean) {
        if (settings.baseUrl.isBlank()) return
        if (!settings.backgroundLive) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }
        if (startService) DshLiveService.start(this)
    }

    /**
     * Accept `dshmobile://open?url=<paired origin>` so the app can be opened
     * straight from a pairing link instead of the user retyping the address.
     */
    /**
     * Accept `dshmobile://open?url=<link>` so the app can be opened straight from a
     * pairing link instead of the user retyping it.
     *
     * The distinction between a pairing link and a plain origin is load-bearing:
     * a pairing link carries a token that expires in minutes, so it must be stored
     * as a *pending handshake* and cleared after use. Storing it as the address is
     * what made the app relaunch onto an expired token and show a black screen.
     */
    private fun handleDeepLink(intent: Intent?): Boolean {
        val data = intent?.data ?: return false
        if (data.scheme != "dshmobile") return false
        val url = data.getQueryParameter("url") ?: return false
        val before = ConnectionTarget(settings.baseUrl, settings.pairingUrl)
        val target = LaunchRouting.apply(before, url)
        // The URL has been handed to SettingsStore. Do not replay a one-shot
        // pairing grant if Android later recreates this Activity with its Intent.
        intent.data = null
        if (target == before) return false
        if (com.dsh.mobile.core.ConnectionAddress.infer(target.baseUrl) == null) return false
        if (!com.dsh.mobile.BuildConfig.USB_DEVELOPMENT && com.dsh.mobile.core.DevelopmentTransport.isUsbOrigin(target.baseUrl)) return false
        settings.baseUrl = target.baseUrl
        settings.pairingUrl = target.pairingUrl
        settings.onboarded = true
        Log.i(TAG, "deep link accepted; page target changed")
        return true
    }

    private fun handleStaticPreviewIntent(intent:Intent?) {
        if(com.dsh.mobile.BuildConfig.USB_DEVELOPMENT && intent?.hasExtra("debug_usb_static")==true) {
            settings.usbStaticPreview=intent.getBooleanExtra("debug_usb_static",false)
            RunLog.info("Cache","development USB static preview=${settings.usbStaticPreview}; business route unchanged")
            intent.removeExtra("debug_usb_static")
        }
    }

    /**
     * Post or animate a sample island when started with `--es debug_island <phase>`.
     *
     * This exists because a broadcast receiver is unreliable from adb on HyperOS:
     * the system accepts an exported broadcast, logs "Enqueued", and never runs the
     * receiver. An activity start intent is delivered reliably, so on-device
     * verification drives the island through here instead.
     *
     * `demo` walks the progress bar so the "it moves" behaviour is observable;
     * any other value posts a single static island (the shape that used to look
     * stuck).
     *
     * The extra is harmless in production: no launcher intent carries it.
     */
    private fun handleDebugIslandIntent(intent: Intent?) {
        val phase = intent?.getStringExtra(EXTRA_DEBUG_ISLAND) ?: return
        Log.i(TAG, "debug island requested: $phase")
        // Let the notification permission request settle first on a cold start.
        window.decorView.postDelayed({
            if (phase == "demo") {
                com.dsh.mobile.island.IslandDemo.start(
                    context = this,
                    scope = lifecycleScope,
                ) { verdict -> Log.i(TAG, "demo verdict: $verdict") }
            } else {
                val verdict = com.dsh.mobile.island.postDebugIsland(this, phase)
                Log.i(TAG, "debug island result: $verdict")
            }
        }, 1_200)
    }

    private companion object {
        const val TAG = "DshMainActivity"
        const val EXTRA_DEBUG_ISLAND = "debug_island"
    }
}
