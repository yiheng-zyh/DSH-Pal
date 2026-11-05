package com.dsh.mobile.core

/** A service restart cannot open a session; only an Activity shown by the user can. */
class VpnSessionGate {
    private var open = false
    private var generation = 0L
    @Synchronized fun enter() { if (!open) { open = true; generation++ } }
    @Synchronized fun leave() { open = false; generation++ }
    @Synchronized fun ticket(): Long? = generation.takeIf { open }
    @Synchronized fun permits(ticket: Long): Boolean = open && ticket == generation
}
