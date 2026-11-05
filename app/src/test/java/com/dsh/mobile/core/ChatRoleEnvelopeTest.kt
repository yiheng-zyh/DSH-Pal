package com.dsh.mobile.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

/**
 * 用**真实事件外壳**验证分角色解析与渲染数据。
 *
 * 事件外壳取自项目既有文档（三层嵌套：type=item → value.type=event → event），
 * 正文结构以 DSH 侧 dsh-better-sidebar 的既有解析为准（data.content[]）。
 * 这组用例的意义是：把"谁能被解析出角色"固化下来，避免日后改 DOM 抓取时退化。
 */
class ChatRoleEnvelopeTest {
    private val json = Json { ignoreUnknownKeys = true }

    /** 复刻真实外壳，便于逐层验证。 */
    private fun envelope(eventType: String, dataJson: String, time: Long = 1_700_000_000_000L): String =
        """{"type":"item","streamId":"s1","value":{"type":"event","event":{"type":""" +
            """"$eventType","seq":6,"time":$time,"data":$dataJson}}}"""

    /** 与采集器相同的解包步骤：root → value → (event) → data。 */
    private fun unwrap(raw: String): Triple<String, JsonObject, Long>? {
        val root = Json.parseToJsonElement(raw).jsonObject
        val value = root["value"] as? JsonObject ?: return null
        val valueType = (value["type"] as? JsonPrimitive)?.contentOrNull
        val inner = if (valueType == "event") (value["event"] as? JsonObject ?: return null) else value
        val type = (inner["type"] as? JsonPrimitive)?.contentOrNull ?: return null
        val data = inner["data"] as? JsonObject ?: return null
        val at = (inner["time"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L
        return Triple(type, data, at)
    }

    @Test fun realEnvelopeYieldsUserAndAssistantRoles() {
        // 字段结构与真机日志一致（2026-11-03）：
        // user/message → data.content[]；assistant/message → data.message.content[]
        val userRaw = envelope("user/message", """{"content":[{"type":"text","text":"把背景换成深色"}],"role":"user"}""")
        val assistantRaw = envelope("assistant/message",
            """{"turn":1,"message":{"content":[{"type":"text","text":"好，已改成深色。"}]}}""", 2_000L)

        val user = unwrap(userRaw)!!.let { (type, data, at) -> ChatRole.fromEvent(type, data, at) }
        val assistant = unwrap(assistantRaw)!!.let { (type, data, at) -> ChatRole.fromEvent(type, data, at) }

        assertNotNull(user)
        assertNotNull(assistant)
        assertEquals(ChatRole.Role.User, user!!.role)
        assertEquals("把背景换成深色", user.text)
        assertEquals(ChatRole.Role.Assistant, assistant!!.role)
        assertEquals("好，已改成深色。", assistant.text)

        // 落盘往返后角色仍然可分（离线页据此配色与对齐）
        val transcript = ChatRole.Transcript(listOf(user, assistant))
        val decoded = ChatRole.Transcript.decode(transcript.encode())
        assertEquals(listOf(ChatRole.Role.User, ChatRole.Role.Assistant), decoded.messages.map { it.role })
        assertEquals("把背景换成深色", decoded.messages[0].text)
    }

    @Test fun toolCallEventBecomesAToolRowWithoutArguments() {
        val raw = envelope("tool/call", """{"name":"write","arguments":"{\"path\":\"/secret\"}"}""")
        val (type, data, at) = unwrap(raw)!!
        val message = ChatRole.fromEvent(type, data, at)
        assertNotNull(message)
        assertEquals(ChatRole.Role.Tool, message!!.role)
        assertEquals("调用 写入文件", message.text)
        assertFalse(message.text.contains("/secret"))
    }

    @Test fun nonMessageEventsInsideRealEnvelopeProduceNothing() {
        for (type in listOf("turn/start", "step/start", "turn/end", "assistant/live-chunk", "todo/write", "session/title")) {
            val raw = envelope(type, """{"turn":1,"content":[{"type":"text","text":"x"}]}""")
            val (t, data, at) = unwrap(raw)!!
            assertNull("不应把 $type 归档成消息", ChatRole.fromEvent(t, data, at))
        }
    }
}
