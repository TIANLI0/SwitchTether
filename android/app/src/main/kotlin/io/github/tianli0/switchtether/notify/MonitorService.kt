package io.github.tianli0.switchtether.notify

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.tianli0.switchtether.MainActivity
import io.github.tianli0.switchtether.R
import io.github.tianli0.switchtether.SwitchTetherApp
import io.github.tianli0.switchtether.connection.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the single desktop connection (events WebSocket) alive while the
 * app is in the background, so approvals/questions/status changes can be notified.
 *
 * Type `connectedDevice`: the app's core function is a live network connection to the user's own
 * paired computer (an external device); see docs/ANDROID_UI.md §10 for why not `dataSync`
 * (6 h/day budget on Android 15, meant for transfers), `remoteMessaging` (text-message continuity
 * between devices) or `specialUse` (only when no standard type fits). Its runtime prerequisite is
 * satisfied by the normal-level CHANGE_NETWORK_STATE permission.
 *
 * Started only from the foreground (Android 12+ background-start restrictions) and only when the
 * user setting "Stay connected in background" is on; stopped on unpair or when turned off.
 */
class MonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watching = false
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    /** Held only while a task is running or waiting for the user, so its completion is announced on time without draining the battery all day. */
    private fun setAwake(awake: Boolean) {
        runCatching {
            if (awake) {
                val lock = wakeLock ?: getSystemService(android.os.PowerManager::class.java)
                    .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "switchtether:monitor").also { it.setReferenceCounted(false); wakeLock = it }
                lock.acquire(WAKE_MAX_MS)
            } else {
                wakeLock?.takeIf { it.isHeld }?.release()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** [forwards]: local ports currently forwarded to the computer (named in the notification, with a stop action). */
    private fun build(online: Boolean, forwards: List<Int> = emptyList()) = NotificationCompat.Builder(this, Notifier.SERVICE_CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_notify)
        .setContentTitle(
            when {
                !online -> getString(R.string.service_title_reconnecting)
                forwards.isNotEmpty() -> resources.getQuantityString(R.plurals.service_title_forwarding, forwards.size, forwards.size)
                else -> getString(R.string.service_title)
            },
        )
        .setContentText(
            if (forwards.isEmpty()) getString(R.string.service_text)
            else forwards.joinToString(" · ") { "localhost:$it" },
        )
        .setOngoing(true)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .apply {
            if (forwards.isNotEmpty()) {
                val stop = PendingIntent.getService(
                    this@MonitorService, 1,
                    Intent(this@MonitorService, MonitorService::class.java).setAction(ACTION_STOP_FORWARDS),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                addAction(NotificationCompat.Action.Builder(null, getString(R.string.forward_stop_all), stop).build())
            }
        }
        .build()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val container = (application as SwitchTetherApp).container
        if (intent?.action == ACTION_STOP_FORWARDS) {
            container.portForwards.stopAll()
            if (watching) return START_STICKY
        }
        val notification = build(container.connection.state.value is ConnectionState.Online)
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type) }
            .onFailure {
                stopSelf()
                return START_NOT_STICKY
            }
        container.onServiceKeepAlive()
        if (!watching) {
            watching = true
            // The ongoing notification says whether the computer is actually reachable right now.
            scope.launch {
                kotlinx.coroutines.flow.combine(
                    container.connection.state.map { it is ConnectionState.Online },
                    container.portForwards.active.map { list -> list.map { it.localPort } },
                ) { online, forwards -> online to forwards }
                    .distinctUntilChanged()
                    .collect { (online, forwards) ->
                        runCatching { androidx.core.app.NotificationManagerCompat.from(this@MonitorService).notify(NOTIFICATION_ID, build(online, forwards)) }
                    }
            }
            scope.launch { container.activeSessions.collect { n -> setAwake(n > 0) } }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        setAwake(false)
        scope.cancel()
        (application as SwitchTetherApp).container.onServiceStopped()
        super.onDestroy()
    }

    companion object {
        const val NOTIFICATION_ID = 7
        private const val WAKE_MAX_MS = 15 * 60_000L
        const val ACTION_STOP = "io.github.tianli0.switchtether.STOP_MONITOR"
        const val ACTION_STOP_FORWARDS = "io.github.tianli0.switchtether.STOP_FORWARDS"

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java)) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, MonitorService::class.java)) }
        }
    }
}
