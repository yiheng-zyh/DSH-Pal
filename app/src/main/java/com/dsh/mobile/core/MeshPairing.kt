package com.dsh.mobile.core

import java.net.URI

/**
 * Pairing for the embedded-VPN (MESH) route.
 *
 * A working tunnel is not an authenticated session. The remote plugin's access
 * gate only trusts loopback (gate.ts isLoopbackClient), so a request arriving
 * through Tailscale/WireGuard is an ordinary outside visitor: `/pair-app` is the
 * *already paired* landing and answers 401 until this origin holds a device
 * cookie of its own. Measured on the tablet: the tunnel was up (tun1
 * assigned address, relay active, tx/rx flowing) and still every navigation to
 * `<host>.ts.net/pair-app` ended in `AUTH_REQUIRED http=401`, because that origin had no cookie. VPN connectivity and per-origin application
 * authentication are independent; clearing app data may remove both credentials.
 *
 * The plugin's `accept(token)` reads only its token pool and never inspects the
 * request origin, so one pairing link is a bearer credential for every origin of
 * the same DSH. That is what lets the VPN route pair without a second QR scan:
 * take the token out of whatever link the user already has (the desktop QR is
 * built from the relay/tunnel base) and replay it against the MESH origin, so the
 * device cookie is minted for the host the tunnel actually serves.
 */
object MeshPairing {
    /** Token parameter the plugin's `/pair-accept` page consumes. */
    private const val TOKEN_PARAM = "pair"

    /**
     * Pull the pairing token out of a link or a bare origin.
     *
     * Accepts what a QR scan or a pasted address bar line can contain: the
     * plugin's `/pair-accept?pair=<token>` entry, a `dshmobile://open?url=…`
     * deep link wrapping one, or a plain `?pair=`/`?token=` query. A token is
     * only recognised when it looks like one — the deep link's own `url`
     * parameter and empty values are never mistaken for it.
     * @param raw - scanned or pasted text, may be a full URL or deep link.
     * @returns the token, or null when the input carries none.
     */
    fun token(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        // A deep link wraps the real pairing URL in its own `url` parameter, so
        // unwrap first: otherwise the nested `pair` value is read as absent.
        val unwrapped = runCatching {
            val uri = URI(text)
            val nested = queryValue(uri.rawQuery, "url")
            if (nested != null) URI(nested) else uri
        }.getOrNull() ?: return null
        val candidate = queryValue(unwrapped.rawQuery, TOKEN_PARAM)
            ?: queryValue(unwrapped.rawQuery, "token")
            ?: return null
        return candidate.takeIf { it.isNotBlank() && it.length <= MAX_TOKEN_LENGTH }
    }

    /**
     * Rebuild a pairing URL for [origin] from any link carrying a token.
     *
     * The result is the plugin's own entry page on the requested origin, so the
     * server validates the token normally and sets its cookie for that host.
     * @param raw - the link the user has (any origin).
     * @param origin - the base URL the token must be redeemed against.
     * @returns the origin's `/pair-accept?pair=…` URL, or null without a token.
     */
    fun pairingUrlFor(raw: String, origin: String): String? {
        val token = token(raw) ?: return null
        val base = ConnectionAddress.origin(origin) ?: return null
        return "$base/pair-accept?$TOKEN_PARAM=${encodeUriComponent(token)}"
    }

    /**
     * Whether a stored MESH profile can be opened straight away.
     *
     * A profile restored from settings only carries an origin — the one-shot
     * grant is deliberately not persisted (see ConnectionProfiles) — so "no
     * pairing link" means the landing needs a cookie this route may not have.
     * Callers use this to offer pairing instead of retrying a 401 forever.
     * @param origin - the saved MESH base URL.
     * @param pairingUrl - the pending one-shot link, blank when there is none.
     * @returns true when the route should load without a fresh pairing step.
     */
    fun canOpenSavedRoute(origin: String, pairingUrl: String): Boolean =
        ConnectionAddress.origin(origin) != null && pairingUrl.isNotBlank()

    private fun queryValue(rawQuery: String?, key: String): String? {
        if (rawQuery.isNullOrBlank()) return null
        return rawQuery.split('&').firstNotNullOfOrNull { part ->
            val eq = part.indexOf('=')
            if (eq <= 0) null
            else part.substring(0, eq).takeIf { it == key }?.let { decodeUriComponent(part.substring(eq + 1)) }
        }
    }

    private fun decodeUriComponent(value: String): String =
        runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    private fun encodeUriComponent(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")

    /** Guard against a pasted page body being treated as a token. */
    private const val MAX_TOKEN_LENGTH = 512
}
