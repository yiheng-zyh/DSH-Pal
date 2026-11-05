package com.dsh.mobile.core

import org.junit.Assert.*
import org.junit.Test

/**
 * The summary is the first thing a reader sees, so these tests are mostly about
 * what it must **not** say: no conclusion without a recorded field behind it, no
 * invented hop result, never more than three suggestions, and every suggestion
 * must quote the evidence that produced it.
 */
class DiagSummaryTest {

    private fun render(snapshot: DiagSnapshot) = DiagSummary.render(snapshot)

    /** ① nothing recorded → 未知, and no advice at all. */
    @Test fun withoutAnyEvidenceItSaysUnknownAndGivesNoAdvice() {
        val text = render(DiagSnapshot())
        assertTrue(text.startsWith("=== 诊断摘要 ==="))
        assertTrue(text.contains("结果:   未知"))
        assertFalse("must not invent advice: $text", text.contains("1)"))
        assertTrue(text.contains("无证据"))
        // Nothing is asserted about hops that were never measured.
        assertEquals("手机→中继 未知 · 中继→电脑 未知 · 电脑→DSH 未知", lineOf(text, "三跳"))
        assertEquals("未知", lineOf(text, "网络"))
        assertFalse(text, text.contains("FAILED"))
        assertFalse(text, text.contains("OK"))
    }

    /** ② a recorded DNS failure: FAILED, the stage row names it, advice names DNS. */
    @Test fun dnsFailureIsFailedWithStageAndDnsAdvice() {
        val text = render(
            DiagSnapshot(
                stageDescription = "FAILED · last=headers · reason=dns",
                progressStage = "headers",
                failureReason = "dns",
                targets = listOf(DiagTargetResult("relay-cp", "dns-fail", 3000)),
                segments = ProbeSegments(dnsMs = 3000, host = "node.example.com", error = "UnknownHostException"),
                net = NetEnv(privateDns = "opportunistic"),
            )
        )
        assertTrue(text.contains("结果:   FAILED"))
        assertTrue(text.contains("阶段:   FAILED"))
        assertTrue(text.contains("原因=dns") || text.contains("reason=dns"))
        assertTrue(lineOf(text, "三跳").contains("手机→中继 <DNS失败(3000ms)>"))
        val advice = adviceOf(text)
        assertTrue(advice, advice.startsWith("1) ") && advice.contains("DNS"))
        assertTrue(advice, advice.contains("privateDns=opportunistic"))
    }

    /** ③ root never mounted although every resource arrived → clear the web cache. */
    @Test fun unmountedAppWithCleanResourcesSuggestsClearingCache() {
        val text = render(
            DiagSnapshot(
                stageDescription = "READY · last=ready",
                progressStage = "ready",
                page = PageSnapshot(readyState = "complete", path = "/pair-app", rootChildren = 0, scripts = 37),
                resources = ResourceFacts(total = 37, ok = 37, fail = 0, slow = 0, cached = 12),
            )
        )
        assertTrue(text.contains("结果:   DEGRADED"))
        assertTrue(text.contains("未挂载(rootChildren=0)"))
        assertTrue(text.contains("资源 37/37"))
        assertTrue(text.contains("命中 12 / 未命中 25"))
        val advice = adviceOf(text)
        assertTrue(advice, advice.contains("清除网页资源缓存"))
        assertTrue(advice, advice.contains("rootChildren=0"))
    }

    /** ④ an old WebView is worth reporting even when the load otherwise looked fine. */
    @Test fun oldWebViewSuggestsUpdatingIt() {
        val text = render(
            DiagSnapshot(
                stageDescription = "READY · last=ready",
                progressStage = "ready",
                page = PageSnapshot(readyState = "complete", rootChildren = 1),
                net = NetEnv(transport = "wifi", validated = true, webviewVersion = "120.0.6099.231", webviewOld = true),
            )
        )
        assertTrue(text.contains("结果:   OK"))
        assertTrue(text.contains("已挂载(rootChildren=1)"))
        assertTrue(text.contains("偏旧"))
        assertTrue(adviceOf(text).contains("更新 Android System WebView"))
    }

