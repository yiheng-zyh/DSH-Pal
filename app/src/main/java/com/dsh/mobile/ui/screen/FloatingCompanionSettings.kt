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
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Switch
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
    Row(verticalAlignment=Alignment.CenterVertically) {
        Image(painterResource(R.drawable.whale_companion),"蓝发鲸鱼娘悬浮助手",Modifier.size(100.dp))
        Column(Modifier.weight(1f)) {
            Text("鲸鱼娘助手",fontSize=19.sp)
            Text("陪你看任务，等你来投喂。",fontSize=13.sp)
        }
    }
    Spacer(Modifier.height(12.dp))
    Text("仅在其他应用上方显示，回到 DSH Pal 自动隐藏。单击角色展开或收起文字，双击进入主对话；向边缘拖动贴壁偷看，轻点或往外拖恢复。关闭请回到这里。锁屏时隐藏。",fontSize=13.sp)
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
        Text("显示悬浮助手",Modifier.weight(1f))
        Switch(checked=enabled,onCheckedChange={ settings.overlayEnabled=it;refresh() })
    }
    Text(if(permission) "悬浮窗权限已允许" else "还需允许「显示在其他应用上层」权限；若打开应用列表，请选择 DSH Pal",fontSize=12.sp)
    if(!permission) Button(onClick={
        runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:${context.packageName}"))) }
            .onFailure { message="系统未提供权限入口，请到应用信息中允许悬浮窗。" }
    }) { Text("允许悬浮窗权限") }
    if(enabled && permission && DshLiveService.current()==null) Text("连接工作台后，助手会随任务跟踪启动。",fontSize=12.sp)
    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
        Text("贴壁偷看",Modifier.weight(1f))
        Switch(checked=peekEnabled,onCheckedChange={settings.overlayPeekOnEdge=it;refresh()})
    }
    Text("把角色向屏幕边缘拖动，收成探头的小挂件；贴壁只隐藏气泡，不停止任务跟踪。",fontSize=12.sp)
    Spacer(Modifier.height(12.dp))
    Text("角色大小",fontSize=15.sp)
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        listOf(64 to "小",88 to "中",112 to "大").forEach { (value,label) ->
            Button(onClick={settings.overlaySize=value;refresh()}) { Text(label+if(size==value) " ✓" else "") }
        }
    }
    Spacer(Modifier.height(12.dp))
    Button(onClick=onStyleSettings) {Text("文字框样式与透明度")}
    Spacer(Modifier.height(12.dp))
    Text("信息详细程度",fontSize=15.sp)
    Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        listOf("精简","步骤","详细").forEachIndexed { index,label ->
            Button(onClick={settings.overlayDetail=index;refresh()}) { Text(label+if(detail==index) " ✓" else "") }
        }
    }
    Text("精简：状态与进度；步骤：最近一项；详细：最近公开步骤。内容与任务通知同源，不额外调用模型。",fontSize=12.sp)
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
        Text("显示会话标题",Modifier.weight(1f))
        Switch(checked=showTitle,onCheckedChange={settings.overlayShowTitle=it;refresh()})
    }
    Text("标题默认隐藏；公开步骤也可能含工作内容，可选择精简模式。锁屏不会显示这些内容。",fontSize=12.sp)
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Button(onClick={settings.overlayDocked=false;settings.overlayExpanded=!settings.overlayExpanded;refresh()}) { Text("展开 / 收起信息") }
        Button(onClick={settings.setOverlayDock(1f,.35f,false);refresh()}) { Text("重置位置") }
    }
    Spacer(Modifier.height(16.dp))
    val service by DshLiveService.instances.collectAsState()
    val taskFlow=remember(service){ service?.state ?: kotlinx.coroutines.flow.MutableStateFlow(TaskState()) }
    val task by taskFlow.collectAsState()
    val info=FloatingTaskInfo.capsule(task,detail,showTitle)
    Text("当前信息预览",fontSize=15.sp)
    Text(info.heading,fontSize=14.sp)
    Text(info.body,fontSize=12.sp)
    if(message.isNotBlank()) Text(message,fontSize=12.sp)
    Text("部分系统还需允许后台弹出界面；关闭后台连接或系统结束任务跟踪时，悬浮助手也会停止。",fontSize=12.sp)
    Spacer(Modifier.height(24.dp))
}
