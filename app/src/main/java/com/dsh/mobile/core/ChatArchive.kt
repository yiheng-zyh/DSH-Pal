package com.dsh.mobile.core

/**
 * 对话本地缓存的纯逻辑层（实验功能，默认关闭）。
 *
 * 定位：这不是「离线可用的完整 DSH」，而是**断网时能翻看历史对话正文**的快照库。
 * 联网后由采集侧继续追加新内容；离线时只读展示，不同步、不发送、不重试。
 *
 * 为什么需要单独的模型而不是复用 TaskState：TaskState 是**任务进度投影**，
 * 刻意不含对话正文（见 core/EventReducer.kt 与 TaskState 的隐私注释）。本功能
 * 是用户显式开启的实验能力，正文单独存放、单独销毁，不改变任务通知那套边界。
 *
 * 设计约束（都对应本项目的既有踩坑）：
 *  - 纯逻辑、不依赖 Android：可被 testDebugUnitTest 直接覆盖（DECISIONS 2026-10-27
 *    「core/ 下被单测覆盖的纯逻辑类不许直接依赖 Android 类」）。
 *  - 容量与淘汰全部在这个文件里算完，落盘层只执行结果，便于单测边界值。
 *  - 时间与字节统计均由调用方注入，不在模型里读系统时钟。
 */
object ChatArchive {
    /**
     * 存储上限档位。用户可选项，默认 1 GB。
     *
     * 为什么给上限选项：不同手机剩余空间差异大，写死 1 GB 会让小存储设备用户
     * 无法接受、写死 100 MB 又存不下几个长会话。档位让用户自己权衡。
     */
    enum class Quota(val bytes: Long, val title: String, val description: String) {
        SMALL(100L * 1024 * 1024, "100 MB", "只留最近少量会话"),
        MEDIUM(500L * 1024 * 1024, "500 MB", "日常够用"),
        DEFAULT(1024L * 1024 * 1024, "1 GB（默认）", "可放较多长会话"),
        LARGE(3072L * 1024 * 1024, "3 GB", "长期留存，注意手机剩余空间"),
        ;

        companion object {
            val default = DEFAULT
            fun ofName(name: String?): Quota = entries.firstOrNull { it.name == name } ?: default
            /** 用户可见的档位列表，顺序即 UI 顺序。 */
            fun options(): List<Quota> = listOf(SMALL, MEDIUM, DEFAULT, LARGE)
        }
    }

    /**
     * 一个会话的本地快照。
     *
     * [id] 是网页给出的会话标识的摘要，不存原文，避免把浏览路径与工作区名落盘。
     * 同一会话多次采集会合并进同一个 archive。
     */
    data class Archive(
        /** 稳定标识：sessionKey 的摘要，作文件名。 */
        val id: String,
        /** 用户可读标题（会话名；取不到时用时间兜底）。 */
        val title: String,
        /** 最近一次采集到的时间（epoch millis），用于「更新时间」与淘汰排序。 */
        val updatedAt: Long,
        /** **纯对话正文**的字节数（UTF-8），即真正落盘的对话量，用于容量统计。 */
        val bytes: Long,
        /** 消息条数，用于列表展示。 */
        val lines: Int,
        /**
         * 网页侧**原始记录**的体积（字节），仅用于向用户解释「为什么 9 MB 的对话只占几百 KB」。
         *
         * 为什么单独记这个：用户实测反馈「缓存下来的都没超过 1mb，对话不是有 9mb 吗」。
         * 两者都对 —— 原始记录里 98% 是工具调用的参数与返回（写文件时整个文件内容都在
         * arguments 里），按既有隐私边界（不落命令正文与输出）这些**不落盘**。
         * 把两个数字分开显示，用户才看得懂差异；只显示小数字会让人以为"缓存没生效"。
         * 取不到时为 0（不外推、不估造）。
         */
        val rawBytes: Long = 0L,
    )

    /**
     * 一次采集到的正文片段。采集侧只需给「本次拿到的全文」，
     * 去重与增量由 [merge] 处理。
     */
    data class Capture(
        val sessionKey: String,
        val title: String,
        /** 完整可见正文（含工具输出的文本形式）。 */
        val text: String,
        val capturedAt: Long,
        /** 网页侧原始记录体积（字节）；0 表示这次拿不到（例如 DOM 兜底路径）。 */
        val rawBytes: Long = 0L,
    )

