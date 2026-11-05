package com.dsh.mobile.core

/**
 * Renders the evidence bundle from [DiagFacts] into the short block at the top of
 * an export, so a reader can tell *where* the load stopped without reading 600
 * lines of buffer.
 *
 * ## The rule this file exists to enforce
 *
 * **Evidence or silence.** Every field printed here comes from a [DiagSnapshot]
 * value that something actually recorded; anything not recorded prints 未知. The
 * summary is the last thing a user reads before filing an issue, so a plausible
 * guess is worse than a blank: it sends them debugging the wrong layer.
 *
 * Suggestions are the same rule, tighter: at most three, and each one names the
 * evidence that produced it (`证据：…`). A suggestion without a field behind it is
 * a guess and is not emitted. When nothing at all was recorded the block says
 * `结果: 未知` and prints no advice.
 *
 * Failure safety: this renders on the export path, so [render] catches everything
 * and degrades to a one-line note — a broken summary must never break an export.
 *
 * Android-free on purpose (see `.ai/DECISIONS.md` 2026-10-27); the whole renderer
 * is exercised by `DiagSummaryTest`.
 */
object DiagSummary {

    private const val HEADER = "=== 诊断摘要 ==="
    private const val UNKNOWN = "未知"

    /** Fixed gutter after the label, so the block scans as a table (CJK labels are 2 glyphs). */
    private fun line(label: String, value: String) = "$label:   $value"

    fun render(snapshot: DiagSnapshot): String = runCatching {
        buildString {
            appendLine(HEADER)
            appendLine(line("结果", verdict(snapshot).label))
            appendLine(line("阶段", stageText(snapshot)))
            appendLine(line("三跳", hopsText(snapshot)))
            appendLine(line("页面", pageText(snapshot)))
            appendLine(line("脚本", scriptText(snapshot)))
            appendLine(line("缓存", cacheText(snapshot)))
            appendLine(line("网络", netText(snapshot)))
            val advice = adviceFor(snapshot)
            if (advice.isEmpty()) {
                append(line("建议", if (verdict(snapshot) == Verdict.UNKNOWN_KIND) "无证据，暂不建议" else "无需操作"))
            } else {
                append(line("建议", advice.mapIndexed { index, item -> "${index + 1}) $item" }.joinToString(" ")))
            }
        }.trimEnd()
    }.getOrElse {
        // Never let a summary sink an export.
        HEADER + "\n" + line("结果", UNKNOWN) + "\n(摘要生成失败)"
    }

    // ---- verdict ----

    private enum class Verdict(val label: String) { OK("OK"), DEGRADED("DEGRADED"), FAILED("FAILED"), UNKNOWN_KIND(UNKNOWN) }

    /**
     * FAILED requires a recorded terminal reason token (the contract's vocabulary).
     * DEGRADED means the page came far enough to be inspected but something in it
     * broke. UNKNOWN means literally nothing was recorded. Anything else is OK.
     */
    private fun verdict(snapshot: DiagSnapshot): Verdict {
        if (!snapshot.hasEvidence()) return Verdict.UNKNOWN_KIND
        if (snapshot.failureReason != null) return Verdict.FAILED
        if (hasPageProblem(snapshot)) return Verdict.DEGRADED
        return Verdict.OK
    }

    /** Anything a subsystem actually recorded. */
    private fun DiagSnapshot.hasEvidence(): Boolean =
        stageDescription.isNotBlank() || progressStage.isNotBlank() || failureReason != null ||
            segments.hasData() || targets.isNotEmpty() || page != null || resources.hasData() ||
            jsErrors.isNotEmpty() || injectMissing.isNotEmpty() || injectFailed.isNotEmpty() ||
            mixedContent.isNotEmpty() || net.hasData()

    /**
     * A page that loaded but is not healthy: script errors, failed resources,
     * blocked mixed content, missing injection, or a root that never mounted.
     */
    private fun hasPageProblem(snapshot: DiagSnapshot): Boolean {
        val page = snapshot.page
        return snapshot.jsErrors.isNotEmpty() ||
            snapshot.injectMissing.isNotEmpty() ||
            snapshot.injectFailed.isNotEmpty() ||
            snapshot.mixedContent.isNotEmpty() ||
            snapshot.resources.fail > 0 ||
            snapshot.resources.failures.isNotEmpty() ||
            (page != null && page.rootChildren == 0) ||
            (page != null && page.failedScripts.isNotEmpty()) ||
            (snapshot.segments.status ?: 0) >= 400
    }

