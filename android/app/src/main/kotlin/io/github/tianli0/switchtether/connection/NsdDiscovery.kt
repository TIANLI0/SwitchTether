package io.github.tianli0.switchtether.connection

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import io.github.tianli0.switchtether.remote.api.Endpoint
import io.github.tianli0.switchtether.remote.discovery.DiscoveredDesktop
import io.github.tianli0.switchtether.remote.discovery.DiscoveryTxt
import io.github.tianli0.switchtether.remote.discovery.SERVICE_TYPE
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Finds SwitchTether desktops on the local network (`_switchtether._tcp`, see [DiscoveryTxt]).
 *
 * Discovery runs only while collected: [find] is bounded (a few seconds after every stored host
 * failed) and the pairing screen collects [browse] only while visible. A Wi-Fi multicast lock is
 * held for exactly that time, so idle battery use is zero.
 */
class NsdDiscovery(context: Context) {
    private val app = context.applicationContext
    private val nsd: NsdManager? = app.getSystemService(NsdManager::class.java)
    private val wifi: WifiManager? = app.getSystemService(WifiManager::class.java)
    private val resolveExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "nsd-resolve").apply { isDaemon = true } }

    /** Live list of desktops currently advertised on the network. */
    fun browse(): Flow<List<DiscoveredDesktop>> = callbackFlow {
        val manager = nsd ?: run {
            trySend(emptyList())
            awaitClose { }
            return@callbackFlow
        }
        val lock = wifi?.createMulticastLock("switchtether-nsd")?.apply { setReferenceCounted(false) }
        runCatching { lock?.acquire() }
        val found = ConcurrentHashMap<String, List<DiscoveredDesktop>>()
        val infoCallbacks = ConcurrentHashMap<String, NsdManager.ServiceInfoCallback>()
        fun publish() = trySend(found.values.flatten())

        fun accept(name: String, info: NsdServiceInfo) {
            val hosts = info.hostAddresses.mapNotNull { it.hostAddress }
            val attrs: Map<String, ByteArray?> = info.attributes
            val parsed = hosts.mapNotNull { DiscoveryTxt.parse(name, it, info.port, attrs) }
            if (parsed.isEmpty()) found.remove(name) else found[name] = parsed
            publish()
        }

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { publish() }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

            override fun onServiceFound(info: NsdServiceInfo) {
                val name = info.serviceName
                lateinit var cb: NsdManager.ServiceInfoCallback
                cb = object : NsdManager.ServiceInfoCallback {
                    override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                        infoCallbacks.remove(name, cb)
                    }
                    override fun onServiceUpdated(serviceInfo: NsdServiceInfo) = accept(name, serviceInfo)
                    override fun onServiceLost() {
                        found.remove(name)
                        publish()
                    }
                    override fun onServiceInfoCallbackUnregistered() = Unit
                }
                if (infoCallbacks.putIfAbsent(name, cb) != null) return
                runCatching { manager.registerServiceInfoCallback(info, resolveExecutor, cb) }
                    .onFailure { infoCallbacks.remove(name, cb) }
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                found.remove(info.serviceName)
                infoCallbacks.remove(info.serviceName)?.let { runCatching { manager.unregisterServiceInfoCallback(it) } }
                publish()
            }
        }
        runCatching { manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener) }
            .onFailure { publish() }
        awaitClose {
            runCatching { manager.stopServiceDiscovery(discoveryListener) }
            infoCallbacks.values.forEach { runCatching { manager.unregisterServiceInfoCallback(it) } }
            infoCallbacks.clear()
            runCatching { if (lock?.isHeld == true) lock.release() }
        }
    }

    /** Endpoints of the desktop with [fingerprint], or empty after [timeoutMs]. */
    suspend fun find(fingerprint: String, timeoutMs: Long): List<Endpoint> =
        withTimeoutOrNull(timeoutMs) {
            browse().map { DiscoveryTxt.matching(it, fingerprint) }.first { it.isNotEmpty() }
        }.orEmpty().map { Endpoint(it.host, it.port) }
}
