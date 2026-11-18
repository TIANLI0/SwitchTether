package io.github.tianli0.switchtether.connection

import io.github.tianli0.switchtether.data.PairedDesktop
import io.github.tianli0.switchtether.data.PairingStore
import io.github.tianli0.switchtether.remote.api.ApiException
import io.github.tianli0.switchtether.remote.api.Endpoint
import io.github.tianli0.switchtether.remote.api.RemoteApi
import io.github.tianli0.switchtether.remote.events.CloseCodes
import io.github.tianli0.switchtether.remote.events.EventStreamClient
import io.github.tianli0.switchtether.remote.events.EventStreamReducer
import io.github.tianli0.switchtether.remote.events.StreamCursor
import io.github.tianli0.switchtether.remote.events.StreamDecision
import io.github.tianli0.switchtether.remote.events.StreamFrame
import io.github.tianli0.switchtether.remote.events.StreamSignal
import io.github.tianli0.switchtether.remote.pairing.HostRace
import io.github.tianli0.switchtether.remote.pairing.NoReachableHostException
import io.github.tianli0.switchtether.remote.pairing.causeChain
import io.github.tianli0.switchtether.remote.protocol.ApiErrorBody
import io.github.tianli0.switchtether.remote.protocol.ErrorCodes
import io.github.tianli0.switchtether.remote.protocol.Hello
import io.github.tianli0.switchtether.remote.protocol.StatusView
import io.github.tianli0.switchtether.remote.protocol.str
import io.github.tianli0.switchtether.remote.tls.ClientIdentity
import io.github.tianli0.switchtether.remote.tls.TlsClientFactory
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient

/** Connection status shown across the app. */
sealed interface ConnectionState {
    data object Unpaired : ConnectionState
    data class Connecting(val desktop: PairedDesktop, val attempt: Int, val lastError: String?) : ConnectionState
    data class Online(val desktop: PairedDesktop, val endpoint: Endpoint, val status: StatusView?) : ConnectionState
    data class Offline(val desktop: PairedDesktop, val reason: String?, val retryAtMs: Long?) : ConnectionState

    /** The desktop revoked or forgot this phone. Keys/pairing were wiped; re-pair required. */
    data class Revoked(val desktopName: String?, val reason: String) : ConnectionState

    /** Protocol ranges do not overlap (§9). */
    data class Incompatible(val desktop: PairedDesktop, val kind: Hello.Compatibility, val serverVersion: String) : ConnectionState
}

/** Something the connection layer can hand to repositories. */
interface ConnectionController {
    val state: StateFlow<ConnectionState>

    /** Live events after de-duplication (topic + data). */
    val events: SharedFlow<StreamFrame.Event>

    /** Emitted when state must be refetched over REST (resync / reconnect / epoch change). */
    val resyncs: SharedFlow<String>

    /** The API of the current desktop, or null when not online. */
    fun apiOrNull(): RemoteApi?

    fun retryNow()
}

class NotConnectedException : Exception("not connected to the desktop")

/**
 * Keeps exactly one desktop connection (§1, §6):
 *
 * 1. Find a reachable host (last good host first, then all candidates in parallel; pinned mTLS).
 * 2. `GET /v1/hello` (compatibility + still paired) and `GET /v1/status` (epoch/headSeq).
 * 3. Open the events WebSocket with `since`/`epoch`, de-duplicate by seq, handle `resync`.
 * 4. On failure: exponential backoff with jitter (1 s … 15 s); network changes retry immediately.
 * 5. `unpaired`/`device_revoked` (HTTP or close codes 4401/4403, or `hello.paired == false`)
 *    → [onRevoked] wipes pairing state and keys, state becomes [ConnectionState.Revoked].
 */
