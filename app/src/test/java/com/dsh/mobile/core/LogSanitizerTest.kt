package com.dsh.mobile.core

import org.junit.Assert.*
import org.junit.Test

/**
 * Regression cover for the sanitizer contract.
 *
 * The core case is `mixedContentKeepsBothSchemesOnBothSides`: the shipped
 * sanitizer rewrote every URL to `https://<host>/<path>`, so a Mixed Content line
 * — which is *by definition* an `http://` sub-resource under an `https://` page —
 * came out of the log claiming both sides were HTTPS. The bug report contradicted
 * itself because the logger had erased the evidence. These tests pin scheme, host,
 * port and path in LOCAL mode while still requiring credentials to vanish.
 */
class LogSanitizerTest {

    // ---- the case-B regression: protocol must survive ----

    @Test fun mixedContentKeepsBothSchemesOnBothSides() {
        val raw = "MixedContent: blocked http://ds.example.com:19443/pair-ping (页面来源 https://ds.example.com:19443/pair-app)"
        val text = LogSanitizer.clean(raw)
        assertTrue("blocked http url lost: $text", text.contains("http://ds.example.com:19443/pair-ping"))
        assertTrue("secure page origin lost: $text", text.contains("https://ds.example.com:19443/pair-app"))
        assertFalse("sanitizer must not force https again: $text", text.contains("https://ds.example.com:19443/pair-ping"))
        // Idempotent: RunLog sanitizes at append time and again at export time.
        assertEquals(text, LogSanitizer.clean(text))
    }

    @Test fun cspAndWebSocketKeepSchemeAndPort() {
        assertTrue(LogSanitizer.clean("Refused to load ws://10.0.0.5:8181/socket").contains("ws://10.0.0.5:8181/socket"))
        val csp = LogSanitizer.clean("csp violation http://cdn.example.org/assets/a.js against https://app.example.org")
        assertTrue(csp.contains("http://cdn.example.org/assets/a.js"))
        assertTrue(csp.contains("https://app.example.org"))
    }

    @Test fun viewUrlKeepsHostPathAndPlainScheme() {
        val text = LogSanitizer.clean("Nav: pending +2000ms stage=NAV_REQ progress=10 viewUrl=http://192.168.1.20:19443/pair-accept attached=true netBlocked=false")
        assertTrue(text.contains("http://"))
        assertTrue(text.contains("/pair-accept"))
        assertTrue(text.contains("progress=10"))
        assertTrue(text.contains("attached=true"))
        assertTrue(text.contains("netBlocked=false"))
    }

    @Test fun ordinaryQueryPairsStayReadable() {
        val text = LogSanitizer.clean("GET https://host.example/api/v1/sessions?mode=relay&path=/pair-app&bytes=8123")
        assertTrue(text.contains("mode=relay"))
        assertTrue(text.contains("bytes=8123"))
        assertTrue(text.contains("/api/v1/sessions"))
    }

    // ---- credentials still redacted ----

    @Test fun oneShotCredentialQueryValuesAreRedacted() {
        val text = LogSanitizer.clean("open https://host.example/pair-accept?pair=abc123&grant=def456&mode=relay")
        assertFalse(text.contains("abc123"))
        assertFalse(text.contains("def456"))
        assertTrue(text.contains("pair=<redacted>"))
        assertTrue(text.contains("grant=<redacted>"))
        // The safe pair next to them must survive: redaction is per key, not per query.
        assertTrue(text.contains("mode=relay"))
        assertTrue(text.contains("/pair-accept"))
    }

    @Test fun tokenKeyAndCookiePairsAreRedacted() {
        val text = LogSanitizer.clean("headers key=zz7788 token=aaaa1111 cookie=SID=9; X=8 authorization: Bearer xyz789")
        listOf("zz7788", "aaaa1111", "xyz789").forEach { assertFalse(text.contains(it)) }
        assertTrue(text.contains("token=<redacted>"))
        assertTrue(text.contains("key=<redacted>"))
    }

    @Test fun structuredErrorsAndAuthorizationAreRedacted() {
        val text = LogSanitizer.clean("{\"token\":\"sensitive123\",\"password\":\"hidden456\"} Authorization: Bearer xyz789")
        listOf("sensitive123", "hidden456", "xyz789").forEach { assertFalse(text.contains(it)) }
    }

    @Test fun bareBearerOutsideUrlIsRedacted() {
        val text = LogSanitizer.clean("retry with Bearer abc.def.ghi later")
        assertFalse(text.contains("abc.def.ghi"))
        assertTrue(text.contains("Bearer <redacted>") || text.contains("<redacted>"))
    }

    // ---- host visibility per mode ----

