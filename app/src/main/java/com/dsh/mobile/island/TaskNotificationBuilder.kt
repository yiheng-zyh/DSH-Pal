package com.dsh.mobile.island

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import com.dsh.mobile.MainActivity
import com.dsh.mobile.R
import com.dsh.mobile.core.TaskState
import com.dsh.mobile.core.TaskNotice
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.ForegroundColorSpan

/** Standard Android notification; island rendering belongs exclusively to MediaIsland. */
class TaskNotificationBuilder(private val context: Context) {
    fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL_ID, "任务进度与回复", NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) })
        }
    }

    fun build(state: TaskState): Notification {
        ensureChannel()
        val open = PendingIntent.getActivity(context, 1201,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val lines = TaskNotice.visibleLines(state)
        val richHeading = state.phase != TaskState.Phase.Waiting &&
            (state.noticeLines.isNotEmpty() || state.phase == TaskState.Phase.Done || state.phase == TaskState.Phase.Failed)
        val expandedTitle = if(richHeading) lines.first().text else state.title.ifBlank { "DSH" }
        val bodyLines = if(richHeading) lines.drop(1) else lines
        val body = SpannableStringBuilder()
        bodyLines.forEachIndexed { index, line ->
            if(index>0)body.append("\n")
            val start=body.length
            body.append(line.text)
            if(line.action) {
                body.setSpan(RelativeSizeSpan(.9f),start,body.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                // Notification panels can be dark while the Activity uses a
                // light theme (HyperOS). Avoid taking the Activity's palette.
                body.setSpan(ForegroundColorSpan(0xff999999.toInt()),start,body.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(state.title.ifBlank { "DSH" })
            .setContentText(TaskNotice.compact(state))
            .setStyle(Notification.BigTextStyle().setBigContentTitle(expandedTitle).bigText(body))
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(state.isLive || state.phase == TaskState.Phase.Idle)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setCategory(if (state.phase == TaskState.Phase.Waiting) Notification.CATEGORY_MESSAGE else Notification.CATEGORY_PROGRESS)
        state.progress?.takeIf { state.isLive }?.let { builder.setProgress(100, (it * 100).toInt(), false) }
        state.approval?.takeIf { state.phase == TaskState.Phase.Waiting }?.let { approval ->
            val intent = Intent(context, IslandActionReceiver::class.java).apply {
                action = IslandNotificationBuilder.ACTION_REPLY
                putExtra(IslandNotificationBuilder.EXTRA_APPROVAL_ID, approval.id)
                // Distinct approvals must never reuse a mutable PendingIntent.
                data = android.net.Uri.parse("dshmobile://reply/${android.net.Uri.encode(approval.id)}")
            }
            val pending = PendingIntent.getBroadcast(context, 1202, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            val replyAction = Notification.Action.Builder(
                Icon.createWithResource(context, R.drawable.ic_notification), "回复", pending,
            ).addRemoteInput(RemoteInput.Builder(IslandNotificationBuilder.KEY_REPLY_TEXT)
                .setLabel("输入回复").build())
                .setAllowGeneratedReplies(false)
            if (android.os.Build.VERSION.SDK_INT >= 28) replyAction.setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY)
            builder.addAction(replyAction.build())
        }
        return builder.build()
    }

    companion object {
        const val CHANNEL_ID = "dsh_task_standard"
        const val NOTIFICATION_ID = 4101
    }
}
