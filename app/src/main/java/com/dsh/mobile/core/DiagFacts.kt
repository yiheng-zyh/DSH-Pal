package com.dsh.mobile.core

/**
 * A single immutable view of "what the app actually observed" during one
 * navigation, used only by [DiagSummary].
 *
 * Why a bus instead of the summary reading each subsystem directly: the summary
 * must never guess. If a fact was not recorded here, the summary says 未知 — so
 * the collection points are the only place a new fact can enter the log, and
 * that keeps "evidence or silence" enforceable.
 *
 * Android-free on purpose (JVM unit tests drive it directly).
 */
data class ProbeSegments(
    /** DNS resolution time, -1 when not measured. */
    val dnsMs: Long = -1,
    /** TCP connect time, -1 when not measured. */
    val connectMs: Long = -1,
    /** TLS handshake time, -1 when not measured (plain HTTP, or never reached). */
    val tlsMs: Long = -1,
    /** Time from request start to response headers, -1 when not measured. */
    val headersMs: Long = -1,
    /** Whole call, -1 when not measured. */
    val totalMs: Long = -1,
    /** HTTP status when a response arrived. */
    val status: Int? = null,
    /** Short error class name when no response arrived. */
    val error: String? = null,
    /** Host that was probed (already sanitized by the sink, never a token). */
    val host: String = "",
    /** Number of resolved addresses, -1 when unknown. */
    val addresses: Int = -1,
    /** True when the resolver answered through a CNAME chain. */
    val cname: Boolean = false,
)

/** One entry of the three-target comparison probe. */
data class DiagTargetResult(
    /** `origin` / `relay-cp` / `public`. */
    val target: String,
    /** `ok` / `timeout` / `dns-fail` / `refused` / `tls-fail` / `http-<code>`. */
    val result: String,
    val elapsedMs: Long = -1,
)

/** What the page DOM looked like when it was sampled. */
data class PageSnapshot(
    val readyState: String = "",
    val title: String = "",
    /** Path only: the host is not needed to tell a mount failure from a load failure. */
    val path: String = "",
    val rootChildren: Int = -1,
    val bodyLen: Int = -1,
    val editors: Int = -1,
    val scripts: Int = -1,
    val failedScripts: List<String> = emptyList(),
)

/** One resource that did not arrive intact, or arrived suspiciously small. */
data class ResourceFailure(
    val path: String,
    val status: Int = -1,
    val bytes: Long = -1,
    /** Advertised size; a truncated body shows bytes far below this. */
    val expected: Long = -1,
)

/** Aggregate resource accounting for one navigation. */
data class ResourceFacts(
    val total: Int = -1,
    val ok: Int = -1,
    val fail: Int = -1,
    val slow: Int = -1,
    val cached: Int = -1,
    val failures: List<ResourceFailure> = emptyList(),
)

/** A page script error, deduplicated. */
data class JsErrorFacts(
    /** Script file name (path trimmed to its last segment). */
    val source: String,
    val line: Int,
    val column: Int,
    val message: String,
    /** How many times this exact error was seen. */
    val count: Int = 1,
)

/** Transport/proxy/DNS/WebView environment, sampled once per export. */
data class NetEnv(
    val transport: String = "",
    val validated: Boolean? = null,
    val metered: Boolean? = null,
    /** `none`, or `host:port` of the system proxy. */
    val proxy: String = "",
    /** `off` / `opportunistic` / `host:xxx`. */
    val privateDns: String = "",
    val dnsServers: String = "",
    val webviewVersion: String = "",
    /** True when the WebView is older than the modern-syntax baseline. */
    val webviewOld: Boolean = false,
)

/** Immutable evidence bundle handed to the summary renderer. */
data class DiagSnapshot(
    val stageDescription: String = "",
    val progressStage: String = "",
    val failureReason: String? = null,
    val segments: ProbeSegments = ProbeSegments(),
    val targets: List<DiagTargetResult> = emptyList(),
    val page: PageSnapshot? = null,
    val resources: ResourceFacts = ResourceFacts(),
    val jsErrors: List<JsErrorFacts> = emptyList(),
    val injectMissing: List<String> = emptyList(),
    val injectFailed: List<String> = emptyList(),
    val mixedContent: List<String> = emptyList(),
    val net: NetEnv = NetEnv(),
)

/**
 * Process-wide collector of diagnostic evidence.
 *
 * Every recorder is defensive: a diagnostic path must never be able to break a
 * navigation, so bad input is dropped instead of thrown, and lists are capped so
 * a broken page cannot flood the ring buffer or the summary.
 */
object DiagFacts {
    private const val MAX_JS_ERROR_KINDS = 20
    private const val MAX_RESOURCE_FAILURES = 10
    private const val MAX_TARGETS = 6
    private const val MAX_INJECT = 12
    private const val MAX_MIXED = 6

