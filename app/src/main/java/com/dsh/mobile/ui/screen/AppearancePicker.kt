package com.dsh.mobile.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.mobile.core.LocalAppearance
import com.dsh.mobile.core.SettingsStore
import com.dsh.mobile.ui.motion.pressClickable
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

private fun color(hex: String) = Color(android.graphics.Color.parseColor(hex))

@Composable
fun AppearancePicker(settings: SettingsStore, selected: LocalAppearance, onApply: (LocalAppearance) -> Unit) {
    val saved = remember { if(selected.isCustom) selected else settings.customAppearance }
    val channels = remember { saved.id.split(':').drop(1).map { it.toInt() } }
    var hue by rememberSaveable { mutableStateOf(channels[0].toFloat()) }
    var saturation by rememberSaveable { mutableStateOf(channels[1].toFloat()) }
    var lightness by rememberSaveable { mutableStateOf(channels[2].toFloat()) }
    val draft = LocalAppearance.custom(hue.roundToInt(), saturation.roundToInt(), lightness.roundToInt())

    listOf("跟随与深色" to LocalAppearance.entries.filter { !it.isLight },
        "浅色预设" to LocalAppearance.entries.filter { it.isLight }).forEach { (title, presets) ->
        Text(title, fontSize=13.sp, color=MiuixTheme.colorScheme.onBackgroundVariant)
        Spacer(Modifier.height(8.dp))
        presets.forEach { theme ->
            Card(Modifier.fillMaxWidth().padding(bottom=8.dp).pressClickable { onApply(theme) }) {
                Row(Modifier.padding(16.dp), verticalAlignment=Alignment.CenterVertically) {
                    Box(Modifier.size(30.dp).clip(CircleShape).background(color(theme.background))
                        .border(1.dp, MiuixTheme.colorScheme.outline, CircleShape)) {
                        Box(Modifier.align(Alignment.BottomEnd).size(13.dp).clip(CircleShape).background(color(theme.accent)))
                    }
                    Spacer(Modifier.width(14.dp))
                    Text(theme.title, Modifier.weight(1f))
                    if(selected == theme) Text("当前", color=MiuixTheme.colorScheme.primary)
                }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    Text("自定义调色盘", fontWeight=FontWeight.Medium)
    Text("滑动预览，满意后点“应用配色”。仅影响本机，不改变电脑端主题。", fontSize=13.sp,
        color=MiuixTheme.colorScheme.onBackgroundVariant)
    Spacer(Modifier.height(12.dp))
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(color(draft.background)).padding(18.dp)) {
        Text("配色预览", color=color(draft.foreground), fontWeight=FontWeight.Medium)
        Spacer(Modifier.height(12.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(color(draft.surface)).padding(14.dp)) {
            Text("这是一条消息", color=color(LocalAppearance.readableOn(draft.surface)))
            Spacer(Modifier.height(8.dp))
            Text("强调色 · 链接与操作", color=color(draft.accent))
        }
        Spacer(Modifier.height(12.dp))
        Text("按钮文字", color=color(draft.onAccent), modifier=Modifier.clip(RoundedCornerShape(10.dp))
            .background(color(draft.accent)).padding(horizontal=16.dp, vertical=8.dp))
    }
    Spacer(Modifier.height(14.dp))
    Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(Brush.horizontalGradient(
        listOf(Color.Red,Color.Yellow,Color.Green,Color.Cyan,Color.Blue,Color.Magenta,Color.Red))))
    PaletteSlider("色相", "${hue.roundToInt()}°", hue, 0f..360f) { hue=it }
    PaletteSlider("饱和度", "${saturation.roundToInt()}%", saturation, 0f..100f) { saturation=it }
    PaletteSlider("明暗", "${lightness.roundToInt()}%", lightness, 8f..98f) { lightness=it }
    Text("背景 ${draft.background.uppercase()} · 强调色 ${draft.accent.uppercase()}", fontSize=12.sp,
        color=MiuixTheme.colorScheme.onBackgroundVariant)
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Button(onClick={ settings.customAppearance=draft; onApply(draft) }, enabled=selected!=draft, modifier=Modifier.weight(1f)) {
            Text(if(selected==draft) "已应用" else "应用配色")
        }
        Button(onClick={ hue=210f; saturation=35f; lightness=94f }, modifier=Modifier.weight(1f)) { Text("重置调色盘") }
    }
    Spacer(Modifier.height(8.dp))
    Text(if(selected.isCustom) "当前使用自定义配色；滑动后需再次应用。" else "当前使用${selected.title}；自定义配色会单独保留。",
        fontSize=12.sp, color=MiuixTheme.colorScheme.onBackgroundVariant)
}

@Composable
private fun PaletteSlider(label: String, detail: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween) {
        Text(label); Text(detail, color=MiuixTheme.colorScheme.onBackgroundVariant)
    }
    Slider(value=value, onValueChange=onChange, valueRange=range, modifier=Modifier.fillMaxWidth())
}
