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
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val container = (application as SwitchTetherApp).container
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, Notifier.SERVICE_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(getString(R.string.service_title))
            .setContentText(getString(R.string.service_text))
            .setOngoing(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type) }
            .onFailure {
                stopSelf()
                return START_NOT_STICKY
            }
        container.onServiceKeepAlive()
        return START_STICKY
    }

    override fun onDestroy() {
        (application as SwitchTetherApp).container.onServiceStopped()
        super.onDestroy()
    }

    companion object {
        const val NOTIFICATION_ID = 7
        const val ACTION_STOP = "io.github.tianli0.switchtether.STOP_MONITOR"

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java)) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, MonitorService::class.java)) }
        }
    }
}
