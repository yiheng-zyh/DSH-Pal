package com.dsh.mobile.core

import org.junit.Assert.*
import org.junit.Test

/**
 * 离线请求屏蔽策略测试。
 *
 * 核心断言分两类：
 *  1. **该挡的挡住** —— 实时通道与业务 API 在离线时不打网络（否则白等超时、日志淹没、误触发重连）。
 *  2. **该放的放行** —— 静态资源、插件包、页面外壳、主文档、本地 scheme 必须放行，
 *     否则离线页连缓存的界面都画不出来，功能等于不存在。
 */
class OfflineRequestPolicyTest {
    private val origin = "https://example.test"

    private fun url(path: String) = origin + path

    @Test fun offlineModeOffAllowsEverything() {
        // 没有开启离线模式（实验开关关闭或设备有网）时逐字节等同改动前
        for (path in listOf("/api/session/list", "/api/remote.mux", "/plugins/a.js", "/", "/api/ego/stream")) {
            assertEquals(
                path,
                OfflineRequestPolicy.Decision.ALLOW,
                OfflineRequestPolicy.decide(url(path), offlineMode = false, mainFrame = false),
            )
        }
        // 实时通道在主文档下也不该被改判（开关关闭时一律放行）
        assertEquals(
            OfflineRequestPolicy.Decision.ALLOW,
            OfflineRequestPolicy.decide(url("/api/remote.mux"), offlineMode = false, mainFrame = true),
        )
    }

    @Test fun realtimeChannelsAreBlockedOffline() {
        for (path in listOf(
            "/api/remote.mux",
            "/remote/api/remote.mux",
            "/api/ego/stream",
            "/remote/api/ego/stream",
            "/api/events",
            "/remote/api/remote.events",
            "/remote/api/remote.stream",
        )) {
            assertEquals(path, OfflineRequestPolicy.Decision.BLOCK_REALTIME,
                OfflineRequestPolicy.decide(url(path), offlineMode = true, mainFrame = false))
        }
        // 带查询参数也要认出来（SSE 常带 ?dsh_events_only=1）
        assertEquals(
            OfflineRequestPolicy.Decision.BLOCK_REALTIME,
            OfflineRequestPolicy.decide(url("/api/ego/stream?dsh_events_only=1"), offlineMode = true, mainFrame = false),
        )
    }

    @Test fun businessApiIsBlockedOffline() {
        for (path in listOf(
            "/api/session/list",
            "/api/session/projections",
            "/api/pair/status",
            "/api/settings/describe",
            "/api/credentials/describe",
            "/remote/api/session/list",
        )) {
            assertEquals(path, OfflineRequestPolicy.Decision.BLOCK_API,
                OfflineRequestPolicy.decide(url(path), offlineMode = true, mainFrame = false))
        }
    }

    @Test fun staticAssetsAndPluginBundlesStayAllowed() {
        for (path in listOf(
            "/plugins/??a.js,b.css&rev=abc123",
            "/remote/plugins/??a.js&rev=1",
            "/assets/index-ABC123.js",
            "/assets/main.css",
            "/skin-assets/a.webp",
            "/favicon.ico",
            "/manifest.webmanifest",
            "/foo/bar.woff2",
        )) {
            assertEquals("离线时放行静态资源否则页面画不出来: $path",
                OfflineRequestPolicy.Decision.ALLOW,
                OfflineRequestPolicy.decide(url(path), offlineMode = true, mainFrame = false))
        }
    }

    @Test fun mainDocumentNavigationIsAlwaysAllowed() {
        // 主文档可能命中浏览器缓存里的页面外壳；挡掉只剩白屏，比放行更糟
        for (path in listOf("/", "/index.html", "/pair-app")) {
            assertEquals(path, OfflineRequestPolicy.Decision.ALLOW,
                OfflineRequestPolicy.decide(url(path), offlineMode = true, mainFrame = true))
        }
    }

    @Test fun localSchemesAreNeverBlocked() {
        for (u in listOf(
            "data:text/html,<html></html>",
            "blob:https://example.test/abc-123",
            "file:///android_asset/x.html",
            "about:blank",
            "javascript:void(0)",
        )) {
            assertEquals(u, OfflineRequestPolicy.Decision.ALLOW,
                OfflineRequestPolicy.decide(u, offlineMode = true, mainFrame = false))
        }
    }

    @Test fun unknownAndMalformedUrlsAreAllowedRatherThanGuessed() {
        // 解析不出来就交给 WebView：宁可多试一次也不误杀
        for (u in listOf("", "not-a-url", "https://", "//no-scheme/path")) {
            assertEquals(u, OfflineRequestPolicy.Decision.ALLOW,
                OfflineRequestPolicy.decide(u, offlineMode = true, mainFrame = false))
        }
    }

    @Test fun realtimeTakesPrecedenceOverGenericApiPrefix() {
        // /api/remote.mux 同时命中 /api/ 前缀，必须是实时通道而不是普通 API：
        // 两者的离线响应文案与页面分支不同。
        assertEquals(
            OfflineRequestPolicy.Decision.BLOCK_REALTIME,
            OfflineRequestPolicy.decide(url("/api/remote.mux"), offlineMode = true, mainFrame = false),
        )
    }

    @Test fun offlineBodiesNameTheStateInsteadOfFakingData() {
        val realtime = OfflineRequestPolicy.offlineBody(OfflineRequestPolicy.Decision.BLOCK_REALTIME)
        val api = OfflineRequestPolicy.offlineBody(OfflineRequestPolicy.Decision.BLOCK_API)
        // 明确标 offline，不伪造 ok:true 让页面以为拿到了真实数据
        assertTrue(realtime.contains("\"code\":\"offline\""))
        assertTrue(realtime.contains("\"ok\":false"))
        assertTrue(api.contains("\"code\":\"offline\""))
        assertTrue(api.contains("\"ok\":false"))
        // 允许的情况没有响应体
        assertEquals("", OfflineRequestPolicy.offlineBody(OfflineRequestPolicy.Decision.ALLOW))
        // 中文提示要能让用户看懂该做什么
        assertTrue(api.contains("缓存"))
    }

    @Test fun schemeAndHostCaseAreHandled() {
        assertEquals(
            OfflineRequestPolicy.Decision.BLOCK_API,
            OfflineRequestPolicy.decide("HTTPS://Example.TEST/API/session/list", offlineMode = true, mainFrame = false),
        )
        assertEquals(
            OfflineRequestPolicy.Decision.ALLOW,
            OfflineRequestPolicy.decide("HTTPS://Example.TEST/ASSETS/INDEX.JS", offlineMode = true, mainFrame = false),
        )
    }
}