    /** 一个会话的合并结果：保留最新全文，同时记录历史长度与时间。 */
    data class MergeOutcome(
        val archive: Archive,
        /** 最终要落盘的正文（可能是保留的更完整旧文本）。 */
        val text: String,
        /** 是否真的有新内容（用于避免无意义落盘）。 */
        val changed: Boolean,
    )

    /**
     * UTF-8 字节数的稳定估算：不依赖平台默认编码。
     *
     * 代理对必须**成对**计 4 字节：emoji 在 Kotlin 里是 2 个 char，
     * 逐 char 各算 1 会得到 2，比真实 UTF-8 少一半，容量统计会系统性偏低。
     */
    fun utf8Bytes(text: String): Long {
        var total = 0L
        var i = 0
        while (i < text.length) {
            val code = text[i].code
            when {
                code < 0x80 -> total += 1
                code < 0x800 -> total += 2
                // 高代理 + 低代理 = 一个码点，UTF-8 占 4 字节
                code in 0xD800..0xDBFF && i + 1 < text.length && text[i + 1].code in 0xDC00..0xDFFF -> {
                    total += 4
                    i++
                }
                // 落单的代理（非法文本）：按替换字符 3 字节算，不低估
                code in 0xD800..0xDFFF -> total += 3
                else -> total += 3
            }
            i++
        }
        return total
    }

    /** 会话标识摘要：稳定、不可逆、固定长度，直接可用作文件名。 */
    fun sessionId(sessionKey: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(sessionKey.toByteArray(Charsets.UTF_8))
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }

    /** 正文裁剪：只保留末尾 [maxChars]，避免单会话无限增长。 */
    fun trim(text: String, maxChars: Int = 400_000): String =
        if (text.length <= maxChars) text else text.substring(text.length - maxChars)

    /** 消息条数：以换行分段的非空行计。工具输出常带大量空行，不能按空行算。 */
    fun countLines(text: String): Int = text.lineSequence().count { it.isNotBlank() }

    /**
     * 这段落盘正文里是否**带角色结构**（可以直接按气泡展示）。
     *
     * 为什么不能用 `<!--ts-->` 判定（早先的 bug）：
     * [ChatRole.Transcript.encode] 只在消息**有时间戳**时才写 `<!--millis-->`。
     * 而 DOM 兜底、`session/page` 里 seq 为 0 的消息、以及旧版本数据都只有
     * `[User] 正文` 这样**没有时间戳**的行。用 `<!--ts-->` 判定会把这类
     * 完全正常的带角色记录误判成"无角色"，于是 merge 一直保留旧的 DOM 整段文本，
     * 界面就满屏「历史记录（无角色）」—— 用户实测反馈的正是这一条。
     *
     * 正确判定：正文里有 `[User]` / `[Assistant]` / `[Tool]` 这类角色前缀即可。
     */
    fun looksStructured(text: String?): Boolean =
        text != null && ROLE_PREFIX.containsMatchIn(text)

    /** 落盘格式的角色前缀（行首 `[Role]`，允许前面有空行）。 */
    private val ROLE_PREFIX = Regex("(?m)^\\[(User|Assistant|Tool|Unknown)]")

