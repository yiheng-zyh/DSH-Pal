package com.dsh.mobile.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Folds DSH session events into a [TaskState].
 *
 * ## Wire format
 *
 * The page's `/api/remote.mux` socket is a Typert gateway multiplexer, so every
 * message is a mux *item* wrapping a per-stream payload. A session event looks
 * like this (captured from a real device, 2026-10-24):
 *
 * ```json
 * {"type":"item","streamId":"a2c1fa1f-...","value":{
 *    "type":"event",
 *    "event":{"type":"turn/start","seq":6,"time":1792807077113,"data":{"turn":1}}}}
 * ```
 *
 * Note the three nesting levels: item → value → event. An earlier version of
 * this reducer read `{type, payload}` from the top level and therefore matched
 * nothing; `reduce` now unwraps the envelope first and only then looks at the
 * inner event.
 *
 * `data` is the per-type payload. Field names differ from what one might guess
 * (`turn`/`step` rather than `index`/`total`), so each branch reads what the
 * harness actually sends and falls back gracefully.
 *
 * The reducer is deliberately total: any malformed or unknown payload is
 * skipped and leaves the previous state untouched. A single bad event must
 * never blank the island while the user is watching it.
 */
object EventReducer {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** Event types this app projects onto the island. */
    private val INTERESTING = setOf(
        "turn/start",
        "turn/end",
        "step/start",
        "step/end",
        "tool/call",
        "tool/call/start",
        "tool/result",
        "tool/call/end",
        "approval/asked",
        "approval/decided",
        "todo/write",
        "user/message",
        "assistant/message",
        "session/title",
        "goal/change",
        "agent/status",
        "request/context",
    )

    /**
     * Apply one raw mux item to [current].
     *
     * @param current the last known state.
     * @param raw one decoded mux item from the socket.
     * @param now wall-clock millis used when the payload carries no timestamp.
     * @return the next state; [current] itself when the event is irrelevant.
     */
    fun reduce(current: TaskState, raw: JsonObject, now: Long): TaskState {
        val (type, data, ts) = unwrap(raw) ?: return current
        if (type !in INTERESTING) return current

        val sessionId = data.str("sessionId")
            ?: raw.str("sessionId")
            ?: current.sessionId

        return when (type) {
            "turn/start" -> current.copy(
                sessionId = sessionId,
                phase = TaskState.Phase.Thinking,
                detail = "",
                step = 0,
                stepTotal = 0,
                tool = "",
                approval = null,
                startedAt = ts,
                updatedAt = now,
                noticeLines = emptyList(),
                hasPublicOutput = false,
            )

            "step/start" -> {
                // The harness sends {"turn":n,"step":n}; there is no separate
                // "total", so the live step counter is what we can show.
                val step = data.int("step") ?: data.int("index") ?: (current.step + 1)
                current.copy(
                    sessionId = sessionId,
                    phase = if (current.phase == TaskState.Phase.Waiting) {
                        TaskState.Phase.Waiting
                    } else {
                        TaskState.Phase.Running
                    },
                    step = step,
                    stepTotal = data.int("total") ?: current.stepTotal,
                    detail = if (current.tool.isBlank()) "第 $step 步" else current.detail,
                    updatedAt = now,
                )
            }

            "step/end" -> current.copy(
                step = data.int("step") ?: data.int("index") ?: current.step,
                stepTotal = data.int("total") ?: current.stepTotal,
                updatedAt = now,
            )

            "tool/call", "tool/call/start" -> {
                val name = data.str("name")
                    ?: data.str("tool")
                    ?: data.str("toolName")
                    ?: "工具"
                current.copy(
                    sessionId = sessionId,
                    phase = TaskState.Phase.Running,
                    tool = name,
                    detail = "调用 ${humanTool(name)}",
                    noticeLines = TaskNotice.append(current.noticeLines, TaskState.NoticeLine(TaskNotice.toolLabel(name, data), action = true)),
                    updatedAt = now,
                )
            }

            "tool/result", "tool/call/end" -> current.copy(
                tool = "",
                // A completed tool means the model is thinking about the result.
                phase = if (current.phase == TaskState.Phase.Waiting) {
                    TaskState.Phase.Waiting
                } else {
                    TaskState.Phase.Thinking
                },
                updatedAt = now,
            )

            "approval/asked" -> current.copy(
                sessionId = sessionId,
                phase = TaskState.Phase.Waiting,
                approval = TaskState.Approval(
                    id = data.str("id") ?: data.str("requestId") ?: "",
                    prompt = data.str("prompt") ?: data.str("title") ?: "需要你的确认",
                    tool = data.str("tool") ?: current.tool,
                ),
                detail = "等待确认",
                updatedAt = now,
            )

            "approval/decided" -> current.copy(
                approval = null,
                phase = TaskState.Phase.Running,
                detail = if (data.bool("approved") == false) "已拒绝" else "继续执行",
                updatedAt = now,
            )

            "todo/write" -> {
                val todos = data.arr("todos") ?: data.arr("items")
                if (todos == null) {
                    current
                } else {
                    val done = todos.count { it is JsonObject && it.str("status") in DONE_STATUSES }
                    current.copy(todosDone = done, todosTotal = todos.size, updatedAt = now)
                }
            }

            "session/title" -> current.copy(
                title = data.str("title") ?: current.title,
                updatedAt = now,
            )

            "assistant/message" -> {
                val text = TaskNotice.publicText(data)
                if (text.isBlank()) current else current.copy(
                    noticeLines = TaskNotice.append(current.noticeLines, TaskState.NoticeLine(TaskNotice.narration(text))),
                    hasPublicOutput = true,
                    updatedAt = now,
                )
            }

            "user/message" -> current.copy(
                sessionId = sessionId,
                title = current.title.ifBlank { data.str("title") ?: "" },
                updatedAt = now,
            )

            "turn/end" -> {
                // {"reason":{"kind":"completed"}} on success.
                val kind = data.obj("reason")?.str("kind")
                    ?: data.str("status")
                val failed = kind?.lowercase() in FAIL_STATUSES
                current.copy(
                    phase = if (failed) TaskState.Phase.Failed else TaskState.Phase.Done,
                    detail = if (failed) "执行失败" else "已完成",
                    tool = "",
                    approval = null,
                    updatedAt = now,
                )
            }

            else -> current
        }
    }

