package com.dsh.mobile.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dsh.mobile.BuildConfig
import com.dsh.mobile.core.AppAnnouncement
import com.dsh.mobile.core.AppLinks
import com.dsh.mobile.core.NetworkConnectivity
import com.dsh.mobile.update.AppUpdates

/**
 * 启动时的更新提示。
 *
 * 行为（2026-11-16 按作者要求调整）：
 *  - 进入 App 就**主动检查一次**（不再等 10 秒、也不受「自动检查」开关限制；
 *    那条开关现在只影响后续的后台轮询）。
 *  - 有新版本 → 弹更新窗口，正文是更新公告，下方四个按钮：
 *    GitHub 下载 / 蓝奏云下载 / 提 Issue / 取消。
 *  - 无新版本 → 弹本版本的更新说明（同一份三栏公告），按钮为「提 Issue / 知道了」。
 *  - 同一版本只弹一次（按 versionCode 记忆），避免每次冷启动都打扰。
 */
@Composable fun UpdateNoticeHost(readyForNotice:Boolean){
    val context=LocalContext.current
    val updates=remember{AppUpdates.get(context)}
    val state by updates.state.collectAsState()
    val visible by NetworkConnectivity.visible.collectAsState()
    val network by NetworkConnectivity.state.collectAsState()
    var settled by remember{mutableStateOf(false)}
    var presented by remember{mutableStateOf(false)}
    var openUpdates by remember{mutableStateOf(false)}

    // 更新说明：优先远程（改文案不必重新打包），拉不到回退 APK 内置。
    val bundledNotes=remember{runCatching{context.assets.open("app-release-notes.txt").bufferedReader().use{it.readText()}}.getOrDefault("")}
    val notes=state.releaseNotes?.takeIf{it.isNotBlank()} ?: bundledNotes

    // 启动即检查一次；网络可用后再补一次（防止冷启动时还没网）。
    LaunchedEffect(Unit){updates.check(manual=true)}
    LaunchedEffect(visible,network.available){
        if(visible&&network.available&&state.checkedAt==0L)updates.check(manual=true)
    }
    LaunchedEffect(readyForNotice,visible){
        settled=false
        if(readyForNotice&&visible){kotlinx.coroutines.delay(1200);settled=true}
    }

    // state.release 由 AppRelease.latest() 产出，本身已是「比当前版本新」的结果；
    // 为空即表示没有更新，此时弹窗展示本版本的更新说明。
    val release=state.release
    val promptKey="prompt-${BuildConfig.VERSION_CODE}-${release?.version ?: "installed"}"
    LaunchedEffect(settled,release,state.popups){
        if(!settled||presented)return@LaunchedEffect
        if(!state.popups)return@LaunchedEffect
        if(updates.seen(promptKey))return@LaunchedEffect
        presented=true
    }

    val showPrompt=settled&&presented&&!updates.seen(promptKey)&&!state.downloading

    if(showPrompt){
        val available=release
        UpdatePromptDialog(
            show=true,
            version=available?.version ?: BuildConfig.VERSION_NAME,
            notes=available?.notes?.ifBlank{notes} ?: notes,
            canGithub=available?.downloadable==true,
            canLanzou=available?.hasLanzou==true,
            message=if(state.downloading) state.downloadMessage else "",
            onGithub={updates.markSeen(promptKey);updates.download(AppUpdates.Channel.Github);openUpdates=true},
            onLanzou={updates.markSeen(promptKey);updates.download(AppUpdates.Channel.Lanzou);openUpdates=true},
            onIssue={
                updates.markSeen(promptKey)
                runCatching{context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("${AppLinks.PROJECT}/issues/new")))}
            },
            onDismiss={updates.markSeen(promptKey)},
        )
    }

    if(openUpdates)Dialog(onDismissRequest={openUpdates=false},properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)){
        com.dsh.mobile.ui.screen.UpdatesScreen{openUpdates=false}
    }
}
