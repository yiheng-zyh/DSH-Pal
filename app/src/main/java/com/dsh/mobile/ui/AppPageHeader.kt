package com.dsh.mobile.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

enum class AppActionIcon { Back, Refresh, Settings }

/** Native vector strokes: no icon font, extra dependency or per-theme bitmaps. */
@Composable
fun AppIconButton(icon:AppActionIcon,label:String,onClick:()->Unit, tint:Color=MiuixTheme.colorScheme.onBackground) {
    val ink=tint
    Box(Modifier.size(48.dp).clip(CircleShape)
        .semantics {contentDescription=label}.clickable(role=Role.Button,onClick=onClick),contentAlignment=Alignment.Center) {
        Canvas(Modifier.size(22.dp)) {
            val w=size.width;val h=size.height;val stroke=1.8.dp.toPx()
            when(icon) {
                AppActionIcon.Back -> {
                    drawLine(ink,androidx.compose.ui.geometry.Offset(w*.78f,h*.5f),androidx.compose.ui.geometry.Offset(w*.2f,h*.5f),stroke,StrokeCap.Round)
                    drawLine(ink,androidx.compose.ui.geometry.Offset(w*.2f,h*.5f),androidx.compose.ui.geometry.Offset(w*.48f,h*.22f),stroke,StrokeCap.Round)
                    drawLine(ink,androidx.compose.ui.geometry.Offset(w*.2f,h*.5f),androidx.compose.ui.geometry.Offset(w*.48f,h*.78f),stroke,StrokeCap.Round)
                }
                AppActionIcon.Refresh -> {
                    // Almost closed clockwise ring, with its arrowhead tangent to the end.
                    drawArc(ink,-35f,320f,false,androidx.compose.ui.geometry.Offset(w*.15f,h*.15f),androidx.compose.ui.geometry.Size(w*.7f,h*.7f),style=Stroke(stroke,cap=StrokeCap.Round))
                    val tip=androidx.compose.ui.geometry.Offset(w*.591f,h*.162f)
                    drawLine(ink,tip,tip+androidx.compose.ui.geometry.Offset(-w*.09f,h*.18f),stroke,StrokeCap.Round)
                    drawLine(ink,tip,tip+androidx.compose.ui.geometry.Offset(-w*.17f,-h*.075f),stroke,StrokeCap.Round)
                }
                AppActionIcon.Settings -> {
                    val gear=Path()
                    repeat(8) { tooth ->
                        floatArrayOf(0f,.17f,.63f,.8f).forEachIndexed { index, fraction ->
                            val angle=(tooth+fraction)*Math.PI/4-Math.PI/2
                            val radius=if(index==1||index==2) .43f else .34f
                            val x=center.x+kotlin.math.cos(angle).toFloat()*w*radius
                            val y=center.y+kotlin.math.sin(angle).toFloat()*h*radius
                            if(tooth==0&&index==0)gear.moveTo(x,y)else gear.lineTo(x,y)
                        }
                    }
                    gear.close()
                    drawPath(gear,ink,style=Stroke(stroke,join=androidx.compose.ui.graphics.StrokeJoin.Round))
                    drawCircle(ink,w*.135f,style=Stroke(stroke))
                }
            }
        }
    }
}

@Composable
fun AppPageHeader(title:String,subtitle:String?=null,onBack:()->Unit) {
    val compact=androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp<480
    Row(Modifier.fillMaxWidth().padding(bottom=if(compact)6.dp else 18.dp),verticalAlignment=Alignment.CenterVertically) {
        AppIconButton(AppActionIcon.Back,"返回",onBack)
        Column(Modifier.weight(1f).padding(horizontal=8.dp),horizontalAlignment=Alignment.CenterHorizontally) {
            Text(title,fontSize=22.sp,fontWeight=FontWeight.SemiBold)
            if(subtitle!=null)Text(subtitle,fontSize=12.sp,color=MiuixTheme.colorScheme.onBackgroundVariant)
        }
        Spacer(Modifier.size(48.dp))
    }
}