    @Test fun localModeKeepsTheHostBecauseItIsTheFinding() {
        val text = LogSanitizer.clean("Probe: dns-fail UnknownHostException host=relay-7423.dsh-market.com after=3000ms")
        assertTrue(text.contains("relay-7423.dsh-market.com"))
        // and the URL form keeps it too
        assertTrue(LogSanitizer.clean("load https://relay-7423.dsh-market.com:19443/pair-app").contains("relay-7423.dsh-market.com:19443"))
    }

    @Test fun strongModeCollapsesHostForPublicSharing() {
        val text = LogSanitizer.clean("MixedContent: blocked http://ds.example.com:19443/pair-ping", LogSanitizer.Mode.STRONG)
        assertFalse(text.contains("ds.example.com"))
        assertFalse(text.contains("19443"))
        // the scheme — the actual evidence — is still there
        assertTrue(text.contains("http://<host>/pair-ping"))
    }

    @Test fun strongModeStillRedactsCredentials() {
        val text = LogSanitizer.clean("open https://host.example/pair-accept?pair=abc123", LogSanitizer.Mode.STRONG)
        assertFalse(text.contains("abc123"))
        assertFalse(text.contains("host.example"))
        assertTrue(text.contains("https://<host>/pair-accept?pair=<redacted>"))
    }

    // ---- paths, IPs, version strings ----

    @Test fun plainAddressesAndLocalPathsAreNotPublished() {
        // Any drive letter must be covered, so the sample uses D: on purpose: a
        // real user-profile path here would trip the public-repo scrubber.
        val text = LogSanitizer.clean("connect 192.168.1.12 failed at D:\\work\\secret-project\\file.log")
        assertFalse(text.contains("192.168.1.12"))
        assertFalse(text.contains("secret-project"))
        assertTrue(text.contains("<ip>"))
        assertTrue(text.contains("<local-path>"))
        assertTrue(LogSanitizer.clean("webview 151.0.7872.129").contains("151.0.7872.129"))
    }

    @Test fun bareIpv6AndLocalAndroidPathsAreRedacted() {
        assertTrue(LogSanitizer.clean("peer fd83::1 answered").contains("<ip>"))
        assertTrue(LogSanitizer.clean("db at /data/user/0/com.dsh.mobile/files/a.db").contains("<local-path>"))
    }

    @Test fun longPathsAreTruncatedNotDropped() {
        val deep = "/" + (1..60).joinToString("/") { "seg$it" }
        val text = LogSanitizer.clean("GET https://host.example$deep")
        assertTrue(text.contains("..."))
        assertTrue(text.length < deep.length + 40)
    }

    // ---- QR payload ----

    @Test fun qrFactsDescribeTheEndpointWithoutThePayload() {
        val facts = LogSanitizer.describeQr("https://node-3.example.com:8443/pair-accept?pair=SECRETVALUE")
        assertEquals("https", facts.scheme)
        assertEquals("example.com", facts.hostSuffix)
        assertFalse(facts.isIp)
        assertEquals("8443", facts.port)
        assertEquals(60, facts.chars)
        assertTrue(facts.credentialLike)
        val line = LogSanitizer.describeQrLine("https://node-3.example.com:8443/pair-accept?pair=SECRETVALUE")
        assertFalse(line.contains("SECRETVALUE"))
        assertFalse(line.contains("node-3"))
        assertTrue(line.contains("hostSuffix=example.com"))
        assertTrue(line.contains("port=8443"))
    }

    @Test fun qrFactsHandleIpAndJunkPayloads() {
        val ip = LogSanitizer.describeQr("http://10.8.0.5:19443/pair")
        assertTrue(ip.isIp)
        // An IP literal is not a DNS suffix: nothing name-like leaks into the line.
        assertEquals("", ip.hostSuffix)
        assertEquals("19443", ip.port)
        val junk = LogSanitizer.describeQr("not a url at all")
        assertEquals("none", junk.scheme)
        assertFalse(junk.credentialLike)
        assertEquals(0, LogSanitizer.describeQr(null).chars)
    }

    @Test fun usefulDiagnosticsSurvive() {
        assertEquals("ws OPEN progress=80% HTTP 403", LogSanitizer.clean("ws OPEN progress=80% HTTP 403"))
        assertTrue(LogSanitizer.clean("x".repeat(10000)).length <= 4000)
    }

    @Test fun resourceFailureLineStaysWhole() {
        val text = LogSanitizer.clean("Res: fail path=/assets/index-a1b2.js status=200 bytes=8123 expected=1048576")
        assertEquals("Res: fail path=/assets/index-a1b2.js status=200 bytes=8123 expected=1048576", text)
    }
}
