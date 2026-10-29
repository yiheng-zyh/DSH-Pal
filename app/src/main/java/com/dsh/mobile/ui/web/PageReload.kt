package com.dsh.mobile.ui.web

import android.webkit.WebView
import androidx.compose.runtime.Composable
import com.dsh.mobile.core.RunLog

/** Reload the current page without clearing pairing or static resources. */
fun reloadPairedPage(view:WebView) {
    val current=view.url.orEmpty()
    val path=runCatching{java.net.URI(current).path}.getOrNull()
    if(path in setOf("","/","/pair-app","/pair-accept"))view.loadUrl(com.dsh.mobile.core.LaunchRouting.resumeUrl(current))
    else view.reload()
}

fun hasUnsavedDraft(view: WebView, result: (Boolean) -> Unit) {
    view.evaluateJavascript("""
        (function(){return Array.from(document.querySelectorAll('textarea,[contenteditable="true"],[role="textbox"]'))
          .some(function(el){var r=el.getBoundingClientRect();return r.width>0&&r.height>0&&String(el.value||el.innerText||'').trim().length>0;});})()
    """.trimIndent()) { result(it != "false") }
}

fun requestPageReload(view: WebView?, onDraft: (WebView) -> Unit) {
    if (view == null) return
    hasUnsavedDraft(view) { occupied ->
        fun reload() {
            RunLog.info("Cache", "page reload requested")
            reloadPairedPage(view)
        }
        if (!occupied) reload()
        else {
            RunLog.info("Cache", "draft confirmation requested")
            onDraft(view)
        }
    }
}

@Composable
fun ReloadConfirmationDialog(show: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    com.dsh.mobile.ui.ConfirmationDialog(
        show = show,
        title = "重新加载界面？",
        message = "输入框中还有未发送的内容，刷新后可能丢失。建议先复制保存。",
        cancelText = "保留草稿", confirmText = "仍然刷新",
        onDismiss = onDismiss, onConfirm = onConfirm,
    )
}
