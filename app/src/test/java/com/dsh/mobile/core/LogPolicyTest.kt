package com.dsh.mobile.core

import org.junit.Assert.*
import org.junit.Test

/**
 * Category mapping and the eviction rule.
 *
 * The quota engine is tested here rather than through [RunLog] because RunLog is
 * Android-bound (`SystemClock`, `Handler`, `Context`) and this whole decision is
 * pure arithmetic — it is exactly the part that must not be wrong, and it is what
 * makes "关键事件一定在导出里" provable instead of hoped for.
 */
class LogPolicyTest {
    @Test fun utf8RetentionNeverSplitsChineseOrEmoji() {
        val value="开始连接🙂\n".repeat(200)
        for(limit in 0..80) {
            val result=LogPolicy.tailUtf8(value,limit)
            assertTrue(result.toByteArray(Charsets.UTF_8).size<=limit)
            assertTrue(value.endsWith(result))
            assertFalse(result.contains('\uFFFD'))
        }
    }
    @Test fun asciiAndShortLogsAreRetainedExactly() {
        assertEquals("abc",LogPolicy.tailUtf8("abc",10))
        assertEquals("bc",LogPolicy.tailUtf8("abc",2))
        assertEquals("",LogPolicy.tailUtf8("abc",0))
    }
    @Test fun actualTagsReachTheirUserFacingCategory() {
        listOf("VPN","Pair","Connection","QrScan").forEach {assertEquals(LogCategory.CONNECTION,LogCategory.forTag(it))}
        listOf("Network","Probe").forEach {assertEquals(LogCategory.NETWORK,LogCategory.forTag(it))}
        listOf("File","Download","Keyboard","Motion","Reply").forEach {assertEquals(LogCategory.WEB,LogCategory.forTag(it))}
        assertEquals(LogCategory.NOTIFICATIONS,LogCategory.forTag("Service"))
        assertEquals(LogCategory.APP,LogCategory.forTag("Crash"))
    }

    // ---- the contract's new tags, mixed case as the call sites write them ----

    @Test fun newStageTagsLandInResources() {
        listOf("Stage", "Nav", "Dom", "Res", "Inject").forEach { tag ->
            assertEquals(tag, LogCategory.RESOURCES, LogCategory.forTag(tag))
        }
        // The chatty old RESOURCES tags keep their category but not the reservation.
        listOf("cache", "load", "webview", "WebView").forEach { tag ->
            assertEquals(tag, LogCategory.RESOURCES, LogCategory.forTag(tag))
        }
    }

    @Test fun newProbeTagsLandInNetwork() {
        listOf("Probe", "Diag", "NetEnv").forEach { tag ->
            assertEquals(tag, LogCategory.NETWORK, LogCategory.forTag(tag))
        }
        listOf("Network", "Health", "Net").forEach { tag ->
            assertEquals(tag, LogCategory.NETWORK, LogCategory.forTag(tag))
        }
    }

    @Test fun newPageTagsLandInWeb() {
        listOf("Console", "JSError", "MixedContent").forEach { tag ->
            assertEquals(tag, LogCategory.WEB, LogCategory.forTag(tag))
        }
        // The mapping is case-insensitive by construction, not by luck.
        assertEquals(LogCategory.WEB, LogCategory.forTag("mixedcontent"))
        assertEquals(LogCategory.WEB, LogCategory.forTag("JERROR".replace("ERROR", "SError")))
    }

    // ---- critical / non-critical ----

    @Test fun stageAndProbeAndPageTagsAreCritical() {
        listOf("Stage", "Nav", "Dom", "Res", "Inject", "Probe", "Diag", "NetEnv", "Console", "JSError", "MixedContent")
            .forEach { tag -> assertTrue(tag, LogPolicy.isCritical(tag)) }
    }

    @Test fun connectionCategoryIsCriticalWholesale() {
        listOf("Connection", "Pair", "VPN", "Permission", "QrScan").forEach { tag ->
            assertTrue(tag, LogPolicy.isCritical(tag))
        }
    }

