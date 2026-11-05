package com.dsh.mobile.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

/**
 * 分角色解析测试。
 *
 * 这是"能不能看出谁说的"的核心：角色必须来自事件的显式类型，
 * 不能从文本猜。用例覆盖三种真实事件形态与落盘往返。
 */
class ChatRoleTest {
    private fun data(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test fun userMessageCarriesUserRoleAndText() {
        // 真机确认：user/message 的 dataKeys=[content, source, role, id]
        val raw = """
            {"content":[{"type":"text","text":"帮我看看这个报错"}],"role":"user","id":"m1"}
        """.trimIndent()
        val message = ChatRole.fromEvent("user/message", data(raw), 1000L)
        assertNotNull(message)
        assertEquals(ChatRole.Role.User, message!!.role)
        assertEquals("帮我看看这个报错", message.text)
        assertEquals(1000L, message.at)
    }

    @Test fun assistantMessageReadsFromMessageField() {
        // 真机确认：assistant/message 的 dataKeys=[turn, step, message, usage, stream]
        // 正文在 data.message 里，而不是 data.content
        val raw = """
            {"turn":1,"step":1,"message":{"role":"assistant","content":[
              {"type":"text","text":"这是原因："},
              {"type":"tool_use","id":"x"},
              {"type":"text","text":"端口被占了"}
            ]},"usage":{"inputTokens":10}}
        """.trimIndent()
        val message = ChatRole.fromEvent("assistant/message", data(raw), 2000L)
        assertNotNull(message)
        assertEquals(ChatRole.Role.Assistant, message!!.role)
        // 只拼 text 块，跳过 tool_use 等非文本块
        assertEquals("这是原因：\n端口被占了", message.text)
    }

    @Test fun nestedMessageShapeIsStillAcceptedForCompatibility() {
        // 兼容旧/其它插件形态：data.message.content[]
        val raw = """{"message":{"content":[{"type":"text","text":"嵌套形态也要能读"}]}}"""
        val message = ChatRole.fromEvent("assistant/message", data(raw), 5L)
        assertNotNull(message)
        assertEquals("嵌套形态也要能读", message!!.text)
    }

    @Test fun toolCallKeepsOnlyALabelAndNeverCommandBody() {
        val raw = """
            {"name":"pwsh","arguments":"{\"command\":\"rm -rf /important\"}"}
        """.trimIndent()
        val message = ChatRole.fromEvent("tool/call", data(raw), 3000L)
        assertNotNull(message)
        assertEquals(ChatRole.Role.Tool, message!!.role)
        // 只留一句"调用 X"，绝不带命令正文 —— 与既有隐私边界一致
        assertEquals("调用 运行命令", message.text)
        assertFalse("不能泄露命令正文", message.text.contains("rm -rf"))
    }

    @Test fun streamingChunksAreNotArchivedAsMessages() {
        // assistant/live-chunk 是流式增量，会被最终 assistant/message 取代；
        // 归档只收成品，避免同一条回复被存几十份。
        assertNull(ChatRole.fromEvent("assistant/live-chunk", data("""{"content":[{"type":"text","text":"在"}]}"""), 1L))
    }

    @Test fun unknownEventTypesAreIgnoredInsteadOfGuessed() {
        for (type in listOf("turn/start", "step/start", "todo/write", "session/title", "tool/result")) {
            assertNull(type, ChatRole.fromEvent(type, data("""{"x":1}"""), 1L))
        }
    }

    @Test fun emptyContentProducesNoMessage() {
        assertNull(ChatRole.fromEvent("assistant/message", data("""{"message":{"content":[]}}"""), 1L))
        assertNull(ChatRole.fromEvent("user/message", data("""{"message":{"content":[{"type":"text","text":"  "}]}}"""), 1L))
        // 完全没有 message 字段也不炸
        assertNull(ChatRole.fromEvent("user/message", data("""{}"""), 1L))
    }

    @Test fun transcriptRoundTripsWithRolesAndTimestamps() {
        val transcript = ChatRole.Transcript(listOf(
            ChatRole.Message(ChatRole.Role.User, "你好", 1000L),
            ChatRole.Message(ChatRole.Role.Assistant, "你好，有什么可以帮你？\n第二行也行。", 2000L),
            ChatRole.Message(ChatRole.Role.Tool, "调用 运行命令", 3000L),
        ))
        val encoded = transcript.encode()
        val decoded = ChatRole.Transcript.decode(encoded)
        assertEquals(3, decoded.messages.size)
        assertEquals(ChatRole.Role.User, decoded.messages[0].role)
        assertEquals("你好", decoded.messages[0].text)
        assertEquals(1000L, decoded.messages[0].at)
        // 多行正文要完整保留（不能因为换行被切断）
        assertEquals("你好，有什么可以帮你？\n第二行也行。", decoded.messages[1].text)
        assertEquals(ChatRole.Role.Tool, decoded.messages[2].role)
    }

    @Test fun legacyPlainTextIsReadAsUnknownInsteadOfInventingARole() {
        // 旧版本（无角色前缀）的归档：整段保留，角色标未知，不假装是用户或助手
        val legacy = "这是一段没有角色标记的旧快照正文。\n第二段内容。"
        val decoded = ChatRole.Transcript.decode(legacy)
        assertFalse(decoded.isEmpty())
        assertTrue(decoded.messages.all { it.role == ChatRole.Role.Unknown })
        assertTrue(decoded.encode().contains("这是一段没有角色标记的旧快照正文。"))
    }

    // ---- 「不要整片显示无角色」（用户反馈第 3 条）----
    //
    
    @Test fun legacyBlocksAreResolvedToUserAndAssistantByAlternation() {
        // 旧的无前缀归档：两个长度相近、内容特征不明显的段落。
        // 对话几乎必然是"我一句、助手一段"交替，所以第一个判不出时，
        // 后续块必须能借助前一条已定角色推出来，不能两块都留 Unknown。
        val legacy = listOf(
            "帮我把这个报错看一下",
            "这个报错是因为端口被占用了，换一个端口就行",
        ).joinToString("\n\n")
        val decoded = ChatRole.Transcript.decode(legacy)
        assertEquals(2, decoded.messages.size)
        assertEquals(ChatRole.Role.User, decoded.messages[0].role)
        assertEquals(ChatRole.Role.Assistant, decoded.messages[1].role)
        assertTrue("不应再有 Unknown", decoded.messages.none { it.role == ChatRole.Role.Unknown })
    }

    @Test fun codedBlocksMixedWithLegacyBlocksKeepTheirExplicitRoles() {
        // 现实形态：新数据带 [User]/[Assistant]，中间的旧块没有前缀。
        // 显式角色绝不能被动摇，旧块要补判而不是留空。
        val mixed = listOf(
            "[User]<!--100--> 先跑一遍测试",
            "跑完了，175 项全过。",
            "[User]<!--300--> 再检查一下体积",
        ).joinToString("\n\n")
        val decoded = ChatRole.Transcript.decode(mixed)
        assertEquals(3, decoded.messages.size)
        assertEquals(ChatRole.Role.User, decoded.messages[0].role)
        assertEquals(100L, decoded.messages[0].at)
        // 中间那条旧块：上一条是 User → 这条判成 Assistant
        assertEquals(ChatRole.Role.Assistant, decoded.messages[1].role)
        assertEquals(ChatRole.Role.User, decoded.messages[2].role)
    }

    @Test fun ambiguousLegacyBlockStillStaysUnknownInsteadOfHardGuessing() {
        // 第一块且内容特征不足以判断时，不许硬猜成"我"或"助手"——
        // 宁可如实标 Unknown（界面会说明原因），也不编造。
        val vague = "嗯。"
        val decoded = ChatRole.Transcript.decode(vague)
        assertEquals(1, decoded.messages.size)
        assertEquals(ChatRole.Role.Unknown, decoded.messages[0].role)
    }

    @Test fun inferRoleRecognisesTypicalAssistantAndUserText() {
        // 助手式长回复（含 Markdown 结构）
        assertEquals(ChatRole.Role.Assistant, ChatRole.inferRole("## 结论\n\n- **已完成** 编译\n- 单测通过"))
        // 用户式短句
        assertEquals(ChatRole.Role.User, ChatRole.inferRole("为什么列表只有 8 条"))
        // 空内容不判
        assertEquals(ChatRole.Role.Unknown, ChatRole.inferRole("   "))
    }

    @Test fun emptyTranscriptIsDetected() {
        assertTrue(ChatRole.Transcript(emptyList()).isEmpty())
        // 全空消息不算有内容：避免离线页显示一片空白卡片
        assertTrue(
            ChatRole.Transcript(
                listOf(
                    ChatRole.Message(ChatRole.Role.User, "   "),
                    ChatRole.Message(ChatRole.Role.Assistant, ""),
                )
            ).isEmpty()
        )
        assertTrue(ChatRole.Transcript.decode("").isEmpty())
    }

    @Test fun roleTitlesAreChineseForDisplay() {
        assertEquals("我", ChatRole.Role.User.title)
        assertEquals("助手", ChatRole.Role.Assistant.title)
        assertEquals("工具", ChatRole.Role.Tool.title)
    }
}
