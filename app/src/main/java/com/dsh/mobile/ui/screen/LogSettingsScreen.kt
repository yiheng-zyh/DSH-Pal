package com.dsh.mobile.ui.screen

import android.webkit.WebView
import com.dsh.mobile.ui.AppBackHandler as BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import com.dsh.mobile.core.*
import com.dsh.mobile.ui.AppPageHeader
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun LogSettingsScreen(onBack:()->Unit) {
    var viewing by remember {mutableStateOf(false)}
    var filter by remember {mutableStateOf<LogCategory?>(null)}
    var clear by remember {mutableStateOf(false)}
    var policyRevision by remember {mutableIntStateOf(0)}
    val revision=if(viewing)RunLog.revision.intValue else 0
    val recording=remember(policyRevision){RunLog.enabled}
    val scope=rememberCoroutineScope()
    val context=androidx.compose.ui.platform.LocalContext.current
    var message by remember {mutableStateOf<String?>(null)}
    BackHandler {if(viewing)viewing=false else onBack()}
    Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background).safeDrawingPadding(),contentAlignment=androidx.compose.ui.Alignment.TopCenter) {
    Column(Modifier.widthIn(max=840.dp).fillMaxSize().padding(20.dp)) {
        AppPageHeader(if(viewing)"运行日志" else "日志记录",onBack={if(viewing)viewing=false else onBack()})
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(!viewing) {
                Card {Row(Modifier.fillMaxWidth().padding(16.dp)) {
                    Column(Modifier.weight(1f)){Text("记录诊断日志");Text("关闭后停止新增记录，已有日志保留",fontSize=12.sp,color=MiuixTheme.colorScheme.onBackgroundVariant)}
                    Switch(checked=recording,onCheckedChange={RunLog.enabled=it;policyRevision++})
                }}
                Text("记录分类",fontWeight=FontWeight.Medium)
                LogCategory.entries.forEach {category->Card {Row(Modifier.fillMaxWidth().padding(16.dp)) {
                    Column(Modifier.weight(1f).padding(end=12.dp)){Text(category.title,fontSize=15.sp);Text(category.purpose,fontSize=12.sp,color=MiuixTheme.colorScheme.onBackgroundVariant)}
                    Switch(checked=remember(category,policyRevision){RunLog.categoryEnabled(category)},onCheckedChange={RunLog.setCategoryEnabled(category,it);policyRevision++},enabled=recording)
                }}}
                Card {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("循环保留",fontWeight=FontWeight.Medium)
                    Text("内存最近 400 条 · 每条最多 2048 字符",fontSize=12.sp)
                    Text("本次 / 上次运行各 128 KiB，满额覆盖最旧内容。导出文件最多 512 KiB，每次覆盖。",fontSize=12.sp,color=MiuixTheme.colorScheme.onBackgroundVariant)
                    Text("日志仅保存在手机，导出前自动隐藏配对凭据。",fontSize=12.sp,color=MiuixTheme.colorScheme.onBackgroundVariant)
                }}
                Button(onClick={viewing=true},modifier=Modifier.fillMaxWidth()){Text("查看运行日志")}
            } else {
                val entries=remember(revision,filter){RunLog.snapshot().filter {filter==null||LogCategory.forTag(it.tag)==filter}}
                Text("${entries.size} 条 · 仅显示已启用分类产生的记录",fontSize=12.sp,color=MiuixTheme.colorScheme.onBackgroundVariant)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Button(onClick={filter=null}){Text("全部",fontSize=12.sp)}
                    Button(onClick={filter=LogCategory.entries[(filter?.ordinal?.plus(1)?:0)%LogCategory.entries.size]}){Text(filter?.title?:"按分类查看",fontSize=12.sp)}
                }
                Card {Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    if(entries.isEmpty())Text("暂无记录",fontSize=13.sp)
                    entries.takeLast(120).forEach {entry->Text("+${entry.offsetMs/1000}s ${entry.tag} · ${entry.message}",fontSize=11.sp,
                        color=if(entry.level==RunLog.Entry.Level.ERROR)com.dsh.mobile.ui.theme.LocalDshAccents.current.error else MiuixTheme.colorScheme.onBackgroundVariant)}
                }}
            }
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Button(onClick={com.dsh.mobile.ui.shareDiagnostics(context,scope){message=it}}){Text("导出日志")}
                Button(onClick={clear=true}){Text("清除记录")}
            }
            message?.let {Text(it,fontSize=12.sp)}
        }
    }
    com.dsh.mobile.ui.ConfirmationDialog(clear,"清除诊断记录？","清除本次和上次运行的日志，不影响连接、账号和主题。",confirmText="清除记录",onDismiss={clear=false},onConfirm={clear=false;RunLog.clear()})
    }
}