class ConnectionManager(
    private val scope: CoroutineScope,
    private val pairingStore: PairingStore,
    private val identityLoader: (String) -> ClientIdentity?,
    private val onRevoked: suspend (PairedDesktop, String) -> Unit,
    private val baseClient: OkHttpClient = OkHttpClient(),
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * LAN discovery (NSD): endpoints currently advertised by the desktop with this fingerprint.
     * Used only when every stored host failed (e.g. the computer got a new IP address).
     */
    private val discover: (suspend (fingerprint: String, timeoutMs: Long) -> List<Endpoint>)? = null,
    /**
     * Android 17 `ACCESS_LOCAL_NETWORK`. When false, LAN hosts and LAN discovery are skipped (they
     * could only time out); VPN/Tailscale/DNS hosts and the relay are still tried.
     */
    private val localNetworkAllowed: () -> Boolean = { true },
    /** Opens/seals the stored relay token + key (Keystore-encrypted in the app). */
    private val relaySecrets: RelaySecrets = RelaySecrets.None,
) : ConnectionController {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Unpaired)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<StreamFrame.Event>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events: SharedFlow<StreamFrame.Event> = _events.asSharedFlow()

    private val _resyncs = MutableSharedFlow<String>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val resyncs: SharedFlow<String> = _resyncs.asSharedFlow()

    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val lock = Mutex()
    private var job: Job? = null
    @Volatile private var api: RemoteApi? = null
    @Volatile private var cursor: StreamCursor = StreamCursor.INITIAL
    @Volatile private var streamJob: Job? = null
    private var clientFor: Pair<String, OkHttpClient>? = null

    override fun apiOrNull(): RemoteApi? = api.takeIf { _state.value is ConnectionState.Online }

    /** Starts (or restarts) the connection loop for the stored desktop. */
    fun start() {
        scope.launch {
            lock.withLock {
                if (job?.isActive == true) return@withLock
                job = scope.launch { runLoop() }
            }
        }
    }

    /** Stops networking (app in background, unpair). */
    fun stop() {
        job?.cancel()
        job = null
        api = null
        when (val s = _state.value) {
            is ConnectionState.Online -> _state.value = ConnectionState.Offline(s.desktop, null, null)
            is ConnectionState.Connecting -> _state.value = ConnectionState.Offline(s.desktop, null, null)
            else -> Unit
        }
    }

    /** Forget everything in memory (after unpair). */
    fun reset() {
        stop()
        cursor = StreamCursor.INITIAL
        clientFor = null
        _state.value = ConnectionState.Unpaired
    }

    override fun retryNow() {
        wake.trySend(Unit)
        start()
    }

    /** Network became available / changed: skip remaining backoff or verify active socket. */
    fun onNetworkAvailable() {
        val currentApi = apiOrNull()
        if (currentApi != null) {
            scope.launch {
                val alive = withTimeoutOrNull(3_000) { runCatching { currentApi.hello() }.isSuccess } ?: false
                if (!alive) {
                    streamJob?.cancel()
                }
            }
        }
        retryNow()
    }

    /** One-shot bounded connection probe for background WorkManager recovery. */
    suspend fun probeOnce(timeoutMs: Long = 15_000L): Boolean {
        val desktop = pairingStore.current() ?: return false
        val identity = identityLoader(desktop.identityAlias) ?: return false
        if (_state.value is ConnectionState.Online) return true
        start()
        return withTimeoutOrNull(timeoutMs) {
            var reached = false
            state.collect { s ->
                if (s is ConnectionState.Online) {
                    reached = true
                    throw CancellationException("probe_done")
                }
            }
            reached
        } ?: (_state.value is ConnectionState.Online)
    }

    private suspend fun runLoop() {
        var attempt = 0
        while (scope.isActive) {
            val desktop = pairingStore.current()
            if (desktop == null) {
                _state.value = ConnectionState.Unpaired
                return
            }
            val identity = identityLoader(desktop.identityAlias)
            if (identity == null) {
                // Keystore entry gone (e.g. app data restored to another device): cannot authenticate.
                revoke(desktop, "identity key missing on this phone")
                return
            }
            attempt++
            _state.value = ConnectionState.Connecting(desktop, attempt, (state.value as? ConnectionState.Offline)?.reason)
            val outcome = try {
                connectOnce(desktop, identity)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Outcome.Failed(describe(e))
            }
            when (outcome) {
                is Outcome.Revoked -> {
                    revoke(desktop, outcome.reason)
                    return
                }
                is Outcome.Incompatible -> {
                    _state.value = ConnectionState.Incompatible(desktop, outcome.kind, outcome.serverVersion)
                    return
                }
                is Outcome.Failed -> {
                    if (outcome.wasOnline) attempt = 1
                    val delayMs = backoffMs(attempt)
                    _state.value = ConnectionState.Offline(desktop, outcome.reason, clock() + delayMs)
                    api = null
                    // Wait for backoff, or an explicit wake-up (network change / user retry).
                    withTimeoutOrNull(delayMs) { wake.receive() }
                }
            }
        }
    }

    private sealed interface Outcome {
        data class Failed(val reason: String, val wasOnline: Boolean = false) : Outcome
        data class Revoked(val reason: String) : Outcome
        data class Incompatible(val kind: Hello.Compatibility, val serverVersion: String) : Outcome
    }

    private fun clientFor(desktop: PairedDesktop, identity: ClientIdentity): OkHttpClient {
        val key = desktop.identityAlias + "|" + desktop.fingerprint
        clientFor?.let { if (it.first == key) return it.second }
        val c = TlsClientFactory.create(identity, desktop.fingerprint, base = baseClient)
        clientFor = key to c
        return c
    }

    private suspend fun refreshRelay(remote: RemoteApi) {
        val r = runCatching { remote.relay() }.getOrNull() ?: return
        val cur = pairingStore.current() ?: return
        val url = r.url
        val key = r.key
        val updated = if (r.enabled && url != null && key != null &&
            io.github.tianli0.switchtether.remote.relay.RelayConfig.parseOrNull(url, r.token, key) != null
        ) {
            if (cur.relayUrl == url && relaySecrets.open(cur) == RelaySecret(r.token, key)) return
            val sealed = relaySecrets.seal(RelaySecret(r.token, key)) ?: return
            cur.copy(relayUrl = url, relaySecret = sealed, relayToken = null)
        } else {
            if (cur.relayUrl == null && cur.relaySecret == null) return
            cur.copy(relayUrl = null, relaySecret = null, relayToken = null)
        }
        pairingStore.save(updated)
    }

    private suspend fun connectOnce(desktop: PairedDesktop, identity: ClientIdentity): Outcome {
        val client = clientFor(desktop, identity)
        var authFailure: ApiException? = null
        val makeApiFor = { c: OkHttpClient ->
            { ep: Endpoint ->
                RemoteApi(c, ep) {
                    // Any REST call of any screen answered unpaired/device_revoked: end the session.
                    authFailure = it
                    streamJob?.cancel()
                }
            }
        }
        val makeApi = makeApiFor(client)
        var pathClient = client

        // 1. Stored hosts: last good host first (fast path), then race every candidate.
        // 2. LAN discovery (same pinned fingerprint) when all stored hosts failed.
        // 3. The desktop's relay, if it has one. TLS, pin and client certificate are identical on every path.
        val lanAllowed = localNetworkAllowed()
        val ordered = desktop.orderedHosts()
            .let { if (lanAllowed) it else io.github.tianli0.switchtether.remote.pairing.LocalNetwork.withoutLocal(it) }
            .map { Endpoint(it, desktop.port) }
        val (endpoint, hello) = try {
            if (ordered.isEmpty()) throw NoReachableHostException(emptyList())
            val first = ordered.first()
            val quick = withTimeoutOrNull(3_000) { runCatching { makeApi(first).hello() }.getOrNull() }
            if (quick != null) first to quick else HostRace.first(ordered) { ep -> makeApi(ep).hello() }
        } catch (e: NoReachableHostException) {
            if (e.pinMismatch) return Outcome.Failed("certificate mismatch: the desktop presented a different certificate than the one paired")
            if (isRevokedTlsAlert(e)) return Outcome.Revoked("the desktop refused this phone's certificate")
            val direct = if (!lanAllowed && e.failures.isEmpty()) LOCAL_NETWORK_DENIED else describe(e.failures.lastOrNull()?.second ?: e)
            val lan = discover?.takeIf { lanAllowed }?.let { find ->
                val found = runCatching { find(desktop.fingerprint, DISCOVERY_TIMEOUT_MS) }.getOrDefault(emptyList())
                    .filter { it !in ordered }
                if (found.isEmpty()) null else runCatching { HostRace.first(found) { ep -> makeApi(ep).hello() } }.getOrNull()
            }
            if (lan != null) {
                // The computer moved (new IP): remember the discovered address for next time.
                val (ep, _) = lan
                pairingStore.save(
                    desktop.copy(
                        hosts = io.github.tianli0.switchtether.remote.discovery.DiscoveryTxt.mergeHosts(desktop.hosts, ep.host),
                        lastGoodHost = ep.host,
                        port = ep.port,
                    ),
                )
                lan
            } else {
                // No relay key (or it cannot be opened): the relay would refuse us; do not try.
                val secret = relaySecrets.open(desktop)
                val relay = io.github.tianli0.switchtether.remote.relay.RelayConfig.parseOrNull(desktop.relayUrl, secret?.token, secret?.key)
                    ?: return Outcome.Failed(direct)
                val relayClient = TlsClientFactory.create(
                    identity,
                    desktop.fingerprint,
                    base = io.github.tianli0.switchtether.remote.relay.relayBaseClient(baseClient, relay, desktop.fingerprint, baseClient),
                    connectTimeoutMs = 15_000,
                )
                val ep = Endpoint(io.github.tianli0.switchtether.remote.relay.RELAY_HOST, 443)
                val h = try {
                    makeApiFor(relayClient)(ep).hello()
                } catch (re: Exception) {
                    if (re is CancellationException) throw re
                    if (re.causeChain().any { it is io.github.tianli0.switchtether.remote.tls.PinMismatchException }) {
                        return Outcome.Failed("certificate mismatch: the desktop presented a different certificate than the one paired")
                    }
                    return Outcome.Failed("$direct; relay: ${describe(re)}")
                }
                pathClient = relayClient
                ep to h
            }
        }

        val compat = hello.compatibility()
        if (compat != Hello.Compatibility.OK) return Outcome.Incompatible(compat, hello.serverVersion)
        if (!hello.paired) return Outcome.Revoked("the desktop no longer knows this phone")

        val remote = makeApiFor(pathClient)(endpoint)
        val status = try {
            remote.status()
        } catch (e: ApiException) {
            if (e.isAuthFailure) return Outcome.Revoked(e.body.message.ifEmpty { e.code })
            null
        }
        val viaRelay = endpoint.host == io.github.tianli0.switchtether.remote.relay.RELAY_HOST
        pairingStore.current()?.let { cur ->
            val updated = refreshedAddresses(cur, status, if (viaRelay) null else endpoint.host)
            if (updated != cur) pairingStore.save(updated)
        }
        // Direct connection: pick up relay changes (new URL, reset token, rotated key) over the
        // authenticated channel (GET /v1/relay, RELAY.md §5). Older desktops without it: keep what we have.
        if (!viaRelay) scope.launch { refreshRelay(remote) }

        // Epoch bookkeeping: a new epoch (desktop restart) or first connect → refetch everything.
        val serverEpoch = status?.epoch?.takeIf { it.isNotEmpty() } ?: hello.epoch
        if (cursor.epoch != serverEpoch) {
            cursor = StreamCursor(serverEpoch, status?.headSeq ?: 0)
        }
        api = remote
        _state.value = ConnectionState.Online(desktop, endpoint, status)
        _resyncs.tryEmit("connected")

        // Event stream; returns when the socket ends.
        var opened = false
        coroutineScope {
            val j = launch {
        EventStreamClient(remote).connect(cursor).collect { signal ->
            when (signal) {
                StreamSignal.Opened -> opened = true
                is StreamSignal.Frame -> {
                    val (next, decision) = EventStreamReducer.reduce(cursor, signal.frame)
                    cursor = next
                    when (decision) {
                        is StreamDecision.Apply -> {
                            val ev = decision.event
                            if (ev.topic == "device.revoked" && (ev.data as? JsonObject)?.str("deviceId") == desktop.deviceId) {
                                authFailure = ApiException(403, ApiErrorBody(ErrorCodes.DEVICE_REVOKED, "revoked on the desktop"), null)
                            }
                            _events.tryEmit(ev)
                        }
                        is StreamDecision.Resync -> _resyncs.tryEmit(decision.reason)
                        else -> Unit
                    }
                }
                is StreamSignal.Closed -> when (signal.code) {
                    CloseCodes.UNPAIRED -> authFailure = ApiException(401, ApiErrorBody(ErrorCodes.UNPAIRED, signal.reason), null)
                    CloseCodes.REVOKED -> authFailure = ApiException(403, ApiErrorBody(ErrorCodes.DEVICE_REVOKED, signal.reason), null)
                    else -> Unit
                }
                is StreamSignal.Failure -> {
                    if (signal.httpCode == 401 || signal.httpCode == 403) {
                        val code = signal.body?.let { b -> if (b.contains(ErrorCodes.DEVICE_REVOKED)) ErrorCodes.DEVICE_REVOKED else if (b.contains(ErrorCodes.UNPAIRED)) ErrorCodes.UNPAIRED else null }
                        if (code != null) authFailure = ApiException(signal.httpCode ?: 401, ApiErrorBody(code, "event stream refused"), null)
                    }
                }
            }
        }
            }
            streamJob = j
            j.join()
            streamJob = null
        }
        authFailure?.let { return Outcome.Revoked(it.body.message.ifEmpty { it.code }) }
        return Outcome.Failed(if (opened) "event stream closed" else "event stream could not be opened", wasOnline = opened)
    }

    private suspend fun revoke(desktop: PairedDesktop, reason: String) {
        api = null
        cursor = StreamCursor.INITIAL
        clientFor = null
        runCatching { onRevoked(desktop, reason) }
        _state.value = ConnectionState.Revoked(desktop.name, reason)
    }

    private fun isRevokedTlsAlert(e: Throwable): Boolean =
        e.causeChain().any { it.message?.contains("revoked", ignoreCase = true) == true }

    private fun backoffMs(attempt: Int): Long {
        val base = (1_000L shl (attempt - 1).coerceIn(0, 6)).coerceAtMost(MAX_BACKOFF_MS)
        return base / 2 + Random.nextLong(base / 2 + 1)
    }

    companion object {
        /**
         * Stored addresses after a successful connection: the path that worked first, then the
         * desktop's current candidates (`/v1/status` → `hosts`, so a changed LAN address heals on any
         * connection, including via Tailscale or the relay), then the older ones, bounded. Wake-on-LAN
         * targets are replaced when the desktop reports some (an empty list keeps the last known).
         */
        fun refreshedAddresses(cur: PairedDesktop, status: io.github.tianli0.switchtether.remote.protocol.StatusView?, workingHost: String?): PairedDesktop {
            val reported = status?.hosts.orEmpty()
                .map { it.trim().removePrefix("[").removeSuffix("]") }
                .filter { it.isNotEmpty() && it != io.github.tianli0.switchtether.remote.relay.RELAY_HOST }
            val hosts = (listOfNotNull(workingHost) + reported + cur.hosts).distinct()
                .take(io.github.tianli0.switchtether.remote.pairing.PairingLink.MAX_HOSTS)
            val wake = status?.wake?.filter { io.github.tianli0.switchtether.remote.wake.WakeOnLan.macBytes(it.mac) != null }
                ?.takeIf { it.isNotEmpty() } ?: cur.wake
            return cur.copy(
                hosts = hosts,
                lastGoodHost = workingHost ?: cur.lastGoodHost,
                wake = wake,
            )
        }


        /** Longest wait between reconnect attempts: a task that finished while offline must not wait a minute more. */
        const val MAX_BACKOFF_MS = 15_000L

        /** How long LAN discovery may take before falling back to the relay. */
        const val DISCOVERY_TIMEOUT_MS = 5_000L

        /** Offline reason when only LAN hosts are known and the local-network permission is missing. */
        const val LOCAL_NETWORK_DENIED = "local network permission not granted"

        fun describe(e: Throwable): String = when (e) {
            is ApiException -> "${e.code}: ${e.body.message}"
            else -> e.causeChain().lastOrNull { it.message != null }?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: e.javaClass.simpleName
        }
    }
}
