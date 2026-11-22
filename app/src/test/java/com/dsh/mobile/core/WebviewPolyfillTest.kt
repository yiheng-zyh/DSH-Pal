package com.dsh.mobile.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * webview-polyfill.js（issue #1：鸿蒙卓易通旧 Chromium 缺
 * Promise.withResolvers / AbortSignal.any）的静态契约检查。
 *
 * JVM 测试没有 JS 引擎，不为此引入新依赖：行为验证由会话目录的
 * Node 自测完成（模拟删 API 的旧环境），这里守住「脚本能被旧内核
 * 解析、注入点接线正确、可观测 foothold 存在」这三条结构契约。
 */
class WebviewPolyfillTest {

    // Gradle 测试的 user.dir 通常是模块目录（app/），个别配置下是工程根：两处都探。
    private val moduleDir = listOf(File(System.getProperty("user.dir")), File(System.getProperty("user.dir"), "app"))
        .first { File(it, "src/main/assets").isDirectory }
    private val script = File(moduleDir, "src/main/assets/webview-polyfill.js").readText()

    @Test fun coversBothMissingApis() {
        assertTrue(script.contains("Promise.withResolvers"))
        assertTrue(script.contains("AbortSignal.any"))
        // 缺失才补：两处都必须有 typeof 守卫，新内核零改动。
        assertTrue(script.contains("typeof Promise.withResolvers !== 'function'"))
        assertTrue(script.contains("typeof AbortSignal.any !== 'function'"))
    }

    /**
     * issue #2：鸿蒙 WebView 114 上真正炸渲染的是 URL.canParse（Chromium 120+），
     * 0.1.4 的清单里只有前两项，所以补了兼容层页面照样挂不起来。
     */
    @Test fun coversUrlCanParseAndCompanions() {
        assertTrue("URL.canParse", script.contains("URL.canParse"))
        assertTrue("missing-only guard", script.contains("typeof URL.canParse !== 'function'"))
        assertTrue("URL.parse", script.contains("URL.parse"))
        assertTrue("Object.groupBy", script.contains("Object.groupBy"))
        assertTrue("Map.groupBy", script.contains("Map.groupBy"))
        // 分组键可能是 '__proto__'：必须走 defineProperty，普通赋值会污染原型。
        assertTrue(script.contains("Object.defineProperty(out, key"))
    }

    @Test fun reportsWhichApisWereActuallyInstalled() {
        // 只记"真的补过的"：静态清单在新内核上也会列满，等于没有信息量。
        assertTrue(script.contains("var applied = []"))
        assertTrue(script.contains("window.__DSH_POLYFILLS__ = { version: 2, apis: applied }"))
        // foothold 必须先占位，中途抛错也不会整个消失。
        assertTrue(script.contains("window.__DSH_POLYFILLS__ = { version: 1, apis: [] }"))
    }

    @Test fun staysInsideOldChromiumSyntaxSubset() {
        // WebView 126 子集（PageProbeScript 同款约定）：polyfill 自己绝不能在
        // 旧内核上二次语法报错。去掉注释后检查禁用语法。
        val code = script.lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
        assertFalse("no nullish assignment", code.contains("??="))
        assertFalse("no logical assignment", code.contains("||=") || code.contains("&&="))
        assertFalse("no optional chaining", code.contains("?."))
        assertFalse("no arrow functions", code.contains("=>"))
        assertFalse("no let/const", Regex("\\b(let|const)\\s").containsMatchIn(code))
        assertFalse("no Array.at", code.contains(".at("))
        assertFalse("no structuredClone", code.contains("structuredClone"))
    }

    @Test fun exposesInjectCheckFoothold() {
        assertTrue(script.contains("__DSH_POLYFILLS__"))
        val probe = PageProbeScript.injectCheck()
        assertTrue("injectCheck must watch the polyfill foothold", probe.contains("__DSH_POLYFILLS__"))
    }

    @Test fun wiredIntoBothInjectionPaths() {
        val host = File(moduleDir, "src/main/java/com/dsh/mobile/ui/web/DshWebView.kt").readText()
        // document-start 串联的最前面（先于页面 boot 脚本）。
        val registration = host.substringAfter("WebViewCompat.addDocumentStartJavaScript(")
        assertTrue(Regex("listOf\\(\\s*polyfillScript,").containsMatchIn(registration))
        // onPageStarted 双保险：卓易通上 document-start 整条静默失效，
        // 只有 evaluateJavascript 路径被实测证明能跑（bridge probe ok）。
        assertTrue(host.contains("injectJs(view, polyfillScript, \"webview-polyfill\")"))
    }
}
