package com.dsh.mobile.core

import org.junit.Assert.*
import org.junit.Test

/**
 * Behaviour tests for the contract's navigation stage machine.
 *
 * The machine is the reason a stall is readable at all ("last=headers" instead of
 * "progress stuck at 10%"), so the rules being pinned here are the ones the
 * diagnostic summary silently depends on: forward-only ordering, one terminal
 * failure per navigation, and a `begin()` that really resets.
 *
 * Time is injected, so nothing here sleeps and nothing depends on wall clock.
 */
class NavStageMachineTest {

    private class Clock(var now: Long = 1_000L) {
        fun millis(): Long = now
        operator fun invoke(): Long = now
        fun advance(ms: Long) { now += ms }
    }

    // ---- ① forward only ----

    @Test fun stagesMayOnlyMoveForward() {
        val machine = NavStageMachine { 0L }
        machine.begin("loadUrl")
        assertTrue(machine.reach(NavStage.DNS))
        assertTrue(machine.reach(NavStage.TCP))
        assertTrue(machine.reach(NavStage.TLS))
        // A late callback arriving out of order must not rewind the label.
        assertFalse(machine.reach(NavStage.DNS))
        assertFalse(machine.reach(NavStage.TCP))
        assertEquals(NavStage.TLS, machine.current())
        // Same stage twice is not progress either.
        assertFalse(machine.reach(NavStage.TLS))
        assertEquals(NavStage.TLS, machine.current())
    }

    @Test fun idleAndFailedAreNotReachableAsProgress() {
        val machine = NavStageMachine { 0L }
        machine.begin()
        assertFalse(machine.reach(NavStage.IDLE))
        assertFalse(machine.reach(NavStage.FAILED))
        assertEquals(NavStage.NAV_REQ, machine.current())
    }

    // ---- ② no progress after failure ----

    @Test fun aFailedNavigationIgnoresFurtherProgress() {
        val machine = NavStageMachine { 0L }
        machine.begin()
        machine.reach(NavStage.HTML)
        machine.fail("no-response-headers", "timeout")
        assertFalse(machine.reach(NavStage.SCRIPT_EVAL))
        assertFalse(machine.reach(NavStage.READY))
        assertEquals(NavStage.FAILED, machine.current())
        assertEquals("no-response-headers", machine.failureReason())
        // A failed navigation is not "stalled": its clock stops.
        assertEquals(0L, machine.stalledFor(10_000L))
    }

    // ---- ③ first failure reason wins ----

    @Test fun onlyTheFirstFailureReasonIsKept() {
        val machine = NavStageMachine { 0L }
        machine.begin()
        machine.fail("dns", "UnknownHostException")
        machine.fail("tcp-timeout", "late cascade")
        machine.fail("timeout-90s")
        assertEquals("dns", machine.failureReason())
    }

    @Test fun failureTokensAreNormalisedToTheContractVocabulary() {
        val machine = NavStageMachine { 0L }
        machine.begin()
        machine.fail("  TCP Refused  ")
        assertEquals("tcp-refused", machine.failureReason())
        val blank = NavStageMachine { 0L }
        blank.begin()
        blank.fail("   ")
        assertEquals("unknown", blank.failureReason())
    }

    // ---- ④ begin resets ----

    @Test fun beginResetsFailureAndStage() {
        val clock = Clock(5_000L)
        val machine = NavStageMachine { clock.millis() }
        machine.begin("first")
        machine.reach(NavStage.HTML)
        machine.fail("dns")
        clock.advance(20_000)
        machine.begin("retry")
        assertEquals(NavStage.NAV_REQ, machine.current())
        assertNull(machine.failureReason())
        // The retry gets its own timeline: the 20 s of the old attempt is not carried over.
        clock.advance(1_500)
        assertEquals(1_500L, machine.stalledFor(clock.millis()))
        assertTrue(machine.reach(NavStage.DNS))
    }

    @Test fun beginAnnouncesNavReqToTheListener() {
        val seen = mutableListOf<String>()
        val machine = NavStageMachine { 0L }
        machine.install(object : NavStageMachine.Listener {
            override fun onStage(stage: NavStage, sinceStartMs: Long, detail: String) {
                seen += "${stage.label}/$sinceStartMs/$detail"
            }
        })
        machine.begin("pair-accept")
        machine.reach(NavStage.DNS, "resolving")
        assertEquals(listOf("nav-req/0/pair-accept", "dns/0/resolving"), seen)
        // An exception in the sink must never break the navigation path.
        machine.install(object : NavStageMachine.Listener {
            override fun onStage(stage: NavStage, sinceStartMs: Long, detail: String) { error("sink broke") }
        })
        assertTrue(machine.reach(NavStage.TCP))
        machine.fail("tcp-timeout")
        assertEquals(NavStage.FAILED, machine.current())
    }

    // ---- ⑤ stuck detection boundaries ----

