package com.dsh.mobile.core

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response

/**
 * Per-call timing breakdown of one OkHttp request.
 *
 * ## Why this exists
 *
 * A real RELAY incident (2026-11-05) froze the page at 10% for ninety seconds
 * while the log repeated `health reachable=false http=null error=... rttMs=2`
 * eight times. That line proves only that *something* returned fast. It cannot
 * separate DNS from TCP, TCP from TLS, or "connected, no response headers" from
 * "relay up, desktop offline". This class turns one call into the four numbers a
 * `curl -w` line gives — dns / tcp / tls / time-to-headers — plus the total.
 *
 * ## Threading
 *
 * OkHttp calls the listener from whatever thread is doing the work (an engine
 * thread, sometimes the caller's), so every field is guarded by [lock].
 *
 * ## One instance per call
 *
 * An [EventListener] is scoped to a single call. Reusing one trace across
 * concurrent calls interleaves the timings, so a trace is created per probe via
 * [clientFor], which builds a per-call view of a shared client.
 *
 * ## Failure safety
 *
 * Every callback swallows its own exceptions: a diagnostic must never be able to
 * turn a working request into a failed one.
 *
 * @param host host being probed. Recorded for the log line; never a credential.
 */
class NativeProbeTrace(private val host: String = "") : EventListener() {

    private val lock = Any()
    private var callStartNanos = -1L
    private var dnsStartNanos = -1L
    private var dnsMs = -1L
    private var addressCount = -1
    private var connectStartNanos = -1L
    private var connectMs = -1L
    private var tlsStartNanos = -1L
    private var tlsMs = -1L
    private var headersMs = -1L
    private var totalMs = -1L
    private var status: Int? = null
    private var error: String? = null

    /** True when DNS started but never completed: the call died in resolution. */
    private var dnsUnresolved: Boolean = false

    // ---- OkHttp callbacks ----

    override fun callStart(call: Call) = guard {
        synchronized(lock) { if (callStartNanos < 0) callStartNanos = System.nanoTime() }
    }

    override fun dnsStart(call: Call, hostname: String) = guard {
        synchronized(lock) {
            dnsStartNanos = System.nanoTime()
            dnsUnresolved = true
        }
    }

    override fun dnsEnd(call: Call, hostname: String, inetAddressList: List<InetAddress>) = guard {
        synchronized(lock) {
            dnsMs = elapsed(dnsStartNanos)
            addressCount = inetAddressList.size
            dnsUnresolved = false
        }
    }

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) = guard {
        synchronized(lock) { connectStartNanos = System.nanoTime() }
    }

    override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) = guard {
        synchronized(lock) { connectMs = elapsed(connectStartNanos) }
    }

    override fun connectFailed(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
        ioe: IOException,
    ) = guard {
        synchronized(lock) {
            connectMs = elapsed(connectStartNanos)
            noteError(ioe)
        }
    }

    override fun secureConnectStart(call: Call) = guard {
        synchronized(lock) { tlsStartNanos = System.nanoTime() }
    }

    override fun secureConnectEnd(call: Call, handshake: Handshake?) = guard {
        synchronized(lock) { tlsMs = elapsed(tlsStartNanos) }
    }

    override fun responseHeadersEnd(call: Call, response: Response) = guard {
        synchronized(lock) {
            headersMs = elapsed(callStartNanos)
            status = response.code
        }
    }

    override fun callEnd(call: Call) = guard {
        synchronized(lock) { totalMs = elapsed(callStartNanos) }
    }

    override fun callFailed(call: Call, ioe: IOException) = guard {
        synchronized(lock) {
            totalMs = elapsed(callStartNanos)
            noteError(ioe)
        }
    }

    // ---- stamps from the probe layer ----

    /** Record the status the caller observed, in case the listener missed it. */
    fun markStatus(code: Int) = guard {
        synchronized(lock) { status = code }
    }

    /** Record the exception that ended the call, by class name. */
    fun markError(name: String?) = guard {
        synchronized(lock) { if (name != null && error == null) error = name }
    }

    /** Stamp the whole-call duration measured by the caller. */
    fun markTotal(elapsedMs: Long) = guard {
        synchronized(lock) { totalMs = elapsedMs.coerceAtLeast(0) }
    }

    /**
     * Snapshot for [DiagFacts.recordSegments].
     *
     * Unmeasured segments are -1, not 0: "dns=0ms" reads as a cached answer,
     * while -1 reads as "this step never happened" — the actual finding when a
     * pooled connection was reused or when the call died before TCP.
     *
     * [ProbeSegments.cname] is always false: OkHttp's listener never sees the
     * resolver alias, and the summary is allowed to quote only recorded facts, so
     * claiming one would be invention. [ProbeSegments.addresses] is the evidence
     * that is actually available from a resolution.
     */
    fun segments(): ProbeSegments = synchronized(lock) {
        ProbeSegments(
            dnsMs = dnsMs,
            connectMs = connectMs,
            tlsMs = tlsMs,
            headersMs = headersMs,
            totalMs = totalMs,
            status = status,
            // The exception class name is kept verbatim: log line 5 quotes it
            // (`Probe: dns-fail UnknownHostException ...`). [dnsFailed] reads this
            // name plus the address count to decide which Probe line to print.
            error = error,
            host = host,
            addresses = if (dnsUnresolved) 0 else addressCount,
            cname = false,
        )
    }

    private fun noteError(ioe: IOException) {
        if (error == null) error = ioe.javaClass.simpleName
    }

    private fun elapsed(startNanos: Long): Long =
        if (startNanos < 0) -1L
        else TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos).coerceAtLeast(0)

    private inline fun guard(block: () -> Unit) {
        try {
            block()
        } catch (_: Throwable) {
            // A diagnostic never breaks the request it describes.
        }
    }

    companion object {
        /**
         * Install [trace] on [builder]: every call from the built client reports
         * into it. Correct only when that client serves one in-flight call.
         */
        fun attach(builder: OkHttpClient.Builder, trace: NativeProbeTrace): OkHttpClient.Builder =
            builder.eventListenerFactory { trace }

        /**
         * Per-call view of [base] wired to [trace].
         *
         * `newBuilder()` shares the parent's dispatcher, connection pool and DNS,
         * so this allocates a config object rather than a thread pool.
         */
        fun clientFor(base: OkHttpClient, trace: NativeProbeTrace): OkHttpClient =
            attach(base.newBuilder(), trace).build()

        /** True when a throwable means the TLS handshake itself broke. */
        fun isTlsFailure(error: Throwable?): Boolean =
            error is SSLException || error?.javaClass?.name?.startsWith("javax.net.ssl.") == true

        /** True when a throwable means the name could not be resolved. */
        fun isDnsFailure(error: Throwable?): Boolean =
            error is java.net.UnknownHostException ||
                error?.javaClass?.simpleName?.contains("UnknownHost") == true
    }
}

