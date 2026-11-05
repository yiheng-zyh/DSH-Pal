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

    /**
     * @param browserFirst a fresh one-shot pairing link: Chromium must redeem it,
     *   so no native probe may gate the route (see [run]).
     * @param observeBrowserFirst observation hook for that same branch. The caller
     *   is expected to **launch and forget** a [DiagProbe.run] for the target and
     *   write only to [DiagFacts]; it must not block and cannot veto anything.
     *   A browserFirst pairing is exactly the case where the load later stalls with
     *   no probe evidence at all, so this is where the three-target comparison is
     *   still cheap to collect. Any throw here is swallowed: a diagnostic must
     *   never break the pairing it is describing.
     */
    suspend fun run(
        target:ConnectionProfile,
        previous:ConnectionProfile?,
        prepare:suspend (ConnectionProfile)->Unit,
        probe:suspend (ConnectionProfile)->ProbeResult,
        commit:()->Unit,
        progress:(String)->Unit = {},
        browserFirst:Boolean = false,
        observeBrowserFirst:(ConnectionProfile)->Unit = {},
    ) {
        try {
            prepare(target)
            // A fresh explicit pairing link must be redeemed by Chromium itself.
            // An unrelated native probe cannot veto a route the browser can open.
            if(browserFirst){
                runCatching { observeBrowserFirst(target) }
                progress("通道已准备，正在由网页验证配对");commit();return
            }
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
