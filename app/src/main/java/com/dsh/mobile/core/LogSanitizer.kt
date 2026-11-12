package com.dsh.mobile.core

/**
 * Redacts credentials from every log line before it reaches a sink.
 *
 * ## Why the previous version was wrong
 *
 * The first implementation replaced *every* `http(s)://` URL with
 * `https://<host>/<path>`: it forced the scheme to `https`, dropped the port and
 * dropped the query. That makes protocol-sensitive lines self-contradictory. A
 * Mixed Content block is by definition an `http://` sub-resource reached from an
 * `https://` page, yet after sanitizing the log claimed both sides were `https` —
 * the evidence for the bug had been erased by the logger. CSP violations,
 * `viewUrl=`, and probe origins were damaged the same way.
 *
 * ## Contract
 *
 * Kept in [Mode.LOCAL]:
 *  - the **scheme** (`http` / `https` / `ws` / `wss`) — protocol *is* diagnostic data
 *  - the **host** and the **port** (the host is also what tells an IPv4 literal
 *    apart from noise, so the IP rules never touch a URL host)
 *  - the **path**, truncated to [MAX_PATH_CHARS]
 *  - every query pair whose key is *not* a known credential key
 *
 * Redacted everywhere:
 *  - values of one-shot credential keys (`pair` / `grant` / `token` / `key` …) in
 *    a query string as well as in prose (`cookie=`, `"password":"…"`)
 *  - `Bearer <credentials>`
 *  - IPv4 / compressed-IPv6 literals that are not a URL host
 *  - local filesystem paths (drive letters, `/data/user/`, `/home/`, `/Users/` …)
 *  - the whole QR payload — see [describeQr]
 *
 * [Mode.STRONG] additionally collapses the URL host to `<host>` and drops the
 * port, for text the user is about to paste into a public issue. The on-device
 * buffer keeps the host: without it "which endpoint failed" cannot be answered.
 *
 * Version strings such as `151.0.7872.129` are deliberately untouched: the
 * four-group pattern requires each octet ≤ 255 and a nonzero first octet, so
 * neither a modern build (`7872` is no octet) nor a zero-major system WebView
 * (`0.0.0.0`, the exact header field issue #1 lost) can match. The WebView build
 * is the single most useful field in the export header.
 *
 * The pass is single-scan (URL spans are rewritten, the text between them is
 * redacted) so a URL and a plain-text secret in the same line never interfere,
 * and re-running it is idempotent — [RunLog] sanitizes at append time and again
 * at export time.
 *
 * JVM-only on purpose (see `.ai/DECISIONS.md` 2026-10-27): no `android.*` import.
 */
object LogSanitizer {

    /** How aggressive the redaction is. */
    enum class Mode {
        /** On-device buffer and private export: keep scheme + host + port + path. */
        LOCAL,

        /** Sharing to a public tracker: also collapse the host to `<host>`. */
        STRONG,
    }

    /** Path length limit — long enough for a hashed asset name and a deep route. */
    private const val MAX_PATH_CHARS = 100

    /** Whole-line limit, unchanged from the previous implementation. */
    private const val MAX_OUTPUT_CHARS = 4000

    private const val REDACTED = "<redacted>"

    /**
     * Query/fragment keys whose *value* is a one-shot credential. Matched on the
     * whole key, case-insensitively, so `?mode=relay`, `?path=/pair-app`, `?v=3`,
     * `?bytes=8123` stay readable. Deliberately no single-letter keys: `?t=` and
     * `?k=` are usually timestamps and cache salts, and hiding them would remove
     * load-order evidence without hiding anything secret.
     */
    private val CREDENTIAL_QUERY_KEYS = setOf(
        "pair", "pairing", "paircode", "pairtoken", "grant", "grantcode", "granttoken",
        "token", "accesstoken", "access_token", "refreshtoken", "refresh_token", "id_token",
        "idtoken", "authtoken", "tokenid", "sessiontoken", "code", "ticket", "otp", "pin",
        "auth", "authorization", "cookie", "set-cookie", "setcookie",
        "secret", "clientsecret", "client_secret", "secretkey", "privatekey", "private_key",
        "apikey", "api_key", "key", "signature", "sig", "password", "passwd", "pwd",
        "passphrase", "session", "sessionid", "sid", "jsessionid", "phpsessid", "csrf", "xsrf",
    )

    /**
     * `key: value` / `key=value` / `"key":"value"` for credential-ish keys, applied
     * to the text *outside* URLs. The value class stops at `, ; & "` and `'`, which
     * keeps JSON envelopes and header lists readable. No word boundary is enforced
     * on the key: over-redacting `monkey=…` is cheap, under-redacting a token is not.
     */
    private val secret = Regex(
        "(?i)(token|grant|pair|paircode|cookie|set-cookie|authorization|password|passwd|pwd|" +
            "secret|passphrase|apikey|api[_-]key|access[_-]?key|private[_-]?key|signature|otp|pin|key)" +
            "([\"']?\\s*[:=]\\s*[\"']?)[^\\s,;&\"'\\`]+"
    )