    @Test fun heartbeatTagsAreNotCritical() {
        // Business requests and health polls must be the first to go.
        listOf("Network", "Health", "Net", "Load", "Cache", "WebView", "Bridge", "Task", "Crash", "App", "Update")
            .forEach { tag -> assertFalse(tag, LogPolicy.isCritical(tag)) }
    }

    // ---- the eviction rule ----

    /** The case-A guarantee: 100 heartbeat lines appended to a full buffer must not evict a Probe/Diag/Stage line. */
    @Test fun heartbeatsNeverEvictCriticalEvidence() {
        val slots = ArrayList<LogSlot>()
        // A saturated buffer: all critical kinds, one each, plus noise to fill.
        slots.add(LogSlot(true, LogCategory.NETWORK))          // Probe: dns-fail
        slots.add(LogSlot(true, LogCategory.RESOURCES))        // Stage: FAILED(dns)
        slots.add(LogSlot(true, LogCategory.WEB))              // JSError
        slots.add(LogSlot(true, LogCategory.CONNECTION))       // Pair
        while (slots.size < LogPolicy.MAX_ENTRIES) slots.add(LogSlot(false, LogCategory.NETWORK))
        // 100 more heartbeats arriving on top of a full buffer.
        repeat(100) { slots.add(LogSlot(false, LogCategory.NETWORK)) }

        val doomed = LogPolicy.evictionPlan(slots, LogPolicy.MAX_ENTRIES).toHashSet()
        val kept = slots.filterIndexed { index, _ -> index !in doomed }
        assertEquals(LogPolicy.MAX_ENTRIES, kept.size)
        for (index in 0 until 4) assertFalse("critical entry $index was evicted", doomed.contains(index))
        assertTrue(kept.count { it.critical } >= 4)
        // What was shed is exclusively noise.
        assertTrue(slots.filterIndexed { index, _ -> doomed.contains(index) }.all { !it.critical })
    }

    /** A long `Nav: pending` stream must give back its own lines before Probe/Diag are touched. */
    @Test fun navStormSacrificesItsOwnOldestLinesFirst() {
        val slots = ArrayList<LogSlot>()
        slots.add(LogSlot(true, LogCategory.NETWORK))                 // Probe — must survive
        slots.add(LogSlot(true, LogCategory.RESOURCES))               // Stage — must survive
        repeat(120) { slots.add(LogSlot(true, LogCategory.RESOURCES, repetitive = true)) } // 120 Nav lines
        while (slots.size < LogPolicy.MAX_ENTRIES) slots.add(LogSlot(false, LogCategory.NETWORK))
        repeat(200) { slots.add(LogSlot(true, LogCategory.RESOURCES, repetitive = true)) } // storm

        val doomed = LogPolicy.evictionPlan(slots, LogPolicy.MAX_ENTRIES).toHashSet()
        assertFalse("Probe line evicted by a Nav storm", doomed.contains(0))
        assertFalse("Stage line evicted by a Nav storm", doomed.contains(1))
        val kept = slots.filterIndexed { index, _ -> index !in doomed }
        assertTrue(kept.size <= LogPolicy.MAX_ENTRIES)
        // The repetitive stream is capped…
        assertEquals(LogPolicy.MAX_CRITICAL_NAV, kept.count { it.repetitive })
        // …and what was trimmed is strictly its OLDEST lines: every kept Nav line is
        // newer than every dropped one.
        val lastDroppedNav = slots.indices.filter { doomed.contains(it) && slots[it].repetitive }.maxOrNull() ?: -1
        val firstKeptNav = slots.indices.first { it !in doomed && slots[it].repetitive }
        assertTrue("trimmed the wrong end of the stream", lastDroppedNav < firstKeptNav)
    }

