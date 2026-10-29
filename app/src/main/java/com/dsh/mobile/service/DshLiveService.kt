package com.dsh.mobile.service

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.dsh.mobile.R
import com.dsh.mobile.core.EventReducer
import com.dsh.mobile.core.SettingsStore
import com.dsh.mobile.core.TaskState
import com.dsh.mobile.island.IslandNotificationBuilder
import com.dsh.mobile.island.IslandSupport
import com.dsh.mobile.island.TaskNotificationBuilder
import com.dsh.mobile.core.RunLog
import com.dsh.mobile.island.MediaIsland
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The single owner of live task state.
 *
 * It runs as a foreground service for one reason: when the user leaves the app,
 * Android will eventually freeze or kill a background WebView, and with it the
 * socket carrying the events the island renders. A foreground service with a
 * `dataSync` type keeps the process alive so the island keeps updating while the
 * user is doing something else — which is precisely the feature being built.
 *
 * The service is also the only place that posts notifications, so island and
 * notification can never disagree about the current task.
 */
class DshLiveService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var settings: SettingsStore
    private lateinit var island: TaskNotificationBuilder

    /** Lazily created; owns the MediaSession that backs the media-island channel. */
    private var media: MediaIsland? = null

    /** Latest projected state, observed by the Compose UI. */
    private val _state = MutableStateFlow(TaskState())
    val state: StateFlow<TaskState> = _state.asStateFlow()

    /** One-shot effects for the UI (haptics, toast-worthy transitions). */
    private val _effects = MutableSharedFlow<Effect>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val effects: SharedFlow<Effect> = _effects.asSharedFlow()
    // A Channel retains replies until the UI subscribes; SharedFlow(replay=0) drops them.
    private val answerQueue = kotlinx.coroutines.channels.Channel<Effect.AnswerSubmitted>(8)
    val answers = answerQueue.receiveAsFlow()

    private var turnStartedElapsed: Long? = null
    private var retractJob: Job? = null
    private var unsubscribePreferences: (() -> Unit)? = null
    private var foregroundId: Int? = null
    private var capability: IslandSupport.Capability? = null

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        island = TaskNotificationBuilder(this)
        instance = this
        instances.value = this
        unsubscribePreferences = settings.observeRuntimePreferences { scope.launch { reconcilePreferences() } }

        // The channel MUST exist before the first startForeground: Android rejects a
        // foreground notification whose channel is missing with
        // CannotPostForegroundServiceNotificationException, which kills the process.
        island.ensureChannel()
        if (settings.backgroundLive) startInForeground(placeholderNotification(), KEEPALIVE_ID)
        RunLog.info("Service", "created; foreground notification posted")

        scope.launch { probeCapability() }
        scope.launch { observeStateForNotifications() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if(!com.dsh.mobile.vpn.VpnController.isSessionActive()) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_ISLAND_ACTION -> handleIslandAction(intent)
            ACTION_STOP -> stopSelf()
        }
        // START_STICKY: if the system kills us, bring the tracker back.
        return if (settings.backgroundLive) START_STICKY else START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        RunLog.warn("Service", "system foreground time limit; stopping tracker")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        com.dsh.mobile.vpn.VpnController.onTaskRemoved()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        unsubscribePreferences?.invoke(); unsubscribePreferences = null
        instances.value = null
        instance = null
        stopForegroundCompat(true)
        getSystemService(NotificationManager::class.java)?.cancel(TaskNotificationBuilder.NOTIFICATION_ID)
        getSystemService(NotificationManager::class.java)?.cancel(KEEPALIVE_ID)
        RunLog.info("Service", "destroyed")
        getSystemService(NotificationManager::class.java)?.cancel(MediaIsland.NOTIFICATION_ID)
        // The MediaSession must be released or the ROM keeps showing a stale
        // island card after the tracker is gone.
        runCatching { media?.release() }
        media = null
        scope.cancel()
        super.onDestroy()
    }

    // ---- public surface ------------------------------------------------------

    /**
     * Feed one raw event frame from the WebView bridge.
     *
     * Called from the JS interface thread; the actual fold happens on the
     * service's dispatcher so the UI thread is never blocked.
     */
    fun updateTitle(title: String) {
        scope.launch {
            if (title.isNotBlank() && _state.value.title != title) {
                _state.value = _state.value.copy(title = title.take(180))
                RunLog.info("Task", "session title synchronized; chars=${title.length}")
            }
        }
    }

    fun onRawEvent(raw: String) {
        scope.launch {
            val envelope = EventReducer.parse(raw) ?: run {
                Log.w(TAG, "event parse failed: ${raw.take(100)}")
                return@launch
            }
            val now = System.currentTimeMillis()
            val previous = _state.value
            val next = EventReducer.reduce(previous, envelope, now)

            // Log the resolved event type so "nothing happened" can be told apart
            // from "the type was not one we project".
            Log.i(TAG, "event type=${EventReducer.describe(envelope)} -> phase=${next.phase} (was ${previous.phase})")
            if (next.phase != previous.phase) RunLog.info("Task", "${previous.phase} -> ${next.phase}")

            if (next == previous) return@launch

            // Track the chronometer base on the thinking -> running transition.
            if (previous.isLive.not() && next.isLive) {
                turnStartedElapsed = SystemClock.elapsedRealtime()
            }

            _state.value = next

            if (next.phase == TaskState.Phase.Waiting && previous.phase != TaskState.Phase.Waiting) {
                if (settings.hapticsOnApproval) runCatching {
                    val vibrator = if (Build.VERSION.SDK_INT >= 31) getSystemService(android.os.VibratorManager::class.java).defaultVibrator else @Suppress("DEPRECATION") (getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator)
                    vibrator.vibrate(android.os.VibrationEffect.createOneShot(60, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                }
                _effects.tryEmit(Effect.ApprovalNeeded(next.approval?.prompt ?: ""))
            }
            if (next.phase == TaskState.Phase.Done && previous.phase != TaskState.Phase.Done) {
                _effects.tryEmit(Effect.TurnFinished(success = true))
            }
            if (next.phase == TaskState.Phase.Failed && previous.phase != TaskState.Phase.Failed) {
                _effects.tryEmit(Effect.TurnFinished(success = false))
            }
        }
    }

    /** Called by the UI when the user explicitly clears the current task. */
    fun clearTask() {
        retractJob?.cancel()
        _state.value = TaskState()
        turnStartedElapsed = null
        scope.launch { updateNotification(TaskState()) }
    }

    // ---- notification pipeline ----------------------------------------------

    private fun observeStateForNotifications() {
        scope.launch {
            _state.collectLatest { task ->
                updateNotification(task)
            }
        }
    }

    private suspend fun updateNotification(task: TaskState) {
        val nm = getSystemService(NotificationManager::class.java)
        if (!settings.islandEnabled) {
            releaseMedia()
            com.dsh.mobile.island.clearDebugMedia(this)
            com.dsh.mobile.island.IslandDemo.suppressMedia()
        }
        if (!settings.backgroundLive && foregroundId != null) {
            stopForegroundCompat(true); foregroundId = null
            nm?.cancel(KEEPALIVE_ID)
        }
        if (!settings.notificationEnabled && task.phase != TaskState.Phase.Done && task.phase != TaskState.Phase.Failed) {
            if (settings.backgroundLive) startInForeground(placeholderNotification(), KEEPALIVE_ID)
            nm?.cancel(TaskNotificationBuilder.NOTIFICATION_ID)
            if (task.isLive) { retractJob?.cancel(); postMedia(task) } else releaseMedia()
            return
        }

        if (task.isLive) {
            retractJob?.cancel()
            post(task)
            return
        }

        // Terminal state: show the result briefly, then retract.
        if (task.phase == TaskState.Phase.Done || task.phase == TaskState.Phase.Failed) {
            post(task)
            if (settings.islandRetractImmediately) {
                clearTask()
                return
            }
            retractJob?.cancel()
            retractJob = scope.launch {
                delay(RETRACT_DELAY_MS)
                if (!_state.value.isLive) {
                    _state.value = TaskState()
                    updateNotification(TaskState())
                }
            }
            return
        }

        releaseMedia()

        // Idle: stay alive on a low-priority placeholder rather than stopping.
        //
        // Stopping here would mean the service is start → stop → start on every
        // recomposition that clears the task, and a foreground service may not be
        // restarted from the background on Android 12+, so the tracker would
        // silently die while the user is in another app — exactly when the island
        // is the whole point. The placeholder is PRIORITY_LOW, silent and
        // non-dismissable-by-accident; users who do not want it can turn off
        // "后台保持连接" in diagnostics.
        if (settings.backgroundLive) startInForeground(placeholderNotification(), KEEPALIVE_ID)
        else getSystemService(NotificationManager::class.java)?.cancel(TaskNotificationBuilder.NOTIFICATION_ID)
    }

    private fun post(task: TaskState) {
        if (settings.notificationEnabled) {
            if (settings.backgroundLive) startInForeground(island.build(task), TaskNotificationBuilder.NOTIFICATION_ID)
            else getSystemService(NotificationManager::class.java)?.notify(TaskNotificationBuilder.NOTIFICATION_ID, island.build(task))
        } else {
            if (settings.backgroundLive) startInForeground(placeholderNotification(), KEEPALIVE_ID)
            getSystemService(NotificationManager::class.java)?.cancel(TaskNotificationBuilder.NOTIFICATION_ID)
        }
        postMedia(task)
    }

    /**
     * Mirror the task onto the media island channel.
     *
     * The focus-notification channel is refused by the ROM (`FocusPlugin:
     * onAuthFailed`) for apps without a Xiaomi-side registration, so MediaStyle
     * is the only island a sideloaded build can actually reach. Posted as a
     * separate notification id so the foreground notification keeps its own
     * lifecycle.
     */
    private fun postMedia(task: TaskState) {
        if (!settings.islandEnabled) { releaseMedia(); return }
        runCatching {
            if (media == null) media = MediaIsland(this)
            val notification = media!!.build(task)
            val nm = getSystemService(NotificationManager::class.java) ?: return
            nm.notify(MediaIsland.NOTIFICATION_ID, notification)
        }.onFailure { Log.w(TAG, "media island post failed", it); RunLog.error("Notification", "media post failed ${it.javaClass.simpleName}") }
    }

    private fun releaseMedia() {
        getSystemService(NotificationManager::class.java)?.cancel(MediaIsland.NOTIFICATION_ID)
        media?.release()
        media = null
    }

    private suspend fun reconcilePreferences() {
        RunLog.info("Notification", "preferences applied island=${settings.islandEnabled} progress=${settings.notificationEnabled} background=${settings.backgroundLive}")
        updateNotification(_state.value)
    }

    private fun placeholderNotification(): Notification =
        NotificationCompat.Builder(this, TaskNotificationBuilder.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("后台连接已开启（系统保活通知）")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()

    /**
     * Enter the foreground with [notification].
     *
     * `startForeground` throws when the notification is malformed (missing channel,
     * invalid small icon) and the framework then kills the process with
     * `CannotPostForegroundServiceNotificationException`. That is unrecoverable
     * *and* costs the user the whole app, so the call is guarded: a failure stops
     * the service (the tracker cannot legally run in the background anyway) instead
     * of taking the process down with it.
     */
    private fun startInForeground(notification: Notification, id: Int) {
        try {
            island.ensureChannel()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    id,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(id, notification)
            }
            val oldId = foregroundId
            foregroundId = id
            if (oldId != null && oldId != id) getSystemService(NotificationManager::class.java)?.cancel(oldId)
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground rejected; stopping the tracker", t)
            RunLog.error("Notification", "foreground rejected ${t.javaClass.simpleName}: ${t.message}")
            runCatching { stopSelf() }
        }
    }

    private fun stopForegroundCompat(removeNotification: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(if (removeNotification) STOP_FOREGROUND_REMOVE else STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(removeNotification)
        }
    }

    // ---- island action handling ---------------------------------------------

    private fun handleIslandAction(intent: Intent) {
        val action = intent.getStringExtra(EXTRA_ACTION_NAME) ?: return
        val approvalId = intent.getStringExtra(EXTRA_APPROVAL_ID).orEmpty()
        val reply = intent.getStringExtra(EXTRA_REPLY_TEXT).orEmpty()

        Log.d(TAG, "island action=$action approval=$approvalId replyLen=${reply.length}")

        if (reply.isNotEmpty()) {
            // A free-text answer was typed in the shade. Hand it to the shell,
            // which owns the WebView and therefore the only authenticated channel
            // back to the harness. The service deliberately cannot send it itself:
            // there is no second socket, and opening one would need its own
            // credentials (see docs/ARCHITECTURE.md, "why listen rather than
            // open a second socket").
            if (approvalId == "debug") {
                RunLog.info("Reply", "debug RemoteInput verified; no live task submitted")
                return
            }
            if (!com.dsh.mobile.core.ReplyPolicy.canSubmit(_state.value, approvalId)) {
                RunLog.warn("Reply", "stale approval rejected; open current task")
                android.widget.Toast.makeText(this, "审批已变化，请打开当前任务检查", android.widget.Toast.LENGTH_LONG).show()
                return
            }
            if (!answerQueue.trySend(Effect.AnswerSubmitted(approvalId, reply)).isSuccess) {
                RunLog.error("Reply", "reply queue full; answer was not submitted")
                return
            }
            Log.i(TAG, "inline answer queued for approval=$approvalId")
            RunLog.info("Reply", "inline answer queued; chars=${reply.length}")
            return
        }

        // A plain tap on the action body: bring the app forward so the user lands
        // on the pending approval instead of hunting for it.
        val launch = Intent(this, com.dsh.mobile.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OPEN_APPROVAL_ID, approvalId)
        }
        startActivity(launch)
    }

    private suspend fun probeCapability() {
        capability = kotlinx.coroutines.withContext(Dispatchers.IO) { IslandSupport.probe(this@DshLiveService) }
        Log.i(TAG, "island capability: ${capability?.level} protocol=${capability?.protocolVersion} focusPerm=${capability?.hasFocusPermission}")
    }

    /** Read the last probed capability (may be null before the probe finishes). */
    fun capability(): IslandSupport.Capability? = capability

    /** Effects the UI reacts to. */
    sealed interface Effect {
        data class ApprovalNeeded(val prompt: String) : Effect
        data class TurnFinished(val success: Boolean) : Effect

        /**
         * The user typed an answer into the notification shade.
         *
         * The shell consumes this and drives the page's own reply affordance;
         * the service cannot do it because only the WebView holds an
         * authenticated channel to the harness.
         */
        data class AnswerSubmitted(val approvalId: String, val text: String) : Effect
    }

    companion object {
        private const val KEEPALIVE_ID = 4100
        val instances = MutableStateFlow<DshLiveService?>(null)
        private const val TAG = "DshLiveService"

        const val ACTION_ISLAND_ACTION = "com.dsh.mobile.action.ISLAND_ACTION"
        const val ACTION_STOP = "com.dsh.mobile.action.STOP"
        const val EXTRA_ACTION_NAME = "action_name"
        const val EXTRA_APPROVAL_ID = "approval_id"

        /** Free-text answer typed in the notification shade (RemoteInput result). */
        const val EXTRA_REPLY_TEXT = "reply_text"
        const val EXTRA_OPEN_APPROVAL_ID = "open_approval_id"

        /** How long a completed task stays visible before the island retracts. */
        private const val RETRACT_DELAY_MS = 4_000L

        @Volatile
        private var instance: DshLiveService? = null

        /** The running service, or null when the tracker is not active. */
        fun current(): DshLiveService? = instance

        /** Start (or reuse) the live tracker. */
        fun start(context: Context) {
            val intent = Intent(context, DshLiveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && SettingsStore(context).backgroundLive) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Stop the tracker and drop its notification. */
        fun stop(context: Context) {
            context.stopService(Intent(context, DshLiveService::class.java))
        }
    }
}
