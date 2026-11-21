package com.dsh.mobile.ui.screen

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.dsh.mobile.core.DownloadLibrary
import com.dsh.mobile.core.DownloadStore
import com.dsh.mobile.core.ResourceLoad
import com.dsh.mobile.ui.AppPageHeader
import com.dsh.mobile.ui.ConfirmationDialog
import com.dsh.mobile.ui.AppBackHandler as BackHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DownloadsScreen(onBack: () -> Unit) {
    val context=LocalContext.current
    val store=remember { DownloadStore.get(context) }
    val scope=rememberCoroutineScope()
    var files by remember { mutableStateOf<List<DownloadStore.File>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(DownloadLibrary.Sort.TIME) }
    var descending by remember { mutableStateOf(true) }
    var age by remember { mutableStateOf(DownloadLibrary.Age.ALL) }
    var size by remember { mutableStateOf(DownloadLibrary.Size.ALL) }
    var choosing by remember { mutableStateOf<String?>(null) }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var confirm by remember { mutableStateOf<List<DownloadStore.File>>(emptyList()) }
    var failedFiles by remember {mutableStateOf<List<DownloadStore.File>>(emptyList())}
    var forgetConfirm by remember {mutableStateOf(false)}
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val compact=androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp<480
    suspend fun refresh() { files=withContext(Dispatchers.IO) { store.list() } }
    LaunchedEffect(Unit) { runCatching { refresh() }.onFailure { message="读取下载记录失败，请返回后重试" } }
    val visible=remember(files,query,sort,descending,age,size) { DownloadLibrary.files(files,query,sort,descending,age,size) }
    LaunchedEffect(visible.map { it.id }) { selected=selected.intersect(visible.map { it.id }.toSet()) }
    val import=rememberLauncherForActivityResult(object : ActivityResultContracts.OpenMultipleDocuments() {
        override fun createIntent(context: android.content.Context, input: Array<String>): Intent {
            val intent=super.createIntent(context,input).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                .putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI,android.net.Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload"))
            // Prefer the system document picker: some vendor file managers return read-only MediaStore URIs.
            for(pkg in listOf("com.google.android.documentsui","com.android.documentsui")) {
                intent.setPackage(pkg)
                val resolved=context.packageManager.resolveActivity(intent,0)
                if(resolved!=null && resolved.activityInfo.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0) return intent
            }
            return intent.setPackage(null)
        }
    }) { uris ->
        if(uris.isNotEmpty()) scope.launch {
            busy=true
            var count=0; var failed=0
            try {
                withContext(Dispatchers.IO) { uris.forEach { uri ->
                    runCatching {
                        runCatching {context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)}
                            .getOrElse {context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)}
                        if(store.importFile(uri)) count++
                    }.onFailure { failed++ }
                } }
                refresh(); message="已添加 $count 个文件"+(if(failed>0)"，$failed 个文件未获持久读取权限，无法添加" else "（相同来源记录不会重复添加）")
            } finally { busy=false }
        }
    }
    fun back() { if(!busy) { if(selecting) { selecting=false; selected=emptySet() } else onBack() } }
    BackHandler { back() }
    val controls: @Composable () -> Unit = {
            SettingsSearch(query,"搜索文件名或对话") {query=it}
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Button(onClick={choosing="排序"},modifier=Modifier.weight(1f),enabled=!busy) {Text(sort.label,fontSize=13.sp)}
                Button(onClick={descending=!descending},enabled=!busy) {Text(if(descending)"降序 ↓" else "升序 ↑",fontSize=13.sp)}
                Button(onClick={choosing="筛选"},enabled=!busy) {Text("筛选",fontSize=13.sp)}
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Button(onClick={import.launch(arrayOf("*/*"))},modifier=Modifier.weight(1f),enabled=!busy) {Text("添加已有文件",fontSize=13.sp)}
                Button(onClick={selecting=!selecting;selected=emptySet()},enabled=!busy) {Text(if(selecting)"退出多选" else "多选",fontSize=13.sp)}
            }
            Spacer(Modifier.height(8.dp))
            Text("${visible.size} 个文件 · ${ResourceLoad.size(visible.sumOf {it.bytes})} · ${age.label} · ${size.label}",fontSize=12.sp,color=MiuixTheme.colorScheme.onBackgroundVariant)
            if(message!=null) SettingsHint(message!!)
            if(failedFiles.isNotEmpty()) SettingsAction("移除未能删除的记录",enabled=!busy) {forgetConfirm=true}
            if(selecting) {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Button(onClick={selected=if(selected.size==visible.size) emptySet() else visible.map{it.id}.toSet()},enabled=!busy,modifier=Modifier.weight(1f)) {Text("${if(selected.size==visible.size)"取消全选" else "全选当前结果"}",fontSize=12.sp)}
                    Button(onClick={confirm=visible.filter{it.id in selected}},enabled=!busy&&selected.isNotEmpty(),modifier=Modifier.weight(1f)) {Text("删除所选（${selected.size}）",fontSize=12.sp)}
                }
                SettingsHint("已选 ${selected.size} 个 · ${ResourceLoad.size(visible.filter{it.id in selected}.sumOf{it.bytes})}")
            }
    }
    val fileList: @Composable () -> Unit = {
            LazyColumn(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(10.dp),contentPadding=PaddingValues(vertical=12.dp)) {
                if(visible.isEmpty()) item {SettingsHint(if(files.isEmpty())"还没有下载记录。新下载会自动出现在这里；旧版下载可用「添加已有文件」选择加入。" else "没有符合条件的文件，请调整搜索或筛选。")}
                items(visible,key={it.id}) { file ->
                    Card(Modifier.fillMaxWidth().clickable(enabled=!busy) {
                        if(selecting) selected=if(file.id in selected) selected-file.id else selected+file.id
                        else runCatching {store.open(file)}.onFailure {message="文件无法打开，可能已被移动、删除，或没有支持此格式的应用。"}
                    },cornerRadius=20.dp) {
                        Row(Modifier.padding(16.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                            if(selecting) Text(if(file.id in selected)"☑" else "☐",fontSize=22.sp)
                            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                                Text(file.name,fontSize=15.sp,fontWeight=FontWeight.Medium)
                                Text("${ResourceLoad.size(file.bytes)} · ${if(file.time>0)"下载于 "+downloadDate(file.time) else "下载时间未知"}",fontSize=12.sp,color=MiuixTheme.colorScheme.onBackgroundVariant)
                                Text(file.conversation.ifBlank {if(file.session.isNotBlank())"对话 ${file.session.take(12)}" else "未记录对话"},fontSize=12.sp,color=MiuixTheme.colorScheme.onBackgroundVariant)
                                if(file.time==0L) Text("导入于 ${downloadDate(file.added)}",fontSize=12.sp,color=MiuixTheme.colorScheme.onBackgroundVariant)
                            }
                        }
                    }
                }
                item {
                    SettingsHint(if(android.os.Build.VERSION.SDK_INT>=29)"默认保存到手机 Download 文件夹。此处仅管理 Pal 新下载和你主动添加的文件。删除会移除本地文件，不影响电脑原件。" else "Android 8–9 使用系统保存窗口，默认定位 Download。此处仅管理 Pal 新下载和你主动添加的文件。删除会移除本地文件，不影响电脑原件。")
                }
            }    }
    Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background).safeDrawingPadding(),contentAlignment=androidx.compose.ui.Alignment.TopCenter) {
        Column(Modifier.widthIn(max=if(compact)1000.dp else 680.dp).fillMaxSize().padding(horizontal=20.dp,vertical=if(compact)4.dp else 12.dp)) {
            AppPageHeader("已下载文件",onBack={back()})
            if(compact) Row(Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy(20.dp)) {
                Column(Modifier.width(250.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {controls()}
                Box(Modifier.weight(1f).fillMaxHeight()) {fileList()}
            } else {
                controls()
                Box(Modifier.weight(1f)) {fileList()}
            }
        }
    }

    if(choosing!=null) Dialog(onDismissRequest={choosing=null}) {
        Card(cornerRadius=24.dp) {
            Column(Modifier.heightIn(max=520.dp).verticalScroll(rememberScrollState()).padding(16.dp)) {
                Text(choosing!!,fontSize=20.sp,fontWeight=FontWeight.SemiBold)
                if(choosing=="排序") DownloadLibrary.Sort.entries.forEach { value -> SettingsItem(value.label,"",selected=sort==value){sort=value;choosing=null} }
                else {
                    SettingsGroup("下载时间") { DownloadLibrary.Age.entries.forEach { value -> SettingsItem(value.label,"",selected=age==value){age=value} } }
                    SettingsGroup("文件大小") { DownloadLibrary.Size.entries.forEach { value -> SettingsItem(value.label,"",selected=size==value){size=value} } }
                    SettingsAction("重置筛选") {age=DownloadLibrary.Age.ALL;size=DownloadLibrary.Size.ALL}
                    SettingsAction("完成",primary=true) {choosing=null}
                }
            }
        }
    }
    ConfirmationDialog(forgetConfirm,"仅移除记录？","将移除 ${failedFiles.size} 条下载记录，不会删除手机上的文件，也不会释放对应存储空间。文件仍可通过系统文件管理器查找。",confirmText="移除记录",onDismiss={forgetConfirm=false},onConfirm={
        forgetConfirm=false;busy=true
        val targets=failedFiles
        scope.launch {try {withContext(Dispatchers.IO){store.forget(targets)};failedFiles=emptyList();refresh();message="记录已移除，本地文件未做改动"}finally {busy=false}}
    })
    ConfirmationDialog(confirm.isNotEmpty(),"删除所选文件？","将删除手机上的 ${confirm.size} 个文件，共 ${ResourceLoad.size(confirm.sumOf{it.bytes})}。电脑上的原文件保留。本地删除无法撤销。",confirmText="删除文件",onDismiss={confirm=emptyList()},onConfirm={
        val targets=confirm; confirm=emptyList();busy=true
        scope.launch {
            try {
                val failures=withContext(Dispatchers.IO) {targets.filter {!store.delete(it)}}
                failedFiles=failures;val failed=failures.size
                refresh(); selected=emptySet(); message=if(failed==0)"已删除 ${targets.size} 个文件" else "${targets.size-failed} 个已删除，$failed 个未能删除，记录已保留。请用系统文件管理器处理，或仅移除记录。"
            } finally {busy=false}
        }
    })
}
private fun downloadDate(time:Long):String = SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.getDefault()).format(Date(time))
