package com.dsh.mobile.ui.screen

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.dsh.mobile.core.*
import com.dsh.mobile.service.DshLiveService
import com.dsh.mobile.ui.AppBackHandler
import com.dsh.mobile.ui.AppPageHeader
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.Locale

@Composable
fun ComputerMonitorScreen(settings:SettingsStore,onBack:()->Unit) {
    val context=LocalContext.current;val lifecycle=LocalLifecycleOwner.current
    var revision by remember {mutableIntStateOf(0)}
    DisposableEffect(lifecycle) {
        var token:Any?=null
        fun sync(){
            if(lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {if(token==null)token=ComputerPerformance.retain(context,fullPage=true);revision++}
            else {token?.let(ComputerPerformance::release);token=null}
        }
        val observer=LifecycleEventObserver {_,_->sync()};lifecycle.lifecycle.addObserver(observer);sync()
        onDispose {lifecycle.lifecycle.removeObserver(observer);token?.let(ComputerPerformance::release)}
    }
    val state by ComputerPerformance.state.collectAsState()
    var interval by remember {mutableIntStateOf(settings.computerMonitorIntervalMs)}
    var metric by remember {mutableStateOf(ComputerMetric.VRAM)}
    var gpuId by remember {mutableStateOf<String?>(null)}
    var query by remember {mutableStateOf("")}
    var sort by remember {mutableStateOf(ComputerMetric.CPU)}
    var selected by remember {mutableStateOf<ComputerProcess?>(null)}
    val current=state.current;val gpu=current?.gpus?.find{it.id==gpuId} ?: current?.gpus?.firstOrNull()
    val processes=remember(current?.processes,query,sort){current?.processes.orEmpty().filter {it.name.contains(query,true)||it.pid.toString()==query}.sortedByDescending {p->when(sort){ComputerMetric.CPU->p.cpu;ComputerMetric.GPU->p.gpu;ComputerMetric.RAM->p.memory;ComputerMetric.VRAM->p.dedicated} ?: -1.0}}
    var overlay by remember(revision) {mutableStateOf(settings.computerMonitorOverlay)}
    var hint by remember {mutableStateOf<String?>(null)}
    AppBackHandler {if(selected!=null)selected=null else onBack()}
    Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background).safeDrawingPadding(),contentAlignment=androidx.compose.ui.Alignment.TopCenter) {
        Column(Modifier.widthIn(max=860.dp).fillMaxSize().padding(horizontal=20.dp,vertical=8.dp)) {
            AppPageHeader("电脑性能",subtitle=settings.activeProfile?.routeTitle,onBack=onBack)
            LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(bottom=20.dp)) {
                item {
                    SettingsHint(if(state.message.isNotBlank())state.message+(if(current!=null)" · 仅显示上次数据" else "") else "总览 ${if(interval==500)"0.5" else (interval/1000).toString()} 秒 · 进程约 3 秒 · 最多约 6 分钟")
                    if(hint!=null)SettingsHint(hint!!)

                }
                item {
                    val configuration=androidx.compose.ui.platform.LocalConfiguration.current
                    val compact=configuration.screenHeightDp<480 && configuration.screenWidthDp>=600
                    val tiles=listOf(
                        Triple(ComputerMetric.CPU,monitorPercent(current?.cpu),"处理器占用"),
                        Triple(ComputerMetric.RAM,monitorBytes(current?.memory),"共 ${monitorBytes(current?.memoryTotal)}"),
                        Triple(ComputerMetric.GPU,monitorPercent(gpu?.load),"最忙引擎"),
                        Triple(ComputerMetric.VRAM,monitorBytes(gpu?.used),"共 ${monitorBytes(gpu?.total)}")
                    )
                    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        tiles.chunked(if(compact)4 else 2).forEach {row->
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                row.forEach {(kind,value,detail)->
                                    MonitorTile(if(kind==ComputerMetric.VRAM)"专用显存" else kind.title,value,detail,metric==kind,Modifier.weight(1f)){metric=kind}
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    if(gpu!=null)Text("${gpu.name}\n共享 GPU 内存 ${monitorBytes(gpu.shared)} / ${monitorBytes(gpu.sharedTotal)}",fontSize=12.sp,lineHeight=18.sp,color=MiuixTheme.colorScheme.onBackgroundVariant)
                    else SettingsHint("GPU 信息未提供。请检查电脑驱动及新版远程访问工具。")
                    if(current!=null&&current.gpus.size>1)Row(Modifier.horizontalScroll(rememberScrollState())) {current.gpus.forEachIndexed {i,g->Button(onClick={gpuId=g.id}){Text("GPU $i",fontSize=12.sp)}}}
                    MonitorPlot(state.samples,metric,gpu?.id)
                    SettingsHint("${metric.title}历史曲线。断开或暂停期间不补零；GPU 为最忙引擎，显存为专用显存。")
                }
                item {
                    SettingsGroup("总览刷新速度") {
                        Column(Modifier.padding(14.dp)) {
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                                listOf(500 to "0.5 秒",1000 to "1 秒",2000 to "2 秒").forEach {(value,label)->
                                    Button(onClick={interval=value;settings.computerMonitorIntervalMs=value},modifier=Modifier.weight(1f)) {Text((if(interval==value)"● " else "")+label,fontSize=12.sp)}
                                }
                            }
                            SettingsHint("只加快 CPU、GPU、内存和显存。进程约 3 秒更新；网络较慢时按实际响应速度显示，不堆积请求。")
                        }
                    }
                    SettingsGroup("悬浮查看") {
                        Column(Modifier.padding(horizontal=16.dp)) {ToggleRow("性能悬浮窗","离开 App 后查看 CPU、GPU、显存与短曲线",overlay){on->
                            if(on&&!Settings.canDrawOverlays(context)) {
                                hint="请允许显示在其他应用上层，返回后再打开此开关"
                                runCatching{context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:${context.packageName}")))}
                            } else {
                                overlay=on;settings.computerMonitorOverlay=on;hint=if(on)"悬浮窗已开启，离开完整监控页后显示" else null
                                if(DshLiveService.current()==null&&on)DshLiveService.start(context)
                                DshLiveService.current()?.refreshMonitor()
                            }
                        }}
                    }
                }
                item {
                    SettingsSearch(query,"搜索进程名或 PID") {query=it}
                    Text("电脑进程 · ${processes.size} 项",fontSize=16.sp)
                    Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {ComputerMetric.entries.forEach {m->Button(onClick={sort=m}){Text((if(sort==m)"↓ " else "")+m.title,fontSize=12.sp)}}}
                    SettingsHint("按列降序，点进程看单独曲线。内存为工作集；专用显存可能由多个进程共享。DSH 对话不能与进程占用一一对应。")
                }
                if(processes.isEmpty())item {SettingsHint(if(current==null)"等待读取电脑数据。此功能需更新电脑端 Pal 远程访问工具，目前采集端支持 Windows。" else "没有匹配的可读取进程。部分系统保护进程无法读取。")}
                items(processes,key={it.identity}) {p->
                    Card(Modifier.fillMaxWidth().clickable{selected=p},cornerRadius=18.dp) {
                        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                            Text("${p.name} · ${p.pid}",fontSize=14.sp)
                            Text("CPU ${monitorPercent(p.cpu)} · GPU ${monitorPercent(p.gpu)}",fontSize=12.sp)
                            Text("内存 ${monitorBytes(p.memory)} · 专用显存 ${monitorBytes(p.dedicated)}",fontSize=12.sp,color=MiuixTheme.colorScheme.onBackgroundVariant)
                        }
                    }
                }
            }
        }
    }
    if(selected!=null)Dialog(onDismissRequest={selected=null}) {
        var processMetric by remember {mutableStateOf(ComputerMetric.VRAM)}
        Card(modifier=Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(24.dp)).background(MiuixTheme.colorScheme.background),cornerRadius=24.dp) {Column(Modifier.heightIn(max=(androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp-56).coerceAtLeast(140).dp).verticalScroll(rememberScrollState()).padding(20.dp)) {
            Text("${selected!!.name} · ${selected!!.pid}",fontSize=18.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(4.dp)) {ComputerMetric.entries.forEach {m->Button(onClick={processMetric=m}){Text(m.title,fontSize=12.sp)}}}
            val latest=state.current?.processes?.find{it.identity==selected!!.identity}
            SettingsHint(if(latest==null)"进程已退出或暂不可读取，保留历史曲线" else "CPU ${monitorPercent(latest.cpu)} · GPU ${monitorPercent(latest.gpu)}\n内存 ${monitorBytes(latest.memory)} · 专用显存 ${monitorBytes(latest.dedicated)}\n共享显存 ${monitorBytes(latest.shared)}")
            MonitorPlot(state.samples,processMetric,gpu?.id,selected!!.identity)
            SettingsAction("关闭") {selected=null}
        }}
    }
}
@Composable
private fun MonitorPlot(samples:List<ComputerSample>,metric:ComputerMetric,gpu:String?,process:String?=null) {
    val values=samples.map{computerValue(it,metric,gpu,process)}
    if(values.all{it==null}){SettingsHint("尚无可用${metric.title}采样，连接后开始记录曲线");return}
    val percent=metric==ComputerMetric.CPU||metric==ComputerMetric.GPU
    val capacity=if(process==null) {if(metric==ComputerMetric.RAM)samples.lastOrNull()?.memoryTotal else samples.lastOrNull()?.gpus?.find{it.id==gpu}?.total} else null
    val maximum=if(percent)100.0 else maxOf(1.0,capacity?:((values.filterNotNull().maxOrNull()?:1.0)*1.1))
    val ink=MiuixTheme.colorScheme.primary;val grid=MiuixTheme.colorScheme.onBackground.copy(alpha=.12f)
    Text("${metric.title} · ${if(percent)monitorPercent(values.lastOrNull()) else monitorBytes(values.lastOrNull())}",fontSize=14.sp)
    Text(if(percent)"0 — 100%" else "0 — ${monitorBytes(maximum)}",fontSize=11.sp,color=MiuixTheme.colorScheme.onBackgroundVariant)
    Canvas(Modifier.fillMaxWidth().height(110.dp).padding(vertical=8.dp)) {
        for(i in 0..4)drawLine(grid,Offset(0f,size.height*i/4),Offset(size.width,size.height*i/4),1.dp.toPx())
        val path=Path();var open=false
        val start=samples.firstOrNull()?.time?:0;val span=maxOf(5000L,(samples.lastOrNull()?.time?:0)-start)
        values.forEachIndexed {index,value->
            if(value==null){open=false;return@forEachIndexed}
            val x=((samples[index].time-start).toDouble()/span*size.width).toFloat();val y=(size.height*(1-value.coerceIn(0.0,maximum)/maximum)).toFloat()
            if(index>0&&samples[index].time-samples[index-1].time>15000)open=false
            if(open)path.lineTo(x,y) else path.moveTo(x,y);open=true
            if(index==values.lastIndex)drawCircle(ink,2.dp.toPx(),Offset(x,y))
        }
        drawPath(path,ink,style=Stroke(1.5.dp.toPx()))
    }
    if(samples.isNotEmpty())Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
        val fast=samples.size>1 && samples.last().time-samples[samples.lastIndex-1].time<900
        val format=java.text.SimpleDateFormat(if(fast)"HH:mm:ss.SSS" else "HH:mm:ss",Locale.getDefault())
        Text(format.format(java.util.Date(samples.first().time)),fontSize=11.sp,color=MiuixTheme.colorScheme.onBackgroundVariant)
        Text(format.format(java.util.Date(samples.last().time)),fontSize=11.sp,color=MiuixTheme.colorScheme.onBackgroundVariant)
    }
}
internal fun monitorPercent(value:Double?):String = value?.let{String.format(Locale.getDefault(),"%.1f%%",it)}?:"未提供"
internal fun monitorBytes(value:Double?):String = value?.let{if(it>=1073741824.0)String.format(Locale.getDefault(),"%.2f GB",it/1073741824.0) else ResourceLoad.size(it.toLong())}?:"未提供"
@Composable
private fun MonitorTile(title:String,value:String,detail:String,selected:Boolean,modifier:Modifier,onClick:()->Unit) {
    Card(modifier.then(Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp))).clickable(onClick=onClick),cornerRadius=20.dp) {
        Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text((if(selected)"● " else "")+title,fontSize=12.sp,color=MiuixTheme.colorScheme.onBackgroundVariant)
            Text(value,fontSize=23.sp,fontWeight=androidx.compose.ui.text.font.FontWeight.SemiBold)
            Text(detail,fontSize=11.sp,color=MiuixTheme.colorScheme.onBackgroundVariant)
        }
    }
}
