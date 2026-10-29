package com.dsh.mobile.core

object ReplyPolicy {
    fun canSubmit(state: TaskState, approvalId: String): Boolean =
        approvalId.isNotBlank() && approvalId != "debug" &&
            state.phase == TaskState.Phase.Waiting && state.approval?.id == approvalId
}
