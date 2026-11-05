package com.dsh.mobile.core

object WebFilePolicy {
    fun safeName(raw: String): String = raw.substringAfterLast('/').substringAfterLast('\\')
        .replace(Regex("[\\p{Cntrl}:*?\"<>|]"), "_").trim().take(160).takeUnless { it.isBlank() || it == "." || it == ".." } ?: "dsh-export.bin"
    fun sameOrigin(a: String, b: String): Boolean = ConnectionAddress.origin(a)?.let { it == ConnectionAddress.origin(b) } == true
    fun <T> chooserItems(success:Boolean,data:T?,clip:List<T>,fallback:List<T>):List<T> =
        if(!success)emptyList()else (clip+listOfNotNull(data)+fallback).distinct()
    fun acceptedUris(schemes: List<String?>): Boolean = schemes.isNotEmpty() && schemes.all { it == "content" }
    fun validBlobOffer(token: String, size: Long): Boolean = token.matches(Regex("[a-zA-Z0-9-]{16,80}")) && size in 0..536870912L
    fun redirect(origin: String, current: String, location: String): String? = runCatching {
        java.net.URI(current).resolve(location).toString().takeIf { sameOrigin(it, origin) }
    }.getOrNull()
}
