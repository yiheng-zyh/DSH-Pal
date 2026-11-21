package com.dsh.mobile.ui.web

import android.content.Context
import android.graphics.Rect
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.WebView
import android.widget.Toast

/** Keep Android selection handles and expose the mounted DSH quote action. */
class QuoteWebView(context: Context) : WebView(context) {
    override fun startActionMode(callback: ActionMode.Callback): ActionMode? =
        super.startActionMode(wrap(callback))

    override fun startActionMode(callback: ActionMode.Callback, type: Int): ActionMode? =
        super.startActionMode(wrap(callback), type)

    private fun wrap(callback: ActionMode.Callback): ActionMode.Callback =
        if (callback is QuoteCallback) callback else QuoteCallback(callback)

    private inner class QuoteCallback(private val original: ActionMode.Callback) : ActionMode.Callback2() {
        private val quoteId = View.generateViewId()
        private var ended = false
        private var canQuote = false

        private fun item(menu: Menu): MenuItem = menu.findItem(quoteId) ?: menu
            .add(Menu.NONE, quoteId, 0, "引用文本")
            .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_ALWAYS)
            .setVisible(canQuote)

        private fun refresh(mode: ActionMode) {
            evaluateJavascript("window.__DSH_MOBILE_SELECTION__&&window.__DSH_MOBILE_SELECTION__.active(true)") { result ->
                if (!ended) {
                    val quote = item(mode.menu)
                    val visible = result == "true"
                    canQuote = visible
                    if (quote.isVisible != visible) {
                        quote.isVisible = visible
                        mode.invalidate()
                    }
                }
            }
        }

        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            if (!original.onCreateActionMode(mode, menu)) return false
            item(menu)
            refresh(mode)
            postDelayed({ if (!ended) refresh(mode) }, 180)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            val changed = original.onPrepareActionMode(mode, menu)
            item(menu)
            refresh(mode)
            return changed
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            if (item.itemId != quoteId) return original.onActionItemClicked(mode, item)
            evaluateJavascript("window.__DSH_MOBILE_SELECTION__&&window.__DSH_MOBILE_SELECTION__.quote()") { result ->
                if (result == "true") mode.finish()
                else if (!ended) Toast.makeText(context, "引用暂时不可用，请重新选择文字", Toast.LENGTH_SHORT).show()
            }
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            ended = true
            evaluateJavascript("window.__DSH_MOBILE_SELECTION__&&window.__DSH_MOBILE_SELECTION__.active(false)", null)
            original.onDestroyActionMode(mode)
        }

        override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
            if (original is ActionMode.Callback2) original.onGetContentRect(mode, view, outRect)
            else super.onGetContentRect(mode, view, outRect)
        }
    }
}