/**
 * A HEAD call against [url] on a per-call view of [base], timed by this trace.
 *
 * Only the path it is given is requested and no credential is attached: the
 * diagnostic path must never redeem a one-shot pairing token.
 */
internal fun NativeProbeTrace.headCall(base: OkHttpClient, url: String): Call =
    NativeProbeTrace.clientFor(base, this).newCall(
        Request.Builder().url(url).head().header("Cache-Control", "no-cache").build()
    )

/**
 * True when the breakdown shows the call died in name resolution.
 *
 * Two kinds of evidence, because a resolver failure reaches us either as the
 * exception OkHttp rethrew (`UnknownHostException`, possibly wrapped) or as a
 * resolution that started and never returned — the latter leaves `dnsMs` at -1,
 * so comparing timings alone would miss it. [ProbeSegments.addresses] is 0 only
 * when a resolution began and did not finish: a successful lookup either returns
 * at least one address or throws, and a never-attempted one stays -1.
 *
 * The log line still quotes [ProbeSegments.error] verbatim
 * (`Probe: dns-fail UnknownHostException host=… after=…ms`); this only answers
 * "which of the three Probe lines should be printed".
 */
fun ProbeSegments.dnsFailed(): Boolean =
    error?.contains("UnknownHost") == true ||
        (addresses == 0 && connectMs < 0 && status == null)

/**
 * True when the breakdown shows the TLS handshake is what failed.
 *
 * Keyed on the exception name only. A timeout after DNS is **not** evidence of a
 * TLS failure — it is equally a stalled TCP session or a silent network — and
 * mislabelling it would send the reader to the wrong fix. When the handshake
 * starts but never returns, OkHttp rethrows an `SSL*`/`SSLHandshakeException`, so
 * the name is the honest signal.
 */
fun ProbeSegments.tlsFailed(): Boolean =
    error?.let { it.startsWith("SSL") || it.contains("Certificate") || it.contains("Handshake") } == true

/** True when any segment was actually measured; -1 means "this step never happened". */
val ProbeSegments.measured: Boolean get() = dnsMs >= 0 || connectMs >= 0 || tlsMs >= 0 || headersMs >= 0 || status != null

/**
 * The contract's line 4, ready to hand to `RunLog.info("Probe", …)`:
 * `Probe: dns=<N>ms addrs=<N> tcp=<N>ms tls=<N>ms status=<code> total=<N>ms`
 *
 * Returns null when **no response arrived**, because a failed call is line 5 or
 * line 6 (`dns-fail` / `tls-fail`), not a breakdown of a request that never
 * completed — printing all -1 would read as "every hop was instant".
 *
 * -1 is printed verbatim for a segment that was not measured: an HTTPS call that
 * reused a pooled connection has no DNS/TCP/TLS timings to report, and the
 * contract's own data class documents -1 as that case. Never contains a URL,
 * query or credential: only [ProbeSegments.host].
 */
fun ProbeSegments.probeLine(): String? {
    val code = status ?: return null
    return "Probe: dns=${dnsMs}ms addrs=$addresses tcp=${connectMs}ms tls=${tlsMs}ms status=$code total=${totalMs}ms"
}

/**
 * The contract's line 5 (DNS failed) — WARN level — or null when the breakdown
 * does not show a resolution failure:
 * `Probe: dns-fail <ExceptionSimpleName> host=<host> after=<N>ms`
 *
 * `after` prefers the DNS segment (that is where the time went) and falls back to
 * the total when the resolver never returned a timing.
 */
fun ProbeSegments.dnsFailLine(): String? = when {
    !dnsFailed() -> null
    else -> "Probe: dns-fail ${error ?: "UnknownHostException"} host=$host after=${if (dnsMs >= 0) dnsMs else totalMs}ms"
}

/**
 * The contract's line 6 (TLS failed) — WARN level — or null:
 * `Probe: tls-fail <ExceptionSimpleName> after=<N>ms`
 */
fun ProbeSegments.tlsFailLine(): String? = when {
    !tlsFailed() -> null
    else -> "Probe: tls-fail ${error ?: "SSLException"} after=${if (tlsMs >= 0) tlsMs else totalMs}ms"
}
