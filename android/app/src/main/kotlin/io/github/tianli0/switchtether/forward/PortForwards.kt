package io.github.tianli0.switchtether.forward

import io.github.tianli0.switchtether.connection.ConnectionController
import io.github.tianli0.switchtether.connection.ConnectionState
import io.github.tianli0.switchtether.domain.StepUpGate
import io.github.tianli0.switchtether.remote.api.ApiException
import io.github.tianli0.switchtether.remote.relay.RELAY_HOST
import io.github.tianli0.switchtether.remote.forward.LocalForwarder
import io.github.tianli0.switchtether.remote.forward.TunnelConnector
import io.github.tianli0.switchtether.remote.forward.TunnelStatus
import io.github.tianli0.switchtether.remote.protocol.ErrorCodes
import io.github.tianli0.switchtether.remote.protocol.ForwardView
import io.github.tianli0.switchtether.remote.protocol.StepUpScopes
import java.io.IOException
import java.net.BindException
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A forward running on this phone (`localhost:[localPort]` → the computer's [remotePort]). */
@kotlinx.serialization.Serializable
data class ActiveForward(
    val leaseId: String,
    val remotePort: Int,
    val localPort: Int,
    val process: String?,
    val expiresAtMs: Long,
    val status: TunnelStatus,
    val viaRelay: Boolean,
    val connections: Int,
    val totalConnections: Long,
    val bytesDown: Long,
    val bytesUp: Long,
) {
    val url: String get() = "http://localhost:$localPort/"
}

sealed interface StartResult {
    data class Started(val forward: ActiveForward, val listening: Boolean?) : StartResult
    /** The user cancelled the biometric confirmation, or the desktop refused the grant. */
    data object NotConfirmed : StartResult
    /** Paired without a step-up key (no secure lock screen at pairing time). */
    data object NoStepUpKey : StartResult
    data class LocalPortBusy(val localPort: Int, val remotePort: Int) : StartResult
    data class Failed(val message: String) : StartResult
}

/** The port forwards as the screens see them (UI process: [io.github.tianli0.switchtether.link.LinkedForwards]). */
interface Forwards {
    val active: StateFlow<List<ActiveForward>>

    /** Remote ports whose forward the computer ended (stopped there, expired, device revoked). */
    val ended: SharedFlow<Int>

    /** Biometric confirmation, then a lease on the computer, then the local listener. */
    suspend fun start(remotePort: Int, localPort: Int = remotePort, process: String? = null, ttlMinutes: Int = PortForwards.DEFAULT_TTL_MINUTES): StartResult

    /** Asks the computer for more time (needs a fresh confirmation once the grant expired). */
    suspend fun extend(leaseId: String, ttlMinutes: Int = PortForwards.DEFAULT_TTL_MINUTES): StartResult

    /** Starts listening for an existing lease (no step-up: the lease is the authorisation). */
    suspend fun attach(lease: ForwardView, localPort: Int = lease.port, process: String? = null, listening: Boolean? = null): StartResult

    /** Leases on the computer that this phone does not serve right now (resumable). */
    suspend fun resumable(): List<ForwardView>

    fun stop(leaseId: String)
}

/** A new lease, or why there is none. */
sealed interface LeaseOutcome {
    data class Ok(val lease: ForwardView) : LeaseOutcome
    data class Err(val result: StartResult) : LeaseOutcome
}

/**
 * A lease for [remotePort] (step-up `port_forward`, asked again once if the cached grant expired
 * on the computer). Runs where the biometric prompt can be shown: the UI process.
 */
suspend fun createForwardLease(
    conn: ConnectionController,
    gate: StepUpGate,
    stepUpAvailable: () -> Boolean,
    remotePort: Int,
    ttlMinutes: Int,
): LeaseOutcome {
    if (conn.apiOrNull() == null) return LeaseOutcome.Err(StartResult.Failed(PortForwards.OFFLINE))
    if (!stepUpAvailable()) return LeaseOutcome.Err(StartResult.NoStepUpKey)
    repeat(2) { attempt ->
        if (gate.ensure(StepUpScopes.PORT_FORWARD).isFailure) return LeaseOutcome.Err(StartResult.NotConfirmed)
        val api = conn.apiOrNull() ?: return LeaseOutcome.Err(StartResult.Failed(PortForwards.OFFLINE))
        try {
            return LeaseOutcome.Ok(api.createForward(remotePort, ttlMinutes))
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            // The cached grant expired on the computer: confirm once more.
            if (e.code == ErrorCodes.STEP_UP_REQUIRED && attempt == 0) {
                gate.invalidate(StepUpScopes.PORT_FORWARD)
            } else {
                return LeaseOutcome.Err(StartResult.Failed("${e.code}: ${e.body.message}"))
            }
        } catch (e: Exception) {
            return LeaseOutcome.Err(StartResult.Failed(e.message ?: e.javaClass.simpleName))
        }
    }
    return LeaseOutcome.Err(StartResult.NotConfirmed)
}

/**
 * The phone's port forwards (docs/PROTOCOL.md §7.11): one [LocalForwarder] per lease, listening on
 * the phone's loopback. Lives in the `:link` process (docs/ANDROID_UI.md §38), so a browser on the
 * phone keeps its forward while the UI process is gone; the UI asks for leases (step-up) and hands
 * them over. Forwards live in memory only: nothing listens on the phone after the process ends,
 * and leases end on the computer by expiry.
 */
class PortForwards(
    private val scope: CoroutineScope,
    private val conn: ConnectionController,
    /** HTML shown to a browser when nothing listens on the computer / the computer is unreachable. */
    private val errorPage: (LocalForwarder.ErrorKind, Int) -> String,
) {
    private class Entry(
        @Volatile var lease: ForwardView,
        val localPort: Int,
        val process: String?,
        val forwarder: LocalForwarder,
    )

    private val entries = ConcurrentHashMap<String, Entry>()
    private val _active = MutableStateFlow<List<ActiveForward>>(emptyList())
    val active: StateFlow<List<ActiveForward>> = _active.asStateFlow()

    private val _ended = MutableSharedFlow<Int>(extraBufferCapacity = 8)

    /** Remote ports whose forward the computer ended (stopped there, expired, device revoked). */
    val ended: SharedFlow<Int> = _ended.asSharedFlow()

    init {
        scope.launch {
            conn.state.collect { s ->
                when (s) {
                    // A new path (network change, relay ↔ direct): reconnect at once instead of after the backoff.
                    is ConnectionState.Online -> entries.values.forEach { it.forwarder.reconnectNow() }
                    is ConnectionState.Unpaired, is ConnectionState.Revoked -> stopAllLocal()
                    else -> Unit
                }
                refresh()
            }
        }
        scope.launch {
            while (true) {
                delay(1_000)
                if (entries.isEmpty()) continue
                val now = System.currentTimeMillis()
                entries.values.filter { it.lease.expiresAtMs in 1..now }.forEach { e ->
                    removeLocal(e.lease.id)
                    _ended.tryEmit(e.lease.port)
                }
                refresh()
            }
        }
    }

    private fun viaRelay(): Boolean = (conn.state.value as? ConnectionState.Online)?.endpoint?.host == RELAY_HOST

    private fun refresh() {
        val relay = viaRelay()
        _active.value = entries.values.map { e ->
            val st = e.forwarder.stats()
            ActiveForward(
                leaseId = e.lease.id,
                remotePort = e.lease.port,
                localPort = e.localPort,
                process = e.process,
                expiresAtMs = e.lease.expiresAtMs,
                status = st.status,
                viaRelay = relay,
                connections = st.activeConnections,
                totalConnections = st.totalConnections,
                bytesDown = st.bytesDown,
                bytesUp = st.bytesUp,
            )
        }.sortedBy { it.localPort }
    }

    fun isForwarded(remotePort: Int): Boolean = entries.values.any { it.lease.port == remotePort }

    /** The computer extended [lease] (a new expiry); unknown leases are ignored. */
    fun updateLease(lease: ForwardView) {
        entries[lease.id]?.let { it.lease = lease }
        refresh()
    }

    /** Starts listening for an existing lease (no step-up: the lease is the authorisation). */
    suspend fun attach(lease: ForwardView, localPort: Int = lease.port, process: String? = null, listening: Boolean? = null): StartResult {
        entries[lease.id]?.let { return StartResult.Started(viewOf(it), listening) }
        val forwarder = LocalForwarder(
            localPort = localPort,
            connector = TunnelConnector { events ->
                val api = conn.apiOrNull() ?: throw IOException(OFFLINE)
                api.openForwardTunnel(lease.id, events)
            },
            onStatus = { scope.launch { refresh() } },
            onEnded = { _, _ ->
                if (entries.remove(lease.id) != null) {
                    _ended.tryEmit(lease.port)
                    scope.launch { refresh() }
                }
            },
            errorPage = { kind -> errorPage(kind, lease.port) },
        )
        try {
            withContext(Dispatchers.IO) { forwarder.start() }
        } catch (e: BindException) {
            forwarder.stop()
            return StartResult.LocalPortBusy(localPort, lease.port)
        } catch (e: IOException) {
            forwarder.stop()
            return StartResult.Failed(e.message ?: "bind failed")
        }
        val entry = Entry(lease, localPort, process, forwarder)
        entries[lease.id] = entry
        refresh()
        return StartResult.Started(viewOf(entry), listening)
    }

    fun stop(leaseId: String) {
        removeLocal(leaseId)
        scope.launch { runCatching { conn.apiOrNull()?.stopForward(leaseId) } }
        refresh()
    }

    fun stopAll() {
        entries.keys.toList().forEach(::stop)
    }

    private fun removeLocal(leaseId: String) {
        entries.remove(leaseId)?.forwarder?.stop()
    }

    private fun stopAllLocal() {
        entries.keys.toList().forEach(::removeLocal)
    }

    private fun viewOf(e: Entry): ActiveForward {
        refresh()
        return _active.value.firstOrNull { it.leaseId == e.lease.id } ?: ActiveForward(
            e.lease.id, e.lease.port, e.localPort, e.process, e.lease.expiresAtMs, e.forwarder.status, viaRelay(), 0, 0, 0, 0,
        )
    }

    companion object {
        const val DEFAULT_TTL_MINUTES = 120
        const val OFFLINE = "not connected to the computer"
    }
}
