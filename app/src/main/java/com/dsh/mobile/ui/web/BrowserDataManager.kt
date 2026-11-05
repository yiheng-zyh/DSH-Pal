package com.dsh.mobile.ui.web

import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.webkit.WebStorageCompat
import androidx.webkit.WebViewFeature
import com.dsh.mobile.core.ConnectionAddress
import com.dsh.mobile.core.LaunchRouting
import com.dsh.mobile.core.RunLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** Independent choices; never deletes native settings, saved endpoints or VPN credentials. */
enum class BrowserCleanup(val title:String,val detail:String) {
    RESOURCES("清除网页资源缓存","清理脚本、样式、图片和字体；保留 Cookie 与站点数据。"),
    PAGE_PREFERENCES("清除当前页面本地存储","清理此地址的 localStorage 与 sessionStorage，可能包含草稿、插件设置或网页授权；保留 Cookie。"),
    COOKIES("清除所有网页 Cookie","仅删除 Cookie，其他站点存储仍保留。完整退出请选择“清除全部登录与站点数据”。"),
    SITE("清除当前站点登录数据","按站点主域清理 Cookie、存储与网页缓存，包含其子域，可能影响多个入口。"),
    ALL("清除全部登录与站点数据","清理所有网页 Cookie、站点存储、Service Worker 和缓存；所有网页入口可能需要重新配对。")
}

object BrowserDataManager {
    fun fullCleanupSupported()=WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA)
    suspend fun perform(context:android.content.Context,view:WebView?,origin:String,action:BrowserCleanup) {
        check(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())
        when(action) {
            BrowserCleanup.RESOURCES -> {
                withContext(Dispatchers.IO){WebResourceCache.clearResources(context)}
                view?.clearCache(true)
            }
            BrowserCleanup.PAGE_PREFERENCES -> {
                val target=ConnectionAddress.origin(origin)?:error("尚未选择站点")
                check(view!=null && ConnectionAddress.origin(view.url.orEmpty())==target){"请先打开当前站点，再清理页面偏好"}
                val ok=suspendCancellableCoroutine<Boolean> {c->
                    view.evaluateJavascript("(function(){try{if(location.origin!==${org.json.JSONObject.quote(target)})return false;localStorage.clear();sessionStorage.clear();return true;}catch(e){return false;}})()") {if(c.isActive)c.resume(it=="true")}
                }
                check(ok){"站点未允许清理页面偏好"}
            }
            BrowserCleanup.COOKIES -> {
                suspendCancellableCoroutine<Unit>{c->CookieManager.getInstance().removeAllCookies {if(c.isActive)c.resume(Unit)}}
                CookieManager.getInstance().flush()
                ConnectionAddress.origin(origin)?.let {view?.loadUrl(LaunchRouting.resumeUrl(it))}
            }
            BrowserCleanup.SITE,BrowserCleanup.ALL -> {
                check(fullCleanupSupported()){"当前 WebView 不支持完整清理，请先更新 Android System WebView"}
                val target=ConnectionAddress.origin(origin)
                check(action!=BrowserCleanup.SITE || target!=null){"尚未选择站点"}
                // Stop the old document before deleting: it must not recreate its
                // persistent shell or keep using an in-memory device credential.
                view?.stopLoading();view?.loadUrl("about:blank")
                suspendCancellableCoroutine<Unit> {c->
                    val done=Runnable {if(c.isActive)c.resume(Unit)}
                    if(action==BrowserCleanup.ALL)WebStorageCompat.deleteBrowsingData(WebStorage.getInstance(),done)
                    else WebStorageCompat.deleteBrowsingDataForSite(WebStorage.getInstance(),target!!,done)
                }
                CookieManager.getInstance().flush()
                if(action==BrowserCleanup.ALL)withContext(Dispatchers.IO){WebResourceCache.clearResources(context)}
                target?.let {view?.loadUrl(LaunchRouting.resumeUrl(it))}
            }
        }
        RunLog.info("Cache","browser cleanup completed kind=${action.name}; native accounts and connections retained")
    }
}
