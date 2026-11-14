package io.github.tianli0.switchtether.remote.events

import io.github.tianli0.switchtether.remote.api.RemoteApi
import io.github.tianli0.switchtether.remote.protocol.ProtocolJson
import io.github.tianli0.switchtether.remote.protocol.long
import io.github.tianli0.switchtether.remote.protocol.obj
import io.github.tianli0.switchtether.remote.protocol.str
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
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
                    seq = o.long("seq") ?: return Unknown(type),
                    epoch = o.str("epoch") ?: "",
                    ts = o.str("ts") ?: "",
                    topic = o.str("topic") ?: "",
                    data = o["data"] ?: JsonNull,
                )
                "resync" -> Resync(o.str("epoch") ?: "", o.long("headSeq") ?: 0, o.str("reason") ?: "unknown")
                "pong" -> Pong(o.str("ts"))
                else -> Unknown(type)
            }
        }
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
class EventStreamClient(private val api: RemoteApi, private val pingIntervalMs: Long = 20_000) {
    fun connect(cursor: StreamCursor): Flow<StreamSignal> = callbackFlow {
        val ws = api.openEvents(cursor.lastSeq, cursor.epoch, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                trySend(StreamSignal.Opened)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
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
                delay(pingIntervalMs)
                ws.send("""{"type":"ping"}""")
            }
        }
        awaitClose {
            pinger.cancel()
            ws.close(1000, null)
            ws.cancel()
        }
    }
}
