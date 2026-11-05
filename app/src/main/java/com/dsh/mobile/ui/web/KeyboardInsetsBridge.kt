package com.dsh.mobile.ui.web

import android.view.Window
import android.webkit.WebView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsAnimationCompat
import com.dsh.mobile.core.RunLog

/** Keep the WebView viewport stable. Send one compositor transition per IME animation. */
class KeyboardInsetsBridge(window: Window, private val webView: () -> WebView?) {
    private var target = 0
    private var animating = false
    init {
        val decor = window.decorView
        ViewCompat.setOnApplyWindowInsetsListener(decor) { _, insets ->
            target = (insets.getInsets(WindowInsetsCompat.Type.ime()).bottom -
                insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom).coerceAtLeast(0)
            if (!animating) send(0)
            insets
        }
        ViewCompat.setWindowInsetsAnimationCallback(decor, object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
            override fun onPrepare(animation: WindowInsetsAnimationCompat) {
                if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) animating = true
            }
            override fun onStart(animation: WindowInsetsAnimationCompat, bounds: WindowInsetsAnimationCompat.BoundsCompat): WindowInsetsAnimationCompat.BoundsCompat {
                if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) {
                    send(animation.durationMillis.coerceIn(0, 500))
                    RunLog.info("Keyboard", "transition targetPx=$target durationMs=${animation.durationMillis}; viewport retained")
                }
                return bounds
            }
            override fun onProgress(insets: WindowInsetsCompat, runningAnimations: MutableList<WindowInsetsAnimationCompat>): WindowInsetsCompat = insets
            override fun onEnd(animation: WindowInsetsAnimationCompat) {
                if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) { animating = false; send(0) }
            }
        })
    }
    private fun send(duration: Long) {
        webView()?.evaluateJavascript("window.__DSH_MOBILE_IME__&&window.__DSH_MOBILE_IME__.applyPixels($target,$duration)", null)
    }
}
