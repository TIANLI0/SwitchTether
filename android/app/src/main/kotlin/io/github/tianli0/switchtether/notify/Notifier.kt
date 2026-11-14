package io.github.tianli0.switchtether.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.tianli0.switchtether.MainActivity
import io.github.tianli0.switchtether.R
import io.github.tianli0.switchtether.connection.ConnectionController
import io.github.tianli0.switchtether.connection.ConnectionState
import io.github.tianli0.switchtether.remote.notify.NotificationEngine
import io.github.tianli0.switchtether.remote.notify.NotifyIntent
import io.github.tianli0.switchtether.remote.notify.NotifyPrefs
import io.github.tianli0.switchtether.remote.notify.NotifyType
import io.github.tianli0.switchtether.remote.protocol.AgentEvent
import io.github.tianli0.switchtether.remote.protocol.ProtocolJson
import io.github.tianli0.switchtether.remote.protocol.SessionSummary
import java.time.LocalTime
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * Derives notifications from the live event stream (`session.event`, `session.upserted`,
 * connection state) through [NotificationEngine] and posts them on per-type channels.
 * Lock screen: VISIBILITY_PRIVATE with a redacted public version (never code or message text).
 * Only posts while the app is not in the foreground.
 */
class Notifier(
    private val context: Context,
    private val scope: CoroutineScope,
    private val conn: ConnectionController,
    private val prefs: StateFlow<NotifyPrefs>,
    private val isForeground: () -> Boolean,
) {
    private val engine = NotificationEngine({ prefs.value }, { LocalTime.now().let { it.hour * 60 + it.minute } })
    private val sessions = ConcurrentHashMap<String, SessionSummary>()
    private val nm = NotificationManagerCompat.from(context)

    fun start() {
        createChannels()
        scope.launch {
            conn.events.collect { ev ->
                val data = ev.data as? JsonObject ?: return@collect
                val intent = when (ev.topic) {
                    "session.upserted" -> runCatching { ProtocolJson.decodeFromJsonElement(SessionSummary.serializer(), (data["session"] as? JsonObject) ?: data) }.getOrNull()?.let {
                        sessions[it.id] = it
                        engine.onSessionUpsert(it)
                    }
                    "session.event" -> runCatching { ProtocolJson.decodeFromJsonElement(AgentEvent.serializer(), (data["event"] as? JsonObject) ?: data) }.getOrNull()?.let {
                        engine.onSessionEvent(it, sessions[it.sessionId])
                    }
                    else -> null
                }
                intent?.let(::post)
            }
        }
        scope.launch {
            var wasOnline: String? = null
            conn.state.collect { s ->
                when (s) {
                    is ConnectionState.Online -> {
                        if (wasOnline == "lost") engine.connection(false, s.desktop.name)?.let(::post)
                        wasOnline = "online"
                    }
                    is ConnectionState.Offline -> if (wasOnline == "online") {
                        engine.connection(true, s.desktop.name)?.let(::post)
                        wasOnline = "lost"
                    }
                    else -> Unit
                }
            }
        }
        scope.launch {
            while (isActive) {
                delay(60_000)
                engine.longRunning(sessions.values).forEach(::post)
            }
        }
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java)
        NotifyType.entries.forEach { t ->
            val importance = when (t) {
                NotifyType.WAITING_APPROVAL, NotifyType.WAITING_INPUT, NotifyType.TASK_FAILED -> NotificationManager.IMPORTANCE_HIGH
                NotifyType.TOOL_CALL, NotifyType.TASK_STARTED -> NotificationManager.IMPORTANCE_LOW
                else -> NotificationManager.IMPORTANCE_DEFAULT
            }
            // Sound/vibration per channel are user-adjustable in system settings (channel-level).
            mgr.createNotificationChannel(NotificationChannel(channelId(t), context.getString(channelName(t)), importance).apply {
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            })
        }
        mgr.createNotificationChannel(NotificationChannel(SERVICE_CHANNEL, context.getString(R.string.notif_ch_service), NotificationManager.IMPORTANCE_MIN))
    }

    private fun post(n: NotifyIntent) {
        if (isForeground()) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val tap = PendingIntent.getActivity(
            context,
            n.key.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                n.sessionId?.let { putExtra(MainActivity.EXTRA_SESSION_ID, it) }
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val public = NotificationCompat.Builder(context, channelId(n.type))
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(n.publicBody)
            .build()
        val notif = NotificationCompat.Builder(context, channelId(n.type))
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(n.title)
            .setContentText(n.body.ifBlank { null })
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setSilent(n.silent)
            .setCategory(if (n.type == NotifyType.WAITING_APPROVAL || n.type == NotifyType.WAITING_INPUT) NotificationCompat.CATEGORY_REMINDER else NotificationCompat.CATEGORY_STATUS)
            .setGroup(n.sessionId ?: "switchtether")
            .build()
        runCatching { nm.notify(n.sessionId?.hashCode() ?: n.type.ordinal, notif) }
    }

    companion object {
        const val SERVICE_CHANNEL = "service"

        fun channelId(t: NotifyType) = "evt_" + t.name.lowercase()

        fun channelName(t: NotifyType): Int = when (t) {
            NotifyType.WAITING_APPROVAL -> R.string.notif_ch_approval
            NotifyType.WAITING_INPUT -> R.string.notif_ch_input
            NotifyType.TASK_STARTED -> R.string.notif_ch_started
            NotifyType.TASK_COMPLETED -> R.string.notif_ch_completed
            NotifyType.TASK_FAILED -> R.string.notif_ch_failed
            NotifyType.SESSION_INTERRUPTED -> R.string.notif_ch_interrupted
            NotifyType.TOOL_CALL -> R.string.notif_ch_tool
            NotifyType.AGENT_REPLY -> R.string.notif_ch_reply
            NotifyType.LONG_RUNNING -> R.string.notif_ch_long
            NotifyType.CONNECTION -> R.string.notif_ch_connection
        }
    }
}
