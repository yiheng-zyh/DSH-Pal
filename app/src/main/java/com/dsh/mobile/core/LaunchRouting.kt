package com.dsh.mobile.core

import java.net.URI

data class ConnectionTarget(val baseUrl: String, val pairingUrl: String)

/** Ordinary launcher/notification returns never alter the current page target. */
object LaunchRouting {
    fun resumeUrl(origin:String):String = ConnectionAddress.origin(origin)?.plus("/pair-app") ?: origin
    fun apply(current: ConnectionTarget, requestedUrl: String?): ConnectionTarget {
        if (requestedUrl.isNullOrBlank()) return current
        val uri = runCatching { URI(requestedUrl) }.getOrNull() ?: return current
        val origin = ConnectionAddress.origin(requestedUrl) ?: return current
        val pairing = uri.path.orEmpty() in setOf("/pair-accept", "/pair-app") ||
            uri.rawQuery.orEmpty().split('&').any { it.substringBefore('=') == "pair" }
        return if (pairing) {
            ConnectionTarget(origin, requestedUrl)
        } else {
            ConnectionTarget(origin, "")
        }
    }
}
