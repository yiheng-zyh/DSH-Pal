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

/**
 * 更新与公告。
 *
 * 版面顺序（2026-11-25 按作者要求调整）：**更新动作在最前，公告在后**。
 * 之前把开关、公告排在上面，用户要翻过一堆说明才找得到「下载」，
 * 而且下载只有一个默认通道（蓝奏云优先），GitHub 根本没有显式入口。
 * 现在顶部卡里并排给「GitHub 下载 / 蓝奏云下载」两个通道，各自不可用时单独置灰。
 */
@Composable
fun UpdatesScreen(onBack:()->Unit){
    val context=LocalContext.current
    val updates=remember{AppUpdates.get(context)}
    val state by updates.state.collectAsState()
    val scope=rememberCoroutineScope()
    var installing by remember{mutableStateOf(false)}
    var actionMessage by remember{mutableStateOf("")}
    // 记住用户点了哪个通道，确认弹窗里按这个通道下载（不再隐式走 Auto）。
    var confirmChannel by remember{mutableStateOf<AppUpdates.Channel?>(null)}
    // 优先用远程更新说明（改文案不必重新打包），拉不到时回退到 APK 内置文本。
    val bundledNotes=remember{runCatching{context.assets.open("app-release-notes.txt").bufferedReader().use{it.readText()}}.getOrDefault("")}
    val localNotes=state.releaseNotes?.takeIf{it.isNotBlank()} ?: bundledNotes
    AppBackHandler{onBack()}
    fun open(url:String){runCatching{context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)))}.onFailure{actionMessage="无法打开浏览器，请稍后重试"}}
    val release=state.release
    Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background).safeDrawingPadding(),contentAlignment=Alignment.TopCenter){
        Column(Modifier.widthIn(max=840.dp).fillMaxSize().padding(20.dp)){
            AppPageHeader("更新与公告",onBack=onBack)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp)){
                // ① 版本与更新动作 —— 放在最上面，进页面第一眼就能点到
                Card{Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
                    Text("当前版本 ${BuildConfig.VERSION_NAME}",fontSize=18.sp,fontWeight=FontWeight.Medium)
                    Text(state.message,fontSize=14.sp)
                    Button(onClick={updates.check()},enabled=!state.checking&&!state.downloading){Text(if(state.checking)"正在检查…"else "检查更新")}
                    when{
                        state.downloading->{
                            val total=release?.apkSize?:0L
                            val percent=if(total>0)(state.downloaded*100/total).coerceIn(0,100)else 0
                            Text("下载 $percent% · ${ResourceLoad.size(state.downloaded)} / ${ResourceLoad.size(total)}",fontSize=13.sp)
                            Button(onClick={updates.cancelDownload()}){Text("取消下载")}
                        }
                        state.readyFile!=null&&release!=null->Button(onClick={
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
                        release!=null&&(release.downloadable||release.hasLanzou)->{
                            // 两个通道并列显示；各自没准备好就单独置灰，不隐藏，
                            // 这样用户能看出「这版只有蓝奏云」还是「只有 GitHub」。
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)){
                                Button(
                                    onClick={confirmChannel=AppUpdates.Channel.Github},
                                    enabled=release.downloadable,
                                    modifier=Modifier.weight(1f),
                                    colors=ButtonDefaults.buttonColorsPrimary(),
                                ){Text("GitHub 下载",fontSize=14.sp)}
                                Button(
                                    onClick={confirmChannel=AppUpdates.Channel.Lanzou},
                                    enabled=release.hasLanzou,
                                    modifier=Modifier.weight(1f),
                                    colors=ButtonDefaults.buttonColorsPrimary(),
                                ){Text("蓝奏云下载",fontSize=14.sp)}
                            }
                            Text("国内网络建议走蓝奏云；两条通道都会校验大小与 SHA-256。",fontSize=12.sp,color=MiuixTheme.colorScheme.onBackgroundVariant)
                        }
                        release!=null->Text("此发布缺少唯一正式安装包或完整校验信息，请从发布页查看附件。",fontSize=12.sp)
                    }
                    if(state.downloadMessage.isNotBlank())Text(state.downloadMessage,fontSize=12.sp)
                    Button(onClick={open(AppLinks.RELEASES)}){Text("打开 GitHub 发布页")}
                    Text("检查只获取版本与公告信息，不会自动下载安装包。",fontSize=12.sp,color=MiuixTheme.colorScheme.onBackgroundVariant)
                }}
                // ② 有新版时的版本详情
                if(release!=null){
                    Card{Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
                        Text(release.title,fontSize=18.sp,fontWeight=FontWeight.Medium)
                        Text("版本 ${release.version}"+(release.publishedAt.takeIf{it.isNotBlank()}?.let{" · ${it.take(10)}"}?:""),fontSize=12.sp)
                        Text(release.notes.ifBlank{"此版本未提供更新说明。"},fontSize=13.sp)
                        Button(onClick={open(release.pageUrl)}){Text("查看此版本发布页")}
                    }}
                }
                if(actionMessage.isNotBlank())Text(actionMessage,fontSize=13.sp)
                // ③ 本版本说明
                Card{Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
                    Text("本版本更新说明",fontSize=17.sp,fontWeight=FontWeight.Medium)
                    Text(localNotes,fontSize=13.sp)
                }}
                // ④ 公告 —— 排在更新动作之后
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
                // ⑤ 检查设置 —— 配置项放最后，不挡更新入口
                Card{Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                    Text("更新设置",fontSize=17.sp,fontWeight=FontWeight.Medium)
                    UpdateToggle("自动检查更新",state.automatic){updates.configure(automatic=it)}
                    UpdateToggle("接收开发预览版本",state.previews){updates.configure(previews=it)}
                    UpdateToggle("更新与公告弹窗",state.popups){updates.configure(popups=it)}
                    Text("自动检查最多每天一次；连接或任务进行时延后提示。公告关闭弹窗后仍可在此查看。",fontSize=12.sp,color=MiuixTheme.colorScheme.onBackgroundVariant)
                }}
            }
        }
    }
    val channel=confirmChannel
    com.dsh.mobile.ui.ConfirmationDialog(channel!=null,"下载更新？","安装包约 ${ResourceLoad.size(release?.apkSize?:0)}，将使用当前网络从${if(channel==AppUpdates.Channel.Lanzou)"蓝奏云"else "GitHub"}下载。下载完成后由你确认安装，现有连接和数据会保留。",confirmText="开始下载",onDismiss={confirmChannel=null},onConfirm={val c=confirmChannel;confirmChannel=null;if(c!=null)updates.download(c)})
}

@Composable private fun UpdateToggle(title:String,checked:Boolean,onChange:(Boolean)->Unit){
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(title,modifier=Modifier.weight(1f),fontSize=14.sp);Switch(checked=checked,onCheckedChange=onChange)}
}
