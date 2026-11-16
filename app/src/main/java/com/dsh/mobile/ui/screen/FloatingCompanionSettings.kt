package com.dsh.mobile.ui.screen

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.dsh.mobile.R
import com.dsh.mobile.core.SettingsStore
import com.dsh.mobile.core.FloatingTaskInfo
import com.dsh.mobile.core.TaskState
import com.dsh.mobile.service.DshLiveService
import top.yukonga.miuix.kmp.basic.Text

@Composable
fun FloatingCompanionSettings(settings: SettingsStore, onStyleSettings: () -> Unit = {}) {
    val context=LocalContext.current
    val owner=LocalLifecycleOwner.current
    var revision by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf("") }
    DisposableEffect(owner) {
        val observer=LifecycleEventObserver { _,event -> if(event==Lifecycle.Event.ON_RESUME){ revision++;DshLiveService.current()?.refreshCompanion() } }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val permission=remember(revision) { Settings.canDrawOverlays(context) }
    val enabled=remember(revision) { settings.overlayEnabled }
    val size=remember(revision) { settings.overlaySize }
    val detail=remember(revision) { settings.overlayDetail }
    val showTitle=remember(revision) { settings.overlayShowTitle }
    val peekEnabled=remember(revision) {settings.overlayPeekOnEdge}
    fun refresh(){ revision++;DshLiveService.current()?.refreshCompanion() }
    Row(Modifier.padding(bottom = 16.dp), verticalAlignment=Alignment.CenterVertically) {
        Image(painterResource(R.drawable.whale_companion), null, Modifier.size(76.dp))
        Column(Modifier.weight(1f)) {
            Text("鲸鱼娘助手",fontSize=19.sp)
            SettingsHint("轻点角色查看最新对话，双击打开 DSH Pal。")
        }
    }
    SettingsGroup("显示与权限") {
        Column(Modifier.padding(horizontal = 16.dp)) {
            ToggleRow("显示悬浮助手", "仅在其他应用上方显示，锁屏时隐藏。", enabled) {
                settings.overlayEnabled=it;refresh()
            }
            if (!permission) SettingsAction("允许显示在其他应用上层", primary = true) {
                runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:${context.packageName}"))) }
                    .onFailure { message="请到系统应用信息中允许悬浮窗。" }
            }
            if(enabled && permission && DshLiveService.current()==null) {
                SettingsHint("连接工作台后，助手会随任务跟踪启动。", Modifier.padding(bottom = 12.dp))
            }
            ToggleRow("贴壁偷看", "拖向边缘即可收起，轻点角色恢复。", peekEnabled) {
                settings.overlayPeekOnEdge=it;refresh()
            }
        }
    }
    SettingsGroup("文字框") {
        SettingsItem("样式与透明度", "材质、宽度和字号", onClick = onStyleSettings)
        SettingsDivider()
        Column(Modifier.padding(horizontal = 16.dp)) {
            ToggleRow("显示会话标题", "关闭后使用「当前对话」。", showTitle) {
                settings.overlayShowTitle=it;refresh()
            }
        }
    }
    SettingsGroup("角色大小") {
        listOf(64 to "小",88 to "中",112 to "大").forEach { (value,label) ->
            SettingsItem(label, "$value dp", selected = size == value) {settings.overlaySize=value;refresh()}
        }
    }
    SettingsGroup("紧凑文字框的任务信息") {
        listOf("精简" to "状态与进度", "步骤" to "最近一个步骤", "详细" to "最近的公开步骤").forEachIndexed { index, option ->
            if (index > 0) SettingsDivider()
            SettingsItem(option.first, option.second, selected = detail == index) {settings.overlayDetail=index;refresh()}
        }
    }
    SettingsGroup("位置与展开状态") {
        SettingsItem("展开 / 收起文字框", "下次回到其他应用时生效") {
            settings.overlayDocked=false;settings.overlayExpanded=!settings.overlayExpanded;refresh()
        }
        SettingsDivider()
        SettingsItem("重置位置", "恢复到屏幕右侧") {settings.setOverlayDock(1f,.35f,false);refresh()}
    }
    val service by DshLiveService.instances.collectAsState()
    val taskFlow=remember(service){ service?.state ?: kotlinx.coroutines.flow.MutableStateFlow(TaskState()) }
    val task by taskFlow.collectAsState()
    val info=FloatingTaskInfo.capsule(task,detail,showTitle)
    SettingsGroup("任务信息预览") {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(info.heading,fontSize=14.sp)
            SettingsHint(info.body)
        }
    }
    if(message.isNotBlank()) SettingsHint(message)
    Spacer(Modifier.height(24.dp))
}