    /** A category inside its reservation keeps every critical line even when another category overran. */
    @Test fun categoriesInsideReservationAreNeverStarvedByAnotherCategory() {
        val slots = ArrayList<LogSlot>()
        // WEB far over its reservation, NETWORK comfortably inside it.
        repeat(LogPolicy.MAX_CRITICAL_WEB + 40) { slots.add(LogSlot(true, LogCategory.WEB)) }
        val networkStart = slots.size
        repeat(LogPolicy.MAX_CRITICAL_NETWORK - 10) { slots.add(LogSlot(true, LogCategory.NETWORK)) }

        val doomed = LogPolicy.evictionPlan(slots, LogPolicy.MAX_ENTRIES).toHashSet()
        val kept = slots.filterIndexed { index, _ -> index !in doomed }
        assertEquals(LogPolicy.MAX_CRITICAL_WEB + LogPolicy.MAX_CRITICAL_NETWORK - 10, kept.size)
        assertEquals(LogPolicy.MAX_CRITICAL_WEB, kept.count { it.category == LogCategory.WEB })
        assertEquals(LogPolicy.MAX_CRITICAL_NETWORK - 10, kept.count { it.category == LogCategory.NETWORK })
        // Nothing of the under-reserved category was touched, and the trimmed WEB
        // lines are the oldest ones.
        assertTrue((networkStart until slots.size).none { doomed.contains(it) })
        assertEquals(LogPolicy.MAX_CRITICAL_WEB + 40 - LogPolicy.MAX_CRITICAL_WEB, doomed.maxOrNull()?.plus(1))
    }

    @Test fun planIsEmptyWhenNothingOverruns() {
        val slots = List(10) { LogSlot(it % 2 == 0, LogCategory.NETWORK) }
        assertTrue(LogPolicy.evictionPlan(slots, LogPolicy.MAX_ENTRIES).isEmpty())
        assertTrue(LogPolicy.evictionPlan(emptyList(), LogPolicy.MAX_ENTRIES).isEmpty())
    }

    /** With no noise to shed, the reservations themselves are what bounds the buffer. */
    @Test fun allCriticalBufferShrinksToTheCategoryReservation() {
        val slots = List(LogPolicy.MAX_ENTRIES + 5) { LogSlot(true, LogCategory.CONNECTION) }
        val doomed = LogPolicy.evictionPlan(slots, LogPolicy.MAX_ENTRIES)
        assertEquals(slots.size - LogPolicy.MAX_CRITICAL_CONNECTION, doomed.size)
        // Oldest first, so the newest evidence is what survives.
        assertEquals((0 until doomed.size).toList(), doomed)
        assertEquals(LogPolicy.MAX_CRITICAL_CONNECTION, slots.size - doomed.size)
    }

    /** When only noise is critical-capable and everything is noise, the hard cap still holds. */
    @Test fun hardCapDropsTheOldestEntries() {
        val slots = List(LogPolicy.MAX_ENTRIES + 5) { LogSlot(false, LogCategory.APP) }
        val doomed = LogPolicy.evictionPlan(slots, LogPolicy.MAX_ENTRIES)
        assertEquals(5, doomed.size)
        assertEquals(listOf(0, 1, 2, 3, 4), doomed)
        assertEquals(LogPolicy.MAX_ENTRIES, slots.size - doomed.size)
    }

    @Test fun reservationsFitInsideTheBuffer() {
        // The four critical reservations must not promise more room than the buffer has.
        val total = LogPolicy.MAX_CRITICAL_RESOURCES + LogPolicy.MAX_CRITICAL_NETWORK +
            LogPolicy.MAX_CRITICAL_WEB + LogPolicy.MAX_CRITICAL_CONNECTION
        assertTrue("reservations $total > cap ${LogPolicy.MAX_ENTRIES}", total <= LogPolicy.MAX_ENTRIES)
        assertTrue(LogPolicy.MAX_CRITICAL_NAV <= LogPolicy.MAX_CRITICAL_RESOURCES)
        assertEquals(600, LogPolicy.MAX_ENTRIES)
        assertTrue(LogPolicy.MAX_FILE_BYTES > LogPolicy.MAX_ENTRIES * 200)
        assertEquals(512 * 1024, LogPolicy.MAX_EXPORT_BYTES)
    }
}
