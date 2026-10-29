package com.dsh.mobile.core

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Keep selection durable only after the target transport and endpoint are ready.
 *
 * The endpoint check used to be a single request behind a 5s timeout, and one
 * failure rolled the whole switch back. On a lossy mobile link (measured: relay
 * over cellular drops 60-80% of packets, while the same link serves the
 * cloudflare tunnel at 10%) a single-shot probe fails most of the time even
 * though the transport itself is healthy — so "switching" looked broken while
 * the channel was fine. Retry the probe a few times before declaring failure;
 * a channel that answers on any attempt is usable.
 */
object ConnectionSwitch {
    /** Attempts for the endpoint check. A usable route answers within a few. */
    private const val PROBE_ATTEMPTS = 4
    private val BACKOFF_MS = longArrayOf(400, 900, 1500)

    private val usable = setOf(ProbeOutcome.DSH_FOUND, ProbeOutcome.AUTH_REQUIRED, ProbeOutcome.REDIRECT)

    /**
     * Only a transport-level miss is worth retrying: it usually means the
     * request was dropped, and a lossy link answers the next one. Outcomes where
     * the machine did answer (wrong page, HTTP error) are configuration
     * problems — repeating them would just make the user wait for the same
     * answer.
     */
    private fun worthRetrying(outcome: ProbeOutcome) = outcome == ProbeOutcome.NETWORK_ERROR

    suspend fun run(
        target:ConnectionProfile,
        previous:ConnectionProfile?,
        prepare:suspend (ConnectionProfile)->Unit,
        probe:suspend (ConnectionProfile)->ProbeResult,
        commit:()->Unit,
        progress:(String)->Unit = {},
    ) {
        try {
            prepare(target)
            var result = probe(target)
            var attempt = 1
            while (result.outcome !in usable && worthRetrying(result.outcome) && attempt < PROBE_ATTEMPTS) {
                // Route diagnostics through the caller's progress sink: this
                // object stays free of Android dependencies so it remains
                // unit-testable.
                progress("电脑还没响应，正在重试（第 ${attempt + 1}/${PROBE_ATTEMPTS} 次）")
                delay(BACKOFF_MS[(attempt - 1).coerceAtMost(BACKOFF_MS.size - 1)])
                result = probe(target)
                attempt++
            }
            check(result.outcome in usable) {
                if (result.outcome == ProbeOutcome.NETWORK_ERROR)
                    "连不上电脑入口（已试 $PROBE_ATTEMPTS 次）。请检查目标地址、当前网络和电脑端服务，或换条通道再试"
                else result.outcome.message
            }
            commit()
        } catch(error:Exception) {
            // Cancellation also needs transport rollback: the selected profile
            // has not changed and must not be left with its VPN stopped.
            withContext(NonCancellable) {
                previous?.let { runCatching { prepare(it) } }
            }
            throw error
        }
    }
}