    /** ⑤ every rule fires at once: still exactly three suggestions. */
    @Test fun adviceNeverExceedsThreeItems() {
        val text = render(
            DiagSnapshot(
                stageDescription = "FAILED · last=script-eval · reason=script-error",
                progressStage = "script-eval",
                failureReason = "dns",
                segments = ProbeSegments(dnsMs = 3000, status = 500, host = "node.example.com"),
                targets = listOf(
                    DiagTargetResult("relay-cp", "ok", 340),
                    DiagTargetResult("origin", "dns-fail", 3000),
                    DiagTargetResult("public", "ok", 120),
                ),
                page = PageSnapshot(readyState = "complete", rootChildren = 0, failedScripts = listOf("index-a1b2.js")),
                resources = ResourceFacts(
                    total = 37, ok = 36, fail = 1, slow = 0, cached = 12,
                    failures = listOf(ResourceFailure("/assets/index-a1b2.js", 200, 8123, 1_048_576)),
                ),
                jsErrors = listOf(JsErrorFacts("index-a1b2.js", 1, 5, "Uncaught SyntaxError", 3)),
                injectMissing = listOf("__DSH_MOBILE_COMPAT__"),
                injectFailed = listOf("dsh-js-errors.js TypeError"),
                mixedContent = listOf("blocked http://node.example.com/pair-ping"),
                net = NetEnv(transport = "wifi", validated = true, webviewOld = true, webviewVersion = "118.0"),
            )
        )
        val advice = adviceOf(text)
        assertTrue(advice, advice.contains("1) ") && advice.contains("2) ") && advice.contains("3) "))
        assertFalse(advice, advice.contains("4) "))
        // Each item names the field it came from; that is what makes it advice and not guesswork.
        assertEquals(3, Regex("证据：").findAll(advice).count())
    }

    /** ⑥ a recorded script error shows up as `file:line`. */
    @Test fun jsErrorPrintsSourceLineAndRepeatCount() {
        val text = render(
            DiagSnapshot(
                stageDescription = "READY · last=ready",
                progressStage = "ready",
                page = PageSnapshot(readyState = "complete", rootChildren = 1),
                resources = ResourceFacts(total = 37, ok = 36, fail = 1),
                jsErrors = listOf(
                    JsErrorFacts("index-a1b2.js", 1, 5, "Uncaught SyntaxError: Invalid or unexpected token", 3),
                    JsErrorFacts("vendor-c3d4.js", 88, 12, "TypeError: x is not a function"),
                ),
            )
        )
        assertTrue(text.contains("结果:   DEGRADED"))
        val script = lineOf(text, "脚本")
        assertTrue(script, script.contains("index-a1b2.js:1"))
        assertTrue(script, script.contains("Uncaught SyntaxError"))
        assertTrue(script, script.contains("共 3 次"))
        assertTrue(script, script.contains("vendor-c3d4.js:88"))
    }

    @Test fun scriptRowCapsAtThreeErrors() {
        val many = (1..5).map { JsErrorFacts("chunk$it.js", it, 1, "boom") }
        val script = lineOf(render(DiagSnapshot(stageDescription = "READY · last=ready", jsErrors = many)), "脚本")
        // First three errors are listed (the first one is usually the cause; later
        // ones are cascades), and the remainder is counted rather than printed.
        assertTrue(script, script.contains("chunk1.js"))
        assertFalse(script, script.contains("chunk4.js"))
        assertTrue(script, script.contains("另有 2 类"))
    }

    // ---- honesty of the derived rows ----

    @Test fun unmeasuredHopsStayUnknownInsteadOfBeingGuessed() {
        val text = render(
            DiagSnapshot(
                stageDescription = "READY · last=ready",
                progressStage = "ready",
                targets = listOf(DiagTargetResult("relay-cp", "ok", 340), DiagTargetResult("public", "ok", 120)),
            )
        )
        val hops = lineOf(text, "三跳")
        assertTrue(hops, hops.contains("手机→中继 <可达(340ms)>"))
        assertTrue(hops, hops.contains("中继→电脑 未知"))
        assertTrue(hops, hops.contains("电脑→DSH 未知"))
        // `public` is a phone-side egress probe, not one of the three hops: it is
        // printed under its own name instead of being mapped onto a hop it does not measure.
        assertTrue(hops, hops.contains("public <可达(120ms)>"))
    }

    @Test fun truncatedResourceIsReportedAsByteAnomaly() {
        val text = render(
            DiagSnapshot(
                stageDescription = "READY · last=ready",
                progressStage = "ready",
                page = PageSnapshot(rootChildren = 1, failedScripts = listOf("index-a1b2.js")),
                resources = ResourceFacts(
                    total = 37, ok = 36, fail = 1, cached = 0,
                    failures = listOf(ResourceFailure("/assets/index-a1b2.js", 200, 8123, 1_048_576)),
                ),
            )
        )
        assertTrue(text.contains("1 项字节数异常"))
        assertTrue(text.contains("1 个脚本解析失败"))
        val advice = adviceOf(text)
        assertTrue(advice, advice.contains("资源下载不完整") && advice.contains("bytes=8123/expected=1048576"))
        // rootChildren=1 means it did mount: the unmount rule needs rootChildren=0.
        assertFalse(advice, advice.contains("rootChildren=0"))
    }