    // ---- rows ----

    private fun stageText(snapshot: DiagSnapshot): String {
        val reason = snapshot.failureReason
        val description = snapshot.stageDescription.ifBlank { snapshot.progressStage }
        // NavStageMachine.describe() already renders `· reason=…`; appending it a
        // second time produced `reason=dns · 原因=dns` on the first live sample.
        val alreadyNamesReason = reason != null && description.contains("reason=$reason")
        return when {
            reason != null && description.isNotBlank() && alreadyNamesReason -> description
            reason != null && description.isNotBlank() -> "$description · 原因=$reason"
            reason != null -> "失败原因=$reason"
            description.isNotBlank() -> description
            else -> UNKNOWN
        }
    }

    /**
     * The three-hop chain. Each label is filled **only** from a recorded target
     * that actually measures that hop: the phone can prove its own uplink, it can
     * never prove what happened between the relay and the desktop. Unclaimed
     * targets (the contract's `origin` / `public` are both phone-side egress
     * probes) are printed with their own name instead of being mapped onto a hop
     * they do not measure — a DNS failure for the desktop origin is not evidence
     * about "中继→电脑".
     */
    private fun hopsText(snapshot: DiagSnapshot): String {
        val byName = snapshot.targets.associateBy { it.target.lowercase() }
        val claimed = HashSet<String>()
        val parts = HOP_LABELS.map { hop ->
            val result = hop.aliases.firstNotNullOfOrNull { byName[it] }
            if (result != null) claimed.add(result.target.lowercase())
            "${hop.label} ${describeResult(result)}"
        }.toMutableList()
        // Any recorded target the three labels did not claim is still evidence.
        snapshot.targets.filter { it.target.lowercase() !in claimed && it.target.lowercase() !in HOP_ALIASES }
            .forEach { parts += "${it.target} ${describeResult(it)}" }
        return parts.joinToString(" · ")
    }

    private data class Hop(val label: String, val aliases: List<String>)

    private val HOP_LABELS = listOf(
        Hop("手机→中继", listOf("relay-cp", "relay", "relay-control", "phone-relay")),
        Hop("中继→电脑", listOf("relay-desktop", "relay-to-desktop", "cp-desktop", "desktop", "mesh-peer")),
        Hop("电脑→DSH", listOf("origin-local", "desktop-dsh", "dsh-local", "local-dsh")),
    )

    private val HOP_ALIASES = HOP_LABELS.flatMap { it.aliases }.toSet()

    /** Phone-side probes of the desktop endpoint; evidence for "not the access network". */
    private val DESKTOP_EGRESS_TARGETS = listOf("origin", "origin-direct", "dsh", "desktop-dsh")

    /** `<可达(340ms)>` / `<DNS失败(3000ms)>` / `<未知>` — word plus the measured time. */
    private fun describeResult(result: DiagTargetResult?): String {
        if (result == null) return UNKNOWN
        val time = if (result.elapsedMs >= 0) "${result.elapsedMs}ms" else ""
        val token = when (result.result.lowercase()) {
            "ok" -> "可达"
            "timeout", "timed-out" -> "超时"
            "dns-fail", "dns" -> "DNS失败"
            "refused", "tcp-refused" -> "拒绝连接"
            "tls-fail", "tls-handshake" -> "TLS失败"
            else -> result.result
        }
        return if (time.isEmpty()) "<$token>" else "<$token($time)>"
    }

    private fun pageText(snapshot: DiagSnapshot): String {
        val page = snapshot.page
        val resources = snapshot.resources
        val parts = mutableListOf<String>()
        parts += if (resources.ok >= 0 && resources.total > 0) {
            "资源 ${resources.ok}/${resources.total}" + if (resources.fail > 0) "(失败 ${resources.fail})" else ""
        } else if (resources.total > 0) {
            "资源 ?/${resources.total}"
        } else {
            "资源 $UNKNOWN"
        }
        parts += when {
            page == null -> "挂载 $UNKNOWN"
            page.rootChildren < 0 -> "挂载 $UNKNOWN"
            page.rootChildren == 0 -> "未挂载(rootChildren=0)"
            else -> "已挂载(rootChildren=${page.rootChildren})"
        }
        val parseFailures = page?.failedScripts?.size ?: 0
        parts += if (parseFailures > 0) "$parseFailures 个脚本解析失败" else if (page != null) "无脚本解析失败" else "脚本状态 $UNKNOWN"
        if (page != null && page.readyState.isNotBlank()) parts += "readyState=${page.readyState}"
        return parts.joinToString(" · ")
    }

