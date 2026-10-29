package com.dsh.mobile.core

/**
 * The live execution state of one DSH agent turn, derived from the harness
 * session event stream and rendered by the island and the notification.
 *
 * The app never invents progress: every field here is a direct projection of
 * events the harness already emits (`turn/start`, `step/start`, `tool/call`,
 * `tool/result`, `approval/asked`, `todo/write`, `turn/end`). See
 * [docs/ARCHITECTURE.md](../../../../../../docs/ARCHITECTURE.md) for the full
 * event-to-UI mapping.
 */
data class TaskState(
    /** Session the work belongs to; stable across turns. */
    val sessionId: String = "",
    /** Human title of the session/task, shown as the island headline. */
    val title: String = "",
    /** Current phase of the turn. */
    val phase: Phase = Phase.Idle,
    /** What the agent is doing right now, already localized for display. */
    val detail: String = "",
    /** Zero-based index of the current step within the turn. */
    val step: Int = 0,
    /** Total steps the agent planned, when it declared them. */
    val stepTotal: Int = 0,
    /** Tool currently running, if any (e.g. `pwsh`, `read`, `subagent`). */
    val tool: String = "",
    /** Pending approval prompt, when the harness is blocked on the user. */
    val approval: Approval? = null,
    /** Most recent todo list items, used for the progress bar denominator. */
    val todosDone: Int = 0,
    val todosTotal: Int = 0,
    /** Wall-clock time the current turn started (epoch millis). */
    val startedAt: Long = 0L,
    /** Wall-clock time of the last accepted event (epoch millis). */
    val updatedAt: Long = 0L,
    /** Bounded public progress only: never reasoning, command bodies or results. */
    val noticeLines: List<NoticeLine> = emptyList(),
    val hasPublicOutput: Boolean = false,
) {
    data class NoticeLine(val text: String, val action: Boolean = false)
    enum class Phase {
        /** Nothing running; the island should retract. */
        Idle,

        /** A turn just started and the model is thinking. */
        Thinking,

        /** A step is executing; [tool] may or may not be set. */
        Running,

        /** Blocked on a user approval or question — the highest-priority state. */
        Waiting,

        /** The turn finished successfully. */
        Done,

        /** The turn ended in an error. */
        Failed,
    }

    /** One approval the harness is blocked on. */
    data class Approval(
        val id: String,
        val prompt: String,
        val tool: String = "",
    )

    /** Whether the island should be visible at all. */
    val isLive: Boolean
        get() = phase == Phase.Thinking || phase == Phase.Running || phase == Phase.Waiting

    /** Fractional progress in 0..1, or null when no denominator is known. */
    val progress: Float?
        get() = when {
            todosTotal > 0 -> (todosDone.toFloat() / todosTotal).coerceIn(0f, 1f)
            stepTotal > 0 -> (step.toFloat() / stepTotal).coerceIn(0f, 1f)
            else -> null
        }

    /**
     * One-line description used by the island's compact pill and the
     * notification's content line.
     */
    val headline: String
        get() = when (phase) {
            Phase.Idle -> ""
            Phase.Thinking -> "思考中"
            Phase.Waiting -> "等待你的确认"
            Phase.Done -> "已完成"
            Phase.Failed -> "执行失败"
            Phase.Running -> detail.ifBlank { "执行中" }
        }
}
