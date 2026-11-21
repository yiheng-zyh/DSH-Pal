package com.dsh.mobile.ui.screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.mobile.ui.motion.pressClickable
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SettingsSearch(value: String, hintText: String = "搜索设置", onChange: (String) -> Unit) {
    val scheme = MiuixTheme.colorScheme
    val hint = settingsSecondary()
    BasicTextField(
        value = value, onValueChange = onChange, singleLine = true,
        textStyle = TextStyle(color = scheme.onBackground, fontSize = 15.sp),
        cursorBrush = SolidColor(scheme.onBackground),
        modifier = Modifier.fillMaxWidth().padding(bottom = if(androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp<480)8.dp else 22.dp).semantics { contentDescription = hintText },
        decorationBox = { input ->
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
                .background(lerp(scheme.background, scheme.onBackground, .09f))) {
                Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Canvas(Modifier.size(18.dp)) {
                        val stroke = 1.7.dp.toPx()
                        drawCircle(hint, size.width * .28f, Offset(size.width * .43f, size.height * .43f), style = Stroke(stroke))
                        drawLine(hint, Offset(size.width * .64f, size.height * .64f), Offset(size.width * .88f, size.height * .88f), stroke, StrokeCap.Round)
                    }
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty()) Text(hintText, color = hint, fontSize = 15.sp)
                        input()
                    }
                }
            }
        },
    )
}

@Composable
internal fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    val scheme = MiuixTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, Modifier.padding(start = 12.dp).semantics { heading() },
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = settingsSecondary())
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(lerp(scheme.background, scheme.onBackground, .055f))) { content() }
    }
}

@Composable
internal fun SettingsItem(title: String, detail: String, selected: Boolean? = null, onClick: () -> Unit) {
    val action = if (selected == null) Modifier.pressClickable(onClick = onClick)
        else Modifier.selectable(selected, role = androidx.compose.ui.semantics.Role.RadioButton, onClick = onClick)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 62.dp).then(action).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium)
            SettingsHint(detail)
        }
        val ink = settingsSecondary()
        Canvas(Modifier.size(16.dp)) {
            if (selected != null) {
                drawCircle(ink, size.width * .44f, style = Stroke(1.6.dp.toPx()))
                if (selected) drawCircle(ink, size.width * .25f)
            } else {
                val tip = Offset(size.width * .64f, size.height * .5f)
                drawLine(ink, Offset(size.width * .4f, size.height * .27f), tip, 1.6.dp.toPx(), StrokeCap.Round)
                drawLine(ink, tip, Offset(size.width * .4f, size.height * .73f), 1.6.dp.toPx(), StrokeCap.Round)
            }
        }
    }
}

@Composable
internal fun SettingsDivider() {
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp)
        .background(MiuixTheme.colorScheme.onBackground.copy(alpha = .07f)))
}

@Composable
internal fun SettingsHint(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, fontSize = 13.sp, lineHeight = 19.sp, color = settingsSecondary())
}

@Composable
private fun settingsSecondary() = MiuixTheme.colorScheme.let { lerp(it.background, it.onBackground, .72f) }

@Composable
internal fun SettingsAction(label: String, enabled: Boolean = true, primary: Boolean = false, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        colors = if (primary) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors(),
    ) { Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium) }
}
