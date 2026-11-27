package io.github.tianli0.switchtether.link

import android.content.Context
import io.github.tianli0.switchtether.connection.ConnectionController
import io.github.tianli0.switchtether.data.LinkPrefs
import io.github.tianli0.switchtether.domain.StepUpGate
import io.github.tianli0.switchtether.forward.ActiveForward
import io.github.tianli0.switchtether.forward.Forwards
import io.github.tianli0.switchtether.forward.LeaseOutcome
import io.github.tianli0.switchtether.forward.StartResult
import io.github.tianli0.switchtether.forward.createForwardLease
import io.github.tianli0.switchtether.notify.AndroidLiveUpdateChannel
import io.github.tianli0.switchtether.remote.notify.LocalAction
import io.github.tianli0.switchtether.remote.protocol.ForwardView
import io.github.tianli0.switchtether.remote.protocol.SessionSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Pinned tasks (Live Updates) as the UI process sees them: the cards themselves are posted by the
 * `:link` process, which follows the sessions while the app is closed.
 */
class LivePins(
    context: Context,
    private val scope: CoroutineScope,
    private val link: LinkClient,
    private val prefs: LinkPrefs,
) {
    private val channel = AndroidLiveUpdateChannel(context)

    val pins: StateFlow<Set<String>> = link.state.map { it.pins.toSet() }.stateIn(scope, SharingStarted.Eagerly, emptySet())

    private val autoPin = prefs.autoPinPhoneSessions.stateIn(scope, SharingStarted.Eagerly, true)

    /** "Tasks started from the phone are pinned automatically". */
    val autoPinPhoneSessions: Boolean get() = autoPin.value

    fun setAutoPinPhoneSessions(value: Boolean) {
        scope.launch { prefs.setAutoPinPhoneSessions(value) }
    }

    fun isPinned(sessionId: String): Boolean = sessionId in pins.value

    /** Whether the system will promote the cards (Android 16 Live Updates). */
    fun canPromote(): Boolean = channel.canPromote()

    /** Pins [summary]; [onResult] gets false when all slots are taken by manual pins. */
    fun pin(summary: SessionSummary, manual: Boolean = true, onResult: (Boolean) -> Unit = {}) {
        scope.launch {
            val reply = link.request(
                LinkProtocol.message(LinkProtocol.PIN) {
                    putString(LinkProtocol.K_SUMMARY, LinkProtocol.encodeSummary(summary))
                    putBoolean(LinkProtocol.K_MANUAL, manual)
                },
            )
            onResult(reply?.getBoolean(LinkProtocol.K_OK, false) == true)
        }
    }

    fun unpin(sessionId: String) {
        link.send(LinkProtocol.message(LinkProtocol.UNPIN) { putString(LinkProtocol.K_SESSION, sessionId) })
    }

    /** The session page was opened: its "finished" card has done its job. */
    fun onSessionOpened(sessionId: String) {
        link.send(LinkProtocol.message(LinkProtocol.SESSION_OPENED) { putString(LinkProtocol.K_SESSION, sessionId) })
    }
}

/** Tells the `:link` process's notifications about something the phone itself did. */
fun LinkClient.noteLocalAction(target: String, action: LocalAction) {
    send(
        LinkProtocol.message(LinkProtocol.LOCAL_ACTION) {
            putString(LinkProtocol.K_TARGET, target)
            putString(LinkProtocol.K_ACTION, action.name)
        },
    )
}

/**
 * Port forwards from the UI process: the lease (step-up, biometric prompt) is asked for here, the
 * local listener runs in the `:link` process so a browser on the phone keeps working when the app
 * is closed.
 */
class LinkedForwards(
    private val scope: CoroutineScope,
    private val link: LinkClient,
    private val conn: ConnectionController,
    private val gate: StepUpGate,
    private val stepUpAvailable: () -> Boolean,
) : Forwards {
    override val active: StateFlow<List<ActiveForward>> =
        link.state.map { it.forwards }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    override val ended: SharedFlow<Int> = link.forwardEnded

    override suspend fun start(remotePort: Int, localPort: Int, process: String?, ttlMinutes: Int): StartResult {
        active.value.firstOrNull { it.remotePort == remotePort && it.localPort == localPort }?.let { return StartResult.Started(it, null) }
        return when (val r = createForwardLease(conn, gate, stepUpAvailable, remotePort, ttlMinutes)) {
            is LeaseOutcome.Ok -> attach(r.lease, localPort, process, r.lease.listening)
            is LeaseOutcome.Err -> r.result
        }
    }

    override suspend fun extend(leaseId: String, ttlMinutes: Int): StartResult {
        val current = active.value.firstOrNull { it.leaseId == leaseId } ?: return StartResult.Failed("unknown forward")
        return when (val r = createForwardLease(conn, gate, stepUpAvailable, current.remotePort, ttlMinutes)) {
            is LeaseOutcome.Ok -> {
                link.send(LinkProtocol.message(LinkProtocol.FORWARD_LEASE) { putString(LinkProtocol.K_LEASE, LinkProtocol.encodeLease(r.lease)) })
                StartResult.Started(current.copy(expiresAtMs = r.lease.expiresAtMs), r.lease.listening)
            }
            is LeaseOutcome.Err -> r.result
        }
    }

    override suspend fun attach(lease: ForwardView, localPort: Int, process: String?, listening: Boolean?): StartResult {
        val reply = link.request(
            LinkProtocol.message(LinkProtocol.FORWARD_ATTACH) {
                putString(LinkProtocol.K_LEASE, LinkProtocol.encodeLease(lease))
                putInt(LinkProtocol.K_LOCAL_PORT, localPort)
                process?.let { putString(LinkProtocol.K_PROCESS, it) }
                putInt(LinkProtocol.K_LISTENING, LinkProtocol.tri(listening))
            },
        )
        return LinkProtocol.decodeResult(reply?.getString(LinkProtocol.K_RESULT))
    }

    override suspend fun resumable(): List<ForwardView> {
        val api = conn.apiOrNull() ?: return emptyList()
        val served = active.value.map { it.leaseId }.toSet()
        return runCatching { api.forwards().forwards }.getOrDefault(emptyList()).filter { it.id !in served }
    }

    override fun stop(leaseId: String) {
        link.send(LinkProtocol.message(LinkProtocol.FORWARD_STOP) { putString(LinkProtocol.K_LEASE_ID, leaseId) })
    }
}
