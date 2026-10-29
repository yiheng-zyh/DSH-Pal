package com.dsh.mobile.core

/** Elapsed realtime only; a cancelled gesture never arms the gate. */
class ExitBackGate(private val windowMs: Long = 2500) {
    private var armedAt: Long? = null
    fun reset() { armedAt = null }
    fun request(now: Long): Boolean {
        val previous = armedAt
        if (previous != null && now >= previous && now - previous <= windowMs) {
            reset()
            return true
        }
        armedAt = now
        return false
    }
}
