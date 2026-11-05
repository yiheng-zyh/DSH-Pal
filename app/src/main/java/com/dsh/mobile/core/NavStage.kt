package com.dsh.mobile.core

/**
 * Ordered lifecycle of the main-document navigation.
 *
 * ## Why this exists
 *
 * A user's run log used to show `Load: progress=10%` and then nothing for ninety
 * seconds. "Progress did not move" cannot distinguish a DNS failure from a TCP
 * refusal, a TLS handshake that never finished, or a relay that is up while the
 * desktop is offline — the reader had to guess. A single ordered stage label
 * answers it: the **last** stage reached is the place it stopped.
 *
 * The order is the meaning: [ordinal] may only grow, so a late callback can never
 * move the label backwards and hide the real stall point.
 *
 * Android-free on purpose: core logic classes must stay testable on the JVM
 * (see `.ai/DECISIONS.md`, 2026-10-27). Rendering to the run log is the
 * [NavStageMachine.Listener]'s job, not this file's.
 */
enum class NavStage(val label: String) {
    /** No navigation in flight. */
    IDLE("idle"),

    /** loadUrl was called; the main-document request has not been answered yet. */
    NAV_REQ("nav-req"),

    /** Name resolution for the document host. */
    DNS("dns"),

    /** TCP connect to the document host. */
    TCP("tcp"),

    /** TLS handshake (HTTPS only). */
    TLS("tls"),

    /** Response headers received; the body has not started rendering. */
    HEADERS("headers"),

    /** Response body arrived; the parser is building the document. */
    HTML("html"),

    /** Scripts are being evaluated (a syntax error surfaces in this window). */
    SCRIPT_EVAL("script-eval"),

    /** The app shell reported a mount (a root element gained children). */
    APP_MOUNT("app-mount"),

    /** The page opened its realtime channel. */
    WS_OPEN("ws-open"),

    /** The page reports itself interactive. */
    READY("ready"),

    /** Terminal: something broke after the last successful stage. */
    FAILED("failed");

    /** Stages that are evidence of progress, in order. */
    val progressStage: Boolean get() = this != IDLE && this != FAILED
}

/**
 * Tracks one navigation at a time and records only forward progress.
 *
 * Thread-safety: WebView callbacks, the bridge binder thread and the UI health
 * loop all reach it, so every mutation takes [lock].
 *
 * Failure safety: the listener is user code; an exception there must never break
 * a navigation, so every callback is wrapped.
 */
