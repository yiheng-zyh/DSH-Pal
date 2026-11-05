package com.dsh.mobile.vpn

/** Interface UP only means Android installed routes, not that a peer answered. */
object WireGuardReadiness {
    fun hasRecentHandshake(handshakeAt:Long,now:Long):Boolean =
        handshakeAt>0 && now>=handshakeAt && now-handshakeAt<=180_000
}
