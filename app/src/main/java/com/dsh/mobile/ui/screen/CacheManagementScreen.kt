package com.dsh.mobile.ui.screen

import android.webkit.WebView
import com.dsh.mobile.ui.AppBackHandler as BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import com.dsh.mobile.core.SettingsStore
import com.dsh.mobile.ui.AppPageHeader
import com.dsh.mobile.ui.web.*
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

private enum class DataPage(val title:String) { Overview("缓存与数据"),Resources("网页资源"),Preferences("页面偏好"),Accounts("网页登录与站点数据") }
@Composable
fun CacheManagementScreen(settings:SettingsStore,webView:WebView?,onBack:()->Unit,onReload:()->Unit) {
    var page by remember {mutableStateOf(DataPage.Overview)}
    var pending by remember {mutableStateOf<BrowserCleanup?>(null)}
    var busy by remember {mutableStateOf(false)}
    var result by remember {mutableStateOf<String?>(null)}
    val scope=rememberCoroutineScope();val context=androidx.compose.ui.platform.LocalContext.current
    val full=remember {BrowserDataManager.fullCleanupSupported()}
    val origin=settings.currentBaseUrl
    val domain=origin.toHttpUrlOrNull()?.let {it.topPrivateDomain()?:it.host}
    fun back(){if(!busy){if(page==DataPage.Overview)onBack()else{page=DataPage.Overview;result=null}}}
    BackHandler {back()}
    Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background).safeDrawingPadding(),contentAlignment=androidx.compose.ui.Alignment.TopCenter) {
    Column(Modifier.widthIn(max=840.dp).fillMaxSize().padding(20.dp)) {
        AppPageHeader(page.title,onBack={back()})
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(page==DataPage.Overview) {
                DataLink("网页资源","更新插件、样式或图片；保留登录",{page=DataPage.Resources})
                DataLink("页面偏好","当前站点的设置、草稿与本地存储",{page=DataPage.Preferences})
                DataLink("网页登录与站点数据","单独管理 Cookie 或完整清理",{page=DataPage.Accounts})
                Text("清理均需确认。连接地址、主题、Tailscale 账号和 WireGuard 配置不会被删除。",fontSize=12.sp,color=MiuixTheme.colorScheme.onBackgroundVariant)
            }
            if(page==DataPage.Resources) {
                Card {Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("资源复用",fontWeight=FontWeight.Medium)
                    Text(WebResourceCache.summary(),fontSize=13.sp)
                    Text("静态资源最多 96 MB，自动检查更新。对话和任务不离线缓存。",fontSize=12.sp,color=MiuixTheme.colorScheme.onBackgroundVariant)
                }}
                DataAction(BrowserCleanup.RESOURCES,busy){pending=it}
                Button(onClick=onReload,enabled=!busy,modifier=Modifier.fillMaxWidth()){Text("重新加载页面")}
                Text("未发送内容会先提醒。仅清资源后，下一次访问需要重新下载。",fontSize=12.sp,color=MiuixTheme.colorScheme.onBackgroundVariant)
            }
            if(page==DataPage.Preferences) {
                Text("清理当前地址的本地存储，可能影响草稿、插件设置或网页授权。保留 Cookie、连接记录和原生账号。",fontSize=13.sp,color=MiuixTheme.colorScheme.onBackgroundVariant)
                DataAction(BrowserCleanup.PAGE_PREFERENCES,busy||webView==null){pending=it}
            }
            if(page==DataPage.Accounts) {
                Text("配对记录保留，但网页授权被清理后可能需要重新配对。",fontSize=13.sp,color=MiuixTheme.colorScheme.onBackgroundVariant)
                DataAction(BrowserCleanup.COOKIES,busy){pending=it}
                DataAction(BrowserCleanup.SITE,busy||!full||domain==null){pending=it}
                if(domain!=null)Text("当前主域：$domain · 包含子域",fontSize=12.sp,color=MiuixTheme.colorScheme.onBackgroundVariant)
                DataAction(BrowserCleanup.ALL,busy||!full){pending=it}
                if(!full)Text("完整清理需要更新 Android System WebView；当前不会执行不完整的替代清理。",fontSize=12.sp)
                Text("网页登录清理不会退出本 App 的 Tailscale 账号，也不会删除 WireGuard 密钥。",fontSize=12.sp,color=MiuixTheme.colorScheme.onBackgroundVariant)
            }
            if(busy)Text("正在清理，请稍候…",fontSize=13.sp)
            result?.let {Text(it,fontSize=13.sp)}
        }
    }
    com.dsh.mobile.ui.ConfirmationDialog(pending!=null,pending?.title.orEmpty()+"？",
        pending?.detail.orEmpty()+(if(pending==BrowserCleanup.SITE)"\n作用范围：${domain.orEmpty()} 及其子域。"else "")+"\n保留原生账号与连接地址。请先保存未发送内容。",
        confirmText="确认清理",onDismiss={pending=null},onConfirm={
            val action=pending;pending=null
            if(action!=null)scope.launch {busy=true;result=null
                try {BrowserDataManager.perform(context,webView,origin,action);result="清理完成"}
                catch(e:Exception){if(e is kotlinx.coroutines.CancellationException)throw e;result=e.message?:"清理未完成，请重试"}
                finally{busy=false}
            }
        })
    }
}
@Composable private fun DataLink(title:String,subtitle:String,onClick:()->Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick=onClick)){Row(Modifier.padding(18.dp)) {
        Column(Modifier.weight(1f)){Text(title,fontSize=16.sp,fontWeight=FontWeight.Medium);Spacer(Modifier.height(6.dp));Text(subtitle,fontSize=12.sp,color=MiuixTheme.colorScheme.onBackgroundVariant)}
        Text("›",fontSize=24.sp)
    }}
}
@Composable private fun DataAction(action:BrowserCleanup,disabled:Boolean,onClick:(BrowserCleanup)->Unit) {
    Card(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text(action.title,fontSize=15.sp,fontWeight=FontWeight.Medium)
        Text(action.detail,fontSize=12.sp,color=MiuixTheme.colorScheme.onBackgroundVariant)
        Button(enabled=!disabled,onClick={onClick(action)}){Text("选择清理",fontSize=13.sp)}
    }}
}
