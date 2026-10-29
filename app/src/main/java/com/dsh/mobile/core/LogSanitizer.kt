package com.dsh.mobile.core

/** Pairing URLs and page errors can contain credentials. Sanitize before any sink. */
object LogSanitizer {
    private val url = Regex("(?i)(?:https?|wss?)://[^\\s<>\\\"']+")
    private val secret = Regex("(?i)(token|grant|pair|cookie|authorization|password|secret)([\\\"']?\\s*[:=]\\s*[\\\"']?)[^\\s,;&\\\"'}]+")
    private val bearer = Regex("(?i)Bearer\\s+[^\\s,;]+")
    private val ipv4 = Regex("(?<![\\w.])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?![\\w.])")
    private val privatePath = Regex("(?i)(?:(?<![a-z0-9])[A-Z]:[\\\\/]|/data/user/|/home/)[^\\r\\n\"<>|]+")

    fun clean(message: String): String = privatePath.replace(ipv4.replace(secret.replace(url.replace(bearer.replace(message, "Bearer <redacted>")) {
        val value = it.value
        val path = value.substringAfter("://").substringAfter('/', "").substringBefore('?').substringBefore('#')
        "https://<host>/${path.take(100)}" + if ('?' in value) "?<redacted>" else ""
    }, "$1$2<redacted>"), "<ip>"), "<local-path>").take(4000)
}
