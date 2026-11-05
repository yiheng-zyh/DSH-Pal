package com.dsh.mobile.core
import org.junit.Assert.*
import org.junit.Test
class OverlayDockingTest {
    @Test fun outwardDragAtEitherWallPeeks() {
        assertTrue(OverlayDocking.requestsPeek(true,1080f,0f,-140f,3f))
        assertTrue(OverlayDocking.requestsPeek(true,1080f,1070f,140f,3f))
    }
    @Test fun ordinarySnapOrVerticalDragDoesNotHide() {
        assertFalse(OverlayDocking.requestsPeek(true,1080f,30f,0f,3f))
        assertFalse(OverlayDocking.requestsPeek(true,1080f,300f,-140f,3f))
    }
    @Test fun pullingOutOfWallReveals() {
        assertFalse(OverlayDocking.requestsPeek(true,1080f,30f,100f,3f))
        assertFalse(OverlayDocking.requestsPeek(true,1080f,1040f,-100f,3f))
    }
    @Test fun disabledAndInvalidInputsNeverRequestPeek() {
        assertFalse(OverlayDocking.requestsPeek(false,1080f,0f,-140f,3f))
        assertFalse(OverlayDocking.requestsPeek(true,1080f,Float.NaN,-140f,3f))
        assertFalse(OverlayDocking.requestsPeek(true,0f,0f,-140f,3f))
    }
}
