package io.github.tianli0.switchtether.connection

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import io.github.tianli0.switchtether.SwitchTetherApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Keeps the background connection honest while the phone sleeps (owned by the monitor service).
 *
 * A foreground service keeps the process and its network, but not the CPU: with the screen off
 * the CPU sleeps between packets, and coroutine timers stop with it. Two consequences hurt most
 * over the relay, where tunnels are recycled (relay or Cloudflare restarts, NAT, network switches):
 *
 * - **Reconnecting stalls.** After a drop, the backoff delay and the next handshake only ran when
 *   something else woke the phone, often minutes later. While the connection is down a partial
 *   wake lock is held, for at most [OUTAGE_WAKE_MS] per outage, so the reconnect actually runs.
 * - **A dead path is not noticed.** A healthy socket receives the desktop's ping every 20 s, and
 *   each packet wakes the phone. A path that died silently sends nothing, so nothing wakes the
 *   phone to find out. An allow-while-idle alarm every [TICK_MS] (Doze may stretch it) wakes it
 *   briefly: online, the event stream is checked with one ping; offline, a reconnect is started
 *   with a fresh short wake window.
 */
class KeepAlive(
    private val context: Context,
    private val scope: CoroutineScope,
    private val state: StateFlow<ConnectionState>,
) {
    private val power = context.getSystemService(PowerManager::class.java)
    private val lock: PowerManager.WakeLock? = runCatching {
        power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "switchtether:reconnect").also { it.setReferenceCounted(false) }
    }.getOrNull()

    /** When the current outage began (elapsed realtime), or null while online. */
    @Volatile private var outageSince: Long? = null
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            state.collect { s ->
                when (s) {
                    is ConnectionState.Online -> {
                        outageSince = null
                        release()
                    }
                    is ConnectionState.Connecting, is ConnectionState.Offline -> holdForOutage()
                    else -> {
                        outageSince = null
                        release()
                    }
                }
            }
        }
        schedule(context)
    }

    fun stop() {
        job?.cancel()
        job = null
        outageSince = null
        release()
        cancel(context)
    }

    /** The alarm fired: give the connection a short window to prove itself or come back. */
    fun tick(connection: ConnectionManager) {
        val s = state.value
        if (s is ConnectionState.Online) {
            // One ping on the live socket; an unanswered one fails the stream within 10 s, and the
            // outage that follows takes its own wake lock.
            hold(TICK_WAKE_MS)
            connection.onNetworkAvailable()
        } else if (s is ConnectionState.Connecting || s is ConnectionState.Offline) {
            // Long outage: allow another short reconnect window from now.
            outageSince = SystemClock.elapsedRealtime() - (OUTAGE_WAKE_MS - TICK_RETRY_WAKE_MS)
            holdForOutage()
            connection.retryNow()
        }
        schedule(context)
    }

    private fun holdForOutage() {
        val now = SystemClock.elapsedRealtime()
        val since = outageSince ?: now.also { outageSince = it }
        val left = OUTAGE_WAKE_MS - (now - since)
        if (left > 0) hold(left) else release()
    }

    private fun hold(ms: Long) {
        runCatching { lock?.acquire(ms) }
    }

    private fun release() {
        runCatching { lock?.takeIf { it.isHeld }?.release() }
    }

    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            (context.applicationContext as? SwitchTetherApp)?.link?.keepAliveTick()
        }
    }

    companion object {
        /** Longest wake lock per outage: enough for several relay attempts, bounded for the battery. */
        const val OUTAGE_WAKE_MS = 3 * 60_000L
        /** Slightly longer than EventStreamClient's 10 s probe timeout so a healthy ping releases promptly. */
        private const val TICK_WAKE_MS = 11_000L
        private const val TICK_RETRY_WAKE_MS = 60_000L

        /** Liveness check while asleep (Doze batches allow-while-idle alarms to about every 9 min or more). */
        const val TICK_MS = 5 * 60_000L

        private fun pending(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, Receiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        fun schedule(context: Context) {
            runCatching {
                context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + TICK_MS,
                    pending(context),
                )
            }
        }

        fun cancel(context: Context) {
            runCatching { context.getSystemService(AlarmManager::class.java).cancel(pending(context)) }
        }
    }
}
