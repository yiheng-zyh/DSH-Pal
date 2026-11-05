package com.dsh.mobile.core

import java.net.URI

object HarnessSocketPolicy {
    fun isTaskStream(url: String, profile: ConnectionProfile?, usbDevelopment: Boolean = false): Boolean = runCatching {
        val uri = URI(url)
        if (uri.scheme !in setOf("ws", "wss") || uri.host.isNullOrBlank() || uri.userInfo != null) return false
        if (uri.path !in setOf("/api/remote.mux", "/remote/api/remote.mux")) return false
        val target = ConnectionAddress.origin("${if (uri.scheme == "wss") "https" else "http"}://${uri.rawAuthority}") ?: return false
        if (profile == null || target != profile.baseUrl) return false
        if (profile.kind == ConnectionProfile.Kind.USB && !usbDevelopment) return false
        ConnectionAddress.allowed(target, profile.kind)
    }.getOrDefault(false)

    fun isTaskStream(url: String, usbDevelopment: Boolean): Boolean = runCatching {
        val uri = URI(url)
        if (uri.scheme !in setOf("ws", "wss") || uri.host.isNullOrBlank()) return false
        if (uri.path !in setOf("/api/remote.mux", "/remote/api/remote.mux")) return false
        val local = ConnectionAddress.isLocalish(uri.host)
        !local || (usbDevelopment && uri.host == "dsh-usb.localhost" && uri.port == 18765)
    }.getOrDefault(false)
}
