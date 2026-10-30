package com.dsh.mobile.core

/** A deliberate outward drag, not ordinary edge snapping, requests peek mode. */
object OverlayDocking {
    fun requestsPeek(enabled: Boolean, screenWidth: Float, endX: Float, deltaX: Float, density: Float): Boolean {
        if(!enabled || screenWidth <= 0 || density <= 0 || !endX.isFinite() || !deltaX.isFinite()) return false
        val edge=24*density
        return endX<=edge && deltaX<=-24*density || endX>=screenWidth-edge && deltaX>=24*density
    }
}
