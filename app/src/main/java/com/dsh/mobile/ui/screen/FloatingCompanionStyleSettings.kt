package com.dsh.mobile.ui.screen

import android.widget.SeekBar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import com.dsh.mobile.core.SettingsStore
import com.dsh.mobile.core.FloatingTaskInfo
import com.dsh.mobile.core.TaskState
import com.dsh.mobile.service.DshLiveService
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Text

@Composable
fun FloatingCompanionStyleSettings(settings:SettingsStore) {
    var revision by remember {mutableIntStateOf(0)}
    val width=remember(revision){settings.overlayPanelWidth}
    val font=remember(revision){settings.overlayFontSize}
    val transparency=remember(revision){settings.overlayPanelTransparency}
    val service by DshLiveService.instances.collectAsState()
    val stream=remember(service){service?.state ?: kotlinx.coroutines.flow.MutableStateFlow(TaskState())}
    val task by stream.collectAsState()
    val info=FloatingTaskInfo.capsule(task,settings.overlayDetail,settings.overlayShowTitle)
    Text("实时预览",fontSize=15.sp)
    Spacer(Modifier.height(12.dp))
    Column(Modifier.widthIn(max=width.dp).fillMaxWidth().background(Color.White.copy(alpha=(100-transparency)/100f),RoundedCornerShape(40.dp)).padding(horizontal=16.dp,vertical=8.dp)) {
        Text(info.heading,fontSize=font.sp,fontWeight=FontWeight.Bold,color=Color(0xFF253549),maxLines=1,overflow=TextOverflow.Ellipsis)
        Text(info.body,fontSize=(font-1).sp,color=Color(0xFF7B8492),maxLines=if(settings.overlayDetail==2)3 else 1,overflow=TextOverflow.Ellipsis)
    }
    Spacer(Modifier.height(12.dp))
    Text("回到桌面可查看实际效果。宽度会按屏幕和角色大小自动限制；透明度只影响文字框背景，文字和角色保持清晰。",fontSize=12.sp)
    Spacer(Modifier.height(16.dp))
    CompanionRange("文字框宽度",width,180,360,"dp"){settings.overlayPanelWidth=it;revision++}
    CompanionRange("文字大小",font,10,16,"sp"){settings.overlayFontSize=it;revision++}
    CompanionRange("背景透明度",transparency,0,80,"%（0% 不透明）"){settings.overlayPanelTransparency=it;revision++}
    Button(onClick={settings.overlayPanelWidth=280;settings.overlayFontSize=12;settings.overlayPanelTransparency=8;revision++}){Text("恢复文字框默认样式")}
    Text("单击角色收起或展开文字框，双击进入主对话；输入按钮可展开快捷插话。完整设置与关闭开关都在 App 内。",fontSize=12.sp)
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun CompanionRange(label:String,value:Int,min:Int,max:Int,unit:String,onChange:(Int)->Unit) {
    var draggingValue by remember(value){mutableIntStateOf(value)}
    val change by rememberUpdatedState(onChange)
    Text("$label · $draggingValue $unit",fontSize=14.sp)
    AndroidView(factory={context -> SeekBar(context).apply {
        this.max=max-min;progress=value-min;contentDescription=label
        setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(bar:SeekBar) = Unit
            override fun onProgressChanged(bar:SeekBar,progress:Int,fromUser:Boolean){if(fromUser)draggingValue=progress+min}
            override fun onStopTrackingTouch(bar:SeekBar){change(bar.progress+min)}
        })
    }},update={bar->if(!bar.isPressed && bar.progress!=value-min)bar.progress=value-min},modifier=Modifier.fillMaxWidth().height(44.dp))
    Spacer(Modifier.height(12.dp))
}