    private fun scriptText(snapshot: DiagSnapshot): String {
        if (snapshot.jsErrors.isEmpty()) return if (snapshot.injectFailed.isEmpty()) "无记录" else "注入脚本: " + snapshot.injectFailed.take(3).joinToString(", ")
        return snapshot.jsErrors.take(3).joinToString(" | ") { error ->
            val count = if (error.count > 1) "(共 ${error.count} 次)" else ""
            "${error.source}:${error.line} ${error.message.take(80)}$count"
        } + if (snapshot.jsErrors.size > 3) " | 另有 ${snapshot.jsErrors.size - 3} 类" else ""
    }

    private fun cacheText(snapshot: DiagSnapshot): String {
        val resources = snapshot.resources
        // No resource accounting recorded is not the same as "nothing abnormal":
        // printing 无字节数异常 here would be a conclusion without evidence.
        if (!resources.hasData()) return UNKNOWN + "（未记录资源统计）"
        val hits = resources.cached
        val misses = if (hits >= 0 && resources.ok >= 0) resources.ok - hits else -1
        val abnormal = resources.failures.count { it.expected > 0 && it.bytes in 0 until it.expected }
        val parts = mutableListOf<String>()
        parts += "命中 " + if (hits >= 0) "$hits" else UNKNOWN
        parts += "未命中 " + if (misses >= 0) "$misses" else UNKNOWN
        parts += if (abnormal > 0) "$abnormal 项字节数异常" else "无字节数异常"
        return parts.joinToString(" / ")
    }

    private fun netText(snapshot: DiagSnapshot): String {
        val net = snapshot.net
        if (!net.hasData()) return UNKNOWN
        val validated = when (net.validated) {
            true -> "已验证"
            false -> "未验证"
            null -> UNKNOWN
        }
        val parts = mutableListOf<String>()
        parts += (net.transport.ifBlank { UNKNOWN }) + " " + validated
        if (net.metered != null) parts += "metered=${net.metered}"
        parts += "proxy=" + net.proxy.ifBlank { UNKNOWN }
        parts += "privateDns=" + net.privateDns.ifBlank { UNKNOWN }
        if (net.dnsServers.isNotBlank()) parts += "dns=${net.dnsServers}"
        parts += "WebView " + net.webviewVersion.ifBlank { UNKNOWN } + if (net.webviewOld) "(偏旧)" else ""
        return parts.joinToString(" · ")
    }

    // ---- advice ----

