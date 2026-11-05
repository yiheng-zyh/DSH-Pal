package com.dsh.mobile.ui

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dsh.mobile.BuildConfig
import com.dsh.mobile.core.AppAnnouncement
import com.dsh.mobile.core.NetworkConnectivity
import com.dsh.mobile.update.AppUpdates

/** At most one notice per root composition; never interrupt active work. */
@Composable fun UpdateNoticeHost(readyForNotice:Boolean){
    val context=LocalContext.current
    val updates=remember{AppUpdates.get(context)}
    val state by updates.state.collectAsState()
    val visible by NetworkConnectivity.visible.collectAsState()
    val network by NetworkConnectivity.state.collectAsState()
    var settled by remember{mutableStateOf(false)}
    var presented by remember{mutableStateOf(false)}
    var openUpdates by remember{mutableStateOf(false)}
    var notice by remember{mutableStateOf<AppAnnouncement?>(null)}
    val localNotes=remember{context.assets.open("app-release-notes.txt").bufferedReader().use{it.readText()}}
    LaunchedEffect(visible,network.available){if(visible&&network.available){kotlinx.coroutines.delay(10000);updates.check(manual=false)}}
    LaunchedEffect(readyForNotice,visible){settled=false;if(readyForNotice&&visible){kotlinx.coroutines.delay(8000);settled=true}}
    LaunchedEffect(settled,state.release,state.announcements,state.popups){
        if(!settled||!state.popups||presented||state.downloading)return@LaunchedEffect
        val release=state.release
        val candidate=if(release!=null&&!updates.seen("release-${release.id}"))AppAnnouncement("release-${release.id}","发现新版本 ${release.version}",release.notes.ifBlank{"可以前往更新页下载新版本。"},release.publishedAt)
        else state.announcements.firstOrNull{!updates.seen("announcement-${it.id}")}?.let{it.copy(id="announcement-${it.id}")}
            ?:AppAnnouncement("installed-${BuildConfig.VERSION_CODE}","本版本更新说明",localNotes,"").takeIf{!updates.seen(it.id)}
        if(candidate!=null){notice=candidate;presented=true}
    }
    if(settled)ConfirmationDialog(notice!=null,notice?.title.orEmpty(),notice?.body.orEmpty().take(2000),cancelText="稍后",confirmText="查看详情",
        onDismiss={notice?.let{updates.markSeen(it.id)};notice=null},onConfirm={notice?.let{updates.markSeen(it.id)};notice=null;openUpdates=true})
    if(openUpdates)Dialog(onDismissRequest={openUpdates=false},properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)){
        com.dsh.mobile.ui.screen.UpdatesScreen{openUpdates=false}
    }
}
