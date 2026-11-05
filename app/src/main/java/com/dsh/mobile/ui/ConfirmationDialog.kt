package com.dsh.mobile.ui

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Shared native confirmation surface for settings and page actions. */
@Composable
fun ConfirmationDialog(show: Boolean, title: String, message: String, cancelText: String = "取消", confirmText: String,
                       onDismiss: () -> Unit, onConfirm: () -> Unit) {
    if (!show) return
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Card(Modifier.padding(20.dp).widthIn(max = 420.dp).fillMaxWidth(), cornerRadius = 28.dp) {
            Column(Modifier.heightIn(max=(androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp-48).coerceAtLeast(160).dp).verticalScroll(rememberScrollState()).padding(24.dp)) {
                Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                Text(message, fontSize = 14.sp, color = MiuixTheme.colorScheme.onBackgroundVariant)
                Spacer(Modifier.height(24.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text(cancelText) }
                    Button(onClick = onConfirm, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColorsPrimary()) { Text(confirmText) }
                }
            }
        }
    }
}
