package com.dsh.mobile.core

import kotlinx.serialization.json.*

/** Deterministic, bounded projection of public chat content; no model calls. */
object TaskNotice {
    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun plain(text: String) = text.replace(Regex("<[^>]*>"), "")
        .replace(Regex("\\s+"), " ").trim()
    private fun clip(text: String, limit: Int): String {
        if (text.length <= limit) return text
        var end = limit - 1
        if (end > 0 && text[end - 1].isHighSurrogate()) end--
        return text.take(end).trimEnd() + "…"
    }
    fun toolLabel(name: String, data: JsonObject): String {
        val args = (data["arguments"] as? JsonObject) ?: runCatching {
            Json.parseToJsonElement(data.string("arguments")) as? JsonObject
        }.getOrNull()
        // Labels only. Never fall back to command/script/query bodies or outputs.
        val detail = listOf("title", "description", "objective", "task_name")
            .firstNotNullOfOrNull { key -> args?.string(key)?.takeIf { it.isNotBlank() } }
            ?: data.string("description").ifBlank {
                if(name == "web_search") args?.string("query").orEmpty().ifBlank {
                    (args?.get("queries") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.take(3)?.joinToString("；").orEmpty()
                } else ""
            }
        val label = when(name) {
            "pwsh", "bash", "exec_command" -> "运行命令"
            "web_search" -> "网页搜索"
            else -> EventReducer.humanTool(name)
        }
        return clip(label + if(detail.isBlank()) "" else " · " + plain(detail), 160)
    }
    fun publicText(data: JsonObject): String {
        val message = data["message"] as? JsonObject ?: return ""
        val content = message["content"] as? JsonArray ?: return ""
        return content.mapNotNull { it as? JsonObject }
            .filter { it.string("type") == "text" }
            .joinToString("") { it.string("text").take(512) }.take(1024)
    }
    fun narration(text: String): String {
        val cleaned = plain(text)
        if(cleaned.length > 120 || text.contains("```")) return "正在输出较长内容，请到对话查看"
        return cleaned.replace("**", "").replace("`", "")
    }
    fun append(lines: List<TaskState.NoticeLine>, next: TaskState.NoticeLine): List<TaskState.NoticeLine> {
        if(next.text.isBlank() || lines.lastOrNull() == next) return lines
        return (lines + next).takeLast(5)
    }
    fun visibleLines(state: TaskState): List<TaskState.NoticeLine> {
        state.approval?.takeIf {state.phase == TaskState.Phase.Waiting}?.let {
            return listOf(TaskState.NoticeLine(clip(it.prompt, 400)))
        }
        if(state.phase == TaskState.Phase.Done || state.phase == TaskState.Phase.Failed) {
            val status = when {
                state.phase == TaskState.Phase.Failed -> "执行未完成，请到对话查看原因"
                state.hasPublicOutput -> "已产生结果，请到对话查看"
                else -> "任务已完成，请到对话查看"
            }
            return listOf(TaskState.NoticeLine(status)) + state.noticeLines.filter {it.action}.takeLast(3)
        }
        if(state.noticeLines.isNotEmpty()) {
            val heading = state.noticeLines.lastOrNull { !it.action }
                ?: TaskState.NoticeLine(if(state.phase == TaskState.Phase.Thinking) "正在处理步骤结果" else "正在执行任务")
            return listOf(heading) + state.noticeLines.filter {it.action}.takeLast(3)
        }
        return listOf(TaskState.NoticeLine(state.detail.ifBlank {
            if(state.phase == TaskState.Phase.Idle) "后台连接已开启" else state.headline
        }))
    }
    fun compact(state: TaskState): String {
        if(state.isLive && state.phase != TaskState.Phase.Waiting) {
            state.noticeLines.lastOrNull {it.action}?.let {return it.text}
        }
        return visibleLines(state).first().text
    }
}
