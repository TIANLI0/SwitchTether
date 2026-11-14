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
import io.github.tianli0.switchtether.remote.notify.LocalAction
import io.github.tianli0.switchtether.remote.notify.NotifySlot
import io.github.tianli0.switchtether.remote.notify.slot
import io.github.tianli0.switchtether.remote.protocol.long
import io.github.tianli0.switchtether.remote.protocol.str
import io.github.tianli0.switchtether.remote.protocol.bool
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
    /** Pinned sessions are followed by their Live Update; their waits and outcomes are not notified twice. */
    private val isPinned: (String) -> Boolean = { false },
) {
    private val engine = NotificationEngine({ prefs.value }, { LocalTime.now().let { it.hour * 60 + it.minute } }, t = ResourceNotifyText(context))
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
                        // Nobody is waiting any more: drop the "needs you" notification of that session.
                        if (it.waitingFor == null && it.status != "waiting") cancel(it.id, NotifySlot.ATTENTION)
                        engine.onSessionUpsert(it)
                    }
                    "session.event" -> runCatching { ProtocolJson.decodeFromJsonElement(AgentEvent.serializer(), (data["event"] as? JsonObject) ?: data) }.getOrNull()?.let {
                        if ((it.kind == "approval_request" || it.kind == "question") && it.data.bool("resolved") == true) cancel(it.sessionId, NotifySlot.ATTENTION)
                        engine.onSessionEvent(it, sessions[it.sessionId] ?: lookup(it.sessionId))
                    }
                    "terminal.created" -> {
                        data.str("id")?.let { engine.onTerminalCreated(it, data.str("title") ?: data.str("shell")) }
                        null
                    }
                    "terminal.exited" -> data.str("id")?.let { engine.onTerminalExited(it, data.long("exitCode")) }
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

    /** Summary of a session not seen in `session.upserted` yet (title/project for the notification text). */
    private suspend fun lookup(sessionId: String): SessionSummary? = runCatching {
        kotlinx.coroutines.withTimeoutOrNull(3_000) { conn.apiOrNull()?.session(sessionId)?.summary }
    }.getOrNull()?.also { sessions[it.id] = it }

    /** The phone itself interrupted/stopped a session or killed a terminal: don't notify the echo. */
    fun noteLocalAction(target: String, action: LocalAction) = engine.noteLocalAction(target, action)

    private fun notificationId(sessionId: String?, slot: NotifySlot, key: String): Int =
        if (sessionId != null && slot != NotifySlot.OTHER) "$sessionId:$slot".hashCode() else key.hashCode()

    private fun cancel(sessionId: String, slot: NotifySlot) {
        runCatching { nm.cancel(notificationId(sessionId, slot, "")) }
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java)
        NotifyType.entries.filter { it != NotifyType.LIVE_UPDATE }.forEach { t ->
            val importance = when (t) {
                NotifyType.WAITING_APPROVAL, NotifyType.WAITING_INPUT, NotifyType.TASK_FAILED, NotifyType.SESSION_EXITED -> NotificationManager.IMPORTANCE_HIGH
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
        val sid = n.sessionId
        if (sid != null && isPinned(sid) && n.type in COVERED_BY_LIVE_UPDATE) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val tap = PendingIntent.getActivity(
            context,
            n.key.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                n.sessionId?.let { putExtra(MainActivity.EXTRA_SESSION_ID, it) }
                n.terminalId?.let { putExtra(MainActivity.EXTRA_TERMINAL_ID, it) }
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
            // One "needs you" and one "status" notification per session, grouped by session.
            .setGroup(n.sessionId ?: "switchtether")
            .build()
        runCatching { nm.notify(notificationId(n.sessionId, n.type.slot, n.key), notif) }
    }

    companion object {
        const val SERVICE_CHANNEL = "service"

        private val COVERED_BY_LIVE_UPDATE = setOf(
            NotifyType.WAITING_APPROVAL, NotifyType.WAITING_INPUT, NotifyType.TASK_STARTED, NotifyType.TASK_COMPLETED,
            NotifyType.TASK_FAILED, NotifyType.SESSION_INTERRUPTED, NotifyType.SESSION_EXITED, NotifyType.LONG_RUNNING, NotifyType.TOOL_CALL,
        )

        fun channelId(t: NotifyType) = "evt_" + t.name.lowercase()

        fun channelName(t: NotifyType): Int = when (t) {
            NotifyType.WAITING_APPROVAL -> R.string.notif_ch_approval
            NotifyType.WAITING_INPUT -> R.string.notif_ch_input
            NotifyType.TASK_STARTED -> R.string.notif_ch_started
            NotifyType.TASK_COMPLETED -> R.string.notif_ch_completed
            NotifyType.TASK_FAILED -> R.string.notif_ch_failed
            NotifyType.SESSION_INTERRUPTED -> R.string.notif_ch_interrupted
            NotifyType.SESSION_EXITED -> R.string.notif_ch_exited
            NotifyType.TERMINAL_EXITED -> R.string.notif_ch_terminal
            NotifyType.LIVE_UPDATE -> R.string.notif_ch_live
            NotifyType.TOOL_CALL -> R.string.notif_ch_tool
            NotifyType.AGENT_REPLY -> R.string.notif_ch_reply
            NotifyType.LONG_RUNNING -> R.string.notif_ch_long
            NotifyType.CONNECTION -> R.string.notif_ch_connection
        }
    }
}

/** Localized notification wording from app resources (follows the per-app language). */
private class ResourceNotifyText(private val c: Context) : io.github.tianli0.switchtether.remote.notify.NotifyText {
    private fun s(id: Int, vararg a: Any) = c.getString(id, *a)
    override fun needsApproval(name: String) = s(R.string.ntf_needs_approval, name)
    override fun approvalRequested() = s(R.string.ntf_approval_requested)
    override fun asksQuestion(name: String) = s(R.string.ntf_asks_question, name)
    override fun answerHint() = s(R.string.ntf_answer_hint)
    override fun started(name: String) = s(R.string.ntf_started, name)
    override fun finished(name: String) = s(R.string.ntf_finished, name)
    override fun failed(name: String) = s(R.string.ntf_failed, name)
    override fun interrupted(name: String) = s(R.string.ntf_interrupted, name)
    override fun stopped(name: String) = s(R.string.ntf_stopped, name)
    override fun exited(name: String) = s(R.string.ntf_exited, name)
    override fun terminalExited(title: String, code: Long?) =
        if (code != null) s(R.string.ntf_terminal_exited_code, title, code.toInt()) else s(R.string.ntf_terminal_exited, title)
    override fun waitingInput(name: String) = s(R.string.ntf_waiting_input, name)
    override fun waitingForYou(name: String) = s(R.string.ntf_waiting_for_you, name)
    override fun toolCall(name: String, tool: String?) = s(R.string.ntf_tool_call, name, tool ?: s(R.string.ntf_tool))
    override fun replied(name: String) = s(R.string.ntf_replied, name)
    override fun stillRunning(name: String) = s(R.string.ntf_still_running, name)
    override fun runningFor(minutes: Long) = s(R.string.ntf_running_for, minutes.toInt())
    override fun connectionLost(desktop: String) = s(R.string.ntf_connection_lost, desktop)
    override fun reconnected(desktop: String) = s(R.string.ntf_reconnected, desktop)
    override fun publicApproval() = s(R.string.ntf_public_approval)
    override fun publicWaiting() = s(R.string.ntf_public_waiting)
    override fun publicActivity() = s(R.string.ntf_public_activity)
}
