package com.dsh.mobile

import android.app.Application
import android.util.Log
import com.dsh.mobile.core.SettingsStore
import com.dsh.mobile.island.IslandSupport

/**
 * Process-wide wiring. Deliberately minimal: the app has one long-lived
 * collaborator (the live service) and no dependency-injection framework, because
 * a two-screen app does not need one.
 */
class DshApp : Application() {

    lateinit var settings: SettingsStore
        private set

    override fun onCreate() {
        super.onCreate()
        com.dsh.mobile.core.RunLog.initialize(this)
        settings = SettingsStore(this)
        com.dsh.mobile.vpn.VpnController.initialize(this)
        observeNetwork()

        // Capability probing touches SystemUI over IPC; do it once, off the
        // critical path, and cache the answer for the settings screen.
        Thread {
            val cap = IslandSupport.probe(this)
            capability = cap
            Log.i(
                TAG,
                "device=${if (cap.isXiaomiDevice) "xiaomi" else "other"} " +
                    "level=${cap.level} protocol=${cap.protocolVersion} focus=${cap.hasFocusPermission}",
            )
        }.apply { isDaemon = true }.start()
    }

    private fun observeNetwork() { com.dsh.mobile.core.NetworkConnectivity.start(this) }

    companion object {
        private const val TAG = "DshApp"

        /** Last probed island capability, or null while the probe is running. */
        @Volatile
        var capability: IslandSupport.Capability? = null
            private set
    }
}
