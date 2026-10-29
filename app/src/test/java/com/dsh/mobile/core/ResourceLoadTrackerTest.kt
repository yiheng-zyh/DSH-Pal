package com.dsh.mobile.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The boot screen used to show only a navigation percentage, which stalls while a
 * multi-megabyte plugin bundle streams in. These cases pin the byte accounting
 * that replaces it, including the rule that no percentage is invented when the
 * total is unknown.
 */
class ResourceLoadTrackerTest {

    @Test
    fun `starts empty without a fabricated total`() {
        val t = ResourceLoadTracker()
        assertEquals(0L, t.loadedBytes)
        assertNull(t.fraction)
        assertNull(ResourceLoadText.compact(t.snapshot()))
        assertNull(ResourceLoadText.detail(t.snapshot()))
    }

    @Test
    fun `running totals from one resource become deltas not sums`() {
        val t = ResourceLoadTracker()
        t.onStart()
        t.onProgress(0, 512_000)
        t.onProgress(512_000, 1_500_000)
        assertEquals(1_500_000L, t.inFlightBytes)
    }

    @Test
    fun `a repeated progress report adds nothing`() {
        val t = ResourceLoadTracker()
        t.onStart()
        t.onProgress(0, 700_000)
        t.onProgress(700_000, 700_000)
        assertEquals(700_000L, t.inFlightBytes)
    }

    @Test
    fun `finishing moves in-flight bytes into loaded`() {
        val t = ResourceLoadTracker()
        t.onStart()
        t.onProgress(0, 900_000)
        t.onFinished(1_200_000, counted = 900_000)
        assertEquals(0L, t.inFlightBytes)
        assertEquals(1_200_000L, t.loadedBytes)
        assertEquals(1, t.completedCount)
        assertEquals(0, t.activeCount)
    }

    @Test
    fun `a failed transfer keeps the bytes that did arrive`() {
        val t = ResourceLoadTracker()
        t.onStart()
        t.onProgress(0, 400_000)
        t.onFinished(0, counted = 400_000, failed = true)
        // Bytes already reported were really received before the socket failed;
        // dropping them made the on-screen counter run backwards.
        assertEquals(0L, t.inFlightBytes)
        assertEquals(400_000L, t.loadedBytes)
        assertEquals(1, t.failedCount)
    }

    @Test
    fun `a failure after a reload never reduces the visible total`() {
        val t = ResourceLoadTracker()
        t.onStart()
        t.onProgress(0, 5_000_000)
        t.onFinished(5_000_000, counted = 5_000_000)
        val afterSuccess = t.loadedBytes
        t.onStart()
        t.onProgress(0, 2_000_000)
        t.onFinished(0, counted = 2_000_000, failed = true)
        assertTrue("计数不应倒退: $afterSuccess -> ${t.loadedBytes}", t.loadedBytes >= afterSuccess)
    }

    @Test
    fun `declared lengths sum into a real denominator`() {
        val t = ResourceLoadTracker()
        t.onDeclaredSize(4_000_000)
        t.onDeclaredSize(3_600_000)
        assertEquals(7_600_000L, t.totalBytes)
        assertEquals(7_600_000L, t.pendingBytes)
    }

    @Test
    fun `fraction needs a total and never overflows one`() {
        val t = ResourceLoadTracker()
        t.onStart()
        t.onProgress(0, 1_000_000)
        assertNull(t.fraction)
        t.onDeclaredSize(4_000_000)
        assertEquals(0.25f, t.fraction!!, 0.001f)
        // A resource that outgrows the advertised sum must not report >100%.
        t.onProgress(1_000_000, 9_000_000)
        assertEquals(1.0f, t.fraction!!, 0.001f)
    }

    @Test
    fun `reused resources count as present but not as download`() {
        val t = ResourceLoadTracker()
        t.onReused(1_100_000)
        assertEquals(1_100_000L, t.reusedBytes)
        assertEquals(0L, t.loadedBytes)
        assertEquals(1, t.completedCount)
        assertTrue(t.hasActivity)
    }

    @Test
    fun `reset clears a navigation`() {
        val t = ResourceLoadTracker()
        t.onDeclaredSize(5_000_000)
        t.onStart()
        t.onProgress(0, 2_000_000)
        t.onFinished(2_000_000, counted = 2_000_000)
        t.reset()
        assertEquals(0L, t.loadedBytes)
        // UNKNOWN, not 0: "no total advertised" and "a zero-byte total" are
        // different states, and only the former may suppress a percentage.
        assertEquals(ResourceLoadTracker.UNKNOWN, t.totalBytes)
        assertNull(t.fraction)
        assertFalse(t.hasActivity)
    }

    @Test
    fun `compact shows loaded against total for the strip`() {
        val t = ResourceLoadTracker()
        t.onDeclaredSize(8_000_000)
        t.onStart()
        t.onProgress(0, 3_355_443)
        val text = ResourceLoadText.compact(t.snapshot())
        assertNotNull(text)
        assertTrue("应含已加载体积: $text", text!!.contains("3.2 MB"))
        assertTrue("应含斜杠与总量: $text", text.contains("/"))
    }

    @Test
    fun `compact without a total never claims a percentage`() {
        val t = ResourceLoadTracker()
        t.onStart()
        t.onProgress(0, 7_600_000)
        val text = ResourceLoadText.compact(t.snapshot())!!
        assertTrue("应说明已加载: $text", text.contains("已加载"))
        assertFalse("不应编造百分比: $text", text.contains("%"))
    }

    @Test
    fun `detail reports counts and reuse`() {
        val t = ResourceLoadTracker()
        t.onDeclaredSize(8_000_000)
        t.onStart()
        t.onProgress(0, 4_000_000)
        t.onFinished(4_000_000, counted = 4_000_000)
        t.onReused(1_048_576)
        t.onStart()
        t.onFinished(0, failed = true)
        val detail = ResourceLoadText.detail(t.snapshot())!!
        assertTrue("应含完成数: $detail", detail.contains("完成"))
        assertTrue("应含复用: $detail", detail.contains("复用"))
        assertTrue("应含失败: $detail", detail.contains("失败 1"))
    }

    @Test
    fun `size formatting is human readable`() {
        assertEquals("96 B", ResourceLoad.size(96))
        assertEquals("1 KB", ResourceLoad.size(1024))
        assertEquals("1.5 MB", ResourceLoad.size(1_572_864))
    }
}
