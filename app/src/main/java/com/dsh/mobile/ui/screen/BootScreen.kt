package com.dsh.mobile.ui.screen

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.mobile.core.RunLog
import com.dsh.mobile.ui.DshBrandMark
import com.dsh.mobile.ui.theme.LocalDshAccents
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Original compact launch choreography. Prior DSHgo attribution remains in third_party. */
@Composable
fun BootScreen(modifier:Modifier=Modifier,status:String="正在连接你的工作台…",progress:Int=0,
               onRepair:(()->Unit)?=null,onDiagnostics:(()->Unit)?=null,onReload:(()->Unit)?=null,
               browserResources:com.dsh.mobile.core.BusinessHealth=com.dsh.mobile.core.BusinessHealth()) {
    val colors=MiuixTheme.colorScheme
    val accent=LocalDshAccents.current.accent
    var elapsed by remember {mutableIntStateOf(0)}
    LaunchedEffect(Unit){while(true){delay(1000);elapsed++}}
    val entrance=remember {Animatable(0f)}
    LaunchedEffect(Unit){entrance.animateTo(1f,tween(850,easing=FastOutSlowInEasing))}
    val motion=rememberInfiniteTransition(label="connection-signal")
    val orbit by motion.animateFloat(0f,360f,infiniteRepeatable(tween(9000,easing=LinearEasing)),label="orbit")
    val pulse by motion.animateFloat(.35f,.85f,infiniteRepeatable(tween(1600,easing=LinearEasing),RepeatMode.Reverse),label="signal")
    val fraction by animateFloatAsState(progress.coerceIn(0,100)/100f,tween(300,easing=LinearEasing),label="real-progress")
    val revision=RunLog.revision.intValue
    val lines=remember(revision){RunLog.snapshot().takeLast(32).map{"+${it.offsetMs/1000}s  ${it.tag} · ${it.message}"}}
    // The cache writes on its own worker thread, so this polls the tracker on the
    // same 1s tick that already drives the elapsed counter — no new timer, and a
    // once-a-second figure is finer than a user can read anyway.
    val resourceLoad=remember(revision,elapsed){com.dsh.mobile.ui.web.WebResourceCache.load.snapshot()}
    val scroll=rememberScrollState()
    LaunchedEffect(revision){if(!scroll.isScrollInProgress)scroll.animateScrollTo(scroll.maxValue,tween(180,easing=LinearEasing))}
    BoxWithConstraints(modifier.fillMaxSize().background(colors.background)) {
        val compact=maxHeight<520.dp
        val wide=maxWidth>maxHeight
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(Brush.radialGradient(listOf(accent.copy(alpha=.12f),accent.copy(alpha=0f))),
                radius=size.width*.72f,center=androidx.compose.ui.geometry.Offset(size.width*.9f,size.height*.2f))
        }
        val hero:@Composable ()->Unit = {
            Column(Modifier.graphicsLayer {alpha=entrance.value;translationY=(1f-entrance.value)*24.dp.toPx()},horizontalAlignment=Alignment.CenterHorizontally) {
                Box(Modifier.size(if(compact)96.dp else 136.dp),contentAlignment=Alignment.Center) {
                    Canvas(Modifier.fillMaxSize().graphicsLayer {rotationZ=orbit}) {
                        drawCircle(accent.copy(alpha=.12f),style=androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
                        drawArc(accent.copy(alpha=pulse),-60f,80f,false,style=androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx(),cap=androidx.compose.ui.graphics.StrokeCap.Round))
                    }
                    DshBrandMark(if(compact)72.dp else 96.dp)
                }
                Spacer(Modifier.height(if(compact)16.dp else 24.dp))
                Text("你的工作台，随时相连",fontSize=if(compact)21.sp else 24.sp,fontWeight=FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text("DeepSeek Harness",fontSize=13.sp,color=colors.onBackgroundVariant)
            }
        }
        val details:@Composable ()->Unit = {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(colors.surface).padding(if(compact)12.dp else 18.dp),verticalArrangement=Arrangement.spacedBy(if(compact)8.dp else 12.dp)) {
                Text(status,fontSize=14.sp,fontWeight=FontWeight.Medium,maxLines=2,overflow=TextOverflow.Ellipsis)
                Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(accent.copy(alpha=.12f))) {
                    if(progress>0)Box(Modifier.fillMaxWidth(fraction.coerceAtLeast(.01f)).fillMaxHeight().background(accent))
                    else Canvas(Modifier.fillMaxSize()){drawRect(accent.copy(alpha=pulse))}
                }
                Text(if(elapsed>=30)"连接仍在进行，可以查看日志或前往设置" else "连接准备 → 页面资源 → 实时会话",fontSize=11.sp,color=colors.onBackgroundVariant)
                // Same slot, same typography, same colour as the line above — the
                // resource figure replaces nothing and adds no block; it only
                // appears once the cache has measured something, so an idle boot
                // screen looks exactly as before.
                val resourceDetail=com.dsh.mobile.core.ResourceLoadText.detail(resourceLoad) ?: browserResources.resourceDetail()
                Text(resourceDetail,fontSize=11.sp,color=colors.onBackgroundVariant,maxLines=3,overflow=TextOverflow.Ellipsis)
                if(browserResources.resourceBytes>=0&&!resourceLoad.hasActivity)
                    Text("体积在资源下载完成后更新",fontSize=10.sp,color=colors.onBackgroundVariant)
            }
            Spacer(Modifier.height(12.dp))
            Column(Modifier.fillMaxWidth().height(if(compact)92.dp else 140.dp).clip(RoundedCornerShape(16.dp))
                .border(1.dp,accent.copy(alpha=.16f),RoundedCornerShape(16.dp)).background(colors.surface.copy(alpha=.65f)).padding(12.dp)) {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                    Text("连接日志",fontSize=11.sp,fontWeight=FontWeight.Medium,color=colors.onBackgroundVariant)
                    Text("实时更新",fontSize=10.sp,color=accent)
                }
                Spacer(Modifier.height(6.dp))
                Column(Modifier.weight(1f).verticalScroll(scroll),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    if(lines.isEmpty())Text("正在准备连接…",fontSize=10.sp,color=colors.onBackgroundVariant)
                    lines.forEach {Text(it,fontSize=10.sp,fontFamily=FontFamily.Monospace,color=colors.onBackgroundVariant,maxLines=2,overflow=TextOverflow.Ellipsis)}
                }
            }
            if(elapsed>=30&&(onDiagnostics!=null||onRepair!=null)) {
                Spacer(Modifier.height(12.dp))
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    if(onDiagnostics!=null)top.yukonga.miuix.kmp.basic.Button(onClick=onDiagnostics){Text("设置与日志",fontSize=12.sp)}
                    if(onRepair!=null)top.yukonga.miuix.kmp.basic.Button(onClick=onRepair){Text("更换入口",fontSize=12.sp)}
                }
            }
        }
        Column(Modifier.fillMaxSize().padding(horizontal=24.dp,vertical=12.dp),horizontalAlignment=Alignment.CenterHorizontally) {
            Row(Modifier.widthIn(max=1000.dp).fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("DSH Pal",fontSize=11.sp,fontWeight=FontWeight.SemiBold,color=accent)
                    Text("已用时 ${elapsed} 秒",fontSize=12.sp,color=colors.onBackgroundVariant)
                }
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    if(onReload!=null)com.dsh.mobile.ui.AppIconButton(com.dsh.mobile.ui.AppActionIcon.Refresh,"刷新",onReload)
                    if(onDiagnostics!=null)com.dsh.mobile.ui.AppIconButton(com.dsh.mobile.ui.AppActionIcon.Settings,"设置",onDiagnostics)
                }
            }
            Spacer(Modifier.height(12.dp))
            if(wide) {
                Row(Modifier.weight(1f).widthIn(max=1000.dp).fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(24.dp)) {
                    Box(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),contentAlignment=Alignment.Center){hero()}
                    Column(Modifier.weight(1.25f).fillMaxHeight().verticalScroll(rememberScrollState())){details()}
                }
            } else {
                Column(Modifier.weight(1f).widthIn(max=640.dp).fillMaxWidth().verticalScroll(rememberScrollState()),horizontalAlignment=Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(if(compact)8.dp else 36.dp));hero()
                    Spacer(Modifier.height(if(compact)18.dp else 36.dp));details()
                }
            }
        }
    }
}
