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
    var showNotes by remember {mutableStateOf(false)}
    val current=state.current;val gpu=computerGpu(current,gpuId)
    LaunchedEffect(current?.instance) {gpuId=current?.gpus?.firstOrNull{it.load!=null||it.used!=null||it.shared!=null}?.id ?: current?.gpus?.firstOrNull()?.id}
    val processes=remember(current?.processes,query,sort){current?.processes.orEmpty().filter {it.name.contains(query,true)||it.pid.toString()==query}.sortedByDescending {p->when(sort){ComputerMetric.CPU->p.cpu;ComputerMetric.GPU->p.gpu;ComputerMetric.RAM->p.memory;ComputerMetric.VRAM->p.dedicated;ComputerMetric.SHARED->p.shared} ?: -1.0}}
    var ringVertical by remember {mutableStateOf(settings.computerMonitorRingVertical)}
    var overlayStyle by remember {mutableStateOf(settings.computerMonitorStyle)}
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
                        Triple(ComputerMetric.VRAM,monitorBytes(gpu?.used),if(gpu?.total==0.0)"未预留专用容量" else "共 ${monitorBytes(gpu?.total)}")
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
                    else SettingsHint(if(gpuId!=null)"所选 GPU 暂不可用，请重新选择。" else "GPU 信息未提供。请检查电脑驱动及新版远程访问工具。")
                    if(current!=null&&current.gpus.isNotEmpty()&&(current.gpus.size>1||gpu==null))Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {current.gpus.forEachIndexed {i,g->Button(onClick={gpuId=g.id}){Text((if(gpu?.id==g.id)"● " else "")+"GPU $i · ${g.name}",fontSize=12.sp)}}}
                    if(gpu!=null) {
                        if(gpu.load==null && gpu.used==null && gpu.shared==null)SettingsHint("此 GPU 暂无驱动计数器数据，可选择其他显卡；不表示占用为零。")
                        Text((if(metric==ComputerMetric.SHARED)"● " else "")+"查看共享 GPU 内存曲线 ›",modifier=Modifier.padding(vertical=10.dp).clickable{metric=ComputerMetric.SHARED},fontSize=12.sp,color=MiuixTheme.colorScheme.primary)
                        if(gpu.total==0.0)SettingsHint("系统未报告专用显存容量，可查看共享 GPU 内存。")
                    }
                    MonitorPlot(state.samples,metric,gpuId?:gpu?.id)
                    SettingsHint("${metric.title}历史 · 中断留空 · 点上方指标切换曲线")
                }
                item {
                    SettingsGroup("总览刷新速度") {
                        Column(Modifier.padding(14.dp)) {
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                                listOf(500 to "0.5 秒",1000 to "1 秒",2000 to "2 秒").forEach {(value,label)->
                                    Button(onClick={interval=value;settings.computerMonitorIntervalMs=value},modifier=Modifier.weight(1f)) {Text((if(interval==value)"● " else "")+label,fontSize=12.sp)}
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            SettingsHint("总览按所选速度更新，进程约 3 秒更新。")
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
                        }
                            Row(Modifier.fillMaxWidth().padding(bottom=12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                listOf("curve" to "面积曲线","rings" to "圆环仪表").forEach {(style,title)->
                                    Button(onClick={overlayStyle=style;settings.computerMonitorStyle=style;DshLiveService.current()?.refreshMonitor()},modifier=Modifier.weight(1f)) {Text((if(overlayStyle==style)"● " else "")+title,fontSize=12.sp)}
                                }
                            }
                            if(overlayStyle=="rings") {
                                Text("圆环排布",fontSize=12.sp,color=MiuixTheme.colorScheme.onBackgroundVariant)
                                Spacer(Modifier.height(8.dp))
                                Row(Modifier.fillMaxWidth().padding(bottom=12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                    listOf(false to "横向排列",true to "纵向排列").forEach {(vertical,title)->
                                        Button(onClick={ringVertical=vertical;settings.computerMonitorRingVertical=vertical;DshLiveService.current()?.refreshMonitor()},modifier=Modifier.weight(1f)) {Text((if(ringVertical==vertical)"● " else "")+title,fontSize=12.sp)}
                                    }
                                }
                            }
                            SettingsHint("圆环可横排或竖排，点圆环展开详情；长按图表切换显卡。")
                            Spacer(Modifier.height(12.dp))
                        }
                    }
                }
                item {
                    SettingsSearch(query,"搜索进程名或 PID") {query=it}
                    Text("电脑进程 · ${processes.size} 项",fontSize=16.sp,fontWeight=androidx.compose.ui.text.font.FontWeight.Medium)
                    Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {ComputerMetric.entries.forEach {m->Button(onClick={sort=m}){Text((if(sort==m)"↓ " else "")+m.title,fontSize=12.sp)}}}
                    Spacer(Modifier.height(8.dp))
                    SettingsHint("按所选指标降序排列，点进程查看详情。GPU 与显存为进程跨显卡统计。")
                    Text(if(showNotes)"收起指标说明 ⌃" else "指标说明 ⌄",modifier=Modifier.padding(vertical=10.dp).clickable{showNotes=!showNotes},fontSize=12.sp,color=MiuixTheme.colorScheme.primary)
                    if(showNotes)Column(Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).background(MiuixTheme.colorScheme.onBackground.copy(alpha=.04f)).padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        SettingsHint("采样：网络较慢时按实际响应显示，不堆积请求；中断期间不补零。")
                        SettingsHint("GPU：总览按所选显卡分别显示；暂不可读的指标显示未提供。共享 GPU 内存来自系统内存，上限不是已预占，不应与系统内存重复相加。")
                        SettingsHint("进程：GPU取跨显卡最忙引擎，专用/共享显存为跨显卡统计，不跟随总览显卡切换。进程内存是工作集，显存可能重复计入，不能简单相加；DSH 对话与进程没有一一对应关系。")
                    }
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
            SettingsHint("GPU与显存为此进程跨显卡统计；内存为工作集。")
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
    val capacity=if(process==null) {if(metric==ComputerMetric.RAM)samples.lastOrNull()?.memoryTotal else computerGpu(samples.lastOrNull(),gpu)?.let{if(metric==ComputerMetric.SHARED)it.sharedTotal else it.total}} else null
    val maximum=if(percent)100.0 else maxOf(1.0,capacity?.takeIf{it>0}?:((values.filterNotNull().maxOrNull()?:1.0)*1.1))
    val ink=MiuixTheme.colorScheme.primary;val grid=MiuixTheme.colorScheme.onBackground.copy(alpha=.12f)
    Text("${if(process!=null && metric in listOf(ComputerMetric.GPU,ComputerMetric.VRAM,ComputerMetric.SHARED))"进程${metric.title}（跨显卡）" else metric.title} · ${if(percent)monitorPercent(values.lastOrNull()) else monitorBytes(values.lastOrNull())}",fontSize=14.sp)
    Text(if(percent)"0 — 100%" else "0 — ${monitorBytes(maximum)}",fontSize=11.sp,color=MiuixTheme.colorScheme.onBackgroundVariant)
    val reveal=remember(metric,gpu,process) {androidx.compose.animation.core.Animatable(1f)}
    LaunchedEffect(samples.lastOrNull()?.time,metric,gpu,process) {
        if(samples.size>1) {
            reveal.snapTo(0f)
            reveal.animateTo(1f,androidx.compose.animation.core.tween(250,easing=androidx.compose.animation.core.LinearOutSlowInEasing))
        }
    }
    Canvas(Modifier.fillMaxWidth().height(110.dp).padding(vertical=8.dp)) {
        for(i in 0..4)drawLine(grid,Offset(0f,size.height*i/4),Offset(size.width,size.height*i/4),1.dp.toPx())
        for(i in 0..8)drawLine(grid,Offset(size.width*i/8,0f),Offset(size.width*i/8,size.height),.5.dp.toPx())
        val path=Path();var area=Path();var open=false;var lastX=0f
        val start=samples.firstOrNull()?.time?:0
        val last=samples.lastOrNull()?.time?:start
        val previous=samples.getOrNull(samples.lastIndex-1)?.time?:last
        val continuous=last-previous in 1..15000
        val progress=if(continuous)reveal.value else 1f
        val end=previous+(last-previous)*progress.toDouble()
        val span=maxOf(5000.0,end-start)
        fun finishArea() {
            if(open){area.lineTo(lastX,size.height);area.close();drawPath(area,ink.copy(alpha=.18f))}
            area=Path();open=false
        }
        values.forEachIndexed {index,raw->
            if(raw==null){finishArea();return@forEachIndexed}
            if(index>0&&samples[index].time-samples[index-1].time>15000)finishArea()
            val newest=index==values.lastIndex
            val prior=values.getOrNull(index-1)
            val value=if(newest&&continuous&&prior!=null)prior+(raw-prior)*progress else raw
            val time=if(newest&&continuous&&prior!=null)end else samples[index].time.toDouble()
            val x=((time-start)/span*size.width).toFloat().coerceIn(0f,size.width)
            val y=(size.height*(1-value.coerceIn(0.0,maximum)/maximum)).toFloat()
            if(open){path.lineTo(x,y);area.lineTo(x,y)}
            else {path.moveTo(x,y);area.moveTo(x,size.height);area.lineTo(x,y)}
            open=true;lastX=x
            if(newest)drawCircle(ink,2.dp.toPx(),Offset(x,y))
        }
        finishArea()
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