    /**
     * 合并一次采集。
     *
     * 策略是「**保留最新全文**」而不是 append：网页已经给了完整可见正文，
     * 自己再拼增量会出现重复段与错位（滚动加载、React 重排都会导致文本顺序变化）。
     * 若新抓到的正文反而变短（例如只加载了部分历史），保留更长的旧文本 ——
     * 宁可显示旧一点的完整版，也不要用半截内容盖掉已缓存的内容。
     *
     * @param existing 已有归档（首次采集为 null）。
     * @param capture 本次采集结果。
     */
    fun merge(existing: Archive?, storedText: String?, capture: Capture): MergeOutcome {
        val incoming = trim(capture.text)
        // 关键修正：**有角色的完整记录必须替换掉无角色的 DOM 快照**。
        //
        // 早先只按「长度更长」决定保留旧内容，结果 DOM 抓到的整段文本（无角色）
        // 永远压过后来通过 session/page 拉到的完整记录，界面上一直显示
        // 「历史记录（无角色）」—— 而对话本来只有「我」和「助手」两个角色。
        val storedHasRoles = looksStructured(storedText)
        val incomingHasRoles = looksStructured(incoming)
        val keepOld = when {
            incoming.isBlank() -> true
            // 新数据带角色、旧数据不带 → 一定换新
            incomingHasRoles && !storedHasRoles -> false
            // 旧带角色、新不带（DOM 兜底）→ 保留旧的，别把结构降级
            !incomingHasRoles && storedHasRoles -> true
            // 同层级才比长度（"更长且是后缀延伸"视为同一条在增长）
            else -> storedText != null && storedText.length > incoming.length &&
                storedText.endsWith(incoming.takeLast(minOf(incoming.length, 2000)))
        }
        val text = when {
            keepOld && storedText != null -> storedText
            else -> incoming
        }
        val id = sessionId(capture.sessionKey)
        val rawBytes = maxOf(capture.rawBytes, existing?.rawBytes ?: 0L)
        // changed 也把 rawBytes 算进来：正文没变但这次量到了原始体积（例如先 DOM 快照
        // 后 session/page 拉到完整记录），也必须落盘 —— 否则「原始记录 9 MB」永远写不进去，
        // 用户看到的还是"只有几百 KB"，等于没修。
        val changed = existing == null || text != storedText || rawBytes != existing.rawBytes
        val archive = Archive(
            id = id,
            title = capture.title.ifBlank { existing?.title.orEmpty() }.ifBlank { "未命名会话" }.take(80),
            updatedAt = capture.capturedAt,
            bytes = utf8Bytes(text),
            lines = countLines(text),
            // 原始体积只增不减：这次取不到（DOM 兜底=0）时保留上次量到的值，
            // 否则界面上的"原始记录 9 MB"会莫名其妙退回 0，反而更让人困惑。
            rawBytes = rawBytes,
        )
        return MergeOutcome(archive, text, changed)
    }

    /**
     * 按容量上限挑选要保留的归档，返回 `(keep, evict)`。
     *
     * 用户要求「优先缓存最新对话」，所以淘汰顺序是**最久未更新在前**。
     * 单个归档即使超限也保留（否则会陷入「刚存就删」的抖动），
     * 由采集侧的 [trim] 保证单会话不会真的无限大。
     *
     * @param all 全部归档。
     * @param limitBytes 容量上限。
     */
    fun planEviction(all: List<Archive>, limitBytes: Long): Pair<List<Archive>, List<Archive>> {
        if (all.isEmpty()) return emptyList<Archive>() to emptyList()
        val ordered = all.sortedByDescending { it.updatedAt }
        val keep = ArrayList<Archive>(ordered.size)
        val evict = ArrayList<Archive>()
        var total = 0L
        for (archive in ordered) {
            val next = total + archive.bytes
            // 至少保留一个：单个归档超限时也留，避免空库。
            if (keep.isNotEmpty() && next > limitBytes) evict += archive
            else { keep += archive; total = next }
        }
        return keep to evict
    }

    /** 当前占用的人类可读文本。 */
    fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
        bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    /** 采集间隔：太频繁会浪费电，太稀疏会丢内容。页面稳定后每 45 秒一次足够。 */
    const val CAPTURE_INTERVAL_MS = 45_000L

    /**
     * 会话选择策略（用户要求"能选择缓存哪些对话"）。
     *
     * AUTO      —— 缓存全部会话（默认；开箱即用，不用逐条勾选）
     * SELECTED  —— 只缓存用户勾选的会话（敏感项目、或者空间有限时用）
     *
     * 为什么默认 AUTO：用户开这个功能就是为了"断网能回看"，
     * 默认全缓存最省心；需要精细控制的人再切到 SELECTED。
     */
    enum class Selection {
        AUTO,
        SELECTED,
        ;

        companion object {
            val default = AUTO
            fun ofName(name: String?): Selection = entries.firstOrNull { it.name == name } ?: default
        }
    }

