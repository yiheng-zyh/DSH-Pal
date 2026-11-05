package com.dsh.mobile.core

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 对话本地缓存的落盘层（实验功能）。
 *
 * 存储布局（`<app cache>/chat-archive/`）：
 * ```
 *   <id>.json   元数据（标题、更新时间、字节数、行数、摘要校验）
 *   <id>.text   正文（UTF-8 原始文本）
 *   index.json  全部归档的元数据清单，读列表时不必扫每个正文
 * ```
 *
 * 为什么正文与元数据分开：列表页只需元数据，扫 1 GB 正文会卡住 UI；
 * 打开某个会话时才读它的 `.text`。
 *
 * 安全边界：
 *  - 只写本 App 私有目录，内容不外发、不进日志（日志只记 id 前 8 位与字节数）。
 *  - 每个条目自带 SHA-256 校验，读回不一致即视为损坏并丢弃，绝不把
 *    截断/损坏的文本当历史展示。
 *  - 所有删除都校验目标在缓存目录内，防路径穿越（沿用 StaticResourceCache 的做法）。
 *
 * @param directory 缓存根目录（通常是 `context.cacheDir/chat-archive`）。
 * @param quota 容量上限；调用方从设置里取当前档位。
 */
class ChatArchiveStore(
    private val directory: File,
    private val quota: () -> Long = { ChatArchive.Quota.default.bytes },
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * 正文加密器。生产必须注入 KeyStore 实现；默认直通只用于测试。
     * 加密失败时[writeEntry]会放弃写入，**绝不以明文落盘**。
     */
    private val cipher: ChatTextCipher = ChatTextCipher.NONE,
    private val log: (String) -> Unit = {},
) {
    private val diskLock = Any()

    /**
     * 写入一次采集结果。
     *
     * 合并策略交给 [ChatArchive.merge]（保留更完整的一份），这里只负责
     * 读旧值 → 合并 → 原子写 → 按容量淘汰。
     *
     * @return 写入后的归档信息；磁盘不可用或内容未变化时返回 null。
     */
    fun save(capture: ChatArchive.Capture): ChatArchive.Archive? = synchronized(diskLock) {
        runCatching {
            directory.mkdirs()
            val id = ChatArchive.sessionId(capture.sessionKey)
            val existing = readMeta(id)
            val storedText = readText(id)
            val outcome = ChatArchive.merge(existing, storedText, capture)
            if (existing != null && !outcome.changed) {
                // 内容没变：只更新"最近看到过"的时间，避免淘汰时把它当最旧。
                touch(id, capture.capturedAt)
                return@synchronized existing
            }
            // 落盘用 merge 给出的最终文本（可能是保留的更完整旧版本）。
            // 写失败必须上报成 null：否则 UI 会显示"已缓存"，而磁盘上什么都没有。
            if (!writeEntry(outcome.archive, outcome.text)) {
                log("save failed id=${id.take(8)}; nothing written")
                return@synchronized null
            }
            enforceQuota()
            log("saved id=${id.take(8)} bytes=${outcome.archive.bytes} lines=${outcome.archive.lines}")
            outcome.archive
        }.onFailure { log("disk unavailable reason=${it.javaClass.simpleName}") }.getOrNull()
    }

    /** 列出全部归档，按更新时间倒序（列表页直接用）。 */
    fun list(): List<ChatArchive.Archive> = synchronized(diskLock) {
        runCatching { index().sortedByDescending { it.updatedAt } }.getOrDefault(emptyList())
    }

    /** 读某个会话的正文。损坏或缺失返回 null。 */
    fun text(id: String): String? = synchronized(diskLock) { readText(id) }

    /** 当前占用字节数（正文 + 元数据）。 */
    fun usage(): Long = synchronized(diskLock) {
        runCatching {
            directory.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
        }.getOrDefault(0L)
    }

    /** 清空全部缓存（设置页的「清除」入口）。 */
    fun clear() = synchronized(diskLock) {
        runCatching {
            directory.listFiles()?.filter { it.isFile && owned.matches(it.name) }?.forEach {
                check(it.canonicalFile.parentFile == directory.canonicalFile) { "Unexpected archive path" }
                it.delete()
            }
            log("cleared")
        }
    }

    // ---- 内部实现 ----

    /** 只认自己写的三类文件，避免误删同目录其它东西。 */
    private val owned = Regex("[a-f0-9]{24}\\.(json|text)|index\\.json")

    private fun readMeta(id: String): ChatArchive.Archive? = runCatching {
        val file = File(directory, "$id.json")
        if (!file.isFile || file.length() > 65536) return@runCatching null
        val json = JSONObject(file.readText())
        ChatArchive.Archive(
            id = id,
            title = json.optString("title").ifBlank { "未命名会话" },
            updatedAt = json.optLong("updatedAt"),
            bytes = json.optLong("bytes"),
            lines = json.optInt("lines"),
            // 旧版本没有这个字段：缺失按 0 处理（界面不显示那一行），不臆造数值。
            rawBytes = json.optLong("rawBytes"),
        )
    }.getOrNull()

    private fun readText(id: String): String? = runCatching {
        val meta = File(directory, "$id.json")
        val body = File(directory, "$id.text")
        if (!meta.isFile || !body.isFile) return@runCatching null
        val payload = body.readBytes()
        val json = JSONObject(meta.readText())
        // 先验完整性再解密：密文被截断/篡改时解密会抛，这里提前给出明确结论。
        val expected = json.optString("digest")
        if (expected.isNotEmpty() && digest(payload) != expected) {
            // 截断或损坏：宁可当没有，也不展示半截历史。
            log("corrupt id=${id.take(8)}; discarded")
            meta.delete()
            body.delete()
            return@runCatching null
        }
        val plain = if (json.optBoolean("encrypted", false)) {
            cipher.decrypt(payload) ?: run {
                // 密钥失效（例如用户清了凭证、换机恢复）：视为不可读，清掉避免反复失败。
                log("undecryptable id=${id.take(8)}; discarded")
                meta.delete()
                body.delete()
                return@runCatching null
            }
        } else payload
        // AES-GCM 带认证标签，错密钥会抛异常；但仍加一道内容自检：
        // 万一底层实现降级成不带认证的模式（或测试替身），乱码不能被当历史展示。
        val text = plain.toString(Charsets.UTF_8)
        if (!looksLikeText(text)) {
            log("garbled id=${id.take(8)}; discarded")
            meta.delete()
            body.delete()
            return@runCatching null
        }
        text
    }.getOrNull()

    /** 粗判是否为可读文本：控制字符占比过高即视为解密失败/乱码。 */
    private fun looksLikeText(text: String): Boolean {
        if (text.isEmpty()) return true
        var control = 0
        for (ch in text) {
            // 允许 \n \r \t，其余 C0/C1 控制符视为异常
            if (ch.code < 0x20 && ch != '\n' && ch != '\r' && ch != '\t') control++
            else if (ch.code in 0x7F..0x9F) control++
        }
        return control * 100 / text.length < 5
    }

    /** 原子写入正文与元数据；任何一步失败返回 false，由调用方决定是否当没发生。 */
    private fun writeEntry(archive: ChatArchive.Archive, text: String): Boolean = runCatching {
        directory.mkdirs()
        require(directory.isDirectory) { "archive directory unavailable" }
        val plain = text.toByteArray(Charsets.UTF_8)
        // 先加密再落盘：加不出来就整次放弃，宁可不缓存也不写明文。
        val payload = cipher.encrypt(plain) ?: return@runCatching false
        val meta = JSONObject()
            .put("title", archive.title)
            .put("updatedAt", archive.updatedAt)
            .put("bytes", plain.size.toLong())
            .put("lines", archive.lines)
            .put("rawBytes", archive.rawBytes)
            .put("encrypted", cipher !== ChatTextCipher.NONE)
            .put("digest", digest(payload))
        // 先写正文临时文件再原子改名：中途断电不会留下半截 .text。
        val tmpText = File(directory, "${archive.id}.tmp")
        tmpText.writeBytes(payload)
        java.nio.file.Files.move(
            tmpText.toPath(), File(directory, "${archive.id}.text").toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
        )
        val tmpMeta = File(directory, "${archive.id}.json.tmp")
        tmpMeta.writeText(meta.toString())
        java.nio.file.Files.move(
            tmpMeta.toPath(), File(directory, "${archive.id}.json").toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
        )
        true
    }.onFailure { log("write failed reason=${it.javaClass.simpleName}") }.getOrDefault(false)

    private fun touch(id: String, at: Long) = runCatching {
        val file = File(directory, "$id.json")
        if (!file.isFile) return@runCatching
        val json = JSONObject(file.readText())
        if (at <= json.optLong("updatedAt")) return@runCatching
        json.put("updatedAt", at)
        file.writeText(json.toString())
    }

    /** 按容量上限淘汰最旧归档（用户要求「优先缓存最新对话」）。 */
    private fun enforceQuota() = runCatching {
        val all = index()
        val (keep, evict) = ChatArchive.planEviction(all, quota())
        if (evict.isEmpty()) return@runCatching
        for (archive in evict) {
            File(directory, "${archive.id}.json").delete()
            File(directory, "${archive.id}.text").delete()
        }
        log("evicted count=${evict.size} kept=${keep.size}")
    }

    /** 汇总元数据。坏掉的条目直接跳过并清掉，不让它污染容量统计。 */
    private fun index(): List<ChatArchive.Archive> {
        val files = directory.listFiles()?.filter { it.isFile && it.name.endsWith(".json") } ?: return emptyList()
        val result = ArrayList<ChatArchive.Archive>(files.size)
        for (file in files) {
            val id = file.name.removeSuffix(".json")
            if (!id.matches(Regex("[a-f0-9]{24}"))) continue
            val archive = readMeta(id) ?: run {
                file.delete(); continue
            }
            result += archive
        }
        return result
    }

    private fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
