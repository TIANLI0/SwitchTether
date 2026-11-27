package io.github.tianli0.switchtether.link

import android.os.Bundle
import android.os.Message
import io.github.tianli0.switchtether.forward.ActiveForward
import io.github.tianli0.switchtether.forward.StartResult
import io.github.tianli0.switchtether.remote.notify.LocalAction
import io.github.tianli0.switchtether.remote.protocol.ForwardView
import io.github.tianli0.switchtether.remote.protocol.ProtocolJson
import io.github.tianli0.switchtether.remote.protocol.SessionSummary
import kotlinx.serialization.Serializable

/**
 * Messages between the UI process and the `:link` process (docs/ANDROID_UI.md §38). Both sides are
 * this app (the bridge service is not exported), so the format is free to change between builds;
 * payloads are JSON strings in the message's Bundle so only framework types cross the boundary.
 */
object LinkProtocol {
    // UI → link
    /** First message of a binding; [Message.replyTo] receives [STATE] and replies. */
    const val REGISTER = 1

    /** arg1: 1 while an activity of the app is started, 0 when it left. */
    const val FOREGROUND = 2

    /** [K_SUMMARY], [K_MANUAL], [K_REQUEST] → [RESULT] with [K_OK]. */
    const val PIN = 3

    /** [K_SESSION]. */
    const val UNPIN = 4

    /** [K_SESSION]: the user opened the session page (its finished card has done its job). */
    const val SESSION_OPENED = 5

    /** [K_TARGET], [K_ACTION]: the phone itself interrupted / stopped something (no echo notification). */
    const val LOCAL_ACTION = 6

    /** [K_REQUEST], [K_LEASE], [K_LOCAL_PORT], [K_PROCESS], [K_LISTENING] → [RESULT] with [K_RESULT]. */
    const val FORWARD_ATTACH = 7

    /** [K_LEASE]: the computer extended a lease. */
    const val FORWARD_LEASE = 8

    /** [K_LEASE_ID]. */
    const val FORWARD_STOP = 9

    /** Pairing replaced or removed, or Settings → clear: forget connection state and conversations. */
    const val RESET = 10

    /** Companion presence or "nearby only" changed: decide again whether to keep the connection. arg1: present. */
    const val COMPANION = 11

    // link → UI
    /** [K_STATE]: pins and forwards, sent on every change. */
    const val STATE = 100

    /** Reply to a request ([K_REQUEST]). */
    const val RESULT = 101

    /** arg1: remote port whose forward the computer ended. */
    const val FORWARD_ENDED = 102

    /** The desktop revoked this phone ([K_SERVER]). */
    const val REVOKED = 103

    const val K_SUMMARY = "summary"
    const val K_MANUAL = "manual"
    const val K_REQUEST = "request"
    const val K_OK = "ok"
    const val K_SESSION = "session"
    const val K_TARGET = "target"
    const val K_ACTION = "action"
    const val K_LEASE = "lease"
    const val K_LEASE_ID = "leaseId"
    const val K_LOCAL_PORT = "localPort"
    const val K_PROCESS = "process"
    const val K_LISTENING = "listening"
    const val K_RESULT = "result"
    const val K_STATE = "state"
    const val K_SERVER = "server"

    fun message(what: Int, arg1: Int = 0, fill: Bundle.() -> Unit = {}): Message =
        Message.obtain(null, what, arg1, 0).also { it.data = Bundle().apply(fill) }

    fun encodeSummary(s: SessionSummary): String = ProtocolJson.encodeToString(SessionSummary.serializer(), s)
    fun decodeSummary(s: String?): SessionSummary? = s?.let { runCatching { ProtocolJson.decodeFromString(SessionSummary.serializer(), it) }.getOrNull() }

    fun encodeLease(l: ForwardView): String = ProtocolJson.encodeToString(ForwardView.serializer(), l)
    fun decodeLease(s: String?): ForwardView? = s?.let { runCatching { ProtocolJson.decodeFromString(ForwardView.serializer(), it) }.getOrNull() }

    fun encodeState(s: LinkState): String = ProtocolJson.encodeToString(LinkState.serializer(), s)
    fun decodeState(s: String?): LinkState? = s?.let { runCatching { ProtocolJson.decodeFromString(LinkState.serializer(), it) }.getOrNull() }

    fun encodeResult(r: StartResult): String = ProtocolJson.encodeToString(StartReply.serializer(), StartReply.of(r))
    fun decodeResult(s: String?): StartResult =
        s?.let { runCatching { ProtocolJson.decodeFromString(StartReply.serializer(), it) }.getOrNull() }?.toResult()
            ?: StartResult.Failed(LINK_UNAVAILABLE)

    /** Booleans that may be unknown travel as -1 / 0 / 1. */
    fun tri(b: Boolean?): Int = when (b) { null -> -1; false -> 0; true -> 1 }
    fun tri(i: Int): Boolean? = when (i) { 0 -> false; 1 -> true; else -> null }

    fun localAction(name: String?): LocalAction? = LocalAction.entries.firstOrNull { it.name == name }

    /** Shown when the `:link` process could not be reached (should not happen on a working phone). */
    const val LINK_UNAVAILABLE = "background service unavailable"
}

/** What the UI mirrors from the `:link` process. */
@Serializable
data class LinkState(
    val pins: List<String> = emptyList(),
    val forwards: List<ActiveForward> = emptyList(),
)

/** [StartResult] on the wire. */
@Serializable
data class StartReply(
    val kind: String,
    val forward: ActiveForward? = null,
    val listening: Boolean? = null,
    val localPort: Int = 0,
    val remotePort: Int = 0,
    val message: String? = null,
) {
    fun toResult(): StartResult = when (kind) {
        STARTED -> forward?.let { StartResult.Started(it, listening) } ?: StartResult.Failed(message ?: "?")
        NOT_CONFIRMED -> StartResult.NotConfirmed
        NO_STEP_UP_KEY -> StartResult.NoStepUpKey
        LOCAL_PORT_BUSY -> StartResult.LocalPortBusy(localPort, remotePort)
        else -> StartResult.Failed(message ?: "?")
    }

    companion object {
        private const val STARTED = "started"
        private const val NOT_CONFIRMED = "not_confirmed"
        private const val NO_STEP_UP_KEY = "no_step_up_key"
        private const val LOCAL_PORT_BUSY = "local_port_busy"
        private const val FAILED = "failed"

        fun of(r: StartResult): StartReply = when (r) {
            is StartResult.Started -> StartReply(STARTED, forward = r.forward, listening = r.listening)
            StartResult.NotConfirmed -> StartReply(NOT_CONFIRMED)
            StartResult.NoStepUpKey -> StartReply(NO_STEP_UP_KEY)
            is StartResult.LocalPortBusy -> StartReply(LOCAL_PORT_BUSY, localPort = r.localPort, remotePort = r.remotePort)
            is StartResult.Failed -> StartReply(FAILED, message = r.message)
        }
    }
}
