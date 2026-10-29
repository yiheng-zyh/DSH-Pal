package com.dsh.mobile.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the event → state projection.
 *
 * These run on the JVM with no Android dependency, which is the point of keeping
 * [EventReducer] a pure function: the highest-risk logic (turning a stream of
 * loosely-specified JSON into the island's state) is testable in milliseconds.
 */
class EventReducerTest {

    private val now = 1_700_000_000_000L

    private fun reduceAll(vararg events: String, from: TaskState = TaskState()): TaskState =
        events.fold(from) { acc, raw ->
            val obj = Json.parseToJsonElement(raw).jsonObject
            EventReducer.reduce(acc, obj, now)
        }

    /**
     * Build a mux item in the shape the harness actually sends.
     *
     * The socket is a Typert multiplexer, so a session event is nested three
     * deep: item → value → event. `data` is the per-type payload.
     */
    private fun event(type: String, data: String = "{}", seq: Int = 1): String =
        """{"type":"item","streamId":"test-stream","value":{"type":"event",""" +
            """"event":{"type":"$type","seq":$seq,"time":$now,"data":$data}}}"""

    /** A frame the reducer must ignore: a projection update, not a session event. */
    private fun projection(): String =
        """{"type":"item","streamId":"proj","value":{"type":"projection",""" +
            """"sessionId":"s1","key":"sessionStats","value":{"turns":1}}}"""

    // ---- lifecycle -----------------------------------------------------------

    @Test
    fun `turn start enters thinking`() {
        val state = reduceAll(event("turn/start"))
        assertEquals(TaskState.Phase.Thinking, state.phase)
        assertTrue(state.isLive)
        assertEquals("思考中", state.headline)
    }

    @Test
    fun `turn end completes`() {
        val state = reduceAll(
            event("turn/start"),
            event("step/start", """{"turn":1,"step":1}"""),
            event("turn/end"),
        )
        assertEquals(TaskState.Phase.Done, state.phase)
        assertFalse(state.isLive)
    }

    @Test
    fun `turn end with error status fails`() {
        val state = reduceAll(
            event("turn/start"),
            event("turn/end", """{"reason":{"kind":"failed"}}"""),
        )
        assertEquals(TaskState.Phase.Failed, state.phase)
    }

    // ---- steps and tools -----------------------------------------------------

    @Test
    fun `step start records the live step number`() {
        val state = reduceAll(
            event("turn/start"),
            event("step/start", """{"turn":1,"step":2}"""),
        )
        assertEquals(TaskState.Phase.Running, state.phase)
        assertEquals(2, state.step)
        // The harness sends no step total, so progress stays unknown until a
        // todo list supplies a real denominator.
        assertNull(state.progress)
    }

    @Test
    fun `tool call localizes the tool name`() {
        val state = reduceAll(
            event("turn/start"),
            event("tool/call", """{"name":"pwsh"}"""),
        )
        assertEquals("pwsh", state.tool)
        assertEquals("调用 命令", state.detail)
    }

    @Test
    fun `tool result returns to thinking`() {
        val state = reduceAll(
            event("turn/start"),
            event("tool/call", """{"name":"read"}"""),
            event("tool/result", """{"ok":true}"""),
        )
        assertEquals(TaskState.Phase.Thinking, state.phase)
        assertEquals("", state.tool)
    }

    // ---- approval ------------------------------------------------------------

    @Test
    fun `approval asked wins and is actionable`() {
        val state = reduceAll(
            event("turn/start"),
            event("step/start", """{"turn":1,"step":1}"""),
            event("approval/asked", """{"id":"a1","prompt":"允许执行命令？","tool":"pwsh"}"""),
        )
        assertEquals(TaskState.Phase.Waiting, state.phase)
        assertNotNull(state.approval)
        assertEquals("允许执行命令？", state.approval!!.prompt)
        assertEquals("等待你的确认", state.headline)
    }

    @Test
    fun `approval survives a completed tool result`() {
        // A tool finishing must not clear a pending approval: the agent is still
        // blocked on the user, and dropping the state would hide the prompt.
        val state = reduceAll(
            event("turn/start"),
            event("approval/asked", """{"id":"a1","prompt":"确认？"}"""),
            event("tool/result", "{}"),
        )
        assertEquals(TaskState.Phase.Waiting, state.phase)
    }

    @Test
    fun `approval decided clears the approval`() {
        val state = reduceAll(
            event("turn/start"),
            event("approval/asked", """{"id":"a1","prompt":"确认？"}"""),
            event("approval/decided", """{"approved":true}"""),
        )
        assertNull(state.approval)
        assertEquals(TaskState.Phase.Running, state.phase)
    }

    @Test
    fun `approval rejected is reported`() {
        val state = reduceAll(
            event("turn/start"),
            event("approval/asked", """{"id":"a1","prompt":"确认？"}"""),
            event("approval/decided", """{"approved":false}"""),
        )
        assertEquals("已拒绝", state.detail)
    }

    // ---- todos ---------------------------------------------------------------

    @Test
    fun `todo write drives the progress denominator`() {
        val state = reduceAll(
            event("turn/start"),
            event(
                "todo/write",
                """{"todos":[{"status":"completed"},{"status":"completed"},{"status":"pending"}]}""",
            ),
        )
        assertEquals(2, state.todosDone)
        assertEquals(3, state.todosTotal)
        assertEquals(0.666f, state.progress!!, 0.01f)
    }

    // ---- resilience ----------------------------------------------------------

    @Test
    fun `unknown event type is ignored`() {
        val base = TaskState(title = "原任务", phase = TaskState.Phase.Running)
        val state = reduceAll(event("some/future/event", """{"x":1}"""), from = base)
        assertEquals(base, state)
    }

    @Test
    fun `malformed payload does not throw and keeps previous state`() {
        val base = TaskState(title = "原任务", phase = TaskState.Phase.Running)
        // payload is a string, not an object: every accessor must tolerate it.
        val state = reduceAll("""{"type":"tool/call","payload":"oops"}""", from = base)
        assertEquals(TaskState.Phase.Running, state.phase)
    }

    @Test
    fun `session title updates the headline source`() {
        val state = reduceAll(
            event("turn/start"),
            event("session/title", """{"title":"修复登录 bug"}"""),
        )
        assertEquals("修复登录 bug", state.title)
    }

    @Test
    fun `human tool names cover the common set`() {
        assertEquals("命令", EventReducer.humanTool("bash"))
        assertEquals("读取文件", EventReducer.humanTool("read"))
        assertEquals("子代理", EventReducer.humanTool("subagent"))
        // Unknown names pass through rather than becoming "未知".
        assertEquals("some_new_tool", EventReducer.humanTool("some_new_tool"))
    }

    // ---- progress edge cases -------------------------------------------------

    @Test
    fun `progress is null without any denominator`() {
        val state = reduceAll(event("turn/start"))
        assertNull(state.progress)
    }

    @Test
    fun `progress prefers todos over steps`() {
        val state = reduceAll(
            event("turn/start"),
            event("step/start", """{"turn":1,"step":1}"""),
            event("todo/write", """{"todos":[{"status":"completed"},{"status":"pending"}]}"""),
        )
        assertEquals(0.5f, state.progress!!, 0.001f)
    }

    // ---- real captured frames (regression) -----------------------------------
    //
    // These are verbatim frames captured from a Redmi running HyperOS on
    // 2026-10-24 while a real turn executed. They pin the actual wire shape: a
    // reducer that only understands {type, payload} passes every hand-written
    // test above and still produces nothing on a device.

    @Test
    fun `a full captured turn drives the island to done`() {
        val state = reduceAll(
            // The user's message arriving.
            """{"type":"item","streamId":"a2c1fa1f-e1e0-45f0-b365-1b34dda7d24b","value":{"type":"event",""" +
                """"event":{"type":"turn/start","seq":6,"time":1792807077113,"data":{"turn":1}}}}""",
            """{"type":"item","streamId":"a2c1fa1f-e1e0-45f0-b365-1b34dda7d24b","value":{"type":"event",""" +
                """"event":{"type":"step/start","seq":9,"time":1792807077175,"data":{"turn":1,"step":1}}}}""",
            """{"type":"item","streamId":"a2c1fa1f-e1e0-45f0-b365-1b34dda7d24b","value":{"type":"event",""" +
                """"event":{"type":"step/end","seq":22,"time":1792807078459,"data":{"turn":1,"step":1}}}}""",
            """{"type":"item","streamId":"a2c1fa1f-e1e0-45f0-b365-1b34dda7d24b","value":{"type":"event",""" +
                """"event":{"type":"turn/end","seq":23,"time":1792807078459,""" +
                """"data":{"turn":1,"reason":{"kind":"completed"}}}}}""",
        )
        assertEquals(TaskState.Phase.Done, state.phase)
        assertEquals("已完成", state.detail)
    }

    @Test
    fun `a captured turn start puts the island in thinking`() {
        val state = reduceAll(
            """{"type":"item","streamId":"a2c1fa1f-e1e0-45f0-b365-1b34dda7d24b","value":{"type":"event",""" +
                """"event":{"type":"turn/start","seq":6,"time":1792807077113,"data":{"turn":1}}}}""",
        )
        assertEquals(TaskState.Phase.Thinking, state.phase)
        assertTrue(state.isLive)
    }

    @Test
    fun `projection frames do not disturb the island`() {
        // The socket is dominated by projection/rows/assistant-stream items; they
        // must never be mistaken for task events.
        val base = TaskState(phase = TaskState.Phase.Running, step = 3, tool = "pwsh")
        val state = reduceAll(
            projection(),
            """{"type":"item","streamId":"x","value":{"type":"rows","jobs":[]}}""",
            """{"type":"item","streamId":"x","value":{"type":"assistant-stream",""" +
                """"frame":{"type":"start","attemptId":"a:1","turn":1,"step":1}}}""",
            from = base,
        )
        assertEquals(base, state)
    }

    @Test
    fun `tool names arrive on the real path`() {
        val state = reduceAll(
            event("turn/start"),
            event("tool/call", """{"name":"web_search","callId":"c1"}"""),
        )
        assertEquals("web_search", state.tool)
        assertEquals("调用 联网搜索", state.detail)
    }
}
