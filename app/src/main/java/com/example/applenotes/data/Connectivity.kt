package com.example.applenotes.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whether the device currently has a network that can reach the internet. */
interface ConnectivityMonitor {
    val online: StateFlow<Boolean>
}

/** For tests and previews: always online. */
object AlwaysOnline : ConnectivityMonitor {
    override val online: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()
}

/**
 * [ConnectivityMonitor] backed by the default-network callback (needs
 * ACCESS_NETWORK_STATE, already declared). "Online" means the default
 * network has NET_CAPABILITY_INTERNET; we deliberately don't require
 * VALIDATED, which is false on some captive / filtered networks that can
 * still reach iCloud. Registered once for the process (see [AppGraph]).
 */
class AndroidConnectivityMonitor(context: Context) : ConnectivityMonitor {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val state = MutableStateFlow(currentlyOnline())
    override val online: StateFlow<Boolean> = state.asStateFlow()

    init {
        runCatching {
            cm?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    state.value = currentlyOnline()
                }

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    state.value = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                }

                override fun onLost(network: Network) {
                    state.value = currentlyOnline()
                }
            })
        }
    }

    private fun currentlyOnline(): Boolean {
        val manager = cm ?: return true
        val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
