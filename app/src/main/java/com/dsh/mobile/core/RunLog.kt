package com.dsh.mobile.core

import android.content.Context
import android.os.Build
import android.os.SystemClock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import android.os.Handler
import android.os.Looper

/**
 * In-app diagnostic log.
 *
 * ## Why this exists
 *
 * When the WebView fails on a user's device there is no ADB attached and no
 * way to read `logcat`, so every report degenerates into "it is black". This
 * keeps a bounded ring of the app's own lifecycle/webview/bridge events on the
 * device, which the diagnostics screen can show and the user can share.
 *
 * Deliberately not a general-purpose logger: it records what is needed to tell
 * *where* startup stopped (config -> webview -> network -> bridge -> page), not
 * chatter.
 *
 * Thread-safety: written from the WebView binder thread, the main thread and
 * coroutines, so the buffer is copy-on-write and each entry is appended
 * atomically.
 */
object RunLog {

    private const val MAX_ENTRIES = LogPolicy.MAX_ENTRIES
    private const val FILE_NAME = "run-log.txt"

    private val entries = ArrayDeque<Entry>()
    private val main = Handler(Looper.getMainLooper())
    private val writer = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "dsh-log-writer").apply { isDaemon = true } }
    @Volatile private var logFile: File? = null
    @Volatile private var previousLog = ""
    @Volatile private var dirty = false
    private val publishScheduled = java.util.concurrent.atomic.AtomicBoolean(false)
    private val publish = Runnable { publishScheduled.set(false); revision.intValue++ }
    private val lock = Any()
    @Volatile private var preferences:android.content.SharedPreferences?=null
    var enabled:Boolean
        get()=preferences?.getBoolean("enabled",true)?:true
        set(value){preferences?.edit()?.putBoolean("enabled",value)?.apply();main.post(publish)}
    fun categoryEnabled(category:LogCategory)=preferences?.getBoolean(category.name,true)?:true
    fun setCategoryEnabled(category:LogCategory,value:Boolean){preferences?.edit()?.putBoolean(category.name,value)?.apply();main.post(publish)}

    /** Wall-clock start of this process, for relative timing. */
    private val startedAt = System.currentTimeMillis()
    private val elapsedBase = SystemClock.elapsedRealtime()

    data class Entry(
        /** Milliseconds since process start. */
        val offsetMs: Long,
        val level: Level,
        val tag: String,
        val message: String,
    ) {
        enum class Level { INFO, WARN, ERROR }
    }

    fun info(tag: String, message: String) = append(Entry.Level.INFO, tag, message)

    fun warn(tag: String, message: String) = append(Entry.Level.WARN, tag, message)

    fun error(tag: String, message: String) = append(Entry.Level.ERROR, tag, message)

    private fun append(level: Entry.Level, tag: String, message: String) {
        if(!enabled||!categoryEnabled(LogCategory.forTag(tag)))return
        val entry = Entry(
            offsetMs = SystemClock.elapsedRealtime() - elapsedBase,
            level = level,
            tag = tag,
            message = LogSanitizer.clean(message).take(LogPolicy.MAX_MESSAGE_CHARS),
        )
        synchronized(lock) {
            entries.addLast(entry)
            while (entries.size > MAX_ENTRIES) entries.removeFirst()
        }
        when (level) {
            Entry.Level.INFO -> android.util.Log.i("DshRun/$tag", entry.message)
            Entry.Level.WARN -> android.util.Log.w("DshRun/$tag", entry.message)
            Entry.Level.ERROR -> android.util.Log.e("DshRun/$tag", entry.message)
        }
        dirty = true
        // Coalesce bursts without postponing indefinitely while events keep arriving.
        if (publishScheduled.compareAndSet(false, true)) main.postDelayed(publish, 120)
    }

    /** Only owns these two bounded diagnostic files; never traverses or deletes user data. */
    fun initialize(context: Context) {
        preferences=context.getSharedPreferences("diagnostic_log_settings",Context.MODE_PRIVATE)
        val current = File(context.filesDir, "diagnostics-current.log")
        val previous = File(context.filesDir, "diagnostics-previous.log")
        writer.execute {
            runCatching {
                if (current.exists()) previous.writeText(LogPolicy.tailUtf8(current.readText(),LogPolicy.MAX_FILE_BYTES))
                previousLog = if (previous.exists()) LogPolicy.tailUtf8(previous.readText(),LogPolicy.MAX_FILE_BYTES) else ""
                logFile = current
                dirty = true
            }
        }
        writer.scheduleWithFixedDelay({
            if (dirty) {
                dirty = false
                runCatching { logFile?.writeText(LogPolicy.tailUtf8(renderEntries(),LogPolicy.MAX_FILE_BYTES)) }
            }
        }, 1, 1, TimeUnit.SECONDS)
        val original = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            this.error("Crash", "thread=${thread.name} ${error.stackTraceToString()}")
            // Sync only on the fatal path; normal log I/O is never on the UI thread.
            synchronized(lock) {
                runCatching { current.writeText(LogPolicy.tailUtf8(current.takeIf {it.exists()}?.readText().orEmpty()+"\n"+renderEntries(),LogPolicy.MAX_FILE_BYTES)) }
            }
            original?.uncaughtException(thread, error)
        }
        info("App", "process started; build=${com.dsh.mobile.BuildConfig.VERSION_NAME}")
    }

    private fun renderEntries(): String = snapshot().joinToString("\n") {
        "+${it.offsetMs}ms ${it.level}/${it.tag}: ${it.message}"
    }

    /** Snapshot for rendering. */
    fun snapshot(): List<Entry> = synchronized(lock) { entries.toList() }

    /**
     * Observable view of the buffer, for UI that must update as lines arrive.
     *
     * Compose cannot observe a plain list, and polling it from a LaunchedEffect
     * would either flicker or cost a frame budget on an idle screen. Exposing a
     * version counter lets the UI re-read [snapshot] only when something changed.
     */
    val revision = androidx.compose.runtime.mutableIntStateOf(0)

    /** Clear the buffer (the diagnostics screen offers this). */
    fun clear() {
        synchronized(lock) { entries.clear() }
        previousLog=""
        writer.execute {previousLog="";runCatching {logFile?.parentFile?.resolve("diagnostics-previous.log")?.writeText("")}}
        main.post(publish)
        dirty = true
    }

    /**
     * Render the buffer as plain text, newest last.
     *
     * Includes the device/build header because a bug report without the WebView
     * version is almost always unactionable — Chromium differences are the most
     * common cause of "the page is blank".
     */
    fun asText(context: Context): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(startedAt))
        val header = buildString {
            appendLine("DSH Pal run log")
            appendLine("app: ${com.dsh.mobile.BuildConfig.VERSION_NAME}; usbBuild=${com.dsh.mobile.BuildConfig.USB_DEVELOPMENT}")
            val settings = SettingsStore(context)
            appendLine("connection: ${settings.activeProfile?.kind?.name ?: "unconfigured"}; saved=${settings.profiles.size}")
            val vpn = com.dsh.mobile.vpn.VpnController.state.value
            appendLine("vpn: ${vpn.active?.name ?: "none"}; up=${vpn.up}; backend=${vpn.tailscaleStatus}; scanning=${vpn.scanning}")
            appendLine("appearance: ${settings.localAppearance.id}")
            appendLine("started: $stamp")
            appendLine("android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("webview: ${webViewVersion(context)}")
            appendLine("-".repeat(48))
        }
        val body = snapshot().joinToString("\n") { e ->
            val t = "+${"%.1f".format(e.offsetMs / 1000f)}s"
            val lvl = when (e.level) {
                Entry.Level.INFO -> "I"
                Entry.Level.WARN -> "W"
                Entry.Level.ERROR -> "E"
            }
            "$t $lvl/${e.tag}: ${e.message}"
        }
        val text = header + LogPolicy.tailUtf8(body,LogPolicy.MAX_FILE_BYTES) + if (previousLog.isNotBlank()) "\n\nPrevious process:\n$previousLog" else ""
        return text.lineSequence().joinToString("\n") { LogSanitizer.clean(it) } +
            "\n\nIssue notes (please fill in):\nExpected behavior:\nActual behavior:\nSteps to reproduce:\nNetwork: Wi-Fi / cellular / VPN / USB\nReview this attachment before publishing.\n"
    }

    /**
     * Write [asText] to a shareable file and return it.
     *
     * Goes to the app's external files dir so `adb pull` and the system share
     * sheet can both reach it without any storage permission.
     */
    fun export(context: Context): File {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val file = File(dir, FILE_NAME)
        file.writeText(LogPolicy.tailUtf8(asText(context),512*1024))
        return file
    }

    /**
     * Installed WebView version.
     *
     * Read via reflection on the framework loader: the public API needs the
     * androidx.webkit dependency, and this whole path must not fail on a device
     * where that dependency is unavailable.
     */
    private fun webViewVersion(context: Context): String = runCatching {
        val pkg = android.webkit.WebView.getCurrentWebViewPackage()
        "${pkg?.packageName} ${pkg?.versionName}"
    }.getOrDefault("unknown")
}
