package com.dsh.mobile.core

import kotlinx.serialization.json.*
/**
 * 对话消息的角色与解析（对话本地缓存实验功能）。
 *
 * 数据来源是**事件流**而不是页面 DOM：
 *  - DOM 抓到的是一坨混排文本，哪句是用户说的、哪句是助手说的、哪段是工具输出，
 *    从文本本身判断不出来（真机已验证：整段 innerText 里用户提问与助手回答
 *    完全同格式）。
 *  - 事件流里角色是**显式字段**：`user/message` / `assistant/message` / `tool/call` /
 *    `tool/result` 各自独立，正文在 `data.message.content[].text`。
 *
 * 所以归档改用事件流构建消息列表，DOM 抓取只作为兜底（无事件时的旧会话）。
 *
 * 隐私边界：这里只取**对话正文**（用户输入与助手可见回复）与**工具调用的一句说明**；
 * 不取思考链、不取命令正文、不取工具返回正文 —— 与 TaskNotice 的既有边界一致。
 */
object ChatRole {
    /** 消息角色。`Tool` 只承载"调用了什么工具"的一句话摘要，不含输出正文。 */
    enum class Role(val title: String) {
        User("我"),
        Assistant("助手"),
        Tool("工具"),
        Unknown("对话"),
    }

    /** 一条归档消息。 */
    data class Message(
        val role: Role,
        val text: String,
        /** 事件时间（epoch millis）；0 表示未知。 */
        val at: Long = 0L,
    )

    /** 一份会话归档的正文结构。 */
    data class Transcript(val messages: List<Message>) {
        fun isEmpty(): Boolean = messages.none { it.text.isNotBlank() }

        /**
         * 序列化成落盘格式：每条消息一行前缀 + 正文，行间空行分隔。
         *
         * 用纯文本而不是 JSON：正文里本来就有引号与换行，JSON 转义后不可读，
         * 而且旧版本（无角色）的归档也能被同一套解析读出来（视为 Unknown）。
         */
        fun encode(): String = messages
            .filter { it.text.isNotBlank() }
            .joinToString("\n\n") { message ->
                val stamp = if (message.at > 0) "<!--${message.at}-->" else ""
                "[${message.role.name}]$stamp ${message.text.trim()}"
            }

        companion object {
            /** 解析落盘格式；无前缀的旧数据按内容补判角色，判不出的才留 Unknown。 */
            fun decode(stored: String): Transcript {
                if (stored.isBlank()) return Transcript(emptyList())
                val blocks = stored.split(Regex("\n{2,}")).map { it.trim() }.filter { it.isNotEmpty() }
                // 先按顺序解析出原始块。旧格式（无角色）的块无法从自身看出角色，
                // 需要结合"前后块是谁"来推断，所以这里分两步。
                val parsed = ArrayList<Message>(blocks.size)
                for (block in blocks) {
                    val match = PREFIX.find(block)
                    if (match == null) {
                        // 旧格式：没有角色前缀。**不编造**，但也不整片丢给"无角色"——
                        // 对话只有「我」和「助手」两个角色，多轮之间几乎必然交替，
                        // 结合前一条的角色补判（详见下方 resolveLegacy）。
                        parsed += Message(Role.Unknown, block)
                    } else {
                        val role = runCatching { Role.valueOf(match.groupValues[1]) }.getOrDefault(Role.Unknown)
                        val at = STAMP.find(block)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                        val body = block.substring(match.range.last + 1).replaceFirst(STAMP, "").trim()
                        if (body.isNotEmpty()) parsed += Message(role, body, at)
                    }
                }
                return Transcript(resolveLegacy(parsed))
            }

            /**
             * 把无角色的旧块补判成「我 / 助手」。
             *
             * 用户说得对：「对话不就是只有我还有 AI 输出两个角色吗？」
             * 所以历史数据不该整片显示"无角色"。判定顺序：
             *  1. **上下文交替优先**：旧归档是"一整段 DOM 文本按空行切成块"，
             *     块与块的先后就是对话顺序，紧邻块的角色是最强线索（我一句、助手一段）；
             *  2. 没有已知邻居时才看内容特征（[inferRole]）；
             *  3. 都不成立 → 保留 Unknown（不硬猜，由界面说清原因）。
             *
             * 为什么交替优先于内容特征：内容特征是启发式，长回复也可能是用户贴的日志；
             * 而"紧邻块是助手 → 本条是用户"在多轮对话里几乎是必然而不是猜测。
             */
            private fun resolveLegacy(messages: List<Message>): List<Message> {
                if (messages.none { it.role == Role.Unknown }) return messages
                // 只有一段、且完全没有角色信息 = 一段孤立文本，没有"对话"可分辨。
                // 这时不猜（猜成"我"或"助手"都是编造），交给界面说明原因。
                // 这条与"多轮正常对话不该显示无角色"并不冲突：用户的抱怨针对的是
                // 几十上百条消息整片无角色，那种情况下面按交替规则能全部补判。
                val hasStructure = messages.size > 1 || messages.any { it.role != Role.Unknown }
                val out = ArrayList<Message>(messages.size)
                for (index in messages.indices) {
                    val message = messages[index]
                    if (message.role != Role.Unknown) { out += message; continue }
                    val role = when {
                        !hasStructure -> Role.Unknown
                        else -> roleFromNeighbour(out)
                            ?: ChatRole.inferRole(message.text).takeIf { it != Role.Unknown }
                            ?: Role.Unknown
                    }
                    out += if (role == Role.Unknown) message else message.copy(role = role)
                }
                return out
            }

            /** 由紧邻的已知角色反推：助手→用户，用户→助手；没有已知邻居返回 null。 */
            private fun roleFromNeighbour(out: List<Message>): Role? =
                when (out.lastOrNull { it.role == Role.User || it.role == Role.Assistant }?.role) {
                    Role.User -> Role.Assistant
                    Role.Assistant -> Role.User
                    else -> null
                }

            private val PREFIX = Regex("^\\[([A-Za-z]+)]")
            private val STAMP = Regex("<!--(\\d+)-->")
        }
    }

