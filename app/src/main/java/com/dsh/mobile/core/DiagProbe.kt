package com.dsh.mobile.core

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.resume

/**
 * Three-target comparison probe: the capability incident A was missing.
 *
 * ## The question it answers
 *
 * When the RELAY channel sat at 10% for ninety seconds, nothing in the log could
 * say whether the phone failed to resolve the instance hostname, failed to reach
 * the relay control plane, or reached both while the desktop behind the tunnel was
 * offline. Those are three different fixes. One result set separates them:
 *
 * | target     | what it proves                                          |
 * |------------|----------------------------------------------------------|
 * | `origin`   | the instance entry (relay/tunnel hostname) is answering   |
 * | `relay-cp` | the relay control plane — its DNS and edge — is reachable |
 * | `public`   | the phone itself has a working Internet path              |
 *
 * The pattern, not a single line, is the finding: `origin=dns-fail` with
 * `relay-cp` and `public` answering means the instance name is gone (lease
 * released, DNS record missing), not that the phone is offline. An `http-NNN`
 * result still counts as "that hop answered" — the control plane's root path has
 * no reason to return 200, and a status is evidence of a live path.
 *
 * ## Constraints
 *
 * * Observation only: nothing here vetoes or triggers a switch; the routing
 *   decision is unchanged.
 * * Only `/` is requested with no query string, so a one-shot pairing token can
 *   never be redeemed by this path.
 * * Every target is defensive: an exception becomes a result token, never a throw,
 *   so a diagnostic cannot fail a navigation.
 * * Android-free: callable from JVM unit tests.
 */
object DiagProbe {

    /** Per-target deadline. Long enough for a lossy mobile link, short enough to be useful. */
    private const val TARGET_TIMEOUT_MS = 3_000L

    /**
     * Relay control plane origin.
     *
     * Hard-coded because the App has no constant for the apex: `ConnectionAddress`
     * only knows the `.dsh-market.com` **suffix** used to classify a scanned QR as
     * a RELAY route (`core/ConnectionProfile.kt`). The origin itself comes from the
     * shipping plugin the App pairs with — `third_party/dsh-remote-web-ui/
     * src/relay-control.ts` (`RELAY_CLAIM_URL = https://dsh-market.com/api/relay/claim`)
     * and `relay-registry.ts` (`RELAY_REGISTER_URL`) — whose apex host this is.
     */
    private const val RELAY_CONTROL_PLANE_ORIGIN = "https://dsh-market.com"

    /**
     * Public reference endpoint: the fastest way to say "the phone's own path works".
     * Chosen because it answers from mainland Chinese networks where a
     * Cloudflare-fronted entry may not, so a failure here is really local.
     */
    private const val PUBLIC_REFERENCE_ORIGIN = "https://www.baidu.com"

    /**
     * Probe every target concurrently and record each result on [DiagFacts].
     *
     * @param origin the selected route's origin (`scheme://host[:port]`). A blank or
     *   unparsable origin is **omitted** from the results rather than reported as a
     *   failure: "dns-fail" would claim a resolution was attempted and did not
     *   happen, and the summary is only allowed to quote facts that were observed.
     * @return one result per target that could be honestly classified, in the order
     *   origin / relay-cp / public.
     */
    suspend fun run(origin: String): List<DiagTargetResult> = withContext(Dispatchers.IO) {
        val targets = buildList {
            entryUrl(origin)?.let { add("origin" to it) }
            add("relay-cp" to "$RELAY_CONTROL_PLANE_ORIGIN/")
            add("public" to "$PUBLIC_REFERENCE_ORIGIN/")
        }
        coroutineScope {
            // Launch all three first, then await: awaiting inside the map lambda
            // would serialize them and triple the time the caller waits.
            val inFlight = targets.map { (name, url) -> async { probeOne(name, url) } }
            inFlight.map { it.await() }.filterNotNull()
        }
    }

