package io.github.tianli0.switchtether.connection

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * Calls [onAvailable] when the default network really changes: another network, the same one
 * becoming validated, or new addresses. Capability updates of an unchanged network (mobile signal
 * strength, bandwidth estimates) arrive every few seconds and are not reported.
 */
class NetworkMonitor(context: Context, private val onAvailable: () -> Unit) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private var registered = false

    private val lock = Any()
    private var current: Network? = null
    private var validated = false
    private var addresses: Set<String> = emptySet()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val changed = synchronized(lock) {
                (network != current).also {
                    if (it) {
                        current = network
                        validated = false
                        addresses = emptySet()
                    }
                }
            }
            if (changed) onAvailable()
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val ok = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val changed = synchronized(lock) {
                val c = network != current || (ok && !validated)
                if (network != current) addresses = emptySet()
                current = network
                validated = ok
                c && ok
            }
            if (changed) onAvailable()
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) {
            val now = linkProperties.linkAddresses.mapTo(HashSet()) { it.toString() }
            val changed = synchronized(lock) {
                val first = network == current && addresses.isEmpty()
                val c = network != current || now != addresses
                current = network
                addresses = now
                c && !first // the first report of a network follows onAvailable, already reported
            }
            if (changed) onAvailable()
        }

        override fun onLost(network: Network) {
            synchronized(lock) {
                if (network == current) {
                    current = null
                    validated = false
                    addresses = emptySet()
                }
            }
        }
    }

    fun start() {
        if (registered || cm == null) return
        runCatching { cm.registerDefaultNetworkCallback(callback) }.onSuccess { registered = true }
    }

    fun stop() {
        if (!registered || cm == null) return
        runCatching { cm.unregisterNetworkCallback(callback) }
        registered = false
        // Registering again replays the current network: report it then (it may have changed meanwhile).
        synchronized(lock) {
            current = null
            validated = false
            addresses = emptySet()
        }
    }
}
