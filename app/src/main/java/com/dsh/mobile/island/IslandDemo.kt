package com.dsh.mobile.island

import android.content.Context
import android.util.Log
import com.dsh.mobile.core.TaskState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import android.app.NotificationManager

/**
 * Drives a self-updating demo island.
 *
 * Why this exists: the island is a *progress* surface, so a single static
 * notification looks broken — it sits at one percentage forever and the user
 * reads that as "the app hung". The previous build did exactly that: it posted a
 * snapshot at 40% and never touched it again.
 *
 * This walker posts the same notification repeatedly on one id, advancing the
 * step counter and the progress bar so the island/notification visibly moves.
 * The same update mechanism (`notify` on a fixed id with `updatable=true`) is what
 * the real event pipeline uses, so a successful demo also proves that path.
 */
object IslandDemo {

    private const val TAG = "DshIslandDemo"

    /** Steps in the demo walk. */
    private const val TOTAL_STEPS = 6

    /** How long each step is shown. */
    private const val STEP_MILLIS = 1_200L

    private var job: Job? = null
    private var demoMedia: MediaIsland? = null
    fun suppressMedia() { demoMedia?.release(); demoMedia = null }

    /**
     * Run the demo, replacing any demo already in flight.
     *
     * @param context any context; the app context is used internally.
     * @param scope scope to run on (cancelled with the caller).
     * @param onVerdict receives a human-readable status for the first post and on failure.
     */
    fun start(
        context: Context,
        scope: CoroutineScope,
        onVerdict: (String) -> Unit = {},
    ) {
        val app = context.applicationContext
        job?.cancel()
        job = scope.launch(Dispatchers.Main) {
            val nm = app.getSystemService(NotificationManager::class.java)
            if (nm == null) {
                onVerdict("失败：拿不到 NotificationManager")
                return@launch
            }
            if (!nm.areNotificationsEnabled()) {
                onVerdict("失败：通知总开关被关闭")
                return@launch
            }

            val builder = TaskNotificationBuilder(app)
            val settings = com.dsh.mobile.core.SettingsStore(app)
            builder.ensureChannel()

            // Each step is a distinct tool so the island text visibly changes too,
            // not just the bar.
            val tools = listOf("read", "pwsh", "grep", "web_search", "write", "subagent")

            try {
            for (step in 1..TOTAL_STEPS) {
                if (!isActive) return@launch

                val state = TaskState(
                    title = "DSH 演示任务",
                    phase = TaskState.Phase.Running,
                    detail = "调用 ${com.dsh.mobile.core.EventReducer.humanTool(tools[(step - 1) % tools.size])}",
                    step = step,
                    stepTotal = TOTAL_STEPS,
                    todosDone = step,
                    todosTotal = TOTAL_STEPS,
                    startedAt = System.currentTimeMillis() - step * STEP_MILLIS,
                    updatedAt = System.currentTimeMillis(),
                )

                try {
                    if (settings.notificationEnabled) nm.notify(TaskNotificationBuilder.NOTIFICATION_ID, builder.build(state))
                    if (settings.islandEnabled) {
                        val media = demoMedia ?: MediaIsland(app).also { demoMedia = it }
                        nm.notify(MediaIsland.NOTIFICATION_ID, media.build(state))
                    } else { suppressMedia(); nm.cancel(MediaIsland.NOTIFICATION_ID) }
                } catch (t: Throwable) {
                    Log.e(TAG, "demo notify failed at step $step", t)
                    onVerdict("失败：${t.javaClass.simpleName}: ${t.message}")
                    return@launch
                }

                if (step == 1) {
                    // `activeNotifications` is updated asynchronously by the
                    // notification service, so reading it immediately after
                    // notify() routinely returns stale data and reports a false
                    // "dropped". Give the service a beat before judging.
                    delay(350)
                    val landed = nm.activeNotifications.any {
                        it.id == TaskNotificationBuilder.NOTIFICATION_ID
                    }
                    val verdict = if (landed) {
                        "演示已开始：进度每 ${STEP_MILLIS}ms 前进一格，请按 Home 看状态栏/灵动岛"
                    } else {
                        "已提交，但活动列表暂未更新（通常是系统延迟；若岛上仍无内容再看通知总开关）"
                    }
                    Log.i(TAG, verdict)
                    onVerdict(verdict)
                }

                delay(STEP_MILLIS)
            }

            // Finish, then retract after a beat so the island does not linger.
            val done = TaskState(
                title = "DSH 演示任务",
                phase = TaskState.Phase.Done,
                detail = "已完成",
                step = TOTAL_STEPS,
                stepTotal = TOTAL_STEPS,
                todosDone = TOTAL_STEPS,
                todosTotal = TOTAL_STEPS,
                startedAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
            )
            runCatching { if (settings.notificationEnabled) nm.notify(TaskNotificationBuilder.NOTIFICATION_ID, builder.build(done)) }
            delay(3_000)
            runCatching { nm.cancel(TaskNotificationBuilder.NOTIFICATION_ID) }
            } finally {
                suppressMedia()
                nm.cancel(MediaIsland.NOTIFICATION_ID)
            }
        }
    }

    /** Stop an in-flight demo and remove its notification. */
    fun stop(context: Context) {
        job?.cancel()
        job = null
        val app = context.applicationContext
        runCatching {
            app.getSystemService(NotificationManager::class.java)
                ?.apply { cancel(TaskNotificationBuilder.NOTIFICATION_ID); cancel(MediaIsland.NOTIFICATION_ID) }
        }
    }

    /** Whether a demo is currently running. */
    fun isRunning(): Boolean = job?.isActive == true
}
