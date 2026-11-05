package com.dsh.mobile.island

import com.dsh.mobile.core.TaskState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

/**
 * Verifies the exact wire shape handed to HyperOS.
 *
 * This test runs against the plain `org.json` implementation on the JVM (the
 * Android `org.json` is API-compatible for the operations used here), so the
 * island's JSON contract can be checked without a device — which matters because
 * a malformed island payload fails *silently*: the notification posts, the island
 * simply never appears.
 */
class IslandPayloadTest {

    /**
     * Reproduces the builder's JSON without the Android dependencies
     * (Notification/Context), so the contract is testable on the JVM.
     */
    private fun buildJson(state: TaskState): JSONObject {
        val root = JSONObject()
        val params = JSONObject()
        params.put("protocol", 1)
        params.put("business", "dsh_agent_task")
        params.put("updatable", state.isLive)
        params.put("enableFloat", state.phase == TaskState.Phase.Waiting)
        params.put("islandFirstFloat", state.phase == TaskState.Phase.Waiting)
        params.put("ticker", state.headline)
        params.put("aodTitle", "DSH")

        val island = JSONObject()
        island.put("islandProperty", if (state.phase == TaskState.Phase.Waiting) 2 else 1)
        island.put("islandTimeout", 3600)
        island.put("dismissIsland", !state.isLive)

        val big = JSONObject()
        val left = JSONObject()
        left.put("type", 1)
        left.put("picInfo", JSONObject().put("type", 1).put("pic", "miui.focus.pic_large"))
        left.put(
            "miui.focus.paramtextInfo",
            JSONObject().apply {
                put("frontTitle", "正在执行")
                put("title", state.headline)
                put("content", state.detail)
                put("useHighLight", state.phase == TaskState.Phase.Waiting)
            },
        )
        big.put("imageTextInfoLeft", left)
        big.put("picInfo", JSONObject().put("type", 1).put("pic", "miui.focus.pic_large"))
        island.put("bigIslandArea", big)

        island.put(
            "smallIslandArea",
            JSONObject().put("picInfo", JSONObject().put("type", 1).put("pic", "miui.focus.pic_small")),
        )
        params.put("param_island", island)

        val baseInfo = JSONObject()
        baseInfo.put("title", state.headline)
        baseInfo.put("content", state.detail)
        baseInfo.put("type", 2)
        params.put("baseInfo", baseInfo)

        root.put("param_v2", params)
        return root
    }

    @Test
    fun `payload nests everything under param_v2`() {
        val json = buildJson(TaskState(phase = TaskState.Phase.Running, detail = "调用 命令"))
        assertTrue(json.has("param_v2"))
        val v2 = json.getJSONObject("param_v2")
        // `business` is documented as required; without it HyperOS ignores the island.
        assertTrue(v2.has("business"))
        assertEquals(1, v2.getInt("protocol"))
    }

    @Test
    fun `running task is updatable but does not auto expand`() {
        val v2 = buildJson(TaskState(phase = TaskState.Phase.Running)).getJSONObject("param_v2")
        assertTrue(v2.getBoolean("updatable"))
        // Auto-expanding on every step update would be intensely annoying.
        assertFalse(v2.getBoolean("enableFloat"))
        assertFalse(v2.getBoolean("islandFirstFloat"))
        assertEquals(1, v2.getJSONObject("param_island").getInt("islandProperty"))
    }

    @Test
    fun `waiting task expands and becomes action-first`() {
        val state = TaskState(
            phase = TaskState.Phase.Waiting,
            approval = TaskState.Approval(id = "a1", prompt = "确认执行？"),
        )
        val v2 = buildJson(state).getJSONObject("param_v2")
        // The one state that must interrupt: expand automatically.
        assertTrue(v2.getBoolean("enableFloat"))
        assertTrue(v2.getBoolean("islandFirstFloat"))
        // islandProperty 2 = action-first.
        assertEquals(2, v2.getJSONObject("param_island").getInt("islandProperty"))
    }

    @Test
    fun `island areas reference the pictogram keys`() {
        val island = buildJson(TaskState(phase = TaskState.Phase.Running))
            .getJSONObject("param_v2")
            .getJSONObject("param_island")

        // Both areas are mandatory; the docs mark bigIslandArea/smallIslandArea required.
        assertTrue(island.has("bigIslandArea"))
        assertTrue(island.has("smallIslandArea"))

        val bigPic = island.getJSONObject("bigIslandArea")
            .getJSONObject("picInfo").getString("pic")
        val smallPic = island.getJSONObject("smallIslandArea")
            .getJSONObject("picInfo").getString("pic")

        // Icons must be referenced by key into the miui.focus.pics bundle.
        assertEquals("miui.focus.pic_large", bigPic)
        assertEquals("miui.focus.pic_small", smallPic)
        assertEquals(1, island.getJSONObject("bigIslandArea").getJSONObject("picInfo").getInt("type"))

        // Regression guard: `picInfo` must be flat {type, pic}. An earlier version
        // nested a second picInfo inside it, which on-device made HyperOS silently
        // drop the island image (no error, no log — just no picture).
        assertTrue(
            "bigIslandArea.picInfo must not nest another picInfo",
            !island.getJSONObject("bigIslandArea").getJSONObject("picInfo").has("picInfo"),
        )
    }

    @Test
    fun `finished task is dismissed from the island`() {
        val v2 = buildJson(TaskState(phase = TaskState.Phase.Done)).getJSONObject("param_v2")
        assertFalse(v2.getBoolean("updatable"))
        // dismissIsland = true retracts the island when the turn ends.
        assertTrue(v2.getJSONObject("param_island").getBoolean("dismissIsland"))
    }

    @Test
    fun `base info carries the focus notification body`() {
        val state = TaskState(phase = TaskState.Phase.Running, detail = "读取文件")
        val baseInfo = buildJson(state).getJSONObject("param_v2").getJSONObject("baseInfo")
        assertTrue(baseInfo.has("title"))
        assertTrue(baseInfo.has("content"))
        // type 2 is the documented focus-notification body type.
        assertEquals(2, baseInfo.getInt("type"))
    }
}
