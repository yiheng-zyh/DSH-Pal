package com.dsh.mobile.core

import org.junit.Assert.*
import org.junit.Test

class ReplyPolicyTest {
    private val waiting = TaskState(phase = TaskState.Phase.Waiting, approval = TaskState.Approval("current", "confirm"))
    @Test fun currentApprovalCanReceiveReply() { assertTrue(ReplyPolicy.canSubmit(waiting, "current")) }
    @Test fun oldApprovalCannotReceiveReply() { assertFalse(ReplyPolicy.canSubmit(waiting, "old")) }
    @Test fun completedTaskCannotReceiveReply() { assertFalse(ReplyPolicy.canSubmit(waiting.copy(phase = TaskState.Phase.Done), "current")) }
    @Test fun debugAndMissingApprovalCannotReachLiveTask() {
        assertFalse(ReplyPolicy.canSubmit(waiting.copy(approval = TaskState.Approval("debug", "test")), "debug"))
        assertFalse(ReplyPolicy.canSubmit(TaskState(), ""))
    }
}
