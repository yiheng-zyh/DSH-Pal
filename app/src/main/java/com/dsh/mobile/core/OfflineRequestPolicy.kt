package com.dsh.mobile.core

/**
 * 离线请求屏蔽策略（配合「对话本地缓存」实验功能）。
 *
 * 为什么需要它：断网时页面里的实时通道与轮询接口**注定失败**。放任它们去打网络有三个害处：
 *  1. 每次失败要等满连接/读取超时（实测探针 3–5 秒），页面与状态条长期卡在"正在检测"；
 *  2. 失败日志会淹没真正的诊断线索，把"没网"伪装成"连接坏了"；
 *  3. DSH 的重连逻辑会据此判定故障并触发重连/重载，形成越没网越忙的抖动。
 *
 * 与之相对，**静态资源与历史数据要放行**：页面外壳需要它们才能绘制出缓存的历史对话，
 * 挡掉它们等于让离线页永远白屏。
 *
 * 设计边界（很重要，不是"还没做"）：
 *  - 本策略只回答"这个请求在离线时是否应该被拦截"，**不改写响应、不伪造数据**。
 *    被拦截的请求由调用方返回一个明确的离线错误，页面自己走错误分支。
 *  - 只拦截 **API 与实时通道**；页面导航、静态资源、data:/blob: 一律放行。
 *  - 仅在「实验开关开启 + 设备确实无网络」时生效；正常联网时逐字节等同改动前。
 */
object OfflineRequestPolicy {
    /** 拦截原因：调用方据此返回不同的离线响应。 */
    enum class Decision {
        /** 放行，交给 WebView 正常处理。 */
        ALLOW,

        /** 实时通道（WebSocket / SSE / 长轮询）：离线时直接失败，不要等超时。 */
        BLOCK_REALTIME,

        /** 业务 API：离线时没有可信响应，拦截并给明确的离线错误。 */
        BLOCK_API,
    }

    /**
     * 实时通道的路径特征。这些连接建立后会长期挂起，
     * 离线时等它超时最伤体验（会一直显示"正在连接"）。
     */
    private val realtimePaths = listOf(
        "/api/remote.mux",
        "/remote/api/remote.mux",
        "/api/ego/stream",
        "/remote/api/ego/stream",
        "/api/events",
        "/remote/api/events",
        "/api/remote.events",
        "/remote/api/remote.events",
        "/remote/api/remote.stream",
    )

    /**
     * 业务 API 的前缀。这些请求在离线时不可能有真实响应，
     * 而它们的失败会让页面把状态画成"故障"。
     */
    private val apiPrefixes = listOf("/api/", "/remote/api/")

    /**
     * 明确要放行的路径前缀：页面外壳、静态资源、附件下载等。
     * 放行它们离线页才能把缓存的历史内容渲染出来。
     */
    private val alwaysAllowPrefixes = listOf(
        "/plugins/", "/remote/plugins/",
        "/assets/", "/skin-assets/",
        "/wallpaper-engine/",
        "/favicon", "/manifest",
    )

    /** 静态资源的扩展名：无论路径如何都放行。 */
    private val staticExtensions = setOf(
        "js", "mjs", "css", "woff", "woff2", "ttf", "otf",
        "png", "webp", "svg", "jpg", "jpeg", "gif", "ico", "map",
    )

    /** 这些 scheme 与网络无关，永远放行。 */
    private val localSchemes = setOf("data", "blob", "file", "about", "chrome", "javascript")

    /**
     * 判断一个请求在离线时该怎么处理。
     *
     * @param url 请求的完整 URL。
     * @param offlineMode 是否处于"离线 + 实验开关开启"状态。
     * @param mainFrame 是否是主文档导航（主文档永远放行：它可能命中浏览器缓存，
     *   挡掉会让 App 只剩白屏，反而看不到缓存的页面外壳）。
     * @returns 拦截决策。
     */
    fun decide(url: String, offlineMode: Boolean, mainFrame: Boolean): Decision {
        if (!offlineMode) return Decision.ALLOW
        if (mainFrame) return Decision.ALLOW

        val parsed = runCatching { parse(url) }.getOrNull() ?: return Decision.ALLOW
        val (scheme, path) = parsed
        if (scheme in localSchemes) return Decision.ALLOW

        val lower = path.lowercase()

        // 1) 静态资源与页面外壳优先放行：离线页要靠它们绘制。
        if (alwaysAllowPrefixes.any { lower.startsWith(it) }) return Decision.ALLOW
        val ext = lower.substringAfterLast('.', "")
        if (ext.isNotEmpty() && ext in staticExtensions) return Decision.ALLOW

        // 2) 实时通道：先判，因为它们的路径也在 /api/ 下。
        if (realtimePaths.any { lower == it || lower.startsWith("$it/") || lower.startsWith("$it?") }) {
            return Decision.BLOCK_REALTIME
        }

        // 3) 业务 API：离线时没有可信响应。
        if (apiPrefixes.any { lower.startsWith(it) }) return Decision.BLOCK_API

        return Decision.ALLOW
    }

    /**
     * 极简 scheme/path 解析：不依赖 java.net.URI 的边界行为。
     * 解析不出来时按放行处理 —— 未知请求交给 WebView，宁可多试一次也不误杀。
     */
    private fun parse(url: String): Pair<String, String> {
        val schemeEnd = url.indexOf(':')
        if (schemeEnd <= 0) return "" to url
        val scheme = url.substring(0, schemeEnd).lowercase()
        var rest = url.substring(schemeEnd + 1)
        if (rest.startsWith("//")) {
            rest = rest.substring(2)
            val slash = rest.indexOf('/')
            rest = if (slash < 0) "/" else rest.substring(slash)
        }
        val path = rest.substringBefore('#')
        return scheme to path
    }

    /** 拦截时返回给页面的离线响应体：明确、简短、可被页面/用户识别。 */
    fun offlineBody(decision: Decision): String = when (decision) {
        Decision.BLOCK_REALTIME -> """{"ok":false,"code":"offline","message":"设备当前离线：实时通道已暂停，联网后自动恢复"}"""
        Decision.BLOCK_API -> """{"ok":false,"code":"offline","message":"设备当前离线：这一项需要联网，可在设置里查看已缓存的对话"}"""
        Decision.ALLOW -> ""
    }
}
