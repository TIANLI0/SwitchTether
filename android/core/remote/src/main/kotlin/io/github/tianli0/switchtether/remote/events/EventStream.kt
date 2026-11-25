package io.github.tianli0.switchtether.remote.events

import io.github.tianli0.switchtether.remote.api.RemoteApi
import io.github.tianli0.switchtether.remote.protocol.ProtocolJson
import io.github.tianli0.switchtether.remote.protocol.long
import io.github.tianli0.switchtether.remote.protocol.obj
import io.github.tianli0.switchtether.remote.protocol.str
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** Server → client frames of `GET /v1/events` (§6). */
sealed interface StreamFrame {
    data class Event(val seq: Long, val epoch: String, val ts: String, val topic: String, val data: JsonElement) : StreamFrame
    data class Resync(val epoch: String, val headSeq: Long, val reason: String) : StreamFrame
    data class Pong(val ts: String?) : StreamFrame
    data class Unknown(val type: String?) : StreamFrame

    companion object {
        fun parse(text: String): StreamFrame {
            val o = runCatching { ProtocolJson.parseToJsonElement(text) }.getOrNull().obj() ?: return Unknown(null)
            return when (val type = o.str("type")) {
                "event" -> Event(
                    seq = seq(o, "seq") ?: return Unknown(type),
                    epoch = o.str("epoch") ?: "",
                    ts = o.str("ts") ?: "",
                    topic = o.str("topic") ?: "",
                    data = o["data"] ?: JsonNull,
                )
                "resync" -> Resync(o.str("epoch") ?: "", seq(o, "headSeq") ?: 0, o.str("reason") ?: "unknown")
                "pong" -> Pong(o.str("ts"))
                else -> Unknown(type)
            }
        }

        /**
         * A sequence number: a non-negative integer. Anything else (a fraction, `1e30`, a value beyond
         * the number range) is not one; rounding it would park the cursor at the end of the range and
         * every later event would be dropped as a duplicate.
         */
        private fun seq(o: kotlinx.serialization.json.JsonObject, key: String): Long? =
            (o[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()?.takeIf { it >= 0 }
    }
}

/** Where the client is in the server's event sequence. Persisted only in memory. */
data class StreamCursor(val epoch: String?, val lastSeq: Long) {
    companion object {
        val INITIAL = StreamCursor(null, 0)
    }
}

sealed interface StreamDecision {
    /** New event, in order: apply it. */
    data class Apply(val event: StreamFrame.Event) : StreamDecision

    /** Already seen (duplicate after reconnect): drop. */
    data object Duplicate : StreamDecision

    /** State may be stale: refetch over REST, then continue with live frames. */
    data class Resync(val reason: String) : StreamDecision

    data object Ignore : StreamDecision
}

/**
 * Pure reducer for the event stream (§6): de-duplicates by `seq` within an `epoch` and turns
 * `resync` frames (gap / epoch change / lagged) into a refetch decision. Gaps in `seq` are normal
 * (device-scoped events of other devices are not delivered) and do not trigger a resync.
 */
object EventStreamReducer {
    fun reduce(cursor: StreamCursor, frame: StreamFrame): Pair<StreamCursor, StreamDecision> = when (frame) {
        is StreamFrame.Event -> when {
            cursor.epoch != null && frame.epoch.isNotEmpty() && frame.epoch != cursor.epoch ->
                // Desktop restarted without telling us: everything we hold may be stale.
                StreamCursor(frame.epoch, frame.seq) to StreamDecision.Resync("epoch_changed")
            frame.seq <= cursor.lastSeq -> cursor to StreamDecision.Duplicate
            else -> StreamCursor(frame.epoch.ifEmpty { cursor.epoch }, frame.seq) to StreamDecision.Apply(frame)
        }
        is StreamFrame.Resync -> StreamCursor(frame.epoch.ifEmpty { cursor.epoch }, frame.headSeq) to StreamDecision.Resync(frame.reason)
        is StreamFrame.Pong, is StreamFrame.Unknown -> cursor to StreamDecision.Ignore
    }
}

/** Raw signals from one WebSocket connection. */
sealed interface StreamSignal {
    data object Opened : StreamSignal
    data class Frame(val frame: StreamFrame) : StreamSignal
    data class Closed(val code: Int, val reason: String) : StreamSignal
    data class Failure(val error: Throwable, val httpCode: Int?, val body: String?) : StreamSignal
}

object CloseCodes {
    const val UNPAIRED = 4401
    const val REVOKED = 4403
    const val IDLE_TIMEOUT = 4408
}

/**
 * Opens the events WebSocket and exposes it as a cold Flow. Sends the protocol's JSON
 * `{"type":"ping"}` every [pingIntervalMs] (the server closes idle sockets after 60 s).
 * The flow completes after a close/failure signal; reconnecting is the caller's job.
 */
class EventStreamClient(
    private val api: RemoteApi,
    private val pingIntervalMs: Long = 20_000,
    /**
     * The server answers every ping with a pong, so a healthy socket receives something at least
     * every [pingIntervalMs]. Silence for this long means a half-open socket (Wi-Fi/mobile switch,
     * Doze, NAT timeout): the OS would take minutes to notice, and finished tasks would be
     * announced that much too late. Tear it down so the connection loop reconnects at once.
     */
    private val stallTimeoutMs: Long = 50_000,
    /** How long one ping may go unanswered once the socket is suspect (silence, network change). */
    private val probeTimeoutMs: Long = 10_000,
    /**
     * Monotonic, and must not run on while the CPU sleeps (Android's `System.nanoTime` is
     * CLOCK_MONOTONIC): our own ping timer is paused then too, so wall-clock time would read the
     * phone's sleep as a silent server and drop a healthy socket on every wake-up.
     */
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    /**
     * [checks]: each element asks "is this socket still alive?" (the network changed): one ping,
     * and the stream fails if nothing arrives within [probeTimeoutMs]. Cheaper and more accurate
     * than a REST probe, which over the relay would need a tunnel of its own.
     */
    fun connect(cursor: StreamCursor, checks: ReceiveChannel<Unit>? = null): Flow<StreamSignal> = callbackFlow {
        val lastRx = java.util.concurrent.atomic.AtomicLong(nowMs())
        val ws = api.openEvents(cursor.lastSeq, cursor.epoch, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                trySend(StreamSignal.Opened)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                lastRx.set(nowMs())
                trySend(StreamSignal.Frame(StreamFrame.parse(text)))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                trySend(StreamSignal.Closed(code, reason))
                channel.close()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                trySend(StreamSignal.Closed(code, reason))
                channel.close()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val body = runCatching { response?.peekBody(4096)?.string() }.getOrNull()
                trySend(StreamSignal.Failure(t, response?.code, body))
                channel.close()
            }
        })
        val pinger = launch {
            while (isActive) {
                val asked = if (checks == null) {
                    delay(pingIntervalMs)
                    false
                } else {
                    withTimeoutOrNull(pingIntervalMs) { checks.receive() } != null
                }
                ws.send("""{"type":"ping"}""")
                if (!asked && nowMs() - lastRx.get() <= stallTimeoutMs) continue
                // Suspect: confirm with the ping just sent before giving up on the socket (after a
                // freeze or a long sleep it is usually still fine, and reconnecting costs far more).
                val sentAt = nowMs()
                delay(probeTimeoutMs)
                if (lastRx.get() < sentAt) {
                    trySend(StreamSignal.Failure(java.io.IOException("event stream stalled (no pong)"), null, null))
                    channel.close()
                    break
                }
            }
        }
        awaitClose {
            pinger.cancel()
            ws.close(1000, null)
            ws.cancel()
        }
    }
}
