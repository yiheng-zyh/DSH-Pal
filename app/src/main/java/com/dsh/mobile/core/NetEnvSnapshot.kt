package com.dsh.mobile.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.webkit.WebView
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI

/**
 * Snapshot of the device's network environment for the exported log header.
 *
 * ## Platform helper — needs a [Context]
 *
 * This file lives in `core/` next to the other connectivity readers
 * ([NetworkConnectivity], which also imports `android.net.*`), but it is **not**
 * pure logic: it reads the Android connectivity service and the installed WebView
 * package. Pure-logic classes must not call into it — the platform layer collects
 * here and hands a [NetEnv] over, so unit tests never touch `android.os.Looper`
 * (`.ai/DECISIONS.md`, 2026-10-27).
 *
 * ## Why the header needs it
 *
 * Both incidents were read as "the app is broken" when the environment was the
 * variable: a RELAY hostname that resolves only on some resolvers, and a page that
 * dies on syntax the installed WebView does not understand. `dns-fail` on `origin`
 * while `public=ok` means the network works and the *name* is the problem — legible
 * only if the transport and resolvers are on the record.
 *
 * ## Failure safety
 *
 * Every field is optional: a missing service, an API level that cannot answer, or
 * any exception yields the [NetEnv] default (empty string / null / false) instead
 * of throwing, because a diagnostic header must never be the reason an export fails.
 */
object NetEnvSnapshot {

    /** WebView major below this predates the syntax the DSH front-end emits. */
    private const val MIN_MODERN_WEBVIEW_MAJOR = 130

    /**
     * Collect the environment and record it on [DiagFacts].
     *
     * Safe from any thread, any number of times: the last snapshot wins.
     */
    fun collect(context: Context): NetEnv {
        val env = try {
            val transport = transportOf(context)
            NetEnv(
                transport = transport.transport,
                validated = transport.validated,
                metered = transport.metered,
                proxy = proxyDescription(),
                privateDns = privateDnsOf(context),
                dnsServers = dnsServersOf(context),
                webviewVersion = webViewVersion(),
                webviewOld = webViewIsOld(),
            )
        } catch (error: Exception) {
            // Nothing above is allowed to break an export. Every field keeps its
            // default, so the summary says "unknown" instead of inventing a value.
            NetEnv()
        }
        annotate(env)
        return env
    }

    /** Record an already-collected snapshot (for callers that cached one). */
    fun annotate(result: NetEnv) {
        runCatching { DiagFacts.recordNetEnv(result) }
    }

    // ---- transport ----

    private data class TransportInfo(val transport: String, val validated: Boolean?, val metered: Boolean?)

    /**
     * Active network, in the contract's token vocabulary
     * (`cellular` / `wifi` / `vpn` / `none`).
     *
     * VPN is checked first: when an embedded tunnel owns the default route, "the
     * phone is on Wi-Fi" would hide the hop that actually matters.
     */
    private fun transportOf(context: Context): TransportInfo {
        val manager = context.getSystemService(ConnectivityManager::class.java)
            ?: return TransportInfo("none", null, null)
        val network: Network = manager.activeNetwork ?: return TransportInfo("none", null, null)
        val capabilities = manager.getNetworkCapabilities(network)
            ?: return TransportInfo("none", null, null)

        fun has(transport: Int) = capabilities.hasTransport(transport)
        val token = when {
            has(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            has(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            has(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            has(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "none"
        }
        return TransportInfo(
            transport = token,
            validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            // NOT_METERED absent means metered: on a phone this is cellular billing,
            // and it is also why a route can be "available" but throttled.
            metered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
        )
    }

    // ---- private DNS / resolvers ----

    /**
     * Private DNS mode: `off` / `opportunistic` / `host:<name>` / `unknown`.
     *
     * `isPrivateDnsActive` and `privateDnsServerName` are API 30+, so below that the
     * honest answer is `unknown` — reporting `off` would be a fabricated fact, and a
     * wrong DNS mode in the header is exactly what misleads a reader of a dns-fail.
     */
    private fun privateDnsOf(context: Context): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return "unknown"
        val link = linkProperties(context) ?: return "unknown"
        return when {
            !link.isPrivateDnsActive -> "off"
            link.privateDnsServerName.isNullOrBlank() -> "opportunistic"
            else -> "host:${link.privateDnsServerName}"
        }
    }

    /** Up to three resolver addresses, comma separated. */
    private fun dnsServersOf(context: Context): String =
        linkProperties(context)?.dnsServers?.take(3)?.joinToString(", ") { it.hostAddress.orEmpty() }.orEmpty()

    private fun linkProperties(context: Context): LinkProperties? = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        manager?.activeNetwork?.let { manager.getLinkProperties(it) }
    }.getOrNull()

    // ---- proxy ----

    /**
     * System proxy as `none` or `host:port`.
     *
     * Android publishes a global proxy through the `http.proxyHost` system
     * properties, so that is read first; `ProxySelector` is the fallback for a proxy
     * configured outside those properties (an app-level or JVM-level setting).
     */
    private fun proxyDescription(): String {
        systemPropertyProxy()?.let { return it }
        val viaSelector = runCatching {
            ProxySelector.getDefault().select(URI("https://dsh.invalid/"))
                .firstOrNull { it.type() != Proxy.Type.DIRECT }
        }.getOrNull()?.address() as? InetSocketAddress
        val host = viaSelector?.hostString
        return if (host.isNullOrBlank()) "none"
        else "$host${if (viaSelector.port > 0) ":${viaSelector.port}" else ""}"
    }

    private fun systemPropertyProxy(): String? {
        val host = System.getProperty("http.proxyHost").orEmpty()
        if (host.isBlank()) return null
        val port = System.getProperty("http.proxyPort").orEmpty().ifBlank { "8080" }
        return "$host:$port"
    }

    // ---- WebView ----

    /**
     * Installed WebView version name, or `unknown`.
     *
     * `getCurrentWebViewPackage()` needs a loaded provider and can return null
     * before the first WebView exists; that is reported as unknown, never guessed.
     */
    private fun webViewVersion(): String = runCatching {
        WebView.getCurrentWebViewPackage()?.versionName.orEmpty().ifBlank { "unknown" }
    }.getOrDefault("unknown")

    private fun webViewIsOld(): Boolean = runCatching {
        // Version names look like "130.0.6723.58"; a system WebView reports only
        // the major, so parse the leading integer and treat anything else as unknown.
        val major = webViewVersion().substringBefore('.').toIntOrNull() ?: return false
        major < MIN_MODERN_WEBVIEW_MAJOR
    }.getOrDefault(false)
}
