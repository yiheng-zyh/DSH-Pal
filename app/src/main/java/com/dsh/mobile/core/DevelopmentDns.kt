package com.dsh.mobile.core

/** Chromium resolves .localhost itself; OkHttp must use the same USB-only mapping. */
object DevelopmentDns : okhttp3.Dns {
    override fun lookup(hostname: String): List<java.net.InetAddress> {
        val mapped = DevelopmentTransport.nativeLoopback(hostname, com.dsh.mobile.BuildConfig.USB_DEVELOPMENT)
        return if (mapped != null) listOf(java.net.InetAddress.getByName(mapped)) else okhttp3.Dns.SYSTEM.lookup(hostname)
    }
}
