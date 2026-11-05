package com.dsh.mobile.core

/** A reconnect can open its replacement before the previous mux closes. */
class TaskSocketTracker {
    private val open = mutableSetOf<String>()
    fun update(id: String, connected: Boolean): Boolean {
        if (connected) open.add(id) else open.remove(id)
        return open.isNotEmpty()
    }
    fun reset() { open.clear() }
}
