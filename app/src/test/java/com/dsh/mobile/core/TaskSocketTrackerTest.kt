package com.dsh.mobile.core
import org.junit.Assert.*
import org.junit.Test
class TaskSocketTrackerTest {
    @Test fun closingReplacedSocketDoesNotDisconnectTheActiveOne() {
        val tracker = TaskSocketTracker()
        assertTrue(tracker.update("old", true))
        assertTrue(tracker.update("new", true))
        assertTrue(tracker.update("old", false))
        assertFalse(tracker.update("new", false))
    }
    @Test fun duplicateAndUnknownCloseAreHarmless() {
        val tracker = TaskSocketTracker()
        tracker.update("one", true); tracker.update("one", true)
        assertTrue(tracker.update("unknown", false))
        assertFalse(tracker.update("one", false))
    }
    @Test fun navigationDiscardsPreviousDocumentState() {
        val tracker = TaskSocketTracker()
        tracker.update("old", true); tracker.reset()
        assertFalse(tracker.update("old", false))
        assertTrue(tracker.update("new", true))
    }
}
