package com.dsh.mobile.core

import org.junit.Assert.*
import org.junit.Test

class DevelopmentTransportTest {
    @Test fun acceptsOnlyReservedUsbOrigin() {
        assertTrue(DevelopmentTransport.isUsbOrigin("http://dsh-usb.localhost:18765/"))
        for (url in listOf("http://127.0.0.1:80/", "http://localhost:18765/", "https://127.0.0.1:18765/", "http://192.0.2.1:18765/", "http://user@127.0.0.1:18765/", "bad")) assertFalse(url, DevelopmentTransport.isUsbOrigin(url))
    }
}