    private val bearer = Regex("(?i)Bearer\\s+[^\\s,;]+")

    /**
     * A real IPv4 dotted quad: first octet 1–255, the rest 0–255, never a
     * fragment of a longer number (see class doc). The octet bounds are what
     * keep version strings readable — `151.0.7872.129` fails on `7872`, and
     * `0.0.0.0` (a system WebView build) fails on the leading zero. `0.0.0.0`
     * as an address is never a real peer, so nothing sensitive is lost.
     */
    private val ipv4 = Regex(
        "(?<![\\w.])(?:25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9][0-9]?)" +
            "(?:\\.(?:25[0-5]|2[0-4][0-9]|1[0-9]{2}|[0-9]{1,2})){3}(?![\\w.])"
    )

    /**
     * Compressed IPv6 only (must contain `::`). A fully expanded IPv6 literal is
     * not matched: acceptable, because the pattern that would catch it also eats
     * `module::symbol` style references, and IPv6 endpoints in this app appear as
     * URL hosts (protected by the single-scan design).
     */
    private val ipv6 = Regex(
        "(?i)(?<![\\w.])(?:" +
            // Three or more groups, e.g. 1:2:3.
            "(?:[0-9a-f]{1,4}:){2,7}[0-9a-f]{0,4}" +
            // Compressed in the middle, e.g. fd83::1 or fe80::1:2. Requires the
            // double colon, so a lone `C:` (a Windows drive) can never match.
            "|(?:[0-9a-f]{1,4}:){1,7}:(?:[0-9a-f]{1,4}){0,6}" +
            // Leading compression, e.g. ::1.
            "|::(?:[0-9a-f]{1,4}:?){1,7}" +
            ")(?![\\w.])"
    )

    private val privatePath = Regex(
        "(?i)(?:(?<![a-z0-9])[A-Z]:[\\\\/]|/data/user/|/data/data/|/storage/emulated/|/home/|/root/|/Users/)[^\\r\\n\"<>|]+"
    )

    private val url = Regex("(?i)(?:https?|wss?)://[^\\s<>\\\"'`]+")

    /** Trailing characters that are prose punctuation, not part of the URL. */
    private const val TRAILING_PUNCTUATION = ".,;:!?'\")"

    /** Single-argument form keeps every existing call site compiling unchanged. */
    fun clean(message: String): String = clean(message, Mode.LOCAL)

    fun clean(message: String, mode: Mode): String {
        if (message.isEmpty()) return message
        val out = StringBuilder(message.length.coerceAtMost(MAX_OUTPUT_CHARS))
        var last = 0
        for (match in url.findAll(message)) {
            if (match.range.first > last) out.append(redactPlain(message.substring(last, match.range.first)))
            out.append(rewriteUrl(match.value, mode))
            last = match.range.last + 1
        }
        if (last < message.length) out.append(redactPlain(message.substring(last)))
        return out.take(MAX_OUTPUT_CHARS).toString()
    }

    /**
     * Structured, payload-free description of a scanned QR code.
     *
     * A pairing QR payload *is* a credential, so it must never be logged — but
     * "the QR was unreadable" is otherwise undiagnosable. These fields answer the
     * only two questions that matter: what kind of endpoint did it point at, and
     * did it look like it carried a credential.
     *
     * The scan call site lives in `ui/screen/QrScannerSheet.kt` (another agent's
     * write domain) and today writes its own `chars=` line; this function exists so
     * that call site can adopt the richer fields later, and so the log layer never
     * has to see a payload.
     */
    data class QrFacts(
        val scheme: String,
        val hostSuffix: String,
        val isIp: Boolean,
        val port: String,
        val chars: Int,
        val credentialLike: Boolean,
    ) {
        /** `scheme=https hostSuffix=example.com isIp=false port=8443 chars=184 credential=true` */
        fun fields(): String = buildString {
            append("scheme=").append(scheme.ifBlank { "none" })
            append(" hostSuffix=").append(hostSuffix.ifBlank { "none" })
            append(" isIp=").append(isIp)
            append(" port=").append(port.ifBlank { "none" })
            append(" chars=").append(chars)
            append(" credential=").append(credentialLike)
        }
    }

