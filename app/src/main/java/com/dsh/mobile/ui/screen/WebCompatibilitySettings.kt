package com.dsh.mobile.ui.screen

import android.webkit.WebView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import com.dsh.mobile.core.SettingsStore
import com.dsh.mobile.core.WebFilePolicy
import com.dsh.mobile.core.WebPluginBundles
import com.dsh.mobile.ui.ConfirmationDialog
import com.dsh.mobile.ui.web.WebPluginCompatibility
import com.dsh.mobile.ui.web.reloadPairedPage
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Text

private const val WallpaperPlugin = "dsh-plugin-wallpaper-engine"
private const val PluginPauseImpact = "暂停仅作用于当前连接在这台手机上的网页。插件提供的入口、样式或交互可能消失，依赖这些功能的页面也可能异常。"
private const val PluginPauseRecovery = "如遇异常，可点「取消所有暂停」，再点「应用兼容设置」恢复。"

@Composable
internal fun WebCompatibilitySettings(settings: SettingsStore, webView: WebView?) {
    val origin = settings.currentBaseUrl
    var saved by remember(origin) { mutableStateOf(settings.webCompatibility(origin)) }
    var edited by remember(origin) { mutableStateOf(saved) }
    var confirm by remember(origin) { mutableStateOf(false) }
    var pluginReport by remember(webView, origin) { mutableStateOf<JSONObject?>(null) }
    var failed by remember(webView, origin) { mutableStateOf(WebPluginCompatibility.failuresFor(webView)) }
    var expanded by remember(origin) { mutableStateOf(false) }
    var result by remember(webView, origin) { mutableStateOf<String?>(null) }
    val currentView = webView?.takeIf { WebFilePolicy.sameOrigin(it.url.orEmpty(), origin) }
    LaunchedEffect(currentView) {
        while (currentView != null) {
            currentView.evaluateJavascript("window.__DSH_PAL_PLUGIN_POLICY__?.report()") {
                pluginReport = runCatching { JSONObject(it) }.getOrNull()
                failed = WebPluginCompatibility.failuresFor(currentView)
            }
            kotlinx.coroutines.delay(1500)
        }
    }

    Column(Modifier.padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (origin.isBlank()) {
            Text("连接 DSH 后，可为这个连接调整网页兼容设置。")
            return@Column
        }
        SettingsGroup("网页显示") {
            Column(Modifier.padding(horizontal = 16.dp)) {
                ToggleRow("使用 DSH 原始网页", "暂停 Pal 对网页布局、外观和交互的调整。", edited.originalPage) {
                    edited = edited.copy(originalPage = it)
                }
            }
        }

        val packages = pluginReport.packageNames("packages")
        val paused = pluginReport.packageNames("paused")
        val retained = pluginReport.packageNames("required")
        val candidates = (packages + edited.pausedPlugins).filter(WebPluginBundles::canPause)
            .sortedWith(compareByDescending<String> { it in failed }.thenBy { it })
        if (edited != saved) SettingsAction("应用兼容设置", primary = true) { confirm = true }
        SettingsGroup("本机暂停插件") {
            SettingsItem("选择要暂停的插件", if (expanded) "收起插件列表" else "${candidates.size} 个可选 · 已选 ${edited.pausedPlugins.size} 个") {
                expanded = !expanded
            }
            if (expanded || edited.pausedPlugins.isNotEmpty() || saved.pausedPlugins.isNotEmpty()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("暂停插件可能影响网页功能", fontWeight = FontWeight.Medium)
                    SettingsHint(PluginPauseImpact)
                    SettingsHint(PluginPauseRecovery)
                }
            }
            if (expanded && candidates.isEmpty()) SettingsHint("页面尚未提供可暂停的插件列表。", Modifier.padding(16.dp))
            if (expanded) candidates.forEach { name ->
                SettingsDivider()
                Column(Modifier.padding(horizontal = 16.dp)) {
                    val inherited = name.startsWith("$WallpaperPlugin-") && WallpaperPlugin in edited.pausedPlugins
                    val note = when (name) {
                        in retained -> "有仍在使用的模块依赖它，已保留"
                        WallpaperPlugin -> "同时暂停壁纸的动效、字体、玻璃等子模块"
                        in paused -> "当前页面已暂停"
                        in failed -> "所在资源批次加载失败"
                        else -> ""
                    }
                    ToggleRow(name, if (inherited && name !in retained) "随壁纸主插件暂停" else note,
                        name in edited.pausedPlugins || inherited, enabled = !inherited) { checked ->
                        edited = edited.copy(pausedPlugins = if (checked) edited.pausedPlugins + name else edited.pausedPlugins - name)
                    }
                }
            }
        }
        if (edited.pausedPlugins.isNotEmpty()) SettingsAction("取消所有暂停") {
            edited = edited.copy(pausedPlugins = emptySet())
        }

        SettingsAction("诊断当前页面", enabled = currentView != null) {
            val view = currentView ?: return@SettingsAction
            failed = WebPluginCompatibility.failuresFor(view)
            result = "正在检查当前页面…"
            view.evaluateJavascript(diagnosePage) { raw ->
                val data = runCatching { JSONObject(raw) }.getOrNull()
                failed = WebPluginCompatibility.failuresFor(view)
                result = when (data?.optString("input")) {
                    "blocked" -> "输入区上方检测到其他网页元素，可能有遮罩。"
                    "clear" -> "输入区采样点可命中。可返回网页实际点击验证。"
                    "hidden" -> "输入区当前不可见，可返回工作区后重试。"
                    else -> "没有读到当前页面的输入区。"
                }
            }
        }
        result?.let { SettingsHint(it) }
        if (failed.isNotEmpty()) SettingsHint("有包含 ${failed.size} 个模块的资源批次加载失败。可先刷新，再逐个暂停可选插件对比。")
        if (paused.isNotEmpty()) SettingsHint("当前页面已暂停 ${paused.size} 个插件。")
        if (retained.isNotEmpty()) SettingsHint("保留了 ${retained.size} 个被其他模块依赖的插件。")
        if (result != null && pluginReport?.optBoolean("supported") != true) {
            SettingsHint("当前页面没有可用的插件清单。仍可尝试使用 DSH 原始网页。")
        }
    }
    val addingPause = (edited.pausedPlugins - saved.pausedPlugins).isNotEmpty()
    val reloadMessage = if (currentView != null) "将重新加载当前页面。未发送的内容可能丢失，请先保存。"
        else "设置将在这个连接下次打开时生效。"
    ConfirmationDialog(
        show = confirm,
        title = if (addingPause) "暂停所选插件？" else "应用兼容设置？",
        message = if (addingPause) "$PluginPauseImpact\n\n$PluginPauseRecovery\n\n$reloadMessage" else reloadMessage,
        confirmText = "应用", cancelText = "取消", onDismiss = { confirm = false },
        onConfirm = {
            confirm = false
            settings.setWebCompatibility(origin, edited)
            saved = edited
            pluginReport = null
            failed = emptySet()
            expanded = false
            result = null
            currentView?.let(::reloadPairedPage)
        },
    )
}

private fun JSONObject?.packageNames(key: String): Set<String> {
    val array = this?.optJSONArray(key) ?: return emptySet()
    return (0 until minOf(array.length(), 512)).map(array::optString).filter(WebPluginBundles::canPause).toSet()
}

private val diagnosePage = """
    (function(){
      var state='hidden';
      var editors=document.querySelectorAll('textarea,[contenteditable="true"],[role="textbox"]');
      for(var i=0;i<editors.length;i++){
        var editor=editors[i], r=editor.getBoundingClientRect();
        if(r.width<20||r.height<8||getComputedStyle(editor).visibility!=='visible')continue;
        var x=r.left+r.width/2,y=r.top+r.height/2;
        if(x<0||y<0||x>=innerWidth||y>=innerHeight)continue;
        var hit=document.elementFromPoint(x,y);
        state=hit&&(hit===editor||editor.contains(hit))?'clear':'blocked';
        break;
      }
      return {input:state};
    })()
""".trimIndent()
