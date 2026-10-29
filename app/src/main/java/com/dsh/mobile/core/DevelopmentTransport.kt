package com.dsh.mobile.core

import java.net.URI

object DevelopmentTransport {
    fun nativeLoopback(host: String, usbDevelopment: Boolean): String? =
        if (usbDevelopment && host.equals("dsh-usb.localhost", ignoreCase = true)) "127.0.0.1" else null
    /** The one loopback origin reserved for an explicit adb reverse development build. */
    fun isUsbOrigin(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "http" && uri.host == "dsh-usb.localhost" && uri.port == 18765 && uri.userInfo == null
    }.getOrDefault(false)
}