    /** The URL the `origin` target asks for: root only, never a pairing path. */
    private fun entryUrl(origin: String): String? {
        val trimmed = origin.trimEnd('/')
        if (trimmed.isBlank()) return null
        val host = runCatching { java.net.URI(trimmed).host }.getOrNull()
        return if (host.isNullOrBlank()) null else "$trimmed/"
    }

    private suspend fun probeOne(target: String, url: String): DiagTargetResult? {
        val start = System.nanoTime()
        val result = try {
            withTimeout(TARGET_TIMEOUT_MS) { attempt(url) }
        } catch (cancelled: CancellationException) {
            // withTimeout and a caller-side cancellation both land here: only the
            // first is a finding, the second must keep propagating.
            if (cancelled is kotlinx.coroutines.TimeoutCancellationException) RESULT_TIMEOUT else throw cancelled
        } catch (error: Exception) {
            classify(error)
        }
        if (result == null) return null
        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        return record(DiagTargetResult(target, result, elapsed.coerceAtMost(TARGET_TIMEOUT_MS)))
    }

    /**
     * One HEAD request; returns the result token, or null when nothing in the
     * contract's vocabulary would describe it honestly.
     *
     * No timing trace is attached here on purpose: [DiagFacts.recordSegments] holds
     * a single slot, so three concurrent targets would overwrite each other's
     * breakdown. Per-target tokens are this probe's contribution; the segment
     * breakdown comes from [HealthProbe.checkDetailed].
     */
    private suspend fun attempt(url: String): String? = suspendCancellableCoroutine { continuation ->
        val call: Call = try {
            HealthProbe.probeClient.newCall(Request.Builder().url(url).head().header("Cache-Control", "no-cache").build())
        } catch (error: Exception) {
            continuation.resume(classify(error))
            return@suspendCancellableCoroutine
        }
        continuation.invokeOnCancellation { runCatching { call.cancel() } }
        try {
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resume(classify(e))
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (continuation.isActive) continuation.resume(if (it.code < 400) RESULT_OK else "http-${it.code}")
                    }
                }
            })
        } catch (error: Exception) {
            if (continuation.isActive) continuation.resume(classify(error))
        }
    }

    private fun record(result: DiagTargetResult): DiagTargetResult {
        DiagFacts.recordTarget(result.target, result.result, result.elapsedMs)
        return result
    }

    /**
     * Map a failure onto the contract's token vocabulary.
     *
     * Anything that is not DNS / TCP-refused / TLS / timeout has no honest token in
     * that list, so it yields null and the exception class name stays on the log
     * line rather than being filed under a token that would state a false fact.
     */
    private fun classify(error: Throwable?): String? = when {
        error == null -> null
        error is UnknownHostException || NativeProbeTrace.isDnsFailure(error) -> RESULT_DNS_FAIL
        error is SSLException || NativeProbeTrace.isTlsFailure(error) -> RESULT_TLS_FAIL
        error is SocketTimeoutException -> RESULT_TIMEOUT
        error is ConnectException -> RESULT_REFUSED
        else -> null
    }

    // Result tokens, exactly as the contract's log line 8 spells them.
    const val RESULT_OK = "ok"
    const val RESULT_TIMEOUT = "timeout"
    const val RESULT_DNS_FAIL = "dns-fail"
    const val RESULT_REFUSED = "refused"
    const val RESULT_TLS_FAIL = "tls-fail"
}

/**
 * The contract's line 8 — WARN level — for a target result:
 * `Diag: target=<origin|relay-cp|public> result=<ok|timeout|dns-fail|refused|tls-fail|http-NNN> <N>ms`
 *
 * Lives next to the probe so the token spelling has one owner. Contains only the
 * target label and a duration: no host, path or credential.
 */
fun DiagTargetResult.diagLine(): String = "Diag: target=$target result=$result ${elapsedMs}ms"