    @Test fun missingResourceAccountingIsUnknownNotClean() {
        val text = render(
            DiagSnapshot(
                stageDescription = "READY · last=ready",
                progressStage = "ready",
                page = PageSnapshot(rootChildren = 1),
            )
        )
        val cache = lineOf(text, "缓存")
        assertTrue(cache, cache.contains("未知"))
        assertFalse(cache, cache.contains("无字节数异常"))
    }

    @Test fun mixedContentBlockDrivesItsOwnAdvice() {
        val text = render(
            DiagSnapshot(
                stageDescription = "READY · last=ready",
                progressStage = "ready",
                page = PageSnapshot(rootChildren = 2),
                resources = ResourceFacts(total = 10, ok = 9, fail = 1),
                mixedContent = listOf("blocked http://node.example.com/pair-ping", "blocked http://cdn.example.com/a.js"),
            )
        )
        assertTrue(text.contains("结果:   DEGRADED"))
        val advice = adviceOf(text)
        assertTrue(advice, advice.contains("https") && advice.contains("MixedContent 2 条"))
    }

    @Test fun injectMissingAndInjectFailedAreReported() {
        val missing = render(
            DiagSnapshot(
                stageDescription = "READY · last=ready",
                progressStage = "ready",
                page = PageSnapshot(rootChildren = 1),
                resources = ResourceFacts(total = 5, ok = 5, fail = 0),
                injectMissing = listOf("__DSH_MOBILE_COMPAT__", "__dshRemoteAdapt"),
            )
        )
        assertTrue(missing.contains("结果:   DEGRADED"))
        assertTrue(adviceOf(missing), adviceOf(missing).contains("__DSH_MOBILE_COMPAT__"))

        val failed = render(
            DiagSnapshot(
                stageDescription = "READY · last=ready",
                progressStage = "ready",
                page = PageSnapshot(rootChildren = 1),
                resources = ResourceFacts(total = 5, ok = 5, fail = 0),
                injectFailed = listOf("dsh-js-errors.js TypeError"),
            )
        )
        assertTrue(failed.contains("结果:   DEGRADED"))
        assertTrue(adviceOf(failed), adviceOf(failed).contains("dsh-js-errors.js"))
    }

    @Test fun everyDeclaredRowIsPresent() {
        // A guard against a new evidence field silently not reaching the block.
        val text = render(DiagSnapshot())
        for (label in listOf("结果", "阶段", "三跳", "页面", "脚本", "缓存", "网络", "建议")) {
            assertTrue("missing row $label", text.contains("$label:   "))
        }
        assertEquals(8, text.lines().count { it.contains(":   ") })
    }

    @Test fun netRowShowsTransportProxyAndDns() {
        val text = render(
            DiagSnapshot(
                stageDescription = "READY · last=ready",
                progressStage = "ready",
                page = PageSnapshot(rootChildren = 1),
                net = NetEnv(
                    transport = "cellular", validated = false, metered = true, proxy = "10.0.0.1:8888",
                    privateDns = "off", dnsServers = "fd83::1", webviewVersion = "151.0.7872.129",
                ),
            )
        )
        val net = lineOf(text, "网络")
        assertTrue(net, net.contains("cellular 未验证"))
        assertTrue(net, net.contains("proxy=10.0.0.1:8888"))
        assertTrue(net, net.contains("privateDns=off"))
        assertTrue(net, net.contains("dns=fd83::1"))
        assertTrue(net, net.contains("WebView 151.0.7872.129"))
    }

    @Test fun renderNeverThrowsOnContradictoryEvidence() {
        // Line 136 of the contract's vocabulary is not enough to guarantee clean data.
        val text = render(
            DiagSnapshot(
                failureReason = "  ",
                segments = ProbeSegments(dnsMs = -999, status = 0, host = ""),
                page = PageSnapshot(rootChildren = -1, readyState = "x".repeat(5000)),
                resources = ResourceFacts(total = -5, ok = -1, fail = -1, cached = -1),
                jsErrors = List(50) { JsErrorFacts("s", 0, 0, "m") },
            )
        )
        assertTrue(text.contains("结果:"))
    }

    // ---- helpers ----

    private fun lineOf(text: String, label: String): String =
        text.lines().firstOrNull { it.startsWith("$label:   ") }?.removePrefix("$label:   ") ?: ""

    private fun adviceOf(text: String): String = lineOf(text, "建议")
}
