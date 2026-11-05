package com.dsh.mobile.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class FloatingTaskInfoTest {
    @Test fun compactDoesNotExposeTitleOrStepsText() {
        val t=TaskState(title="PRIVATE_TITLE",phase=TaskState.Phase.Running,step=3,noticeLines=listOf(TaskState.NoticeLine("PUBLIC_TEXT",true)))
        val info=FloatingTaskInfo.project(t,0,false)
        assertEquals("正在执行",info.heading)
        assertTrue(info.body.contains("第 3 步"))
        assertFalse(info.body.contains("PRIVATE_TITLE"));assertFalse(info.body.contains("PUBLIC_TEXT"))
    }
    @Test fun waitingKeepsApprovalAndDoesNotInventCompletion() {
        val t=TaskState(phase=TaskState.Phase.Waiting,approval=TaskState.Approval("1","请确认此操作"))
        val info=FloatingTaskInfo.project(t,1,false)
        assertEquals("需要你确认",info.heading);assertEquals("请确认此操作",info.body)
    }
    @Test fun unknownTotalDoesNotInventPercentage() {
        val info=FloatingTaskInfo.project(TaskState(phase=TaskState.Phase.Running,step=4),0,false)
        assertEquals("第 4 步",info.body);assertFalse(info.body.contains("%"))
    }
    @Test fun commandArgumentsStayOutsideOverlayProjection() {
        val raw=Json.parseToJsonElement("""{"value":{"type":"event","event":{"type":"tool/call","data":{"name":"exec_command","arguments":{"command":"PRIVATE_COMMAND_BODY","description":"检查资源"}}}}}""").jsonObject
        val t=EventReducer.reduce(TaskState(),raw,100)
        val info=FloatingTaskInfo.project(t,2,false)
        assertTrue(info.body.contains("检查资源"));assertFalse(info.body.contains("PRIVATE_COMMAND_BODY"))
    }
    @Test fun finishedStatesAndIdleRemainHonest() {
        assertEquals("任务遇到问题",FloatingTaskInfo.project(TaskState(phase=TaskState.Phase.Failed),2,false).heading)
        assertEquals("任务完成啦",FloatingTaskInfo.project(TaskState(phase=TaskState.Phase.Done),1,false).heading)
        assertTrue(FloatingTaskInfo.project(TaskState(),1,false).body.contains("任务开始后"))
    }
    @Test fun stepsModeShowsLatestActionInsteadOfGenericHeading() {
        val t=TaskState(phase=TaskState.Phase.Running,noticeLines=listOf(TaskState.NoticeLine("检查文件来源",true)))
        assertEquals("检查文件来源",FloatingTaskInfo.project(t,1,false).body)
    }
    @Test fun capsulePutsActionAboveToolAndProgress() {
        val task=TaskState(phase=TaskState.Phase.Running,step=3,noticeLines=listOf(TaskState.NoticeLine("运行命令 · 检查资源",true)))
        val info=FloatingTaskInfo.capsule(task,1,false)
        assertEquals("检查资源",info.heading);assertTrue(info.body.contains("运行命令"));assertTrue(info.body.contains("第 3 步"))
    }
    @Test fun capsuleKeepsApprovalProminentAndPrivateTitleHidden() {
        val task=TaskState(title="PRIVATE_TITLE",phase=TaskState.Phase.Waiting,approval=TaskState.Approval("1","确认操作"))
        val info=FloatingTaskInfo.capsule(task,1,false)
        assertEquals("确认操作",info.heading);assertEquals("等待你的确认",info.body)
    }
    @Test fun longPublicContentIsBounded() {
        val t=TaskState(title="长".repeat(1000),phase=TaskState.Phase.Running,noticeLines=List(30){TaskState.NoticeLine("文".repeat(1000),true)})
        assertTrue(FloatingTaskInfo.project(t,2,true).body.length<=720)
    }
}
