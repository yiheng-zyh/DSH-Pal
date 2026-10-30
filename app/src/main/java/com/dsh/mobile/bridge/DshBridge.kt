package com.dsh.mobile.bridge

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/**
 * Native half of the page bridge.
 *
 * The injected script ([BridgeScript]) calls [onBridgeMessage] for every frame it
 * wants to hand over. This class does the cheap things (routing, rate limiting)
 * and delegates the meaning to its listeners, so it stays a transport.
 *
 * Threading: `@JavascriptInterface` methods run on a WebView binder thread, not
 * the main thread. Listeners are therefore invoked on the main thread, which is
 * where both the Compose state and the notification pipeline live.
 */
class DshBridge(
    private val profileProvider: () -> com.dsh.mobile.core.ConnectionProfile? = { null },
    private val webViewProvider: () -> WebView?,
) {

    /**
     * The active route's kind, or null when nothing is configured.
     *
     * The pairing failure text has to differ for the embedded-VPN route: there a
     * dead-looking page sits behind a working tunnel, so "re-pair this entry"
     * understates what the user has to do. Read through the provider so the
     * answer tracks the currently selected profile rather than a captured one.
     */
    fun profileKind(): com.dsh.mobile.core.ConnectionProfile.Kind? = profileProvider()?.kind

    /** Receives bridge callbacks. All methods are invoked on the main thread. */
    interface Listener {
        /** One raw harness event frame, as JSON text. */
        fun onEvent(raw: String)

        /** The page finished installing the bridge. */
        fun onPageReady(href: String) = Unit

        fun onSessionTitle(title: String) = Unit

        /** The page hit a 401: the pairing cookie is gone or expired. */
        fun onAuthLost() = Unit

        /** A WebSocket opened or closed; useful for connection indicators. */
        fun onSocketState(connected: Boolean) = Unit
        fun onDismissKeyboard() = Unit

        /** The page reported an uncaught error. */
        fun onPageError(message: String) = Unit

        /** Network availability as seen by the page. */
        fun onNetwork(online: Boolean) = Unit
        fun onBusinessHealth(health: com.dsh.mobile.core.BusinessHealth) = Unit
        fun onPairingEstablished() = Unit
    }

    private val boundProfileId = profileProvider()?.id
    private val main = Handler(Looper.getMainLooper())
    private val calls = AtomicLong(0)
    private val dropped = AtomicLong(0)
    private val taskSockets = com.dsh.mobile.core.TaskSocketTracker()
    private var lastDropLog = 0L
    private var lastBusinessSummary = ""

    @Volatile
    var listener: Listener? = null
        set(value) {
            field = value
            if (value != null) flushPending()
        }

    /**
     * Events that arrived before a listener was attached.
     *
     * The page starts emitting as soon as it boots, which is typically *before*
     * the Compose shell has finished installing its listener. Without this buffer
     * those first frames (including the socket open and the first turn) are
     * silently discarded and the strip stays stuck at "disconnected".
     */
    private val pending = ArrayDeque<String>()
    private val pendingLock = Any()

    private companion object {
        const val TAG = "DshBridge"

        /**
         * Emit full frames as indexed logcat chunks.
         *
         * Left off normally: it costs dozens of log lines per frame, which
         * buries everything else. Flip to true when the mux wire format needs
         * re-deriving (that is how the `item → value → event` nesting in
         * [com.dsh.mobile.core.EventReducer] was confirmed on a device).
         */
        const val DIAG_FRAMES = false

        /**
         * Frames accepted before the rate limiter engages. A busy turn emits on
         * the order of tens of events, so this is far above normal traffic while
         * still bounding a runaway page.
         */
        const val RATE_WINDOW_MAX = 20_000

        /** Upper bound on frames buffered while no listener is attached. */
        const val PENDING_MAX = 512
    }

    /**
     * Entry point for the page. Runs on a binder thread.
     *
     * The harness can emit events in bursts (a turn start fans out into several
     * events at once), so this method is cheap by design: it parses the envelope
     * kind, forwards, and returns.
     */
    @JavascriptInterface
    fun onBridgeMessage(json: String) {
        val n = calls.incrementAndGet()

        val parsed = try {
            JSONObject(json)
        } catch (t: Throwable) {
            Log.w(TAG, "bridge: unparseable message", t)
            return
        }

        val kind = parsed.optString("kind")

        // A single misbehaving page must not be able to spin the CPU: allow a
        // generous budget (the harness is chatty) and drop beyond it.
        if (n > RATE_WINDOW_MAX) {
            dropped.incrementAndGet()
            val now = System.currentTimeMillis()
            if (now - lastDropLog > 10_000) {
                lastDropLog = now
                Log.w(TAG, "bridge: dropping frames (dropped=${dropped.get()})")
            }
            return
        }

        when (kind) {
            "pair-established" -> post { listener?.onPairingEstablished() }
            "business-health" -> {
                val data = parsed.optJSONObject("payload") ?: return
                val health = com.dsh.mobile.core.BusinessHealth(data.optInt("sampleCount").coerceIn(0,10),data.optInt("failurePercent",-1).coerceIn(-1,100),data.optInt("slowPending").coerceIn(0,1000),data.optInt("resourceFailures").coerceIn(0,1000),data.optLong("lastSuccessAgeMs",-1),data.optLong("lastLatencyMs"),data.optInt("loadingResources").coerceIn(0,256),data.optLong("oldestResourceMs").coerceAtLeast(0),data.optInt("restrictedPlugins").coerceIn(0,100))
                val summary="samples=${health.sampleCount} failed=${health.failurePercent} slow=${health.slowPending} resources=${health.resourceFailures} loading=${health.loadingResources} restricted=${health.restrictedPlugins} verified=${health.verified}"
                if(summary!=lastBusinessSummary) {
                    lastBusinessSummary=summary
                    com.dsh.mobile.core.RunLog.info("Network","business $summary latencyMs=${health.lastLatencyMs}")
                }
                post { listener?.onBusinessHealth(health) }
            }
            "web-capability" -> {
                val data = parsed.optJSONObject("payload")
                val feature = data?.optString("feature")
                if (feature in setOf("microphone", "recording", "file-upload")) {
                    val phase = data!!.optString("phase").filter { it.isLetter() }.take(30)
                    val detail = data.optString("detail").filter { it.isLetterOrDigit() }.take(60)
                    com.dsh.mobile.core.RunLog.info(if (feature == "file-upload") "File" else "Permission", "web $feature phase=$phase detail=$detail")
                }
            }
            "dismiss-keyboard" -> post { listener?.onDismissKeyboard() }
            "page-probe-result" -> {
                val data=parsed.optJSONObject("payload") ?: return
                val id=data.optString("id")
                val status=data.optInt("status").takeIf {it in 100..599}
                val result=com.dsh.mobile.core.Reachability(status,data.optLong("elapsedMs").coerceIn(0,60_000),data.optString("error").takeIf {it.isNotBlank() && it!="null"})
                post {pageProbeCallbacks.remove(id)?.invoke(result)}
            }
            "quick-reply-result" -> {
                val data=parsed.optJSONObject("payload") ?: return
                val id=data.optString("id");val outcome=data.optString("outcome")
                post {quickCallbacks.remove(id)?.invoke(outcome)}
            }
            "page-title" -> {
                val title = parsed.optString("payload").trim().take(180)
                if (title.isNotBlank()) post { listener?.onSessionTitle(title) }
            }
            "ui-layout" -> {
                com.dsh.mobile.core.RunLog.info("Layout", parsed.optString("payload").take(500))
            }
            "ui-motion" -> {
                com.dsh.mobile.core.RunLog.info("Motion", parsed.optString("payload").take(500))
            }
            "event" -> {
                val payload = parsed.optString("payload")
                if (payload.isNotEmpty()) {
                    Log.i(TAG, "event frame received chars=${payload.length}")
                    logFrameInChunks(payload)
                    dispatchEvent(payload)
                }
            }
            "ready" -> {
                val href = parsed.optJSONObject("payload")?.optString("href").orEmpty()
                Log.i(TAG, "bridge ready, hasListener=${listener != null}")
                com.dsh.mobile.core.RunLog.info("Bridge", "ready ${href.take(100)}")
                post { listener?.onPageReady(href) }
            }
            "auth-lost" -> post { listener?.onAuthLost() }
            "ws" -> {
                val url = parsed.optJSONObject("payload")?.optString("url").orEmpty()
                Log.i(TAG, "ws created")
                com.dsh.mobile.core.RunLog.info("Bridge", "ws ${url.take(80)}")
            }
            "ws-open" -> {
                val url = parsed.optJSONObject("payload")?.optString("url").orEmpty()
                Log.i(TAG, "ws OPEN, hasListener=${listener != null}")
                com.dsh.mobile.core.RunLog.info("Bridge", "ws OPEN ${url.take(80)}")
                // Only the harness mux (and the sidebar channel) carries the task
                // stream. The page also cycles a ws://127.0.0.1:<desktop>/ingest
                // socket which can never connect on a phone; letting its close
                // event clear the flag left the status bar stuck on "connecting".
                val id = parsed.optJSONObject("payload")?.optString("socketId").orEmpty().ifBlank { url }
                if (isHarnessStream(url)) post { listener?.onSocketState(taskSockets.update(id, true)) }
            }
            "ws-close" -> {
                val url = parsed.optJSONObject("payload")?.optString("url").orEmpty()
                Log.i(TAG, "ws CLOSED")
                com.dsh.mobile.core.RunLog.warn("Bridge", "ws CLOSED ${url.take(80)}")
                val id = parsed.optJSONObject("payload")?.optString("socketId").orEmpty().ifBlank { url }
                if (isHarnessStream(url)) post { listener?.onSocketState(taskSockets.update(id, false)) }
            }
            "page-error" -> {
                val msg = parsed.optJSONObject("payload")?.optString("message").orEmpty()
                com.dsh.mobile.core.RunLog.error("Bridge", "page-error $msg")
                post { listener?.onPageError(msg) }
            }
            "net" -> {
                val online = parsed.optJSONObject("payload")?.optBoolean("online", true) ?: true
                post { listener?.onNetwork(online) }
            }
            else -> Log.v(TAG, "bridge: ignoring kind=$kind")
        }
    }

    /**
     * Whether [url] is the socket that actually carries harness task events.
     *
     * Matches the mux and the sidebar agent-opens channel, and deliberately
     * rejects the page's local ingest socket (127.0.0.1) and anything else.
     */
    private fun isHarnessStream(url: String): Boolean {
        return com.dsh.mobile.core.HarnessSocketPolicy.isTaskStream(url, profileProvider(), com.dsh.mobile.BuildConfig.USB_DEVELOPMENT)
    }

    /**
     * Log a frame in fixed-size chunks so it survives logcat's per-line cap.
     *
     * A single logcat line truncates around 100 characters of payload, which is
     * far shorter than a projection frame. Emitting indexed chunks lets the
     * frames be reassembled offline when working out the wire protocol.
     */
    private fun logFrameInChunks(payload: String) {
        if (!DIAG_FRAMES) return
        val chunk = 90
        val total = (payload.length + chunk - 1) / chunk
        val tag = "DsFrame"
        for (i in 0 until total) {
            val from = i * chunk
            val to = minOf(from + chunk, payload.length)
            Log.i(tag, "#$i/$total ${payload.substring(from, to)}")
        }
    }

    /** Hand one event frame to the listener, buffering it if nobody is attached yet. */
    private fun dispatchEvent(payload: String) {
        val target = listener
        if (target != null) {
            post { if (listener === target) target.onEvent(payload) }
            return
        }
        synchronized(pendingLock) {
            if (pending.size >= PENDING_MAX) pending.removeFirst()
            pending.addLast(payload)
        }
    }

    /** Replay buffered frames to the newly attached listener. */
    private fun flushPending() {
        val target = listener ?: return
        val replay: List<String> = synchronized(pendingLock) {
            val copy = pending.toList()
            pending.clear()
            copy
        }
        if (replay.isEmpty()) return
        Log.i(TAG, "replaying ${replay.size} buffered frames")
        post {
            if (listener === target) replay.forEach { target.onEvent(it) }
        }
    }

    /** Inject the bridge script into [webView]. Safe to call repeatedly. */
    fun install(webView: WebView, forwardEvents: Boolean = true) {
        val script = BridgeScript.build(forwardEvents = forwardEvents, forwardState = true) + "\n" + WebMotionScript.build() + "\n" + WebLayoutScript.build(webView.context)
        webView.evaluateJavascript(script) { result ->
            Log.i(TAG, "bridge install result=$result")
        }
    }

    /** Ask the page to scroll the conversation to the newest message. */
    @SuppressLint("JavascriptInterface")
    fun scrollToLatest() {
        val view = webViewProvider()
        if (view == null) {
            com.dsh.mobile.core.RunLog.warn("WebView", "scroll skipped; no view")
            return
        }
        view.evaluateJavascript(
            """
            (function(){
              try {
                var el = document.querySelector('[class$="_scroll"], [class$="_scrollBody"], main');
                if (el) el.scrollTop = el.scrollHeight;
              } catch (e) {}
            })();
            """.trimIndent(),
            null,
        )
    }

    /** Reset the per-window call budget; called when the page reloads. */
    fun resetBudget() {
        calls.set(0)
        dropped.set(0)
        post { taskSockets.reset(); listener?.onSocketState(false) }
    }

    private var quickSource: String? = null
    private val pageProbeCallbacks = mutableMapOf<String,(com.dsh.mobile.core.Reachability)->Unit>()
    private var pageProbeSource:String?=null
    suspend fun checkPageReachability():com.dsh.mobile.core.Reachability = kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
        val id=java.util.UUID.randomUUID().toString()
        continuation.invokeOnCancellation {main.post {pageProbeCallbacks.remove(id)}}
        main.post {
            if(!continuation.isActive)return@post
            val view=webViewProvider()
            val profile=profileProvider()
            if(view==null || profile==null || profile.id!=boundProfileId) {
                continuation.resumeWith(Result.success(com.dsh.mobile.core.Reachability(error="browser-not-ready")))
                return@post
            }
            val source=pageProbeSource ?: runCatching {view.context.assets.open("browser-probe.js").bufferedReader().use {it.readText()}}.getOrNull()?.also {pageProbeSource=it}
            if(source==null){continuation.resumeWith(Result.success(com.dsh.mobile.core.Reachability(error="probe-unavailable")));return@post}
            pageProbeCallbacks[id]={result->if(continuation.isActive)continuation.resumeWith(Result.success(result))}
            view.evaluateJavascript(source+";window.__DSH_PROBE_PAGE__("+JSONObject.quote(profile.baseUrl)+","+JSONObject.quote(id)+")",null)
            main.postDelayed({pageProbeCallbacks.remove(id)?.invoke(com.dsh.mobile.core.Reachability(elapsedMs=14_000,error="browser-probe-timeout"))},14_000)
        }
    }

    private val quickCallbacks = mutableMapOf<String,(String)->Unit>()
    fun captureQuickContext(callback:(String?)->Unit) {
        val view=webViewProvider();val profile=profileProvider()?.id
        if(view==null || profile==null || profile!=boundProfileId){callback(null);return}
        val source=quickSource ?: runCatching {view.context.assets.open("quick-composer.js").bufferedReader().use {it.readText()}}.getOrNull()?.also {quickSource=it}
        if(source==null){callback(null);return}
        view.evaluateJavascript(source+";window.__DSH_QUICK_COMPOSER__.context()") { result ->
            val c=runCatching {JSONObject(result)}.getOrNull()
            callback(if(c!=null && profileProvider()?.id==profile) JSONObject().put("profile",profile).put("context",c).toString() else null)
        }
    }
    fun stageQuickDraft(text:String,callback:(String)->Unit) {
        val view=webViewProvider()
        if(view==null || profileProvider()?.id!=boundProfileId){callback("context-changed");return}
        val source=quickSource ?: runCatching {view.context.assets.open("quick-composer.js").bufferedReader().use {it.readText()}}.getOrNull()?.also {quickSource=it}
        if(source==null){callback("no-composer");return}
        view.requestFocus()
        val id=java.util.UUID.randomUUID().toString()
        quickCallbacks[id]=callback
        view.evaluateJavascript(source+";window.__DSH_QUICK_COMPOSER__.stage("+JSONObject.quote(text)+").then(function(outcome){DshNative.onBridgeMessage(JSON.stringify({kind:'quick-reply-result',payload:{id:"+JSONObject.quote(id)+",outcome:outcome}}))})",null)
        main.postDelayed({quickCallbacks.remove(id)?.invoke("timeout")},3000)
    }
    fun submitQuick(text:String,token:String,callback:(String)->Unit) {
        val saved=runCatching {JSONObject(token)}.getOrNull()
        val view=webViewProvider();val profile=profileProvider()?.id
        if(view==null || saved==null || profile==null || saved.optString("profile")!=profile || profile!=boundProfileId){callback("context-changed");return}
        val source=quickSource ?: runCatching {view.context.assets.open("quick-composer.js").bufferedReader().use {it.readText()}}.getOrNull()?.also {quickSource=it}
        if(source==null){callback("no-composer");return}
        val id=java.util.UUID.randomUUID().toString()
        quickCallbacks[id]=callback
        val literal=JSONObject.quote(text);val idLiteral=JSONObject.quote(id)
        view.evaluateJavascript(source+";window.__DSH_QUICK_COMPOSER__.submit("+literal+","+saved.getJSONObject("context").toString()+","+idLiteral+").then(function(outcome){DshNative.onBridgeMessage(JSON.stringify({kind:'quick-reply-result',payload:{id:"+idLiteral+",outcome:outcome}}))})",null)
        main.postDelayed({quickCallbacks.remove(id)?.invoke("timeout")},3000)
    }

    /**
     * Deliver a free-text answer typed in the notification shade.
     *
     * The service cannot send this itself — only the page holds an authenticated
     * channel to the harness — so the text is injected into the chat composer and
     * submitted exactly the way a keyboard would.
     *
     * This is intentionally best-effort and defensive: the DSH web UI is a React
     * app whose DOM we do not control, so the script tries the composer, then the
     * page's own submit affordances, and reports what happened back through
     * `console.log` for `adb logcat`.
     *
     * @param text the answer to send.
     */
    fun submitAnswer(text: String) {
        if (text.isEmpty()) return
        // JSON-encode rather than interpolating: an answer can contain quotes,
        // backslashes or newlines, any of which would break out of a JS literal.
        val literal = org.json.JSONObject.quote(text)
        val view = webViewProvider()
        if (view == null) {
            com.dsh.mobile.core.RunLog.error("Reply", "no WebView; answer was not sent")
            return
        }
        com.dsh.mobile.core.RunLog.info("Reply", "dispatch chars=${text.length}")
        view.evaluateJavascript(
            """
            (function(){
              try {
                var value = $literal;

                // A visible, enabled textarea/contenteditable is the composer.
                var box = Array.prototype.slice
                  .call(document.querySelectorAll('textarea, [contenteditable="true"]'))
                  .filter(function(el){
                    var r = el.getBoundingClientRect();
                    return r.width > 0 && r.height > 0 && !el.disabled && !el.readOnly;
                  })
                  .pop();
                if (!box) { console.log('dsh-answer: no composer'); return 'no-composer'; }

                if ((box.value || box.textContent || '').trim()) return 'draft-occupied';
                box.focus();
                if (box.tagName === 'TEXTAREA') {
                  // React tracks the native value setter; assigning .value directly
                  // does not fire its onChange, so the framework would submit an
                  // empty composer.
                  var setter = Object.getOwnPropertyDescriptor(
                    window.HTMLTextAreaElement.prototype, 'value').set;
                  setter.call(box, value);
                } else {
                  box.textContent = value;
                }
                box.dispatchEvent(new Event('input', { bubbles: true }));

                // Submit: prefer an explicit send button, else Enter.
                var send = Array.prototype.slice
                  .call(document.querySelectorAll('button'))
                  .filter(function(b){
                    var t = (b.getAttribute('aria-label') || b.textContent || '').trim();
                    return /发送|send/i.test(t) && !b.disabled;
                  })
                  .pop();
                if (send) { send.click(); console.log('dsh-answer: clicked send'); return 'clicked'; }

                box.dispatchEvent(new KeyboardEvent('keydown', {
                  key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true
                }));
                console.log('dsh-answer: dispatched Enter');
                return 'enter';
              } catch (e) {
                console.log('dsh-answer: failed ' + e);
                return 'error';
              }
            })();
            """.trimIndent(),
        ) { result ->
            val outcome = result?.trim('"').orEmpty()
            com.dsh.mobile.core.RunLog.info("Reply", "DOM result=$outcome")
            if (outcome != "clicked") {
                android.widget.Toast.makeText(view.context,
                    "回复尚未确认发送，请打开任务检查（$outcome）", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun post(block: () -> Unit) {
        val deliver = { if (profileProvider()?.id == boundProfileId) block() }
        if (Looper.myLooper() == Looper.getMainLooper()) deliver() else main.post { deliver() }
    }
}