    /** Describe [raw] without ever returning it. Blank or null yields empty facts. */
    fun describeQr(raw: String?): QrFacts {
        val value = raw ?: return QrFacts("", "", false, "", 0, false)
        if (value.isBlank()) return QrFacts("", "", false, "", value.length, false)
        val hasScheme = value.contains("://")
        val scheme = if (hasScheme) value.substringBefore("://").lowercase() else ""
        val rest = if (hasScheme) value.substringAfter("://") else value
        val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        val hostPart = if (authority.contains('@')) authority.substringAfter('@') else authority
        val host = hostPart.substringBefore(':').ifEmpty { hostPart }
        val portPart = hostPart.substringAfter(':', missingDelimiterValue = "")
        val port = if (portPart.isNotEmpty() && portPart.all { it.isDigit() }) portPart else ""
        val tail = value.substringAfter('?', missingDelimiterValue = "").ifEmpty {
            value.substringAfter('#', missingDelimiterValue = "")
        }
        // An IP literal is not a DNS suffix: reporting `0.5` for `10.8.0.5` both
        // looks like a leak and tells the reader nothing.
        val isIp = host.isNotBlank() && (ipv4.matches(host) || host.contains(':'))
        return QrFacts(
            // `none` rather than an empty string: the field is rendered into a log
            // line, and a blank there reads as a missing value instead of "no scheme
            // was present in the payload".
            scheme = scheme.ifBlank { "none" },
            hostSuffix = if (isIp) "" else hostSuffixOf(host),
            isIp = isIp,
            port = port,
            chars = value.length,
            credentialLike = tail.split('&', ';').any { credentialQueryKey(it) != null },
        )
    }

    /** Ready-to-log line: never contains the payload. */
    fun describeQrLine(raw: String?): String = "decoded QR; ${describeQr(raw).fields()}; payload excluded"

    // ---- internals ----

    private fun redactPlain(text: String): String {
        if (text.isEmpty()) return text
        var value = bearer.replace(text, REDACTED)
        value = secret.replace(value) { match -> match.groupValues[1] + match.groupValues[2] + REDACTED }
        value = ipv4.replace(value, "<ip>")
        value = ipv6.replace(value, "<ip>")
        value = privatePath.replace(value, "<local-path>")
        return value
    }

    private fun rewriteUrl(raw: String, mode: Mode): String {
        val trailing = raw.takeLastWhile { it in TRAILING_PUNCTUATION }
        val core = if (trailing.isEmpty()) raw else raw.dropLast(trailing.length)
        val scheme = core.substringBefore("://").lowercase()
        val rest = core.substringAfter("://")
        val authority = rest.substringBefore('/')
        val afterAuthority = rest.removePrefix(authority)
        val hostPart = if (authority.contains('@')) authority.substringAfter('@') else authority
        val (host, port) = splitHostPort(hostPart)
        val authorityText = when {
            host.isEmpty() -> ""
            mode == Mode.STRONG -> "<host>"
            port.isEmpty() -> host
            else -> "$host:$port"
        }
        val withoutFragment = afterAuthority.substringBefore('#')
        val querySource = if (withoutFragment.contains('?')) withoutFragment.substringAfter('?') else ""
        val pathSource = withoutFragment.substringBefore('?')
        val path = if (pathSource.length > MAX_PATH_CHARS) pathSource.take(MAX_PATH_CHARS) + "..." else pathSource
        val query = if (querySource.isEmpty()) "" else "?" + redactPairs(querySource)
        val fragment = afterAuthority.substringAfter('#', missingDelimiterValue = "")
        val fragmentText = if (fragment.isEmpty() || fragment == querySource) "" else
            "#" + if (fragment.contains('=')) redactPairs(fragment) else fragment
        return "$scheme://$authorityText$path$query$fragmentText" + trailing
    }

    /** Keep every `k=v` pair whose key is not a credential key. */
    private fun redactPairs(pairs: String): String =
        pairs.split("&").joinToString("&") { segment ->
            if (credentialQueryKey(segment) == null) segment
            else segment.substringBefore("=") + "=" + REDACTED
        }

    /** The credential key of a `key=value` segment, or null when the pair is safe. */
    private fun credentialQueryKey(segment: String): String? {
        if (!segment.contains('=')) return null
        val key = segment.substringBefore('=').trim().trim('"', '\'').lowercase().replace('-', '_')
        return if (key in CREDENTIAL_QUERY_KEYS || key.replace("_", "") in CREDENTIAL_QUERY_KEYS) key else null
    }

    private fun splitHostPort(hostPart: String): Pair<String, String> {
        if (hostPart.startsWith("[")) {
            val end = hostPart.indexOf(']')
            if (end >= 0) return hostPart.substring(0, end + 1) to hostPart.substring(end + 1).removePrefix(":")
        }
        val colon = hostPart.lastIndexOf(':')
        if (colon < 0) return hostPart to ""
        val port = hostPart.substring(colon + 1)
        return if (port.isNotEmpty() && port.all { it.isDigit() }) hostPart.substring(0, colon) to port else hostPart to ""
    }

    /** Last two DNS labels, so an instance is distinguishable without its subdomain. */
    private fun hostSuffixOf(host: String): String {
        if (host.isBlank() || host.contains(':') || !host.contains('.')) return ""
        val labels = host.split('.').filter { it.isNotEmpty() }
        return if (labels.size <= 2) labels.joinToString(".") else labels.takeLast(2).joinToString(".")
    }
}
