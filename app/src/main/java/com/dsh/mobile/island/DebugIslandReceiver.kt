package com.dsh.mobile.island

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.dsh.mobile.core.TaskState

/**
 * Developer/test entry point for the island, drivable over adb:
 *
 * ```
 * adb shell am broadcast -a com.dsh.mobile.DEBUG_ISLAND \
 *   -n com.dsh.mobile/.island.DebugIslandReceiver --es phase running
 * ```
 *
 * Why it exists: the island is a *notification* surface, so verifying it means
 * posting one and then looking at the status bar. Driving that through UI taps is
 * fragile (soft-keyboard resize moves every coordinate), and a real device under
 * adb is exactly where the interesting failures live. This receiver makes the
 * whole path testable without touching the screen.
 *
 * It is `exported=false`, so only adb (which runs as shell and can target a
 * non-exported component explicitly) or the app itself can reach it.
 */
class DebugIslandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val phase = intent.getStringExtra(EXTRA_PHASE) ?: "running"
        val state = when (phase) {
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

        val builder = IslandNotificationBuilder(context)
        builder.ensureChannel()
        val nm = context.getSystemService(android.app.NotificationManager::class.java)

        if (nm == null) {
            Log.e(TAG, "no NotificationManager")
            return
        }
        if (!nm.areNotificationsEnabled()) {
            Log.e(TAG, "notifications disabled for this app; island cannot appear")
            return
        }

        runCatching {
            nm.notify(IslandNotificationBuilder.NOTIFICATION_ID, builder.build(state))
        }.onFailure {
            Log.e(TAG, "notify failed", it)
        }

        val landed = nm.activeNotifications.any {
            it.id == IslandNotificationBuilder.NOTIFICATION_ID
        }
        Log.i(
            TAG,
            "debug island phase=$phase posted=$landed id=${IslandNotificationBuilder.NOTIFICATION_ID}",
        )
    }

    private companion object {
        const val TAG = "DshDebugIsland"
        const val EXTRA_PHASE = "phase"
    }
}