class NavStageMachine(
    /** Injected so tests can drive time deterministically. */
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    /** Where stage transitions go. Installed by the platform layer (writes RunLog). */
    interface Listener {
        /** A forward stage transition happened. */
        fun onStage(stage: NavStage, sinceStartMs: Long, detail: String) {}

        /** The navigation failed; [reason] is a short structured token. */
        fun onFailed(reason: String, sinceStartMs: Long, detail: String) {}
    }

    private val lock = Any()
    private var listener: Listener? = null
    private var stage: NavStage = NavStage.IDLE
    private var reason: String? = null
    private var startedAt: Long = clock()
    private var lastAt: Long = startedAt
    private var detail: String = ""

    /** Install (or clear) the sink. Platform layer calls this once at startup. */
    fun install(sink: Listener?) {
        synchronized(lock) { listener = sink }
    }

    /**
     * A new main-document navigation starts.
     *
     * Resets the machine and records [NavStage.NAV_REQ] even when the previous
     * navigation had failed: a retry deserves its own timeline.
     */
    fun begin(navDetail: String = "") {
        val at = clock()
        val target: Listener?
        val since: Long
        synchronized(lock) {
            stage = NavStage.NAV_REQ
            lastProgress = NavStage.NAV_REQ
            reason = null
            detail = navDetail
            startedAt = at
            lastAt = at
            target = listener
            since = 0
        }
        dispatch(target) { it.onStage(NavStage.NAV_REQ, since, navDetail) }
    }

    /**
     * Record forward progress.
     *
     * Ignored when the machine has already failed (the failure is the finding),
     * when the target is not ahead of the current stage, or when [NavStage.IDLE]
     * is passed (idle is not progress).
     * @returns true when the stage actually moved.
     */
    fun reach(next: NavStage, note: String = ""): Boolean {
        if (next == NavStage.IDLE) return false
        val at = clock()
        val target: Listener?
        var since = 0L
        synchronized(lock) {
            if (stage == NavStage.FAILED) return false
            if (next == NavStage.FAILED) return false
            if (next.ordinal <= stage.ordinal) return false
            stage = next
            lastProgress = next
            lastAt = at
            detail = note.take(120)
            since = at - startedAt
            target = listener
        }
        dispatch(target) { it.onStage(next, since, detail) }
        return true
    }

    /**
     * Record a terminal failure.
     *
     * [reason] must be a stable token (for example `dns-fail`, `tls-handshake`,
     * `no-response-headers`) because the diagnostic summary matches on it. Asking
     * twice keeps the first reason: the earliest failure is the cause, later ones
     * are cascades.
     */
    fun fail(failureReason: String, note: String = "") {
        val at = clock()
        val target: Listener?
        val since: Long
        val token = failureReason.trim().ifBlank { "unknown" }.lowercase().replace(' ', '-').take(48)
        synchronized(lock) {
            if (stage == NavStage.FAILED) return
            stage = NavStage.FAILED
            reason = token
            lastAt = at
            if (note.isNotBlank()) detail = note.take(120)
            since = at - startedAt
            target = listener
        }
        dispatch(target) { it.onFailed(token, since, detail) }
    }

    /** Current stage. [NavStage.FAILED] once [fail] was recorded. */
    fun current(): NavStage = synchronized(lock) { stage }

    /** Structured failure token, or null while nothing has failed. */
    fun failureReason(): String? = synchronized(lock) { reason }

    /** The furthest stage that was actually reached before any failure. */
    fun lastProgressStage(): NavStage = synchronized(lock) { lastProgress }

    /** True when the navigation is still in flight and has not moved for [stalledMs]. */
    fun stalledFor(nowMs: Long = clock()): Long = synchronized(lock) {
        if (stage == NavStage.IDLE || stage == NavStage.FAILED) 0L else (nowMs - lastAt).coerceAtLeast(0)
    }

    /** True when [stage] has been stuck at or below [ceiling] for at least [stalledMs]. */
    fun isStuckAtOrBelow(ceiling: NavStage, stalledMs: Long, nowMs: Long = clock()): Boolean =
        synchronized(lock) {
            stage != NavStage.IDLE && stage != NavStage.FAILED &&
                stage.ordinal <= ceiling.ordinal && (nowMs - lastAt) >= stalledMs
        }

    /**
     * One-line description for the diagnostic summary, e.g.
     * `FAILED · last=headers · reason=no-response-headers` or `READY · last=ready`.
     */
    fun describe(): String {
        val snapshot = synchronized(lock) {
            Triple(stage, reason, lastProgress)
        }
        val (now, why, last) = snapshot
        return when {
            now == NavStage.FAILED -> "FAILED · last=${last.label}" + (why?.let { " · reason=$it" } ?: "")
            now == NavStage.IDLE -> "IDLE · not started"
            else -> "${now.label.uppercase()} · last=${now.label}"
        }
    }

    private var lastProgress: NavStage = NavStage.IDLE

    private fun dispatch(target: Listener?, block: (Listener) -> Unit) {
        val sink = target ?: return
        // A logging sink must never be able to break the navigation path.
        try {
            block(sink)
        } catch (_: Throwable) {
        }
    }
}

/** Process-wide navigation tracker, for call sites that have no owner to hand. */
object NavStages {
    val machine = NavStageMachine()
}