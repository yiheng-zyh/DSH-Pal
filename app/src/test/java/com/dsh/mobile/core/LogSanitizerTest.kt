package com.dsh.mobile.core

import org.junit.Assert.*
import org.junit.Test

class LogSanitizerTest {
    @Test fun plainAddressesAndLocalPathsAreNotPublished() {
        val text = LogSanitizer.clean("connect 192.168.1.12 failed at C:\\Users\\Private\\project\\file.log")
        assertFalse(text.contains("192.168.1.12"))
        assertFalse(text.contains("Private"))
        assertTrue(text.contains("<ip>"))
        assertTrue(text.contains("<local-path>"))
        assertTrue(LogSanitizer.clean("webview 151.0.7872.129").contains("151.0.7872.129"))
    }
    @Test fun pairingUrlDoesNotLeakCredentialsOrHost() {
        val text = LogSanitizer.clean("start https://private.example/pair-accept?pair=abc123&grant=other")
        assertFalse(text.contains("private.example"))
        assertFalse(text.contains("abc123"))
        assertFalse(text.contains("other"))
        assertTrue(text.contains("pair-accept"))
        assertTrue(text.contains("https://<host>/pair-accept"))
    }

    @Test fun structuredErrorsAndAuthorizationAreRedacted() {
        val text = LogSanitizer.clean("{\"token\":\"sensitive123\",\"password\":\"hidden456\"} Authorization: Bearer xyz789")
        listOf("sensitive123", "hidden456", "xyz789").forEach { assertFalse(text.contains(it)) }
    }

    @Test fun usefulDiagnosticsSurvive() {
        assertEquals("ws OPEN progress=80% HTTP 403", LogSanitizer.clean("ws OPEN progress=80% HTTP 403"))
        assertTrue(LogSanitizer.clean("x".repeat(10000)).length <= 4000)
    }
}