    @Test fun stuckAtOrBelowHonoursCeilingAndThreshold() {
        val machine = NavStageMachine { 0L }
        machine.begin()
        machine.reach(NavStage.TCP)
        assertFalse(machine.isStuckAtOrBelow(NavStage.DNS, 2_000, nowMs = 3_000L))
        assertTrue(machine.isStuckAtOrBelow(NavStage.TCP, 2_000, nowMs = 3_000L))
        assertTrue(machine.isStuckAtOrBelow(NavStage.HEADERS, 2_000, nowMs = 3_000L))
        // Exactly at the threshold counts as stuck.
        assertTrue(machine.isStuckAtOrBelow(NavStage.TCP, 2_000, nowMs = 2_000L))
        // Below it, not yet.
        assertFalse(machine.isStuckAtOrBelow(NavStage.TCP, 2_000, nowMs = 1_999L))
        // Advending past the ceiling releases it; reaching a new stage restarts the timer.
        machine.reach(NavStage.TLS)
        assertFalse(machine.isStuckAtOrBelow(NavStage.TCP, 2_000, nowMs = 9_000L))
        assertTrue(machine.isStuckAtOrBelow(NavStage.TLS, 2_000, nowMs = 2_000L))
        assertFalse(machine.isStuckAtOrBelow(NavStage.TLS, 2_000, nowMs = 1_999L))
    }

    @Test fun idleAndFailedAreNeverStuck() {
        val idle = NavStageMachine { 0L }
        assertFalse(idle.isStuckAtOrBelow(NavStage.READY, 0, nowMs = 999_999L))
        assertEquals(0L, idle.stalledFor(999_999L))
        val failed = NavStageMachine { 0L }
        failed.begin()
        failed.fail("dns")
        assertFalse(failed.isStuckAtOrBelow(NavStage.READY, 0, nowMs = 999_999L))
        assertEquals(0L, failed.stalledFor(999_999L))
    }

    // ---- ⑥ injected clock drives the elapsed numbers ----

    @Test fun sinceStartMsComesFromTheInjectedClock() {
        var now = 100L
        val machine = NavStageMachine { now }
        val seen = mutableListOf<Pair<NavStage, Long>>()
        machine.install(object : NavStageMachine.Listener {
            override fun onStage(stage: NavStage, sinceStartMs: Long, detail: String) { seen += stage to sinceStartMs }
            override fun onFailed(reason: String, sinceStartMs: Long, detail: String) { seen += NavStage.FAILED to sinceStartMs }
        })
        machine.begin()
        now += 2_000
        machine.reach(NavStage.DNS)
        now += 1_000
        machine.reach(NavStage.HEADERS)
        now += 12_000
        machine.fail("no-response-headers")
        assertEquals(
            listOf(NavStage.NAV_REQ to 0L, NavStage.DNS to 2_000L, NavStage.HEADERS to 3_000L, NavStage.FAILED to 15_000L),
            seen,
        )
        // stalledFor is relative to the last move, not to the start. `begin` records
        // the last-move time, so a query earlier than that is clamped to 0.
        val later = NavStageMachine { now }
        later.begin()
        now += 500
        assertEquals(0L, later.stalledFor(15_000L))
        assertEquals(500L, later.stalledFor(now))
    }

    // ---- describe(): what the summary reads ----

    @Test fun describeMatchesTheContractExamples() {
        val machine = NavStageMachine { 0L }
        assertEquals("IDLE · not started", machine.describe())
        machine.begin()
        machine.reach(NavStage.HEADERS)
        machine.fail("no-response-headers")
        assertEquals("FAILED · last=headers · reason=no-response-headers", machine.describe())
        assertEquals(NavStage.HEADERS, machine.lastProgressStage())
        val ok = NavStageMachine { 0L }
        ok.begin()
        ok.reach(NavStage.READY)
        assertEquals("READY · last=ready", ok.describe())
        assertEquals(NavStage.READY, ok.lastProgressStage())
    }

    @Test fun lastProgressStageSurvivesOutOfOrderCallbacks() {
        val machine = NavStageMachine { 0L }
        machine.begin()
        machine.reach(NavStage.HTML)
        machine.reach(NavStage.SCRIPT_EVAL)
        machine.reach(NavStage.HTML)   // ignored, must not rewind `last`
        machine.fail("script-error")
        assertEquals(NavStage.SCRIPT_EVAL, machine.lastProgressStage())
        assertTrue(machine.describe().contains("last=script-eval"))
    }

    @Test fun progressStageFlagMatchesTheEnumOrder() {
        assertFalse(NavStage.IDLE.progressStage)
        assertFalse(NavStage.FAILED.progressStage)
        assertTrue(NavStage.NAV_REQ.progressStage)
        assertTrue(NavStage.READY.progressStage)
        // The summary matches on label text, so the labels are part of the contract.
        assertEquals("nav-req", NavStage.NAV_REQ.label)
        assertEquals("script-eval", NavStage.SCRIPT_EVAL.label)
        assertEquals("app-mount", NavStage.APP_MOUNT.label)
        assertEquals("ws-open", NavStage.WS_OPEN.label)
    }

    @Test fun sharedInstanceIsUsableFromThePlatformLayer() {
        // NavStages.machine is the process-wide sink the UI health loop reads.
        val shared = NavStages.machine
        shared.install(null)
        val before = shared.current()
        assertTrue(before == NavStage.IDLE || before.progressStage || before == NavStage.FAILED)
    }
}