    private val lock = Any()
    private var stageDescription: String = ""
    private var progressStage: String = ""
    private var failureReason: String? = null
    private var segments: ProbeSegments = ProbeSegments()
    private var targets: MutableList<DiagTargetResult> = mutableListOf()
    private var page: PageSnapshot? = null
    private var resources: ResourceFacts = ResourceFacts()
    private var jsErrors: MutableList<JsErrorFacts> = mutableListOf()
    private val injectMissing = mutableListOf<String>()
    private val injectFailed = mutableListOf<String>()
    private val mixedContent = mutableListOf<String>()
    private var net: NetEnv = NetEnv()

    /** Forget everything: a new navigation starts from silence, not from stale facts. */
    fun reset() {
        synchronized(lock) {
            stageDescription = ""
            progressStage = ""
            failureReason = null
            segments = ProbeSegments()
            targets = mutableListOf()
            page = null
            resources = ResourceFacts()
            jsErrors = mutableListOf()
            injectMissing.clear()
            injectFailed.clear()
            mixedContent.clear()
            net = NetEnv()
        }
    }

    // ---- navigation stage ----

    fun recordStage(description: String, reachedStage: String, failReason: String?) {
        synchronized(lock) {
            stageDescription = description.take(120)
            progressStage = reachedStage.take(40)
            failureReason = failReason?.take(48)
        }
    }

    // ---- native probe segments ----

    fun recordSegments(value: ProbeSegments) {
        synchronized(lock) { segments = value }
    }

    // ---- three-target comparison ----

    fun recordTarget(target: String, result: String, elapsedMs: Long) {
        val entry = DiagTargetResult(target.take(24), result.take(32), elapsedMs)
        synchronized(lock) {
            targets.removeAll { it.target == entry.target }
            if (targets.size >= MAX_TARGETS) targets.removeAt(0)
            targets.add(entry)
        }
    }

    // ---- page DOM ----

    fun recordPage(snapshot: PageSnapshot) {
        synchronized(lock) { page = snapshot }
    }

    // ---- resources ----

    fun recordResources(facts: ResourceFacts) {
        synchronized(lock) { resources = facts.copy(failures = facts.failures.take(MAX_RESOURCE_FAILURES)) }
    }

    fun recordResourceFailure(path: String, status: Int, bytes: Long, expected: Long) {
        val failure = ResourceFailure(path.take(120), status, bytes, expected)
        synchronized(lock) {
            val list = resources.failures.toMutableList()
            if (list.size >= MAX_RESOURCE_FAILURES) return
            list.removeAll { it.path == failure.path }
            list.add(failure)
            resources = resources.copy(failures = list)
        }
    }

    // ---- page script errors (deduplicated by source+line+message) ----

    fun recordJsError(source: String, line: Int, column: Int, message: String) {
        val src = source.take(80)
        val msg = message.take(200)
        if (msg.isBlank()) return
        synchronized(lock) {
            val existing = jsErrors.indexOfFirst { it.source == src && it.line == line && it.message == msg }
            if (existing >= 0) {
                jsErrors[existing] = jsErrors[existing].copy(count = jsErrors[existing].count + 1)
                return
            }
            if (jsErrors.size >= MAX_JS_ERROR_KINDS) return
            jsErrors.add(JsErrorFacts(src, line, column, msg))
        }
    }

    // ---- injection self-check ----

    fun recordInjectMissing(name: String) {
        val value = name.take(60)
        if (value.isBlank()) return
        synchronized(lock) {
            if (!injectMissing.contains(value) && injectMissing.size < MAX_INJECT) injectMissing.add(value)
        }
    }

    fun recordInjectFailed(name: String, error: String) {
        val value = (name.take(40) + " " + error.take(40)).trim()
        if (value.isBlank()) return
        synchronized(lock) {
            if (!injectFailed.contains(value) && injectFailed.size < MAX_INJECT) injectFailed.add(value)
        }
    }

    // ---- protocol-sensitive console lines ----

    fun recordMixedContent(line: String) {
        val value = line.take(200)
        if (value.isBlank()) return
        synchronized(lock) {
            if (!mixedContent.contains(value) && mixedContent.size < MAX_MIXED) mixedContent.add(value)
        }
    }

    // ---- environment ----

    fun recordNetEnv(value: NetEnv) {
        synchronized(lock) { net = value }
    }

    /** Immutable bundle for the summary renderer. */
    fun snapshot(): DiagSnapshot = synchronized(lock) {
        DiagSnapshot(
            stageDescription = stageDescription,
            progressStage = progressStage,
            failureReason = failureReason,
            segments = segments,
            targets = targets.toList(),
            page = page,
            resources = resources,
            jsErrors = jsErrors.toList(),
            injectMissing = injectMissing.toList(),
            injectFailed = injectFailed.toList(),
            mixedContent = mixedContent.toList(),
            net = net,
        )
    }
}