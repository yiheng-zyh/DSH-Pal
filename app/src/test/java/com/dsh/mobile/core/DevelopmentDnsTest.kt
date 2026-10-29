package com.dsh.mobile.core
import org.junit.Assert.*
import org.junit.Test
class DevelopmentDnsTest {
    @Test fun reservedAliasHasNativeResolutionOnlyInDevelopment() {
        assertEquals("127.0.0.1", DevelopmentTransport.nativeLoopback("dsh-usb.localhost", true))
        assertEquals("127.0.0.1", DevelopmentTransport.nativeLoopback("DSH-USB.LOCALHOST", true))
        assertNull(DevelopmentTransport.nativeLoopback("dsh-usb.localhost", false))
    }
    @Test fun otherHostsKeepTheirSystemResolution() {
        for(host in listOf("localhost","other.localhost","example.org","100.64.1.2")) assertNull(DevelopmentTransport.nativeLoopback(host,true))
    }
}