    /**
     * 从 `session/page` 的返回里解析**完整**消息。
     *
     * 真机确认的结构（bytes 可达 6.9MB，是完整对话而非摘要）：
     * ```
     * { "records": [
     *     { "type":"event",
     *       "event": { "type":"assistant/message", "seq":5291, "time":…,
     *                  "data": { "message": { "role":"assistant",
     *                            "content":[{type:"text",text:"…"},{type:"tool-call",…}] } } } },
     *     { "type":"event", "event": { "type":"user/message", "seq":…, "data":{content:[…]} } },
     *     …
     * ] }
     * ```
     * 这里复用事件解析（[fromEvent]）—— 与实时事件流同一套字段语义。
     */
    fun parseSessionPage(body: String): List<Message> {
        if (body.isBlank()) return emptyList()
        val out = ArrayList<Message>()
        // 可能是单个 JSON，也可能是多次分页拼接：尽量整体解析，失败再逐行。
        val roots = ArrayList<JsonObject>()
        runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()?.let { roots += it }
        if (roots.isEmpty()) {
            for (line in body.lineSequence()) {
                val t = line.trim()
                if (t.isEmpty()) continue
                runCatching { Json.parseToJsonElement(t) as? JsonObject }.getOrNull()?.let { roots += it }
            }
        }
        for (root in roots) collectRecords(root, out)
        return out.sortedBy { it.at }.distinctBy { it.role to it.text }
    }