    /**
     * The session event name inside a mux item, for logging.
     *
     * Returns a marker for frames that carry no session event so a log line
     * always says what happened.
     */
    fun describe(raw: JsonObject): String {
        val unwrapped = unwrap(raw) ?: return "non-event(item)"
        return if (unwrapped.type in INTERESTING) unwrapped.type else "${unwrapped.type}(ignored)"
    }

    /**
     * Unwrap the mux item envelope into its event.
     *
     * Returns null for anything that is not a session event (projections,
     * sidebars, stream chunks), which is the common case on this socket.
     */
    private fun unwrap(raw: JsonObject): Unwrapped? {
        val value = raw.obj("value") ?: return null
        // Session events arrive as value.type == "event" with the real event one
        // level deeper. Older/other frames use value.type as the event name
        // directly, so both shapes are accepted.
        val inner: JsonObject = if (value.str("type") == "event") {
            value.obj("event") ?: return null
        } else {
            value
        }
        val type: String = inner.str("type") ?: return null
        if (type == "item" || type == "event") return null
        return Unwrapped(
            type = type,
            data = inner.obj("data") ?: inner.obj("payload") ?: JsonObject(emptyMap()),
            ts = inner.long("time") ?: inner.long("ts") ?: 0L,
        )
    }

    private data class Unwrapped(
        val type: String,
        val data: JsonObject,
        val ts: Long,
    )

    private val DONE_STATUSES = setOf("completed", "done", "cancelled")
    private val FAIL_STATUSES = setOf("error", "failed", "aborted", "cancelled")

    /** Readable Chinese label for a tool name the harness reports. */
    fun humanTool(name: String): String = when (name) {
        "pwsh", "bash" -> "命令"
        "read" -> "读取文件"
        "write", "edit", "str_replace_editor" -> "写入文件"
        "glob", "grep" -> "搜索文件"
        "web_search" -> "联网搜索"
        "web_fetch" -> "抓取网页"
        "subagent", "subagent_fork" -> "子代理"
        "workflow" -> "工作流"
        "todo_write" -> "更新计划"
        "ask_user_question" -> "向你提问"
        "present" -> "整理交付物"
        "team_task_create", "team_task_update" -> "团队任务"
        else -> name
    }

    // ---- tolerant JSON accessors -------------------------------------------------

    private fun JsonObject.str(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()

    private fun JsonObject.long(key: String): Long? =
        runCatching { this[key]?.jsonPrimitive?.longOrNull }.getOrNull()

    private fun JsonObject.bool(key: String): Boolean? =
        runCatching { this[key]?.jsonPrimitive?.booleanOrNull }.getOrNull()

    private fun JsonObject.int(key: String): Int? = long(key)?.toInt()

    private fun JsonObject.obj(key: String): JsonObject? =
        runCatching { this[key]?.jsonObject }.getOrNull()

    private fun JsonObject.arr(key: String): List<Any?>? =
        runCatching {
            (this[key] as? JsonArray)?.toList()
        }.getOrNull()

    /** Parse a raw JSON text frame into an envelope, or null when it is not one. */
    fun parse(raw: String): JsonObject? = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
}
