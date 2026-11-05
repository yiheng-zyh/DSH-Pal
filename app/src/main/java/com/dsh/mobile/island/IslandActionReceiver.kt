package com.dsh.mobile.island

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.dsh.mobile.service.DshLiveService

/**
 * Receives taps on the island / notification action buttons, and free-text
 * answers typed inline in the notification shade.
 *
 * Kept deliberately thin: it forwards the user's intent to the live service and
 * never performs I/O on the broadcast thread.
 */
class IslandActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d(TAG, "island action: $action")

        val approvalId = intent.getStringExtra(IslandNotificationBuilder.EXTRA_APPROVAL_ID)

        // Inline reply: the answer the user typed into the notification's text
        // field. RemoteInput results arrive on the broadcast, not as an extra.
        // Platform class, not androidx: the builder emits android.app.RemoteInput,
        // and the two are not interchangeable for reading results back.
        val typed = android.app.RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(IslandNotificationBuilder.KEY_REPLY_TEXT)
            ?.toString()
            ?.trim()

        if (!typed.isNullOrEmpty()) {
            Log.d(TAG, "inline reply (${typed.length} chars) for approval=$approvalId")
            com.dsh.mobile.core.RunLog.info("Reply", "RemoteInput received chars=${typed.length}")
        }

        val forward = Intent(context, DshLiveService::class.java).apply {
            this.action = DshLiveService.ACTION_ISLAND_ACTION
            putExtra(DshLiveService.EXTRA_ACTION_NAME, action)
            putExtra(DshLiveService.EXTRA_APPROVAL_ID, approvalId)
            if (!typed.isNullOrEmpty()) putExtra(DshLiveService.EXTRA_REPLY_TEXT, typed)
        }
        runCatching { context.startForegroundService(forward) }.onFailure {
            com.dsh.mobile.core.RunLog.error("Reply", "receiver forwarding failed ${it.javaClass.simpleName}")
        }
    }

    private companion object {
        const val TAG = "DshIslandAction"
    }
}
