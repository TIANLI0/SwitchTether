package io.github.tianli0.switchtether.link

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.util.Log
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The UI process's end of the UI ↔ link messages ([LinkProtocol], docs/ANDROID_UI.md §38).
 *
 * Bound while the app is in use ([setForeground]) and for the grace period after it was left; the
 * binding is what keeps the `:link` process's connection up meanwhile. Messages sent while not
 * connected wait (in order) for the connection; requests are answered or time out.
 *
 * [setForeground] and [unbindAfter] run on the main thread; [send] and [request] from any thread.
 */
class LinkClient(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(LinkState())

    /** Pins and forwards as the `:link` process last reported them. */
    val state: StateFlow<LinkState> = _state.asStateFlow()

    private val _ended = MutableSharedFlow<Int>(extraBufferCapacity = 8)
    val forwardEnded: SharedFlow<Int> = _ended.asSharedFlow()

    private val _revoked = MutableSharedFlow<String>(extraBufferCapacity = 2)

    /** Server id the desktop revoked, seen by the `:link` process's connection. */
    val revoked: SharedFlow<String> = _revoked.asSharedFlow()

    private val incoming = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            val data = msg.data
            when (msg.what) {
                LinkProtocol.STATE -> LinkProtocol.decodeState(data.getString(LinkProtocol.K_STATE))?.let { _state.value = it }
                LinkProtocol.RESULT -> data.getString(LinkProtocol.K_REQUEST)?.let { id -> requests.remove(id)?.complete(Bundle(data)) }
                LinkProtocol.FORWARD_ENDED -> _ended.tryEmit(msg.arg1)
                LinkProtocol.REVOKED -> data.getString(LinkProtocol.K_SERVER)?.let { _revoked.tryEmit(it) }
                else -> super.handleMessage(msg)
            }
        }
    })

    private var service: Messenger? = null
    private var bound = false
    private var foreground = false
    private val queue = ArrayDeque<Message>()
    private val requests = ConcurrentHashMap<String, CompletableDeferred<Bundle?>>()
    private var unbindAt: Runnable? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null) return
            val s = Messenger(binder)
            service = s
            deliver(s, LinkProtocol.message(LinkProtocol.REGISTER))
            deliver(s, LinkProtocol.message(LinkProtocol.FOREGROUND, if (foreground) 1 else 0))
            while (queue.isNotEmpty() && service === s) deliver(s, queue.removeFirst())
        }

        /** The `:link` process died; the system binds again once it runs. Forwards ended with it. */
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            _state.value = _state.value.copy(forwards = emptyList())
            failRequests()
        }

        override fun onBindingDied(name: ComponentName?) {
            // The binding cannot be used again: start a fresh one.
            unbindNow()
            bind()
        }
    }

    /** The app came to the front (true) or was left (false). */
    fun setForeground(value: Boolean) {
        foreground = value
        if (value) {
            unbindAt?.let { main.removeCallbacks(it) }
            unbindAt = null
            bind()
        }
        send(LinkProtocol.message(LinkProtocol.FOREGROUND, if (value) 1 else 0))
    }

    /** Lets the binding go after [delayMs] unless the app comes back first (or something is sent). */
    fun unbindAfter(delayMs: Long) {
        unbindAt?.let { main.removeCallbacks(it) }
        val r = Runnable {
            unbindAt = null
            if (!foreground) unbindNow()
        }
        unbindAt = r
        main.postDelayed(r, delayMs)
    }

    /** Any thread (hops to the main thread). */
    fun send(msg: Message) {
        if (Looper.myLooper() !== Looper.getMainLooper()) {
            main.post { send(msg) }
            return
        }
        val s = service
        if (s != null) {
            deliver(s, msg)
        } else {
            queue.addLast(msg)
            bind()
            // Sent while the app is not in use (e.g. a revocation seen during the grace period):
            // the binding is for this message only, not for keeping the link connection up.
            if (!foreground && unbindAt == null) unbindAfter(UNSOLICITED_BIND_MS)
        }
    }

    /** Sends [msg] with a fresh request id and waits for the answer (null: no answer in time / link gone). */
    suspend fun request(msg: Message, timeoutMs: Long = REQUEST_TIMEOUT_MS): Bundle? {
        val id = UUID.randomUUID().toString()
        val answer = CompletableDeferred<Bundle?>()
        requests[id] = answer
        msg.data.putString(LinkProtocol.K_REQUEST, id)
        send(msg)
        return try {
            withTimeoutOrNull(timeoutMs) { answer.await() }
        } finally {
            requests.remove(id)
        }
    }

    private fun deliver(s: Messenger, msg: Message) {
        msg.replyTo = incoming
        try {
            s.send(msg)
        } catch (e: RemoteException) {
            Log.i(TAG, "link process gone: ${e.javaClass.simpleName}")
            // onServiceDisconnected follows; the message is kept for the next connection.
            if (service === s) service = null
            queue.addLast(msg)
        }
    }

    private fun bind() {
        if (bound) return
        bound = runCatching {
            context.bindService(Intent(context, LinkBridgeService::class.java), connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!bound) Log.w(TAG, "cannot bind the link process")
    }

    private fun unbindNow() {
        if (!bound) return
        bound = false
        service = null
        runCatching { context.unbindService(connection) }
        failRequests()
        // Anything queued meanwhile is for a later binding (they all are idempotent or settled by state).
    }

    private fun failRequests() {
        requests.values.forEach { it.complete(null) }
        requests.clear()
    }

    private companion object {
        const val TAG = "ST/LinkClient"
        const val REQUEST_TIMEOUT_MS = 10_000L
        const val UNSOLICITED_BIND_MS = 10_000L
    }
}
