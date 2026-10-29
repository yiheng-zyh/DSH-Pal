package com.dsh.mobile.island

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.widget.RemoteViews
import com.dsh.mobile.MainActivity
import com.dsh.mobile.R
import com.dsh.mobile.core.TaskState
import org.json.JSONObject

/**
 * Builds the HyperOS "Super Island" (超级岛) notification for the current task.
 *
 * Xiaomi's official contract is intentionally notification-shaped: you post a
 * normal notification and attach one extra string under `miui.focus.param`
 * carrying a `param_v2` JSON object. On a device with the island feature the
 * system renders it as an island; everywhere else the same notification
 * degrades to an ordinary progress notification, which is exactly the fallback
 * we want. See [IslandSupport] for capability detection.
 *
 * Documented fields used here (all from the official 超级岛 开发指南):
 *  - `business`        — scenario tag, required.
 *  - `updatable`       — keep the same notification id alive across updates.
 *  - `enableFloat`     — expand the island on update (we only do this for approvals).
 *  - `ticker` / `aodTitle` — status-bar and always-on-display text.
 *  - `param_island.bigIslandArea.imageTextInfoLeft` — expanded left text block.
 *  - `param_island.bigIslandArea.picInfo`          — expanded right icon.
 *  - `param_island.smallIslandArea.picInfo`        — collapsed pill icon.
 *  - `baseInfo`        — the focus-notification body shown outside the island.
 */
class IslandNotificationBuilder(private val context: Context) {

