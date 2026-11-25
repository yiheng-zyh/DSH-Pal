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
 * 启动时的版本弹窗，两种用途共用一个界面：
 *  - 有新版：标题「发现新版本 X」，副标题「当前版本低于最新发布版」，下方给「GitHub 下载 / 蓝奏云下载 / 提 Issue / 取消」。
 *  - 无新版（展示本版说明）：标题「本次更新说明」，副标题为已装版本号，不给下载入口，只留「提 Issue / 知道了」。
 * 任一通道不可用时把该按钮置灰，不隐藏（让用户知道本版只有另一条通道）。
 */
@Composable
fun UpdatePromptDialog(
    show: Boolean,
    version: String,
    notes: String,
    hasUpdate: Boolean,
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
                Text(
                    if (hasUpdate) "发现新版本 $version" else "本次更新说明",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (hasUpdate) "当前版本低于最新发布版" else "已安装 $version，以下是这一版改了什么",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                )
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
                if (hasUpdate) {
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
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onIssue, modifier = Modifier.weight(1f)) { Text("提 Issue", fontSize = 14.sp) }
                    Button(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                        Text(if (hasUpdate) "取消" else "知道了", fontSize = 14.sp)
                    }
                }
            }
        }
    }
}