    /**
     * At most three recommendations, each naming the evidence that produced it,
     * in descending order of how directly it explains a broken load.
     *
     * A rule fires only on a field value, never on the absence of one — "no DNS
     * server recorded" is not evidence that DNS is broken.
     */
    private fun adviceFor(snapshot: DiagSnapshot): List<String> {
        if (!snapshot.hasEvidence()) return emptyList()
        val reason = snapshot.failureReason?.lowercase().orEmpty()
        val candidates = mutableListOf<String>()

        // 1. transport-level failure tokens: check the network first, in order of specificity.
        if (reason.contains("dns")) {
            val dns = snapshot.net.privateDns
            candidates += "检查手机的 DNS / 私人 DNS 设置后重试（证据：failureReason=$reason" +
                (if (dns.isNotBlank()) "; privateDns=$dns" else "; dns=${snapshot.net.dnsServers.ifBlank { UNKNOWN }}") + "）"
        }
        if (reason.contains("tls")) {
            candidates += "检查电脑端证书的有效期与域名匹配后重试（证据：failureReason=$reason" +
                (if (snapshot.segments.tlsMs >= 0) "; tls=${snapshot.segments.tlsMs}ms" else "") + "）"
        }
        if (reason.contains("tcp-refused") || reason.contains("tcp-timeout") || reason.contains("refused") || reason.contains("timeout")) {
            candidates += "确认电脑端服务在监听、端口未被系统防火墙放行外访问后重试（证据：failureReason=$reason）"
        }
        // 4xx is NOT a broken desktop: 401/403 from the relay probe is a *successful*
        // round trip that only says "credential required". Advising a service restart
        // for that sent a real reader to the wrong machine, so the two cases are
        // separated here.
        val status = snapshot.segments.status
        val authStatus = status != null && (status == 401 || status == 403)
        if (authStatus && !reason.contains("http-error")) {
            candidates += "入口返回 HTTP $status：网络已通，但凭据未被接受；先按页面提示重新配对，不必重启电脑端（证据：HTTP status=$status）"
        } else if (reason.contains("no-response-headers") || reason.contains("http-error") || (status ?: 0) >= 500) {
            candidates += "电脑端已应答但请求未完成，先重启本机 DSH 服务再重试（证据：" +
                (if (status != null && status > 0) "HTTP status=$status" else "failureReason=$reason") + "）"
        }

        // 2. protocol mismatch: the page asked for an http resource over an https document.
        if (snapshot.mixedContent.isNotEmpty()) {
            candidates += "把访问地址换成 https 端点（或修正电脑端下发的资源地址），否则这些资源会被拦（证据：MixedContent ${snapshot.mixedContent.size} 条）"
        }

        // 3. the page arrived but did not mount while resources looked fine.
        val page = snapshot.page
        val resourcesClean = snapshot.resources.total > 0 && snapshot.resources.fail == 0 &&
            snapshot.resources.failures.isEmpty() && snapshot.resources.ok >= 0
        if (page != null && page.rootChildren == 0 && resourcesClean) {
            candidates += "清除网页资源缓存后重试（证据：rootChildren=0 且资源 ${snapshot.resources.ok}/${snapshot.resources.total} 全部成功）"
        }

        // 4. a truncated body explains a syntax error at byte 0 of a bundle.
        val truncated = snapshot.resources.failures.filter { it.expected > 0 && it.bytes in 0 until it.expected }
        if (truncated.isNotEmpty()) {
            candidates += "资源下载不完整，清除缓存后重试（证据：" +
                truncated.take(2).joinToString(", ") { "${it.path} bytes=${it.bytes}/expected=${it.expected} status=${it.status}" } + "）"
        }

        // 5. script errors with the page otherwise up.
        if (snapshot.jsErrors.isNotEmpty()) {
            val first = snapshot.jsErrors.first()
            candidates += "页面脚本执行失败，先清除网页资源缓存；仍失败请带此错误向电脑端反馈（证据：${first.source}:${first.line} ${first.message.take(60)}）"
        }

        // 6. injected helpers missing → the app's own compatibility layer did not load.
        if (snapshot.injectMissing.isNotEmpty()) {
            candidates += "重进页面以重新注入手机端脚本（证据：missing ${snapshot.injectMissing.take(3).joinToString(", ")}）"
        }
        if (snapshot.injectFailed.isNotEmpty()) {
            candidates += "手机端注入脚本自身报错，请带此行反馈（证据：${snapshot.injectFailed.first()}）"
        }

        // 7. environment that cannot run modern syntax, regardless of the outcome.
        if (snapshot.net.webviewOld) {
            candidates += "更新 Android System WebView 后重试（证据：webview=${snapshot.net.webviewVersion.ifBlank { UNKNOWN }} 低于现代语法基线）"
        }

        // 8. the access network answered while the desktop endpoint did not: the
        //    phone's own uplink is not the problem. Both targets are measured by
        //    the phone, so this compares two phone-side probes and is sound.
        val relay = snapshot.targets.firstOrNull { it.target.lowercase() in HOP_LABELS[0].aliases }
        val desktop = snapshot.targets.firstOrNull { it.target.lowercase() in DESKTOP_EGRESS_TARGETS }
        if (relay != null && relay.result.equals("ok", true) && desktop != null && !desktop.result.equals("ok", true)) {
            candidates += "手机到中继正常、到电脑端点失败，先查电脑端与隧道而不是手机网络（证据：${relay.target}=ok ${relay.elapsedMs}ms 而 ${desktop.target}=${desktop.result}）"
        }

        return candidates.take(3)
    }

    // ---- small helpers on the data classes ----

    private fun ProbeSegments.hasData(): Boolean =
        dnsMs >= 0 || connectMs >= 0 || tlsMs >= 0 || headersMs >= 0 || totalMs >= 0 ||
            status != null || !error.isNullOrBlank() || host.isNotBlank()

    private fun ResourceFacts.hasData(): Boolean =
        total > 0 || ok >= 0 || fail >= 0 || slow >= 0 || cached >= 0 || failures.isNotEmpty()

    private fun NetEnv.hasData(): Boolean =
        transport.isNotBlank() || validated != null || metered != null || proxy.isNotBlank() ||
            privateDns.isNotBlank() || dnsServers.isNotBlank() || webviewVersion.isNotBlank() || webviewOld
}
