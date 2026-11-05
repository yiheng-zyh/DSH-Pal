package com.dsh.mobile.core

import org.junit.Assert.*
import org.junit.Test

class HarnessSocketPolicyTest {
    @Test fun publicMuxAccepted() {
        assertTrue(HarnessSocketPolicy.isTaskStream("wss://example.org/remote/api/remote.mux?key=ignored", false))
    }
    @Test fun telemetryAndSidebarDoNotSetTaskConnectionState() {
        assertFalse(HarnessSocketPolicy.isTaskStream("wss://example.org/ingest", false))
        assertFalse(HarnessSocketPolicy.isTaskStream("wss://example.org/remote/sidebar/ws/agent-opens", false))
    }
    @Test fun usbMuxRequiresDevelopmentBuild() {
        val url = "ws://dsh-usb.localhost:18765/remote/api/remote.mux"
        assertFalse(HarnessSocketPolicy.isTaskStream(url, false))
        assertTrue(HarnessSocketPolicy.isTaskStream(url, true))
    }
    @Test fun otherLocalEndpointsNotAccepted() {
        for (url in listOf("ws://127.0.0.1:18765/api/remote.mux", "ws://localhost:80/api/remote.mux", "ws://dsh-usb.localhost:80/api/remote.mux")) assertFalse(HarnessSocketPolicy.isTaskStream(url, true))
    }
    @Test fun pathAndSchemeAreExact() {
        assertFalse(HarnessSocketPolicy.isTaskStream("https://example.org/api/remote.mux", false))
        assertFalse(HarnessSocketPolicy.isTaskStream("wss://example.org/ingest?remote.mux", false))
        assertFalse(HarnessSocketPolicy.isTaskStream("not a URL", false))
    }
}
