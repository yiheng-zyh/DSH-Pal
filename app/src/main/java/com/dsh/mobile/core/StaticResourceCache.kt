package com.dsh.mobile.core

import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** App-owned, bounded static files only. No document, API, credential or offline API cache. */
class StaticResourceCache(
    private val directory: File,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS).build(),
    private val limitBytes: Long = 96L * 1024 * 1024,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {
    data class Resource(val bytes: ByteArray, val mime: String, val encoding: String?, val headers: Map<String, String>)
    private data class Entry(val resource: Resource, val saved: Long, val ttl: Long, val etag: String, val modified: String)
    private val locks = Array(32) { Any() }
    private val checked = ConcurrentHashMap<String, Long>()
    private val diskLock = Any()
    private val generation=java.util.concurrent.atomic.AtomicLong()

    /** Only cache-owned files; a concurrent old download cannot repopulate them. */
    fun clear()=synchronized(diskLock) {
        generation.incrementAndGet()
        directory.listFiles()?.filter {it.isFile && owned.matches(it.name)}?.forEach {
            check(it.canonicalFile.parentFile==directory.canonicalFile) {"Unexpected cache path"}
            check(it.delete()) {"Cannot remove cached resource"}
        }
        checked.clear()
    }

    companion object {
        private const val MAX_FILE = 12L * 1024 * 1024
        private val owned = Regex("[a-f0-9]{64}\\.(json|body|tmp)")
        private val fingerprint = Regex("(?:^|[/.-])([A-Za-z0-9_-]{8}|[a-fA-F0-9]{9,64})\\.(js|mjs|css|woff2?|ttf|png|webp|svg|jpg|jpeg)$")
        private val extensions = setOf("js", "mjs", "css", "woff", "woff2", "ttf", "png", "webp", "svg", "jpg", "jpeg", "ico")
        private val responseHeaderNames = setOf("content-type", "cache-control", "etag", "last-modified", "expires", "date", "age", "vary", "access-control-allow-origin", "access-control-allow-credentials", "access-control-expose-headers", "cross-origin-resource-policy", "cross-origin-embedder-policy", "x-content-type-options", "content-security-policy")
        private fun safeHeaders(headers: Headers) = headers.toMultimap().filterKeys { it.lowercase() in responseHeaderNames }.mapValues { it.value.joinToString(", ") }
        fun isVersionedPluginBundle(url:String):Boolean {
            val target=url.toHttpUrlOrNull()?:return false
            if(target.encodedPath !in setOf("/plugins/","/remote/plugins/"))return false
            val names=target.queryParameterNames
            if(names.size!=2 || "rev" !in names)return false
            val bundle=names.singleOrNull {it!="rev"}?:return false
            if(!bundle.startsWith("?") || target.queryParameterValues("rev").size!=1)return false
            if(target.queryParameterValues(bundle)!=listOf<String?>(null))return false
            if(!target.queryParameter("rev").orEmpty().matches(Regex("[A-Za-z0-9._:-]{1,128}")))return false
            val modules=bundle.removePrefix("?").split(',')
            return modules.size in 1..256 && modules.all {name->
                name.length<=240 && ".." !in name && name.matches(Regex("@?[A-Za-z0-9_-]+(?:/[A-Za-z0-9_.-]+)+\\.(?:m?js|css)"))
            }
        }
        fun eligible(origin: String, url: String, method: String, mainFrame: Boolean, headers: Map<String, String>): Boolean {
            // Check before HttpUrl canonicalises encoded dot segments.
            if ('%' in url.substringBefore('?') || '\\' in url) return false
            val base = origin.toHttpUrlOrNull() ?: return false
            val target = url.toHttpUrlOrNull() ?: return false
            if (method != "GET" || mainFrame || target.username.isNotEmpty() || target.password.isNotEmpty()) return false
            if (base.scheme != target.scheme || base.host != target.host || base.port != target.port) return false
            if (headers.keys.any { it.equals("Range", true) }) return false
            if(isVersionedPluginBundle(url))return true
            if (target.queryParameterNames.any { it !in setOf("v", "version", "hash") }) return false
            val path = target.encodedPath.lowercase()
            if ('%' in path || '\\' in path) return false
            val inner = path.removePrefix("/remote")
            if (listOf("/api/", "/pair", "/sidebar/", "/git/", "/pet/", "/files/", "/attachments/", "/download/").any { inner.startsWith(it) }) return false
            return path.substringAfterLast('.').lowercase() in extensions
        }
        private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        private fun token(text: String) = digest(text.toByteArray())
    }

    /** A navigation time, never a persistent identity or credential. */
    fun load(url: String, headers: Map<String, String>, navigation: Long): Resource? {
        val startedGeneration=generation.get()
        // Include the request representation and auth context, but persist only its digest.
        val identity = headers.entries.sortedBy { it.key.lowercase() }.joinToString("\n") { "${it.key.lowercase()}:${it.value}" }
        val key = token("$url\n$identity")
        synchronized(locks[(key.hashCode() and Int.MAX_VALUE) % locks.size]) {
            val requestNoStore = headers.any { it.key.equals("Cache-Control", true) && it.value.contains("no-store", true) }
            val old = if (requestNoStore) null else read(key)
            val now = clock()
            val path = url.toHttpUrlOrNull()?.encodedPath.orEmpty()
            val stamp = fingerprint.find(path)?.groupValues?.get(1)
            val assetPath = path.contains("/assets/") || path.startsWith("/skin-assets/")
            val immutable = assetPath && stamp != null && (stamp.any(Char::isUpperCase) || stamp.any(Char::isDigit) || stamp.all { it in 'a'..'f' }) ||
                isVersionedPluginBundle(url) && old?.resource?.headers?.any {it.key.equals("Cache-Control",true)&&it.value.contains("immutable",true)}==true
            val forceCheck = headers.any { it.key.equals("Cache-Control", true) && (it.value.contains("no-cache", true) || it.value.contains("no-store", true) || it.value.contains("max-age=0", true)) }
            if (old != null && !forceCheck && now >= old.saved && now - old.saved < old.ttl && (immutable || checked[key] == navigation)) {
                File(directory, "$key.body").setLastModified(now)
                log("hit id=${key.take(8)} bytes=${old.resource.bytes.size}")
                return old.resource
            }
            val request = Request.Builder().url(url)
            headers.forEach { (name, value) ->
                if (name.lowercase() !in setOf("host", "connection", "accept-encoding", "content-length", "if-none-match", "if-modified-since")) request.header(name, value)
            }
            if (old != null) {
                if (old.etag.isNotEmpty()) request.header("If-None-Match", old.etag)
                else if (old.modified.isNotEmpty()) request.header("If-Modified-Since", old.modified)
            }
            return try {
                client.newCall(request.build()).execute().use { response ->
                    val cc = CacheControl.parse(response.headers)
                    if (response.code == 304 && old != null) {
                        if (cc.noStore || response.header("Set-Cookie") != null) { discard(key); return null }
                        // 304 may omit Cache-Control: retain the previous policy,
                        // especially no-cache/max-age=0, rather than inventing a TTL.
                        val merged = Headers.Builder().apply {
                            old.resource.headers.forEach { (name, value) -> add(name, value) }
                            response.headers.forEach { (name, value) -> set(name, value) }
                        }.build()
                        val ttl = freshness(merged, immutable)
                        val refreshed = old.copy(resource = old.resource.copy(headers = safeHeaders(merged)), saved = now, ttl = ttl,
                            etag = response.header("ETag") ?: old.etag,
                            modified = response.header("Last-Modified") ?: old.modified)
                        write(key, refreshed,startedGeneration)
                        checked[key] = navigation
                        log("validated id=${key.take(8)} bytes=${old.resource.bytes.size}")
                        return old.resource
                    }
                    val body = response.body ?: return null
                    val type = body.contentType()
                    val mime = type?.let { "${it.type}/${it.subtype}" }.orEmpty()
                    val allowedMime = mime in setOf("text/javascript", "application/javascript", "application/x-javascript", "text/css", "application/font-woff", "application/vnd.ms-fontobject", "application/octet-stream") || mime.startsWith("image/") || mime.startsWith("font/")
                    if (response.code != 200 || !allowedMime || body.contentLength() > MAX_FILE || response.header("Set-Cookie") != null || response.header("Content-Range") != null || response.header("Vary") == "*") {
                        if (response.code in setOf(401, 403, 404, 410)) discard(key)
                        log("bypass id=${key.take(8)} status=${response.code}")
                        return null
                    }
                    // Bound unknown/chunked bodies as well as Content-Length.
                    val source = body.source()
                    val buffer = okio.Buffer()
                    var reported=0L
                    var reportAt=System.nanoTime()
                    while (buffer.size <= MAX_FILE) {
                        if (source.read(buffer, minOf(64 * 1024L, MAX_FILE + 1 - buffer.size)) == -1L) break
                        if(buffer.size-reported>=512*1024 || System.nanoTime()-reportAt>=5_000_000_000L) {
                            reported=buffer.size;reportAt=System.nanoTime()
                            // Length rides on the same line so the tracker can turn
                            // a running total into real progress instead of "7 MB
                            // and counting" with no idea how far along it is.
                            val declared=body.contentLength()
                            log("receiving id=${key.take(8)} bytes=${buffer.size}"+(if(declared>0) " total=$declared" else ""))
                        }
                    }
                    if (buffer.size > MAX_FILE) return null
                    val bytes = buffer.readByteArray()
                    val resource = Resource(bytes, mime, type?.charset()?.name(), safeHeaders(response.headers))
                    if (!cc.noStore && !requestNoStore) {
                        write(key, Entry(resource, now, freshness(response.headers, immutable), response.header("ETag").orEmpty(), response.header("Last-Modified").orEmpty()),startedGeneration)
                        checked[key] = navigation
                    } else discard(key)
                    log("${if (cc.noStore || requestNoStore) "no-store" else "download"} id=${key.take(8)} bytes=${bytes.size}")
                    resource
                }
            } catch (e: Exception) {
                // Let the browser handle its normal network/error path. Never serve stale code on error.
                log("fallback id=${key.take(8)} reason=${e.javaClass.simpleName}")
                null
            }
        }
    }

    private fun freshness(headers: Headers, immutable: Boolean, previous: Long = 0): Long {
        val cc = CacheControl.parse(headers)
        if (cc.noCache || cc.noStore) return 0
        if (cc.maxAgeSeconds >= 0) {
            val age = headers["Age"]?.toLongOrNull()?.coerceAtLeast(0) ?: 0
            return ((cc.maxAgeSeconds.toLong() - age).coerceAtLeast(0) * 1000).coerceAtMost(30L * 86400 * 1000)
        }
        if (headers["Cache-Control"] == null && previous > 0) return previous
        // Only content-addressed assets get a local default; fixed names must validate.
        return if (immutable) 30L * 86400 * 1000 else 0
    }

    private fun read(key: String): Entry? = synchronized(diskLock) {
        try {
            val meta = File(directory, "$key.json")
            val file = File(directory, "$key.body")
            if (!meta.isFile || !file.isFile || file.length() > MAX_FILE || meta.length() > 65536) return null
            val json = JSONObject(meta.readText())
            val bytes = file.readBytes()
            if (digest(bytes) != json.getString("digest")) { discard(key); return null }
            val h = json.getJSONObject("headers")
            Entry(Resource(bytes, json.getString("mime"), json.optString("encoding").ifEmpty { null }, h.keys().asSequence().associateWith { h.getString(it) }),
                json.getLong("saved"), json.getLong("ttl"), json.optString("etag"), json.optString("modified"))
        } catch (_: Exception) { discard(key); null }
    }

    private fun write(key: String, entry: Entry,expectedGeneration:Long) = synchronized(diskLock) {
        if(generation.get()!=expectedGeneration)return@synchronized
        runCatching {
            directory.mkdirs()
            val json = JSONObject().put("digest", digest(entry.resource.bytes)).put("mime", entry.resource.mime)
                .put("encoding", entry.resource.encoding.orEmpty()).put("headers", JSONObject(entry.resource.headers))
                .put("saved", entry.saved).put("ttl", entry.ttl).put("etag", entry.etag).put("modified", entry.modified)
            val temp = File(directory, "$key.tmp")
            temp.writeBytes(entry.resource.bytes)
            java.nio.file.Files.move(temp.toPath(), File(directory, "$key.body").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            temp.writeText(json.toString())
            java.nio.file.Files.move(temp.toPath(), File(directory, "$key.json").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            var total = directory.listFiles()?.filter { owned.matches(it.name) }?.sumOf { it.length() } ?: 0
            directory.listFiles()?.filter { it.name.matches(Regex("[a-f0-9]{64}\\.body")) }?.sortedBy { it.lastModified() }?.forEach {
                if (total > limitBytes) {
                    val metadata = File(directory, it.nameWithoutExtension + ".json")
                    total -= it.length() + metadata.length()
                    it.delete(); metadata.delete(); checked.remove(it.nameWithoutExtension)
                }
            }
            if (checked.size > 2048) checked.clear()
        }.onFailure { log("disk unavailable reason=${it.javaClass.simpleName}") }
        Unit
    }

    private fun discard(key: String) = synchronized(diskLock) {
        File(directory, "$key.json").delete()
        File(directory, "$key.body").delete()
        checked.remove(key)
        Unit
    }
}
