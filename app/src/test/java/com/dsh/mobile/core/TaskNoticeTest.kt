package com.dsh.mobile.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TaskNoticeTest {
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject
    private fun event(type: String, data: String) = obj("""{"value":{"type":"event","event":{"type":"$type","data":$data}}}""")
    @Test fun `command title survives without command body`() {
        val data = buildJsonObject {put("arguments", """{"description":"检查音视频工具链","command":"SECRET_COMMAND"}""")}
        assertEquals("运行命令 · 检查音视频工具链", TaskNotice.toolLabel("pwsh", data))
        assertFalse(TaskNotice.toolLabel("pwsh", obj("""{"arguments":{"command":"SECRET"}}""")).contains("SECRET"))
    }
    @Test fun `only public text blocks are used`() {
        assertEquals("准备检查。", TaskNotice.publicText(obj("""{"message":{"content":[{"type":"reasoning","text":"PRIVATE_THINKING"},{"type":"text","text":"准备检查。"},{"type":"tool-call","arguments":"PRIVATE_ARGS"}]}}""")))
    }
    @Test fun `short narration is preserved and long content opens chat`() {
        assertEquals("我先查清当前有什么能力，再给方案。",TaskNotice.narration("我先查清当前有什么能力，再给方案。"))
        assertEquals("正在输出较长内容，请到对话查看",TaskNotice.narration("长".repeat(121)))
        assertEquals("正在输出较长内容，请到对话查看",TaskNotice.narration("```secret```"))
    }
    @Test fun `history bounded and repeated labels coalesce`() {
        var lines=emptyList<TaskState.NoticeLine>()
        repeat(20){lines=TaskNotice.append(lines,TaskState.NoticeLine("步骤$it",true))}
        assertEquals(5,lines.size)
        assertEquals(lines,TaskNotice.append(lines,lines.last()))
    }
    @Test fun `completion replaces prose and keeps recent action labels`() {
        val state=TaskState(phase=TaskState.Phase.Done,hasPublicOutput=true,noticeLines=listOf(TaskState.NoticeLine("中间正文"),TaskState.NoticeLine("运行命令 · 检查素材来源",true)))
        val lines=TaskNotice.visibleLines(state)
        assertEquals(listOf("已产生结果，请到对话查看","运行命令 · 检查素材来源"),lines.map{it.text})
    }
    @Test fun `failure and approval are never called successful results`() {
        assertEquals("执行未完成，请到对话查看原因",TaskNotice.compact(TaskState(phase=TaskState.Phase.Failed,hasPublicOutput=true)))
        assertEquals("允许执行？",TaskNotice.compact(TaskState(phase=TaskState.Phase.Waiting,approval=TaskState.Approval("a","允许执行？"),noticeLines=listOf(TaskState.NoticeLine("旧步骤",true)))))
    }
    @Test fun `new turn clears old preview and reasoning does not enter history`() {
        val old=TaskState(phase=TaskState.Phase.Done,hasPublicOutput=true,noticeLines=listOf(TaskState.NoticeLine("旧结果")))
        val fresh=EventReducer.reduce(old,event("turn/start","{}"),1)
        assertTrue(fresh.noticeLines.isEmpty());assertFalse(fresh.hasPublicOutput)
        val ignored=EventReducer.reduce(fresh,event("assistant/message","""{"message":{"content":[{"type":"reasoning","text":"PRIVATE"}]}}"""),2)
        assertEquals(fresh,ignored)
    }
    @Test fun `real message and tool shapes reach notification projection`() {
        val spoken=EventReducer.reduce(TaskState(phase=TaskState.Phase.Thinking),event("assistant/message","""{"message":{"content":[{"type":"text","text":"先检查工具链。"}]}}"""),1)
        val tool=EventReducer.reduce(spoken,event("tool/call","""{"name":"pwsh","arguments":"{\"description\":\"检查音视频工具链\"}"}"""),2)
        assertEquals(listOf("先检查工具链。","运行命令 · 检查音视频工具链"),tool.noticeLines.map{it.text})
    }
    @Test fun `tool output never becomes notification prose`() {
        val current=TaskState(phase=TaskState.Phase.Running)
        val next=EventReducer.reduce(current,event("tool/result","""{"output":"PRIVATE_OUTPUT"}"""),1)
        assertTrue(next.noticeLines.isEmpty())
    }
    @Test fun `collapsed shows latest action while expanded puts narration above steps`() {
        val state=TaskState(phase=TaskState.Phase.Running,noticeLines=listOf(
            TaskState.NoticeLine("运行命令 · 检查工具链",true),
            TaskState.NoticeLine("网络全通，继续检查素材。")))
        assertEquals("运行命令 · 检查工具链",TaskNotice.compact(state))
        assertEquals(listOf("网络全通，继续检查素材。","运行命令 · 检查工具链"),TaskNotice.visibleLines(state).map{it.text})
    }
}
