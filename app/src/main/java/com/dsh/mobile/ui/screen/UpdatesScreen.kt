package com.dsh.mobile.ui.screen

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.mobile.BuildConfig
import com.dsh.mobile.core.AppLinks
import com.dsh.mobile.core.ResourceLoad
import com.dsh.mobile.update.AppUpdates
import com.dsh.mobile.update.UpdateInstaller
import com.dsh.mobile.ui.AppBackHandler
import com.dsh.mobile.ui.AppPageHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun UpdatesScreen(onBack:()->Unit){
    val context=LocalContext.current
    val updates=remember{AppUpdates.get(context)}
    val state by updates.state.collectAsState()
    val scope=rememberCoroutineScope()
    var installing by remember{mutableStateOf(false)}
    var actionMessage by remember{mutableStateOf("")}
    var confirmDownload by remember{mutableStateOf(false)}
    val localNotes=remember{context.assets.open("app-release-notes.txt").bufferedReader().use{it.readText()}}
    AppBackHandler{onBack()}
    fun open(url:String){runCatching{context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)))}.onFailure{actionMessage="无法打开浏览器，请稍后重试"}}
    Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background).safeDrawingPadding(),contentAlignment=Alignment.TopCenter){
        Column(Modifier.widthIn(max=840.dp).fillMaxSize().padding(20.dp)){
            AppPageHeader("更新与公告",onBack=onBack)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp)){
                Card{Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
                    Text("当前版本 ${BuildConfig.VERSION_NAME}",fontSize=18.sp,fontWeight=FontWeight.Medium)
                    Text(state.message,fontSize=14.sp)
                    Button(onClick={updates.check()},enabled=!state.checking&&!state.downloading){Text(if(state.checking)"正在检查…"else "检查更新")}
                    Button(onClick={open(AppLinks.RELEASES)}){Text("打开 GitHub 发布页")}
                    Text("检查只获取版本与公告信息，不会自动下载安装包。",fontSize=12.sp,color=MiuixTheme.colorScheme.onBackgroundVariant)
                }}
                Card{Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                    UpdateToggle("自动检查更新",state.automatic){updates.configure(automatic=it)}
                    UpdateToggle("接收开发预览版本",state.previews){updates.configure(previews=it)}
                    UpdateToggle("更新与公告弹窗",state.popups){updates.configure(popups=it)}
                    Text("自动检查最多每天一次；连接或任务进行时延后提示。公告关闭弹窗后仍可在此查看。",fontSize=12.sp,color=MiuixTheme.colorScheme.onBackgroundVariant)
                }}
                state.release?.let{release->
                    Card{Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
                        Text(release.title,fontSize=18.sp,fontWeight=FontWeight.Medium)
                        Text("版本 ${release.version}"+(release.publishedAt.takeIf{it.isNotBlank()}?.let{" · ${it.take(10)}"}?:""),fontSize=12.sp)
                        Text(release.notes.ifBlank{"此版本未提供更新说明。"},fontSize=13.sp)
                        if(state.downloading){
                            val percent=if(release.apkSize>0)(state.downloaded*100/release.apkSize).coerceIn(0,100)else 0
                            Text("下载 $percent% · ${ResourceLoad.size(state.downloaded)} / ${ResourceLoad.size(release.apkSize)}")
                            Button(onClick={updates.cancelDownload()}){Text("取消下载")}
                        }else if(state.readyFile!=null){
                            Button(onClick={
                                if(!installing){
                                    installing=true
                                    scope.launch{
                                        try{
                                            val file=state.readyFile?:return@launch
                                            withContext(Dispatchers.IO){UpdateInstaller.verify(context,file,release)}
                                            actionMessage=UpdateInstaller.install(context,file).orEmpty()
                                        }catch(e:Exception){actionMessage=e.message?.take(160)?:"无法打开系统安装，请重试";if(e is IllegalStateException)updates.invalidateDownloaded(actionMessage)}
                                        finally{installing=false}
                                    }
                                }
                            },enabled=!installing){Text(if(installing)"正在校验…"else "安装更新")}
                        }else if(release.downloadable){
                            Button(onClick={confirmDownload=true}){Text("下载更新 · ${ResourceLoad.size(release.apkSize)}")}
                        }else Text("此发布缺少唯一正式安装包或完整校验信息，请从发布页查看附件。",fontSize=12.sp)
                        if(state.downloadMessage.isNotBlank())Text(state.downloadMessage,fontSize=12.sp)
                        Button(onClick={open(release.pageUrl)}){Text("查看此版本发布页")}
                    }}
                }
                if(actionMessage.isNotBlank())Text(actionMessage,fontSize=13.sp)
                Card{Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
                    Text("本版本更新说明",fontSize=17.sp,fontWeight=FontWeight.Medium)
                    Text(localNotes,fontSize=13.sp)
                }}
                Text("公告",fontSize=18.sp,fontWeight=FontWeight.Medium)
                if(state.announcementMessage.isNotBlank())Text(state.announcementMessage,fontSize=12.sp,color=MiuixTheme.colorScheme.onBackgroundVariant)
                if(state.announcements.isEmpty())Text("暂无远程公告。",fontSize=13.sp)
                state.announcements.forEach{notice->
                    Card{Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                        Text(notice.title,fontWeight=FontWeight.Medium);Text(notice.body,fontSize=13.sp)
                        Text(if(updates.seen("announcement-${notice.id}"))"已读"else "未读",fontSize=12.sp)
                        if(!updates.seen("announcement-${notice.id}"))Button(onClick={updates.markSeen("announcement-${notice.id}");actionMessage="公告已标为已读"}){Text("标为已读")}
                    }}
                }
            }
        }
    }
    com.dsh.mobile.ui.ConfirmationDialog(confirmDownload,"下载更新？","安装包约 ${ResourceLoad.size(state.release?.apkSize?:0)}，将使用当前网络下载。下载完成后由你确认安装，现有连接和数据会保留。",confirmText="开始下载",onDismiss={confirmDownload=false},onConfirm={confirmDownload=false;updates.download()})
}

@Composable private fun UpdateToggle(title:String,checked:Boolean,onChange:(Boolean)->Unit){
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(title,modifier=Modifier.weight(1f),fontSize=14.sp);Switch(checked=checked,onCheckedChange=onChange)}
}
