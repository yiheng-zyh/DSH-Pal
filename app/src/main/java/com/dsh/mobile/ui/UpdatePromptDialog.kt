package com.dsh.mobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 启动时的版本更新弹窗：正文为更新公告，下方给「GitHub 下载 / 蓝奏云下载 / 提 Issue / 取消」。
 * 任一通道不可用时把该按钮置灰，不隐藏（让用户知道本版只有另一条通道）。
 */
@Composable
fun UpdatePromptDialog(
    show: Boolean,
    version: String,
    notes: String,
    canGithub: Boolean,
    canLanzou: Boolean,
    message: String,
    onGithub: () -> Unit,
    onLanzou: () -> Unit,
    onIssue: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!show) return
    val screenHeight = LocalConfiguration.current.screenHeightDp
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Card(Modifier.padding(20.dp).widthIn(max = 460.dp).fillMaxWidth(), cornerRadius = 28.dp) {
            Column(Modifier.heightIn(max = (screenHeight - 56).coerceAtLeast(200).dp).padding(24.dp)) {
                Text("发现新版本 $version", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text("当前版本低于最新发布版", fontSize = 12.sp, color = MiuixTheme.colorScheme.onBackgroundVariant)
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier.weight(1f, fill = false)
                        .heightIn(max = (screenHeight * .52f).dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        notes.ifBlank { "新版本内容请见发布页。" },
                        fontSize = 14.sp,
                        lineHeight = 21.sp,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                    )
                }
                if (message.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(message, fontSize = 12.sp, color = MiuixTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onGithub,
                        enabled = canGithub,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) { Text("GitHub 下载", fontSize = 14.sp) }
                    Button(
                        onClick = onLanzou,
                        enabled = canLanzou,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) { Text("蓝奏云下载", fontSize = 14.sp) }
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onIssue, modifier = Modifier.weight(1f)) { Text("提 Issue", fontSize = 14.sp) }
                    Button(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("取消", fontSize = 14.sp) }
                }
            }
        }
    }
}
