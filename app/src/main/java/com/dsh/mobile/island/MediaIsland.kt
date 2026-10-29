package com.dsh.mobile.island

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import com.dsh.mobile.MainActivity
import com.dsh.mobile.R
import com.dsh.mobile.core.TaskState

/**
 * Renders DSH task progress through the **media** island channel.
 *
 * ## Why this exists
 *
 * Xiaomi's focus-notification protocol (`miui.focus.param` + RemoteViews) is
 * gated behind a per-package authorisation: on a real device the ROM logs
 * `FocusPlugin: onAuthFailed <pkg>` and drops the notification, even when the
 * payload, the JSON schema and the RemoteViews are all correct. That check
 * cannot be satisfied from app code — it needs either carrier registration with
 * Xiaomi or a MIPUSH-served message.
 *
 * `MediaStyle` + `MediaSession` is the one island path any app can use: the ROM
 * promotes a transport notification to the music island on its own, with no
 * private protocol and no allow-list. A third-party music player on the test
 * device proves it — its notification carries no `miui.focus.*` keys at all,
 * only `android.template = MediaStyle` and an `android.mediaSession` token.
 *
 * ## Trade-off
 *
 * The island renders as a *music* card: artwork, title, subtitle and a progress
 * bar. Layout is the ROM's, not ours. In exchange it actually appears, which the
 * focus protocol never did.
 *
 * ## Mapping
 *
 * DSH state is projected onto the only fields the ROM will show:
 *
 * | island slot | DSH meaning                     |
 * |-------------|---------------------------------|
 * | title       | current tool, e.g. 「调用 联网搜索」 |
 * | artist      | task title / headline           |
 * | album       | step counter, e.g. 「第 4/6 步」  |
 * | duration    | progress position               |
 */
class MediaIsland(private val context: Context) {

    private var session: MediaSession? = null

    /**
     * Publish [state] as a media-island notification.
     *
     * Returns the notification; the caller posts it (typically through
     * `startForeground`, so the island survives the app going to background —
     * which is exactly when it is visible).
     */
    fun build(state: TaskState): Notification {
        ensureChannel()
        val mediaSession = ensureSession()

        publishMetadata(mediaSession, state)

        val openIntent = PendingIntent.getActivity(
            context,
            REQ_OPEN,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // MediaStyle is what makes the ROM treat this as a media session and
        // promote it to the island. The three compact actions are the minimum
        // the ROM wants before it will draw the transport controls.
        val style = Notification.MediaStyle()
            .setMediaSession(mediaSession.sessionToken)
            .setShowActionsInCompactView(0, 1, 2)

        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title(state))
            .setContentText(subtitle(state))
            .setSubText(album(state))
            .setContentIntent(openIntent)
            .setStyle(style)
            .setOngoing(state.isLive)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            // The island shows the media session's progress; keep it honest with
            // the same numbers the in-app strip uses.
            .setProgress(100, progressPercent(state), state.progress == null)
            .addAction(transportAction(android.R.drawable.ic_media_previous, ACTION_PREV))
            .addAction(primaryAction(state))
            .addAction(transportAction(android.R.drawable.ic_media_next, ACTION_NEXT))
            .build()
    }

    /** Drop the media session once nothing is being tracked. */
    fun release() {
        session?.isActive = false
        session?.release()
        session = null
    }

    // ---- internals -----------------------------------------------------------

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "任务进度（音乐通道）",
                // LOW keeps it silent: this is a progress surface, not an alert.
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "通过媒体会话在灵动岛显示 DSH 任务进度"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
    }

    private fun ensureSession(): MediaSession {
        session?.let { return it }
        val created = MediaSession(context, "dsh-task").apply {
            setCallback(object : MediaSession.Callback() {
                // The transport buttons are decorative: DSH tasks are driven from
                // the chat UI, so the island must not pretend it can pause a run.
                override fun onPlay() = Unit
                override fun onPause() = Unit
                override fun onSkipToNext() = Unit
                override fun onSkipToPrevious() = Unit
            })
            setPlaybackState(
                PlaybackState.Builder()
                    .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                    .setActions(PlaybackState.ACTION_PLAY_PAUSE)
                    .build(),
            )
            isActive = true
        }
        session = created
        return created
    }

    private fun publishMetadata(mediaSession: MediaSession, state: TaskState) {
        // The island renders METADATA_KEY_DURATION as a clock. A DSH turn has no
        // wall-clock length, so the honest thing is to publish the *step* counter
        // as the position and omit a duration entirely — the ROM then shows the
        // bar as indeterminate rather than inventing "24:00".
        val builder = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title(state))
            .putString(MediaMetadata.METADATA_KEY_ARTIST, subtitle(state))
            .putString(MediaMetadata.METADATA_KEY_ALBUM, album(state))
            .putLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER, state.step.coerceAtLeast(0).toLong())
            .putLong(MediaMetadata.METADATA_KEY_NUM_TRACKS, state.stepTotal.coerceAtLeast(0).toLong())

        mediaSession.setMetadata(builder.build())
        mediaSession.setPlaybackState(
            PlaybackState.Builder()
                .setState(
                    if (state.isLive) PlaybackState.STATE_PLAYING else PlaybackState.STATE_STOPPED,
                    PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                    if (state.isLive) 1f else 0f,
                )
                .setActions(PlaybackState.ACTION_PLAY_PAUSE)
                .build(),
        )
    }

    private fun primaryAction(state: TaskState): Notification.Action {
        val icon = if (state.isLive) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        return transportAction(icon, if (state.isLive) ACTION_PAUSE else ACTION_PLAY)
    }

    private fun transportAction(iconRes: Int, action: String): Notification.Action {
        val intent = Intent(action).setPackage(context.packageName)
        val pending = PendingIntent.getBroadcast(
            context,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Action.Builder(
            Icon.createWithResource(context, iconRes),
            action,
            pending,
        ).build()
    }

    // ---- projection ----------------------------------------------------------

    /** Island line 1: what the agent is doing right now. */
    private fun title(state: TaskState): String = when (state.phase) {
        TaskState.Phase.Waiting -> "待你确认"
        TaskState.Phase.Running -> state.detail.ifBlank { "执行中" }
        TaskState.Phase.Thinking -> "思考中"
        TaskState.Phase.Done -> "已完成"
        TaskState.Phase.Failed -> "失败"
        TaskState.Phase.Idle -> "DSH 待命"
    }

    /** Island line 2: which task this is. */
    private fun subtitle(state: TaskState): String =
        state.title.ifBlank { "DeepSeek Harness" }

    /** Island line 3: where we are in the run. */
    private fun album(state: TaskState): String = when {
        state.stepTotal > 0 -> "第 ${state.step}/${state.stepTotal} 步"
        state.step > 0 -> "第 ${state.step} 步"
        else -> ""
    }

    private fun progressPercent(state: TaskState): Int =
        ((state.progress ?: 0f) * 100).toInt().coerceIn(0, 100)

    companion object {
        const val CHANNEL_ID = "dsh_task_media"
        const val NOTIFICATION_ID = 4102

        private const val REQ_OPEN = 901
        private const val MAX_DURATION_MS = 3_600_000L

        const val ACTION_PLAY = "com.dsh.mobile.action.MEDIA_PLAY"
        const val ACTION_PAUSE = "com.dsh.mobile.action.MEDIA_PAUSE"
        const val ACTION_PREV = "com.dsh.mobile.action.MEDIA_PREV"
        const val ACTION_NEXT = "com.dsh.mobile.action.MEDIA_NEXT"
    }
}
