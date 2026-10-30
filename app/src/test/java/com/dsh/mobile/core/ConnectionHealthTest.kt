package com.dsh.mobile.core

import org.junit.Assert.*
import org.junit.Test

class ConnectionHealthTest {
    @Test fun idleLiveChannelsReportResponseAgeWithoutPretendingToReconnect() {
        val text=ConnectionQualityPolicy.describe("VPN",true,false,false,true,Reachability(200),0,BusinessHealth(lastSuccessAgeMs=90000,restrictedPlugins=3))
        assertTrue(text.contains("通道已建立"));assertTrue(text.contains("90 秒前"));assertTrue(text.contains("3 项插件接口受限"))
        assertFalse(text.contains("正在恢复"))
    }
    @Test fun aSlowSuccessfulResponseDoesNotReloadAnOtherwiseLiveConversation() {
        val health=BusinessHealth(lastSuccessAgeMs=0,lastLatencyMs=8000,slowPending=1)
        assertTrue(health.impaired);assertFalse(health.recoveryNeeded)
        assertTrue(health.copy(failurePercent=40).recoveryNeeded)
    }
    @Test fun deniedOptionalPluginEndpointsAreNotPacketLossOrBrokenScripts() {
        val health=BusinessHealth(lastSuccessAgeMs=0,restrictedPlugins=3)
        assertTrue(health.verified)
        assertEquals(ConnectionQuality.VERIFIED,ConnectionQualityPolicy.classify(true,true,0,health))
        val text=ConnectionQualityPolicy.describe("VPN",true,false,false,true,Reachability(200),0,health)
        assertTrue(text.contains("3 项插件接口受限"));assertFalse(text.contains("资源加载失败"))
    }
    @Test fun aPendingPluginBundleNeverMeansTheWholeInterfaceIsReady() {
        val health=BusinessHealth(lastSuccessAgeMs=0,loadingResources=2,oldestResourceMs=22000)
        assertFalse(health.verified)
        assertEquals(ConnectionQuality.TRANSPORT_ONLY,ConnectionQualityPolicy.classify(true,true,0,health))
        assertTrue(ConnectionQualityPolicy.describe("VPN",true,false,false,true,Reachability(200),0,health).contains("正在加载 2 项界面资源"))
    }
    @Test fun socketHandshakeNeverProvesBusinessUsable() {
        assertEquals(ConnectionQuality.TRANSPORT_ONLY,ConnectionQualityPolicy.classify(true,true,0,BusinessHealth()))
        assertEquals(ConnectionQuality.DEGRADED,ConnectionQualityPolicy.classify(true,true,40,BusinessHealth(lastSuccessAgeMs=0)))
        assertEquals(ConnectionQuality.DEGRADED,ConnectionQualityPolicy.classify(true,true,0,BusinessHealth(slowPending=1,lastSuccessAgeMs=0)))
        assertEquals(ConnectionQuality.DEGRADED,ConnectionQualityPolicy.classify(true,true,0,BusinessHealth(resourceFailures=1,lastSuccessAgeMs=0)))
        assertEquals(ConnectionQuality.VERIFIED,ConnectionQualityPolicy.classify(true,true,0,BusinessHealth(lastSuccessAgeMs=0,lastLatencyMs=100)))
    }
    @Test fun statusPrioritizesActualFaultOverSocketAndOldProbeSamples() {
        fun label(endpoint:Reachability?,business:BusinessHealth=BusinessHealth())=ConnectionQualityPolicy.describe("Wi-Fi",true,false,false,true,endpoint,40,business)
        assertTrue(label(Reachability(530)).contains("公网隧道不可用"))
        assertTrue(label(Reachability(502)).contains("服务端异常"))
        assertTrue(label(Reachability(200),BusinessHealth(slowPending=2)).contains("2 个接口等待过久"))
        assertFalse(label(Reachability(200)).contains("已收到业务响应"))
        assertTrue(ConnectionQualityPolicy.describe("Wi-Fi",false,false,false,true,Reachability(200),0,BusinessHealth(lastSuccessAgeMs=0)).contains("无可用网络"))
    }
    @Test fun responseIsNotTheSameAsAuthentication() {
        assertTrue(Reachability(status=401).reachable)
        assertTrue(Reachability(status=403).reachable)
        assertFalse(Reachability(status=502).reachable)
        assertFalse(Reachability(status=429).reachable)
        assertFalse(Reachability(error="timeout").reachable)
    }
    @Test fun recentFailuresRequireEnoughSamplesAndExpire() {
        val window=HealthWindow(3)
        window.record(false); assertNull(window.failurePercent)
        window.record(true); window.record(true); assertEquals(33,window.failurePercent)
        window.record(true); assertEquals(0,window.failurePercent)
    }
    @Test fun recoveryDoesNotReloadHealthyOrBusySessions() {
        assertFalse(RecoveryPolicy.canReload(true,true,false,false,30_000,60_000))
        assertFalse(RecoveryPolicy.canReload(false,true,false,true,30_000,60_000))
        assertFalse(RecoveryPolicy.canReload(false,true,true,false,30_000,60_000))
        assertFalse(RecoveryPolicy.canReload(false,false,false,false,30_000,60_000))
        assertTrue(RecoveryPolicy.canReload(false,true,false,false,30_000,60_000))
    }
    @Test fun backoffAndReloadAreBounded() {
        assertEquals(1_000L,RecoveryPolicy.delayMs(0))
        assertEquals(30_000L,RecoveryPolicy.delayMs(100))
        assertFalse(RecoveryPolicy.canReload(false,true,false,false,19_999,60_000))
        assertFalse(RecoveryPolicy.canReload(false,true,false,false,30_000,59_999))
    }
    @Test fun automaticReloadStopsAfterTwoAttemptsAndWaitsForResources() {
        assertTrue(RecoveryPolicy.canReload(false,true,false,false,30_000,60_000,attempts=1))
        assertFalse(RecoveryPolicy.canReload(false,true,false,false,30_000,60_000,attempts=2))
        assertFalse(RecoveryPolicy.canReload(false,true,false,false,30_000,60_000,loadingResources=1))
    }
    @Test fun missingBrowserContextIsNotAConnectivityFailureSample() {
        assertFalse(Reachability(error="context-changed").measured)
        assertFalse(Reachability(error="browser-not-ready").measured)
        assertTrue(Reachability(error="timeout").measured)
        assertTrue(Reachability(status=401).measured)
    }
    @Test fun onlyTransientMainPageFailuresCanRetryWithoutProbeContext() {
        for(message in listOf("HTTP 503","HTTP 429","net::ERR_TIMED_OUT","net::ERR_NETWORK_CHANGED"))assertTrue(RecoveryPolicy.retryablePageFailure(message))
        for(message in listOf(null,"HTTP 401","HTTP 403","HTTP 404","配对凭据已失效","页面运行出错，可打开设置查看日志"))assertFalse(RecoveryPolicy.retryablePageFailure(message))
    }
}