    private val manager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /** Ensure the channel exists; safe to call repeatedly. */
    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.island_channel_name),
            NotificationManager.IMPORTANCE_LOW, // no sound: this is a live tracker
        ).apply {
            description = context.getString(R.string.island_channel_desc)
            setShowBadge(false)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * Build (or rebuild) the live task notification.
     *
     * @param state the task projection to render.
     * @param chronometerStart elapsed-realtime base for the running timer, or null.
     */
    fun build(state: TaskState, chronometerStart: Long? = null): Notification {
        ensureChannel()

        val openIntent = PendingIntent.getActivity(
            context,
            REQ_OPEN,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(state.title.ifBlank { "DSH" })
            .setContentText(state.headline)
            .setContentIntent(openIntent)
            .setOngoing(state.isLive)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            // Deliberately NOT CATEGORY_PROGRESS: the ROM treats a progress
            // notification as an ordinary progress bar and never promotes it to
            // the focus island. Xiaomi's own island notifications (timer, call)
            // use the service/transport categories instead.
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setStyle(Notification.BigTextStyle().bigText(buildBody(state)))

        if (state.isLive) {
            builder.setWhen(chronometerStart?.let { System.currentTimeMillis() - (SystemClock.elapsedRealtime() - it) }
                ?: System.currentTimeMillis())
            builder.setUsesChronometer(chronometerStart != null)
        }

        // Progress bar: only when we have a real denominator. A fake spinner on
        // an indeterminate task reads as "stuck", so use the chronometer instead.
        val progress = state.progress
        if (progress != null && state.isLive) {
            builder.setProgress(100, (progress * 100).toInt(), false)
        }

        // Actionable states get an action; passive ones do not.
        if (state.phase == TaskState.Phase.Waiting && state.approval != null) {
            // Free-text reply, typed straight into the shade — the same mechanism
            // messaging apps use for quick replies. This is the only way the user
            // can answer a question from the lock screen without opening the app.
            builder.addAction(buildReplyAction(state.approval.id))
            builder.addExtras(replyExtras(state))
        }

        builder.addExtras(islandExtras(state))

        val notification = builder.build()

        // Custom-View mode ("METHOD B" in HyperIsland-ToolKit).
        //
        // The ROM has two disjoint island protocols and mixing them makes it
        // reject both:
        //
        //   A. standard template : miui.focus.param         + pics + actions
        //   B. custom RemoteViews: miui.focus.param.custom  + miui.focus.rv[.tiny]
        //
        // This builder renders its own RemoteViews, so it must publish the B side.
        // An earlier revision sent the A-side template JSON together with
        // miui.focus.rvBar (a key only the system clock's A-side notification
        // uses), which is why the island never appeared.
        applyCustomExtras(notification, state)

        return notification
    }

    /**
     * Persist the custom-mode extras onto [notification].
     *
     * Kept separate from [build] so the wire keys stay in one readable block.
     */
    fun applyCustomExtras(notification: Notification, state: TaskState) {
        val pics = Bundle()
        val largeIcon = Icon.createWithResource(context, R.drawable.ic_launcher_fg)
        pics.putParcelable(PIC_LARGE, largeIcon)
        pics.putParcelable(PIC_SMALL, largeIcon)
        notification.extras.putBundle(PICS_KEY, pics)

        notification.extras.putString("miui.focus.param.custom", buildCustomJson(state))
        notification.extras.putString("miui.focus.ticker", barText(state))

        notification.extras.putParcelable(RV_MAIN, buildBarRemoteViews(state, night = false))
        notification.extras.putParcelable(RV_MAIN_NIGHT, buildBarRemoteViews(state, night = true))
        notification.extras.putParcelable(RV_TINY, buildBarRemoteViews(state, night = false))
        notification.extras.putParcelable(RV_TINY_NIGHT, buildBarRemoteViews(state, night = true))
    }

    /** `param_v2`-shaped payload for custom mode (`miui.focus.param.custom`). */
    fun buildCustomJson(state: TaskState): String {
        val root = JSONObject()
        root.put("ticker", barText(state))
        root.put("enableFloat", state.phase == TaskState.Phase.Waiting)
        root.put("updatable", state.isLive)
        root.put("isShowNotification", true)
        root.put("islandFirstFloat", state.phase == TaskState.Phase.Waiting)
        root.put("timeout", ISLAND_NOTIFICATION_TIMEOUT_MINUTES)
        root.put("reopen", "reopen")
        root.put("aodTitle", aodTitle(state))

        val island = JSONObject()
        island.put("islandProperty", if (state.phase == TaskState.Phase.Waiting) 2 else 1)
        island.put("islandPriority", 2)
        island.put("islandTimeout", ISLAND_TIMEOUT_SECONDS)
        island.put("islandOrder", true)
        island.put("dismissIsland", !state.isLive)
        island.put("needCloseAnimation", true)
        if (state.phase == TaskState.Phase.Waiting) {
            island.put("highlightColor", HIGHLIGHT_COLOR)
        }
        root.put("param_island", island)
        return root.toString()
    }

    /**
     * Content for the collapsed capsule.
     *
     * Deliberately one line of text: the island capsule is roughly one status-bar
     * row tall, so anything more is clipped by the ROM anyway.
     */
    private fun buildBarRemoteViews(state: TaskState, night: Boolean): RemoteViews {
        val layout = if (night) R.layout.island_bar_night else R.layout.island_bar
        return RemoteViews(context.packageName, layout).apply {
            setTextViewText(R.id.dsh_bar_text, barText(state))
            setImageViewResource(R.id.dsh_bar_icon, R.drawable.ic_notification)
        }
    }

    /** Shortest possible description of what the agent is doing right now. */
    private fun barText(state: TaskState): String = when (state.phase) {
        TaskState.Phase.Waiting -> "待确认"
        TaskState.Phase.Running -> state.detail.ifBlank { "执行中" }
        TaskState.Phase.Thinking -> "思考中"
        TaskState.Phase.Done -> "已完成"
        TaskState.Phase.Failed -> "失败"
        TaskState.Phase.Idle -> "DSH"
    }

    /**
     * Build the `param_v2` JSON that turns this notification into an island.
     * Kept public for unit testing the exact wire shape.
     */
    fun buildIslandJson(state: TaskState): String {
        val root = JSONObject()
        val params = JSONObject()

        // Top-level mirror fields. A working system island (the SMS verification
        // code, captured on a Xiaomi 15 Pro / HyperOS 3) carries these outside
        // param_v2 as well; the ROM reads them for the collapsed capsule and for
        // logging, and refuses the focus notification when they are missing.
        root.put("protocol", 1)
        root.put("scene", BUSINESS_TAG)
        root.put("title", state.headline)
        root.put("desc1", frontTitle(state))
        root.put("desc2", buildBody(state))
        root.put("enableFloat", state.phase == TaskState.Phase.Waiting)

        params.put("protocol", 1)
        params.put("business", BUSINESS_TAG)
        params.put("updatable", state.isLive)
        // Expand the island automatically only when the user must act.
        params.put("enableFloat", state.phase == TaskState.Phase.Waiting)
        params.put("islandFirstFloat", state.phase == TaskState.Phase.Waiting)
        // Degrade to a plain notification instead of vanishing when the user has
        // the island permission switched off — otherwise the app looks dead.
        params.put("filterWhenNoPermission", false)
        // Same-id updates must be able to resurface after the island was
        // dismissed, which is what the system SMS payload does.
        params.put("reopen", "reopen")
        // Minutes, per the official schema (notification default-disappear time).
        params.put("timeout", ISLAND_NOTIFICATION_TIMEOUT_MINUTES)
        params.put("ticker", state.headline)
        params.put("aodTitle", aodTitle(state))
        params.put("aodPic", PIC_LARGE)

        val island = JSONObject()
        // 1 = information-first, 2 = action-first. Waiting on the user is
        // action-first; everything else is informational.
        island.put("islandProperty", if (state.phase == TaskState.Phase.Waiting) 2 else 1)
        // Default 2 in the reference implementation: higher-priority islands
        // displace lower ones. Without it the ROM keeps whatever is already on
        // screen (a running timer, say) and our update is dropped.
        island.put("islandPriority", 2)
        // Official doc (dev.mi.com pId=2131): islandTimeout is in SECONDS.
        island.put("islandTimeout", ISLAND_TIMEOUT_SECONDS)
        island.put("islandOrder", true)
        island.put("dismissIsland", !state.isLive)
        island.put("needCloseAnimation", true)
        if (state.phase == TaskState.Phase.Waiting) {
            island.put("highlightColor", HIGHLIGHT_COLOR)
        }

        // --- expanded (big island) -------------------------------------------
        val big = JSONObject()
        val left = JSONObject()
        left.put("type", 1)
        left.put("picInfo", JSONObject().apply {
            put("type", 1)
            put("pic", PIC_LARGE)
        })
        // TextInfo fields per the working system payload: title (required),
        // content, showHighlightColor. The documented "miui.focus.paramtextInfo"
        // key carries the same object; both spellings are accepted by the ROM,
        // and the working sample uses plain "textInfo".
        left.put("textInfo", JSONObject().apply {
            put("title", state.headline)
            put("content", buildBody(state))
            put("showHighlightColor", state.phase == TaskState.Phase.Waiting)
        })
        big.put("imageTextInfoLeft", left)

        // Right slot of the expanded island: a single pictogram, flat {type, pic}.
        val right = JSONObject()
        right.put("type", 1)
        right.put("pic", PIC_LARGE)
        big.put("picInfo", right)
        island.put("bigIslandArea", big)

        // --- collapsed (small island) ----------------------------------------
        // Flat {type, pic} — the working sample nests nothing here. Wrapping it
        // in picInfo made the ROM drop the capsule silently.
        val small = JSONObject()
        small.put("type", 1)
        small.put("pic", PIC_SMALL)
        island.put("smallIslandArea", small)

        params.put("param_island", island)

        // --- focus notification body ----------------------------------------
        val baseInfo = JSONObject()
        baseInfo.put("title", state.headline)
        baseInfo.put("content", buildBody(state))
        baseInfo.put("type", 2)
        // Where a tap on the island lands. Without a resolvable target the ROM
        // logs `getFocusNotificationContent ... value:{}` and never renders.
        baseInfo.put("smallWindowTarget", MAIN_ACTIVITY)
        if (state.phase == TaskState.Phase.Waiting) {
            baseInfo.put("colorTitle", HIGHLIGHT_COLOR)
        }
        params.put("baseInfo", baseInfo)

        root.put("param_v2", params)
        return root.toString()
    }

    private fun islandExtras(state: TaskState): Bundle = Bundle().apply {
        // Actions must be registered as real Notification.Action objects under
        // miui.focus.actions to be clickable from the island.
        if (state.phase == TaskState.Phase.Waiting) {
            val actions = Bundle()
            actions.putParcelable(ACTION_APPROVE, buildNotificationAction("回复", ACTION_APPROVE, state.approval?.id))
            putBundle(ACTIONS_KEY, actions)
        }
    }

    /**
     * Inline-reply action: a text field inside the notification shade.
     *
     * When the harness is blocked on a question, forcing the user to open the app
     * is the difference between answering in two seconds and abandoning the run.
     * RemoteInput gives us the shade's own text field, the same primitive
     * messaging apps use.
     */
    private fun buildReplyAction(approvalId: String?): Notification.Action {
        val intent = Intent(context, IslandActionReceiver::class.java).apply {
            action = ACTION_REPLY
            putExtra(EXTRA_APPROVAL_ID, approvalId)
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        }
        val pending = PendingIntent.getBroadcast(
            context,
            REQ_REPLY,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val remoteInput = android.app.RemoteInput.Builder(KEY_REPLY_TEXT)
            .setLabel("输入回答…")
            .build()
        return Notification.Action.Builder(
            Icon.createWithResource(context, R.drawable.ic_notification),
            "回复",
            pending,
        ).addRemoteInput(remoteInput).build()
    }

    /**
     * Mark the notification as reply-capable.
     *
     * `android.showBackgroundInlineStyle` is the platform key that tells SystemUI
     * to render the RemoteInput inline instead of behind a chevron.
     */
    private fun replyExtras(state: TaskState): Bundle = Bundle().apply {
        if (state.approval != null) {
            putBoolean("android.showBackgroundInlineStyle", true)
            putBoolean("android.allowInShade", true)
        }
    }

    private fun buildNotificationAction(label: String, action: String, approvalId: String?): Notification.Action {
        val intent = Intent(context, IslandActionReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_APPROVAL_ID, approvalId)
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        }
        val pending = PendingIntent.getBroadcast(
            context,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Action.Builder(
            Icon.createWithResource(context, R.drawable.ic_notification),
            label,
            pending,
        ).build()
    }

    private fun buildAction(label: String, action: String, approvalId: String?): Notification.Action =
        buildNotificationAction(label, action, approvalId)

    /** Second line of the notification: step/tool/approval context. */
    private fun buildBody(state: TaskState): String = when (state.phase) {
        TaskState.Phase.Waiting -> state.approval?.prompt ?: "有操作需要你确认"
        TaskState.Phase.Running -> buildString {
            append(state.detail.ifBlank { "执行中" })
            if (state.stepTotal > 0) append(" · 第 ${state.step}/${state.stepTotal} 步")
        }
        TaskState.Phase.Thinking -> "模型正在思考"
        TaskState.Phase.Done -> "本轮任务已完成"
        TaskState.Phase.Failed -> "本轮任务失败"
        TaskState.Phase.Idle -> ""
    }

    /**
     * Short label above the title in the expanded island.
     *
     * Not sent as its own field: TextInfo has no `frontTitle`. Kept because
     * [aodTitle] and the ticker reuse the same phrasing.
     */
    private fun frontTitle(state: TaskState): String = when (state.phase) {
        TaskState.Phase.Waiting -> "等待确认"
        TaskState.Phase.Running -> "正在执行"
        TaskState.Phase.Thinking -> "思考中"
        TaskState.Phase.Done -> "已完成"
        TaskState.Phase.Failed -> "失败"
        TaskState.Phase.Idle -> "DSH"
    }

    private fun aodTitle(state: TaskState): String = when (state.phase) {
        TaskState.Phase.Waiting -> "DSH 等待确认"
        TaskState.Phase.Running -> "DSH 执行中"
        TaskState.Phase.Thinking -> "DSH 思考中"
        else -> "DSH"
    }

    companion object {
        /** Notification id: one live task at a time keeps the island unambiguous. */
        const val NOTIFICATION_ID = 4101

        const val CHANNEL_ID = "dsh_task_progress"

        /** Documented protocol version for OS2/OS3 island payloads. */
        const val PROTOCOL_VERSION = 1

        private const val BUSINESS_TAG = "dsh_agent_task"

        /** Resolved by the island when the user taps it. */
        private const val MAIN_ACTIVITY = "com.dsh.mobile/com.dsh.mobile.MainActivity"

        /** Accent used for the waiting/approval state, matching the DSH accent. */
        private const val HIGHLIGHT_COLOR = "#FF4C8DFF"

        /**
         * How long the *notification* survives before the ROM may drop it.
         *
         * Minutes — the official field is documented in minutes, unlike
         * islandTimeout which is seconds. An hour is effectively "until the task
         * ends", since we retract explicitly on completion.
         */
        private const val ISLAND_NOTIFICATION_TIMEOUT_MINUTES = 60

        /** islandTimeout is in seconds per the official schema; one hour = no timeout. */
        private const val ISLAND_TIMEOUT_SECONDS = 3600

        private const val FOCUS_PARAM_KEY = "miui.focus.param"

        // --- custom-View ("METHOD B") extras keys ---------------------------
        // The banner/expanded content and the collapsed capsule are separate
        // RemoteViews, each with a light and dark variant.
        private const val RV_MAIN = "miui.focus.rv"
        private const val RV_MAIN_NIGHT = "miui.focus.rvNight"
        private const val RV_TINY = "miui.focus.rv.tiny"
        private const val RV_TINY_NIGHT = "miui.focus.rv.tinyNight"

        /** Whether the island may show its action row for this update. */
        private const val SHOW_ACTION = "miui.showAction"
        private const val PICS_KEY = "miui.focus.pics"
        private const val ACTIONS_KEY = "miui.focus.actions"

        private const val PIC_LARGE = "miui.focus.pic_large"
        private const val PIC_SMALL = "miui.focus.pic_small"

        private const val ACTION_APPROVE = "miui.focus.action_open_approval"

        // --- inline reply ---------------------------------------------------
        /** Action fired when the user submits the shade's text field. */
        const val ACTION_REPLY = "com.dsh.mobile.action.REPLY"

        /** RemoteInput result key; read back in [IslandActionReceiver]. */
        const val KEY_REPLY_TEXT = "dsh_reply_text"

        /** Request codes must differ or PendingIntents collide. */
        private const val REQ_REPLY = 902

        /** Approval id carried by action buttons. */
        const val EXTRA_APPROVAL_ID = "approval_id"

        private const val REQ_OPEN = 900

        /** Bundle keys referenced by the JSON; exported for tests. */
        fun picKeys(): List<String> = listOf(PIC_LARGE, PIC_SMALL)
    }
}
