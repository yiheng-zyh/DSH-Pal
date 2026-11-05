package com.dsh.mobile.island

import android.content.Context
import android.util.Log
import com.dsh.mobile.core.TaskState

private var debugMedia: MediaIsland? = null
private var noticePreviewGeneration = 0
fun clearDebugMedia(context: Context) {
    debugMedia?.release(); debugMedia = null
    context.getSystemService(android.app.NotificationManager::class.java)?.cancel(MediaIsland.NOTIFICATION_ID)
}

/**
 * Post a sample island notification and report exactly what happened.
 *
 * Shared by the in-app diagnostics button and the adb-driven activity extra, so
 * there is one code path to trust and one verdict format to read.
 *
 * Returns a short Chinese sentence suitable for display: the app shows it verbatim
 * in diagnostics, and the adb path writes it to logcat.
 */
fun postDebugIsland(context: Context, phase: String = "running"): String {
    val nm = context.getSystemService(android.app.NotificationManager::class.java)
        ?: return "失败：拿不到 NotificationManager"

    if (!nm.areNotificationsEnabled()) {
        return "失败：通知总开关被关闭（设置 → 通知与控制中心 → DSH）"
    }

    val progressPreview = phase == "progress" || phase == "result"
    val previewToken = ++noticePreviewGeneration
    val state = when (phase) {
        "progress", "result" -> TaskState(
            title = "DSH 通知展示预览",
            phase = if(phase == "result") TaskState.Phase.Done else TaskState.Phase.Running,
            detail = "检查素材来源",
            hasPublicOutput = true,
            noticeLines = listOf(
                TaskState.NoticeLine("我先查清当前有什么能力，再给方案。"),
                TaskState.NoticeLine("运行命令 · 检查音视频工具链", true),
                TaskState.NoticeLine("运行命令 · 检查 Python 视频库", true),
                TaskState.NoticeLine("网络全通。我查一下视频素材最好的来源。"),
                TaskState.NoticeLine("运行命令 · 检查素材来源", true),
            ),
        )
        "waiting" -> TaskState(
            title = "DSH 测试任务",
            phase = TaskState.Phase.Waiting,
            detail = "等待确认",
            step = 2,
            stepTotal = 5,
            approval = TaskState.Approval(id = "debug", prompt = "允许执行这条命令？"),
            startedAt = System.currentTimeMillis(),
        )

        "done" -> TaskState(
            title = "DSH 测试任务",
            phase = TaskState.Phase.Done,
            detail = "已完成",
            step = 5,
            stepTotal = 5,
            startedAt = System.currentTimeMillis(),
        )

        else -> TaskState(
            title = "DSH 测试任务",
            phase = TaskState.Phase.Running,
            detail = "调用 命令",
            step = 2,
            stepTotal = 5,
            todosDone = 2,
            todosTotal = 5,
            startedAt = System.currentTimeMillis(),
        )
    }

    val settings = com.dsh.mobile.core.SettingsStore(context)
    val builder = TaskNotificationBuilder(context)
    builder.ensureChannel()

    // Also exercise the media-island channel: on a sideloaded build the focus
    // protocol is refused by the ROM (FocusPlugin: onAuthFailed), so MediaStyle is
    // the only island the diagnostics entry can actually demonstrate.
    if (!progressPreview) runCatching {
        if (settings.islandEnabled) {
        val media = debugMedia ?: MediaIsland(context).also { debugMedia = it }
        nm.notify(MediaIsland.NOTIFICATION_ID, media.build(state))
        Log.i("DshDebugIsland", "media island posted id=${MediaIsland.NOTIFICATION_ID}")
        } else clearDebugMedia(context)
    }.onFailure { Log.w("DshDebugIsland", "media island failed", it) }

    return try {
        if (settings.notificationEnabled) nm.notify(TaskNotificationBuilder.NOTIFICATION_ID, builder.build(state))
        else return "通知栏进度已关闭；演示遵循显示设置"

        // Presentation previews do not replace live task state or the media
        // session. Restore the newest real state, never a captured stale one.
        if(progressPreview) android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if(previewToken != noticePreviewGeneration) return@postDelayed
            val current = com.dsh.mobile.service.DshLiveService.current()?.state?.value
            if(settings.notificationEnabled && current != null) nm.notify(TaskNotificationBuilder.NOTIFICATION_ID,builder.build(current))
            else nm.cancel(TaskNotificationBuilder.NOTIFICATION_ID)
        }, 60_000)

        // Ask the OS whether the record exists: notify() returns normally even when
        // the notification is suppressed, so this is the only real signal.
        val landed = nm.activeNotifications.any {
            it.id == TaskNotificationBuilder.NOTIFICATION_ID
        }
        val verdict = if (landed) {
            "已发出（id=${TaskNotificationBuilder.NOTIFICATION_ID}, phase=$phase）"
        } else {
            "被系统丢弃：已提交但不在活动列表中"
        }
        Log.i("DshDebugIsland", verdict)
        verdict
    } catch (t: Throwable) {
        val verdict = "失败：${t.javaClass.simpleName}: ${t.message}"
        Log.e("DshDebugIsland", verdict, t)
        verdict
    }
}
