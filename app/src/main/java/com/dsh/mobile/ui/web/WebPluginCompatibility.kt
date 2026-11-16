package com.dsh.mobile.ui.web

import android.webkit.WebResourceRequest
import android.webkit.WebView
import com.dsh.mobile.core.WebFilePolicy
import com.dsh.mobile.core.WebPluginBundles
import java.util.WeakHashMap

/** Records which versioned plugin batches failed in this WebView. */
internal class WebPluginCompatibility(private val origin: String) {
    private val failedPackages = linkedSetOf<String>()

    @Synchronized fun pageStarted() { failedPackages.clear() }

    @Synchronized private fun failures(): Set<String> = failedPackages.toSet()

    @Synchronized fun resourceFailed(request: WebResourceRequest) {
        val url = request.url.toString()
        if (request.isForMainFrame || request.method != "GET" || !WebFilePolicy.sameOrigin(origin, url)) return
        val modules = WebPluginBundles.parse(url) ?: return
        for (module in modules) if (failedPackages.size < 256) failedPackages.add(module.packageName)
    }

    companion object {
        fun presentation(script: String): String = """
            ;(function(){
              if(typeof DshFiles!=='undefined' && DshFiles.originalDshPage())return;
              $script
            })();
        """.trimIndent()

        private val views = WeakHashMap<WebView, WebPluginCompatibility>()
        fun attach(view: WebView, tracker: WebPluginCompatibility) { views[view] = tracker }
        fun detach(view: WebView) { views.remove(view) }
        fun failuresFor(view: WebView?): Set<String> = views[view]?.failures().orEmpty()
    }
}