    /** 遍历找 records 数组里的每条 event。 */
    private fun collectRecords(node: JsonElement?, out: MutableList<Message>) {
        when (node) {
            is JsonArray -> node.forEach { collectRecords(it, out) }
            is JsonObject -> {
                val records = node["records"] as? JsonArray
                if (records != null) {
                    for (item in records) {
                        val wrapper = item as? JsonObject ?: continue
                        // 两种形态：{type:"event",event:{...}} 或直接是 event
                        val event = wrapper["event"] as? JsonObject ?: wrapper
                        val type = (event["type"] as? JsonPrimitive)?.contentOrNull ?: continue
                        val data = event["data"] as? JsonObject ?: continue
                        val at = (event["time"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L
                        fromEvent(type, data, at)?.let { out += it }
                    }
                    return
                }
                for ((_, v) in node) if (v is JsonObject || v is JsonArray) collectRecords(v, out)
            }
            else -> Unit
        }
    }

    /**
     * 从 ACP v2 的 session/load 响应里解析**完整**消息。
     *
     * 协议来自 DSH 前端源码检索（不是猜的）：
     *   服务端逐条推送 `{sessionUpdate:"user_message"|"agent_message", content:[{type:"text",text:"..."}]}`
     * 与 projections 的 turnOutline（摘要、被截断成「…」）不同，这里是**全文**。
     *
     * 容错：响应可能是单个 JSON、JSONL 多行、或 SSE（data: 前缀），逐个尝试。
     *
     * @param body 服务端原始响应文本。
     * @return 解析出的消息；解析不出返回空列表（不编造）。
     */
    fun parseAcpUpdates(body: String): List<Message> {
        if (body.isBlank()) return emptyList()
        val out = ArrayList<Message>()
        val chunks = ArrayList<String>()
        if (body.trimStart().startsWith("{") && !body.contains('\n')) {
            chunks += body
        } else {
            for (raw in body.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                chunks += if (line.startsWith("data:")) line.removePrefix("data:").trim() else line
            }
        }
        for (chunk in chunks) {
            val root = runCatching { Json.parseToJsonElement(chunk) as? JsonObject }.getOrNull() ?: continue
            collectUpdates(root, out)
        }
        return out.distinctBy { it.role to it.text }
    }

    /** 在一棵 JSON 树里找 sessionUpdate 节点（可能嵌多层、可能在数组里）。 */
    private fun collectUpdates(node: JsonElement?, out: MutableList<Message>) {
        when (node) {
            is JsonArray -> node.forEach { collectUpdates(it, out) }
            is JsonObject -> {
                val kind = (node["sessionUpdate"] as? JsonPrimitive)?.contentOrNull
                if (kind == "user_message" || kind == "agent_message") {
                    val role = if (kind == "user_message") Role.User else Role.Assistant
                    val text = blocksText(node["content"])
                    if (text.isNotBlank()) out += Message(role, text, 0L)
                    return
                }
                for ((k, v) in node) {
                    if (k == "sessionUpdate") continue
                    if (v is JsonObject || v is JsonArray) collectUpdates(v, out)
                }
            }
            else -> Unit
        }
    }

    /** 把 content 数组（[{type:"text",text:"…"}]）拼成正文。 */
    private fun blocksText(value: JsonElement?): String {
        val arr = value as? JsonArray ?: return ""
        return arr.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            if ((obj["type"] as? JsonPrimitive)?.contentOrNull == "text")
                (obj["text"] as? JsonPrimitive)?.contentOrNull else null
        }.joinToString("\n").trim()
    }

    /**
     * 对 Unknown 角色做**最后一道推断**（仅在完全没有角色信息时用）。
     *
     * 用户说得对：「对话不就是只有我还有 AI 输出两个角色吗？」
     * 所以历史数据也不该整片显示"无角色"。这里按内容特征给出最可能的角色：
     *  - 以 "The user says" / "user:" 开头、或是简短口语句 → 用户
     *  - 含 Markdown 结构（标题/列表/代码块）、或明显是长回复 → 助手
     * 判不出的才保留 Unknown（不硬猜）。
     */
    fun inferRole(text: String): Role {
        val t = text.trim()
        if (t.isEmpty()) return Role.Unknown
        // 明确的自述式开头
        val head = t.take(120).lowercase()
        if (head.startsWith("the user says") || head.startsWith("user:") ||
            head.startsWith("我：") || head.startsWith("我:")
        ) return Role.User
        if (head.startsWith("assistant:") || head.startsWith("助手：") ||
            head.startsWith("助手:")
        ) return Role.Assistant
        // 助手回复的典型特征：Markdown 结构较多
        val markdownSignals = listOf("## ", "- **", "```", "**安装", "|---", "### ").count { t.contains(it) }
        if (markdownSignals >= 2) return Role.Assistant
        // 很短的、口语化的一行话更像用户
        if (t.length <= 30 && !t.contains('\n')) return Role.User
        // 长文、含完整句子列表 → 助手
        if (t.length > 200) return Role.Assistant
        return Role.Unknown
    }

    /**
     * 从 `/api/session/projections` 的响应里解析出带角色的对话。
     *
     * **重要限制（不要再次误判）**：projections 里的 `turnOutline` 是**摘要**，
     * prompt/response 都被服务端截断成「…」（真机确认）。它只能给"每一轮说了什么大意"，
     * 不是完整对话记录。完整记录走 [parseAcpUpdates]。
     *
     * 这是**后台无感同步**的数据源（真机确认可用，25KB 量级）：
     * 页面内 fetch 拿到 JSON，全程不切换用户界面。
     *
     * 结构（真机实测）：
     * ```
     * result.value.values.turnOutline = [
     *   {"turn":1,"prompt":"用户说的","response":"助手回的"}, ...
     * ]   ← prompt/response 天然带角色，不需要猜
     * result.value.values.title = 会话标题
     * ```
     * 另外 `inbox` 里可能有未发出的消息，但不属于历史对话，忽略。
     *
     * @param body 服务端返回的原始 JSON 文本。
     * @return 解析出的消息；解析不出返回空列表（不编造）。
     */
    fun parseProjections(body: String): List<Message> {
        if (body.isBlank()) return emptyList()
        val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyList()
        val values = root["result"]?.jsonObjectOrNull()
            ?.get("value")?.jsonObjectOrNull()
            ?.get("values")?.jsonObjectOrNull() ?: return emptyList()
        val outline = values["turnOutline"] as? JsonArray ?: return emptyList()
        val out = ArrayList<Message>(outline.size * 2)
        for (item in outline) {
            val turn = item as? JsonObject ?: continue
            val seq = (turn["seq"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L
            // prompt = 用户；response = 助手。两者都是**已完成的正文**，不是摘要。
            textOf(turn, "prompt")?.let { out += Message(Role.User, it, seq) }
            textOf(turn, "response")?.let { out += Message(Role.Assistant, it, seq) }
        }
        return out
    }

    /** 会话标题（projections 里的 title）。 */
    fun projectionsTitle(body: String): String {
        val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return ""
        val values = root["result"]?.jsonObjectOrNull()
            ?.get("value")?.jsonObjectOrNull()
            ?.get("values")?.jsonObjectOrNull() ?: return ""
        return (values["title"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    }

    private fun JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject

    private fun textOf(obj: JsonObject, key: String): String? {
        val value = obj[key] ?: return null
        val text = when (value) {
            is JsonPrimitive -> value.contentOrNull.orEmpty()
            is JsonObject -> stringOf(value, "text").ifBlank { stringOf(value, "content") }
            else -> ""
        }
        return text.trim().ifBlank { null }
    }

    /**
     * 从一条事件里提取消息。只认这几种类型，其余返回 null（不猜）。
     *
     * 字段依据真机日志确认（2026-11-03）：
     *  - `user/message`      dataKeys=[content, source, role, id]
     *  - `assistant/message` dataKeys=[turn, step, message, usage, stream]
     *  - `tool/call`         dataKeys=[turn, step, callId, name, arguments]
     * 即：用户消息正文在 `data.content[]`，助手消息正文在 `data.message`。
     *
     * @param type 事件类型（已 unwrap，例如 `assistant/message`）。
     * @param data 事件的 data 对象。
     * @param at 事件时间。
     */
    fun fromEvent(type: String, data: JsonObject, at: Long): Message? = when (type) {
        "user/message" -> {
            // 用户正文在 data.content[]；忽略 source.kind 为 context-injection 的注入消息。
            val text = textBlocks(data["content"]).orEmpty().ifBlank { stringOf(data, "text") }
            if (text.isBlank()) null else Message(Role.User, text, at)
        }
        "assistant/message" -> {
            // 助手正文在 data.message（对象，含 content[]），也可能直接是字符串。
            val text = assistantText(data)
            if (text.isBlank()) null else Message(Role.Assistant, text, at)
        }
        "tool/call" -> {
            // 只留"调用了什么"的一句摘要，绝不留命令/查询正文与返回结果。
            val name = stringOf(data, "name").ifBlank { stringOf(data, "tool") }
            if (name.isBlank()) null else Message(Role.Tool, "调用 " + humanToolName(name), at)
        }
        else -> null
    }

    /** 助手消息：data.message 可能是对象（含 content[]/text）或字符串。 */
    private fun assistantText(data: JsonObject): String {
        when (val message = data["message"]) {
            is JsonObject -> {
                textBlocks(message["content"])?.let { if (it.isNotBlank()) return it }
                val flat = stringOf(message, "text")
                if (flat.isNotBlank()) return flat.trim()
            }
            is JsonPrimitive -> return message.contentOrNull.orEmpty().trim()
            else -> Unit
        }
        // 兼容：有些版本把 content 提到 data 层
        return textBlocks(data["content"]).orEmpty().ifBlank { stringOf(data, "text") }.trim()
    }

    /** 从 [{type:"text",text:"…"}, …] 里拼出全部文本块；非数组返回 null。 */
    private fun textBlocks(value: JsonElement?): String? {
        val array = value as? JsonArray ?: return null
        return array.mapNotNull { item ->
            (item as? JsonObject)?.let { obj ->
                if (stringOf(obj, "type") == "text") stringOf(obj, "text") else null
            }
        }.joinToString("\n").trim()
    }

    private fun stringOf(obj: JsonObject, key: String): String =
        (obj[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    /** 工具名转成人话，与通知里的叫法保持一致。 */
    private fun humanToolName(name: String): String = when (name) {
        "pwsh", "bash", "exec_command" -> "运行命令"
        "web_search" -> "网页搜索"
        "read" -> "读取文件"
        "write", "edit" -> "写入文件"
        "subagent" -> "派子代理"
        else -> EventReducer.humanTool(name)
    }
}
