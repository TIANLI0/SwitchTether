package io.github.tianli0.switchtether.link

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.util.Log
import io.github.tianli0.switchtether.SwitchTetherApp
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.launch

/** What the UI process may ask of the `:link` process ([LinkContainer]; a fake in tests). */
interface LinkHost {
    val appScope: kotlinx.coroutines.CoroutineScope
    fun onUiForeground(foreground: Boolean)
    fun pin(summary: io.github.tianli0.switchtether.remote.protocol.SessionSummary, manual: Boolean): Boolean
    fun unpin(sessionId: String)
    fun onSessionOpened(sessionId: String)
    fun noteLocalAction(target: String, action: io.github.tianli0.switchtether.remote.notify.LocalAction)
    suspend fun attachForward(lease: io.github.tianli0.switchtether.remote.protocol.ForwardView, localPort: Int, process: String?, listening: Boolean?): io.github.tianli0.switchtether.forward.StartResult
    fun updateForwardLease(lease: io.github.tianli0.switchtether.remote.protocol.ForwardView)
    fun stopForward(leaseId: String)
    fun onReset()
    fun onCompanionPresence(present: Boolean?)
}

/**
 * The `:link` end of the UI ↔ link messages ([LinkProtocol]). Runs on the main thread; anything that
 * takes time is launched in the host's scope and answered with [LinkProtocol.RESULT].
 */
class LinkBridge(private val link: LinkHost) : Handler(Looper.getMainLooper()) {
    val messenger = Messenger(this)
    private val clients = CopyOnWriteArrayList<Messenger>()
    @Volatile private var lastState = LinkState()

    override fun handleMessage(msg: Message) {
        val data = msg.data
        when (msg.what) {
            LinkProtocol.REGISTER -> msg.replyTo?.let { client ->
                if (client !in clients) clients += client
                send(client, LinkProtocol.message(LinkProtocol.STATE) { putString(LinkProtocol.K_STATE, LinkProtocol.encodeState(lastState)) })
            }
            LinkProtocol.FOREGROUND -> link.onUiForeground(msg.arg1 == 1)
            LinkProtocol.PIN -> {
                val replyTo = msg.replyTo
                val request = data.getString(LinkProtocol.K_REQUEST)
                val summary = LinkProtocol.decodeSummary(data.getString(LinkProtocol.K_SUMMARY))
                val ok = summary != null && link.pin(summary, manual = data.getBoolean(LinkProtocol.K_MANUAL, true))
                if (replyTo != null && request != null) {
                    send(replyTo, LinkProtocol.message(LinkProtocol.RESULT) {
                        putString(LinkProtocol.K_REQUEST, request)
                        putBoolean(LinkProtocol.K_OK, ok)
                    })
                }
            }
            LinkProtocol.UNPIN -> data.getString(LinkProtocol.K_SESSION)?.let { link.unpin(it) }
            LinkProtocol.SESSION_OPENED -> data.getString(LinkProtocol.K_SESSION)?.let { link.onSessionOpened(it) }
            LinkProtocol.LOCAL_ACTION -> {
                val target = data.getString(LinkProtocol.K_TARGET)
                val action = LinkProtocol.localAction(data.getString(LinkProtocol.K_ACTION))
                if (target != null && action != null) link.noteLocalAction(target, action)
            }
            LinkProtocol.FORWARD_ATTACH -> {
                val replyTo = msg.replyTo
                val request = data.getString(LinkProtocol.K_REQUEST)
                val lease = LinkProtocol.decodeLease(data.getString(LinkProtocol.K_LEASE))
                val localPort = data.getInt(LinkProtocol.K_LOCAL_PORT, lease?.port ?: 0)
                val process = data.getString(LinkProtocol.K_PROCESS)
                val listening = LinkProtocol.tri(data.getInt(LinkProtocol.K_LISTENING, -1))
                link.appScope.launch {
                    val result = if (lease == null) {
                        io.github.tianli0.switchtether.forward.StartResult.Failed("bad lease")
                    } else {
                        link.attachForward(lease, localPort, process, listening)
                    }
                    if (replyTo != null && request != null) {
                        send(replyTo, LinkProtocol.message(LinkProtocol.RESULT) {
                            putString(LinkProtocol.K_REQUEST, request)
                            putString(LinkProtocol.K_RESULT, LinkProtocol.encodeResult(result))
                        })
                    }
                }
            }
            LinkProtocol.FORWARD_LEASE -> LinkProtocol.decodeLease(data.getString(LinkProtocol.K_LEASE))?.let { link.updateForwardLease(it) }
            LinkProtocol.FORWARD_STOP -> data.getString(LinkProtocol.K_LEASE_ID)?.let { link.stopForward(it) }
            LinkProtocol.RESET -> link.onReset()
            LinkProtocol.COMPANION -> link.onCompanionPresence(LinkProtocol.tri(msg.arg1))
            else -> super.handleMessage(msg)
        }
    }

    /** All clients are gone (the UI process unbound or died). */
    fun onUnbound() {
        clients.clear()
    }

    fun pushState(state: LinkState) {
        lastState = state
        broadcast { LinkProtocol.message(LinkProtocol.STATE) { putString(LinkProtocol.K_STATE, LinkProtocol.encodeState(state)) } }
    }

    fun forwardEnded(port: Int) = broadcast { LinkProtocol.message(LinkProtocol.FORWARD_ENDED, port) }

    fun revoked(serverId: String) = broadcast { LinkProtocol.message(LinkProtocol.REVOKED) { putString(LinkProtocol.K_SERVER, serverId) } }

    private fun broadcast(make: () -> Message) {
        clients.forEach { send(it, make()) }
    }

    private fun send(client: Messenger, msg: Message) {
        try {
            client.send(msg)
        } catch (e: RemoteException) {
            Log.i(TAG, "UI client gone: ${e.javaClass.simpleName}")
            clients.remove(client)
        }
    }

    private companion object {
        const val TAG = "ST/Link"
    }
}

/**
 * Bound by the UI process while the app is in use (and for a short grace after it is left). Not
 * exported; lives in the `:link` process. Binding is what tells the link process to keep its
 * connection; the bound UI is told apart from a merely cached one by [LinkProtocol.FOREGROUND].
 */
class LinkBridgeService : Service() {
    private fun link(): LinkContainer? = (application as? SwitchTetherApp)?.link

    override fun onBind(intent: Intent?): IBinder? {
        val link = link() ?: return null
        link.onUiBound()
        return link.bridge.messenger.binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        link()?.let {
            it.bridge.onUnbound()
            it.onUiUnbound()
        }
        return false
    }
}

/**
 * Periodic recovery in the `:link` process, sent by [io.github.tianli0.switchtether.connection.ReconnectWorker]
 * (WorkManager runs in the UI process). Not exported.
 */
class LinkReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val link = (context.applicationContext as? SwitchTetherApp)?.link ?: return
        if (intent.action != ACTION_RECOVER) return
        val pending = goAsync()
        val job = link.appScope.launch { link.recover() }
        // Never hold goAsync() past the ~10 s background BroadcastReceiver ANR budget;
        // the recovery coroutine finishes in the `:link` process if it needs longer.
        link.appScope.launch {
            try {
                kotlinx.coroutines.withTimeoutOrNull(8_000) { job.join() }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_RECOVER = "io.github.tianli0.switchtether.link.RECOVER"

        fun recover(context: Context) {
            runCatching { context.sendBroadcast(Intent(context, LinkReceiver::class.java).setAction(ACTION_RECOVER)) }
        }
    }
}
