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
        assertTrue(host.contains("polyfillScript + \"\\n\" + WebViewport.script()"))
        // onPageStarted 双保险：卓易通上 document-start 整条静默失效，
        // 只有 evaluateJavascript 路径被实测证明能跑（bridge probe ok）。
        assertTrue(host.contains("injectJs(view, polyfillScript, \"webview-polyfill\")"))
    }
}