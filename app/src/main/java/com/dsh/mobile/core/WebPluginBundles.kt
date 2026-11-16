package com.dsh.mobile.core

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** DSH combines multiple independent client modules in one versioned request. */
object WebPluginBundles {
    private val paths = setOf("/plugins/", "/remote/plugins/")
    private val revision = Regex("[A-Za-z0-9._:-]{1,128}")
    private val module = Regex("@?[A-Za-z0-9_-]+(?:/[A-Za-z0-9_.-]+)+\\.(?:m?js|css)")
    private val packageId = Regex("(?:@[A-Za-z0-9_-]+/)?[A-Za-z0-9_-]+")
    private val required = setOf("@linxin666/dsh-remote-web-ui", "dshmarket")

    fun canPause(id: String): Boolean =
        id.length <= 180 && packageId.matches(id) && !id.startsWith("@deepseek-ai/") && id !in required

    data class Module(val path: String) {
        val packageName: String = path.split('/').take(if (path.startsWith('@')) 2 else 1).joinToString("/")
    }

    fun parse(value: String): List<Module>? {
        val url = value.toHttpUrlOrNull() ?: return null
        if (url.encodedPath !in paths || url.username.isNotEmpty() || url.password.isNotEmpty()) return null
        val names = url.queryParameterNames
        if (names.size != 2 || "rev" !in names) return null
        val list = names.single { it != "rev" }
        if (!list.startsWith('?') || url.queryParameterValues(list) != listOf<String?>(null)) return null
        if (url.queryParameterValues("rev").singleOrNull()?.let(revision::matches) != true) return null
        val modules = list.drop(1).split(',')
        if (modules.size !in 1..256 || modules.any { it.length > 240 || ".." in it || !module.matches(it) }) return null
        if (modules.any { it.startsWith('@') && it.count { char -> char == '/' } < 2 }) return null
        return modules.map(::Module)
    }
}
