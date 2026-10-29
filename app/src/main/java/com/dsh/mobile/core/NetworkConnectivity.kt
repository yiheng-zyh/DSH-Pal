package com.dsh.mobile.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NetworkSnapshot(val available: Boolean = false, val validated: Boolean = false, val transport: String = "无网络", val captive: Boolean = false, val revision: Long = 0)
object NetworkConnectivity {
    private val mutable = MutableStateFlow(NetworkSnapshot())
    val state = mutable.asStateFlow()
    val visible = MutableStateFlow(false)
    private var current: Network? = null
    private var started = false
    @Synchronized fun start(context: Context) {
        if (started) return
        started = true
        val manager = context.getSystemService(ConnectivityManager::class.java)
        fun publish(network: Network?, cap: NetworkCapabilities?) {
            val transport = when {
                cap?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true -> "VPN"
                cap?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi-Fi"
                cap?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "移动网络"
                cap?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "以太网"
                network != null -> "网络"
                else -> "无网络"
            }
            val old = mutable.value
            val next = NetworkSnapshot(network != null, cap?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true, transport, cap?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true, old.revision)
            if (next != old || current != network) {
                current = network
                mutable.value = next.copy(revision = old.revision + 1)
                RunLog.info("Network", "route=$transport available=${next.available} validated=${next.validated} captive=${next.captive} revision=${old.revision+1}")
            }
        }
        publish(manager.activeNetwork, manager.activeNetwork?.let(manager::getNetworkCapabilities))
        runCatching { manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { publish(network, null) }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { if(current == network) publish(network, capabilities) }
            override fun onLost(network: Network) { if (current == network) publish(null, null) }
        }) }.onFailure { RunLog.warn("Network", "monitor unavailable ${it.javaClass.simpleName}") }
    }
}