    /**
     * 该不该缓存这个会话。
     *
     * @param selection 当前策略。
     * @param sessionKey 事件的会话标识。
     * @param selected 用户勾选过的会话标识集合（仅 SELECTED 时用）。
     */
    fun shouldCapture(selection: Selection, sessionKey: String, selected: Set<String>): Boolean =
        selection == Selection.AUTO || sessionKey in selected

    /**
     * 把网页上的会话与本地归档对照，算出「哪些需要更新」。
     *
     * 为什么要这一步：缓存不是一次性的，对话会继续增长。但 App **只能在某个会话
     * 被打开时看到它的内容**（事件流不重放历史），所以"更新"不是后台直接拉取，
     * 而是：先算出哪些会话还没缓存/可能变旧，标成"待更新"，等用户打开时补进去，
     * 并在界面上如实告诉用户"还有 N 个待更新"。**不谎称已自动更新。**
     *
     * @param remote 网页侧栏枚举到的会话（key + 显示时间文本）。
     * @param cachedKeys 本地已缓存过的会话标识。
     */
    data class SyncPlan(
        /** 网页上有、但本地从没缓存过的。 */
        val neverCached: List<String> = emptyList(),
        /** 本地有缓存、但这次网页上又出现的（可能已增长，打开时补）。 */
        val mayNeedRefresh: List<String> = emptyList(),
    ) {
        val needsWork: Int get() = neverCached.size + mayNeedRefresh.size
    }

    fun planSync(remoteKeys: List<String>, cachedKeys: Set<String>): SyncPlan {
        if (remoteKeys.isEmpty()) return SyncPlan()
        val never = remoteKeys.filter { it !in cachedKeys }
        val again = remoteKeys.filter { it in cachedKeys }
        return SyncPlan(neverCached = never, mayNeedRefresh = again)
    }

    // ---- 界面噪声清理（采集与展示共用同一套规则，避免两边不一致）----

    /** 单独成行且只表示界面状态的词。 */
    private val chromeExact = setOf(
        "对话", "轨迹", "继续", "加载更早", "已停止", "思考", "复制", "分享", "编辑", "删除",
        "新建任务", "发送", "停止", "写入", "运行命令", "项目", "子智能体",
    )

    /** 界面元信息行：模型名、进度百分比、时间戳、计数摘要等。 */
    private val chromePatterns = listOf(
        Regex("^\\d+\\s*个子智能体.*$"),
        Regex("^\\d+\\s*轮\\s*\\d+\\s*步.*$"),
        Regex("^\\d+(\\.\\d+)?\\s*[KMG]?\\s*tok.*$"),
        Regex("^缓存命中.*$"),
        Regex("^\\d{1,2}月\\d{1,2}日\\s+\\d{1,2}:\\d{2}$"),
        Regex("^(今天|昨天|前天)?\\s*\\d{1,2}:\\d{2}$"),
        Regex("^\\d{1,3}%$"),
        Regex("^(DeepSeek|Qoder|Claude|GPT)[\\w\\s.\\-]*$"),
        Regex("^(High|Medium|Low)$"),
        Regex("^\\d+(\\.\\d+)?\\s*(KB|MB|GB)$"),
    )

    /** 输入区占位符：出现即说明已到输入框，不属于对话正文。 */
    private val chromeInline = listOf("发消息或创建任务", "调用命令", "文件或对话")

    /** 判断是否只是界面噪声页（空会话/加载中）。 */
    fun looksLikeConversation(text: String): Boolean {
        if (text.length < 40) return false
        val noise = chromeInline.count { text.contains(it) }
        return !(noise >= 3 && text.length < 400)
    }

    /** 逐行剔除界面元素，保留对话正文；连续空行压成一个空行。 */
    fun cleanChrome(raw: String): String {
        if (raw.isBlank()) return ""
        val kept = ArrayList<String>()
        for (line in raw.lineSequence()) {
            val t = line.trim()
            if (t.isEmpty()) { kept += ""; continue }
            if (t in chromeExact) continue
            if (chromePatterns.any { it.matches(t) }) continue
            if (chromeInline.any { t.contains(it) }) continue
            kept += line
        }
        return kept.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
    }
}
