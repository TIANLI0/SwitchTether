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
import io.github.tianli0.switchtether.remote.protocol.EventKinds
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
    /** A Live Update card shows this session; its waits and outcomes are not notified twice. */
    private val coveredByLiveUpdate: (String) -> Boolean = { false },
) {
    /** Not thread-safe: every use holds the Notifier's monitor (events, buttons, timers run on different threads). */
    private val engine = NotificationEngine({ prefs.value }, { LocalTime.now().let { it.hour * 60 + it.minute } }, t = ResourceNotifyText(context))
    private val sessions = ConcurrentHashMap<String, SessionSummary>()
    private val nm = NotificationManagerCompat.from(context)

    /**
     * Approvals / questions still unanswered, per session, in the order they opened (request id → the
     * event that opened it): the ATTENTION notification goes with the last one, and shows the most
     * recent one still open when another is resolved.
     */
    private val openRequests = ConcurrentHashMap<String, LinkedHashMap<String, AgentEvent>>()

    /** Per session, the request the posted ATTENTION notification shows ("" = a generic wait). */
    private val attentionShown = ConcurrentHashMap<String, String>()

    private fun forgetRequests(sessionId: String) {
        openRequests.remove(sessionId)
        attentionShown.remove(sessionId)
    }

    private fun requestIdOf(e: AgentEvent): String? = when (e.kind) {
        EventKinds.APPROVAL_REQUEST, EventKinds.QUESTION, EventKinds.APPROVAL_RESOLVED -> e.data.str("requestId") ?: e.id
        else -> null
    }

    /** `when` of each posted notification id, kept when the same moment is updated in place. */
    private val postedWhen = ConcurrentHashMap<Int, Long>()

    private val _activeSessions = kotlinx.coroutines.flow.MutableStateFlow(0)

    /** Live sessions that are working or waiting: while > 0 the monitor service keeps the CPU awake so the event stream stays timely. */
    val activeSessions: StateFlow<Int> = _activeSessions

    private fun refreshActive() {
        _activeSessions.value = sessions.values.count { it.isLive && (it.status == "busy" || it.status == "waiting" || it.waitingFor != null) }
    }

    fun start() {
        createChannels()
        scope.launch {
            conn.events.collect { ev ->
                val data = ev.data as? JsonObject ?: return@collect
                val intent = when (ev.topic) {
                    "session.upserted" -> runCatching { ProtocolJson.decodeFromJsonElement(SessionSummary.serializer(), (data["session"] as? JsonObject) ?: data) }.getOrNull()?.let {
                        sessions[it.id] = it
                        refreshActive()
                        // Nobody is waiting any more: drop the "needs you" notification of that session.
                        synchronized(this@Notifier) {
                            if (it.waitingFor == null && it.status != "waiting") {
                                forgetRequests(it.id)
                                cancel(it.id, NotifySlot.ATTENTION)
                            }
                            engine.onSessionUpsert(it)
                        }
                    }
                    "session.event" -> {
                        runCatching { ProtocolJson.decodeFromJsonElement(AgentEvent.serializer(), (data["event"] as? JsonObject) ?: data) }.getOrNull()?.let { e ->
                            val summary = sessions[e.sessionId] ?: lookup(e.sessionId)
                            // One at a time with the notification buttons (requestAnswered): both
                            // decide which request the "needs you" notification shows.
                            synchronized(this@Notifier) {
                                noteRequest(e)
                                val n = engine.onSessionEvent(e, summary)
                                if (n != null && post(n, timeoutMs = null) && n.type.slot == NotifySlot.ATTENTION) {
                                    // Which request the "needs you" notification now shows (its buttons act on it).
                                    attentionShown[e.sessionId] = requestIdOf(e) ?: ""
                                }
                            }
                        }
                        null
                    }
                    "session.removed" -> {
                        // The process is gone: it no longer counts as working (the monitor's wake
                        // lock follows this count) and nobody can answer its request any more.
                        (data.str("sessionId") ?: data.str("id"))?.let {
                            sessions.remove(it)
                            refreshActive()
                            synchronized(this@Notifier) {
                                forgetRequests(it)
                                cancel(it, NotifySlot.ATTENTION)
                            }
                        }
                        null
                    }
                    "terminal.created" -> {
                        data.str("id")?.let { synchronized(this@Notifier) { engine.onTerminalCreated(it, data.str("title") ?: data.str("shell")) } }
                        null
                    }
                    "terminal.exited" -> data.str("id")?.let { synchronized(this@Notifier) { engine.onTerminalExited(it, data.long("exitCode")) } }
                    else -> null
                }
                if (intent != null) synchronized(this@Notifier) {
                    if (post(intent, timeoutMs = null) && intent.type.slot == NotifySlot.ATTENTION) {
                        intent.sessionId?.let { attentionShown[it] = "" } // a generic wait, no request
                    }
                }
            }
        }
        scope.launch {
            // One connection notification, replaced in place: "lost" only after the computer has been
            // unreachable for a while (short Wi-Fi hiccups heal on their own), and once it is back
            // the same notification says so quietly and goes away.
            var wasOnline = false
            var lostShown = false
            // Whether "lost" really reached the shade (not with the app in front): only then is "reconnected" news.
            var lostPosted = false
            var serverId: String? = null
            var pendingLost: kotlinx.coroutines.Job? = null
            var droppedAt = 0L
            conn.state.collect { s ->
                val desktop = when (s) {
                    is ConnectionState.Online -> s.desktop
                    is ConnectionState.Offline -> s.desktop
                    is ConnectionState.Connecting -> s.desktop
                    else -> null
                }
                if (desktop != null) {
                    // Another computer: the previous one's sessions are not its (the active count keeps the CPU awake).
                    if (serverId != null && serverId != desktop.serverId) {
                        sessions.clear()
                        synchronized(this@Notifier) {
                            openRequests.clear()
                            attentionShown.clear()
                        }
                        refreshActive()
                    }
                    serverId = desktop.serverId
                }
                when (s) {
                    is ConnectionState.Online -> {
                        pendingLost?.cancel()
                        pendingLost = null
                        if (lostShown) {
                            lostShown = false
                            val replaced = lostPosted && synchronized(this@Notifier) { engine.connection(false, s.desktop.name) }?.let { post(it.copy(silent = true), timeoutMs = RECONNECTED_VISIBLE_MS) } == true
                            lostPosted = false
                            // Nothing replaced "connection lost" (app in the foreground, type switched
                            // off, same-minute repeat): it must not stay up while connected.
                            if (!replaced) runCatching { nm.cancel(CONNECTION_NOTIFICATION_ID) }
                        }
                        wasOnline = true
                    }
                    is ConnectionState.Offline -> if (wasOnline && !lostShown && pendingLost == null) {
                        val name = s.desktop.name
                        droppedAt = System.currentTimeMillis()
                        pendingLost = scope.launch {
                            delay(CONNECTION_LOST_GRACE_MS)
                            // Dated when the connection dropped, not 30 s later.
                            synchronized(this@Notifier) { engine.connection(true, name) }?.let { lostPosted = post(it.copy(atMs = droppedAt), timeoutMs = null); lostShown = true }
                            pendingLost = null
                        }
                    }
                    else -> Unit
                }
            }
        }
        scope.launch {
            while (isActive) {
                delay(60_000)
                synchronized(this@Notifier) { engine.longRunning(sessions.values) }.forEach(::post)
            }
        }
    }

    /** Summary of a session not seen in `session.upserted` yet (title/project for the notification text). */
    private suspend fun lookup(sessionId: String): SessionSummary? = runCatching {
        kotlinx.coroutines.withTimeoutOrNull(3_000) { conn.apiOrNull()?.session(sessionId)?.summary }
    }.getOrNull()?.also { sessions[it.id] = it }

    /** The phone itself interrupted/stopped a session or killed a terminal: don't notify the echo. */
    fun noteLocalAction(target: String, action: LocalAction) = synchronized(this) { engine.noteLocalAction(target, action) }

    private fun notificationId(type: NotifyType, sessionId: String?, slot: NotifySlot, key: String): Int = when {
        type == NotifyType.CONNECTION -> CONNECTION_NOTIFICATION_ID
        sessionId != null && slot != NotifySlot.OTHER -> "$sessionId:$slot".hashCode()
        else -> key.hashCode()
    }

    /**
     * A request opened or resolved (in place with `resolved=true`, or by a separate `approval_resolved`):
     * the "needs you" notification is withdrawn only when no other request of the session is still open.
     * When it showed the resolved request, it is re-posted for the most recent request still open (its
     * text and buttons), so its buttons never act on a request that is already answered.
     */
    private fun noteRequest(e: AgentEvent) {
        val resolved = when (e.kind) {
            EventKinds.APPROVAL_RESOLVED -> true
            EventKinds.APPROVAL_REQUEST, EventKinds.QUESTION -> e.data.bool("resolved") == true
            else -> return
        }
        val requestId = requestIdOf(e) ?: return
        val sid = e.sessionId
        if (!resolved) {
            val open = openRequests.getOrPut(sid) { LinkedHashMap() }
            synchronized(open) {
                open.remove(requestId) // seen again (replay): it moves to the end
                open[requestId] = e
            }
            return
        }
        requestResolved(sid, requestId)
    }

    /**
     * The user allowed / denied [requestId] from its notification and the desktop confirmed it. Used
     * instead of cancelling the notification: another request of the session may still be open, and
     * the desktop's `approval_resolved` may already have replaced the notification with it.
     */
    fun requestAnswered(sessionId: String, requestId: String) = requestResolved(sessionId, requestId)

    /**
     * After a push wake (docs/PROTOCOL.md §7.12): live sessions as they are now, fed through the same
     * rules as `session.upserted`. A wait that began while the phone was not connected is announced
     * even when the event that started it is no longer replayed (the app process was ended meanwhile).
     */
    fun catchUp(current: List<SessionSummary>) {
        current.filter { it.isLive }.forEach { s ->
            sessions[s.id] = s
            synchronized(this) {
                val n = engine.onSessionUpsert(s) ?: return@synchronized
                if (post(n, timeoutMs = null) && n.type.slot == NotifySlot.ATTENTION) attentionShown[s.id] = ""
            }
        }
        refreshActive()
    }

    @Synchronized // the event stream and the notification buttons both resolve requests
    private fun requestResolved(sid: String, requestId: String) {
        val open = openRequests[sid]
        val stillOpen = open?.let { synchronized(it) { it.remove(requestId); it.values.lastOrNull() } }
        if (stillOpen == null) {
            forgetRequests(sid)
            cancel(sid, NotifySlot.ATTENTION)
            return
        }
        when (attentionShown[sid]) {
            requestId -> Unit // it shows the resolved request: show the one still open instead
            null -> return cancel(sid, NotifySlot.ATTENTION) // nothing of ours is up (or not known what)
            else -> return // it already shows another request, or a generic wait
        }
        val n = engine.stillOpenRequest(stillOpen, sessions[sid])
        val id = "$sid:${NotifySlot.ATTENTION}".hashCode()
        postedWhen.remove(id) // dated when the request still shown was asked
        if (n != null && post(n, timeoutMs = null)) {
            attentionShown[sid] = requestIdOf(stillOpen) ?: ""
        } else {
            // Not shown again (type switched off, muted, app in front): don't leave the stale buttons up.
            attentionShown.remove(sid)
            cancel(sid, NotifySlot.ATTENTION)
        }
    }

    private fun cancel(sessionId: String, slot: NotifySlot) {
        val id = "$sessionId:$slot".hashCode()
        postedWhen.remove(id)
        runCatching { nm.cancel(id) }
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
        post(n, timeoutMs = null)
    }

    /** @return whether the notification was handed to the system. */
    private fun post(n: NotifyIntent, timeoutMs: Long?): Boolean {
        if (isForeground()) return false
        val sid = n.sessionId
        if (sid != null && coveredByLiveUpdate(sid) && n.type in COVERED_BY_LIVE_UPDATE) return false
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
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
        val id = notificationId(n.type, n.sessionId, n.type.slot, n.key)
        val whenMs = whenFor(id, n, System.currentTimeMillis())
        val b = NotificationCompat.Builder(context, channelId(n.type))
            // Ordered by when it happened: an update of the same moment keeps its place, and an event
            // posted late (lookup, reconnect replay) does not jump above newer ones.
            .setWhen(whenMs)
            .setShowWhen(true)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(n.title)
            .setContentText(n.body.ifBlank { null })
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setSilent(n.silent)
            // The same wait announced again with more detail: update in place, no second sound.
            .setOnlyAlertOnce(n.alertOnce)
            .setCategory(if (n.type == NotifyType.WAITING_APPROVAL || n.type == NotifyType.WAITING_INPUT) NotificationCompat.CATEGORY_REMINDER else NotificationCompat.CATEGORY_STATUS)
            // One "needs you" and one "status" notification per session, grouped by session.
            .setGroup(n.sessionId ?: "switchtether")
        // Long questions / commands are readable when expanded.
        if (n.body.length > 40) b.setStyle(NotificationCompat.BigTextStyle().bigText(n.body))
        timeoutMs?.let(b::setTimeoutAfter)
        // Act without opening the app (each button needs the phone unlocked).
        if (sid != null) {
            val requestId = n.requestId
            val prompt = n.prompt
            when {
                n.type == NotifyType.WAITING_APPROVAL && requestId != null -> {
                    b.addAction(NotifyActionReceiver.decisionAction(context, allow = true, sid, requestId, id))
                    b.addAction(NotifyActionReceiver.decisionAction(context, allow = false, sid, requestId, id))
                }
                prompt != null -> b.addAction(NotifyActionReceiver.answerAction(context, sid, prompt, id))
                n.replyable && n.type != NotifyType.WAITING_APPROVAL -> b.addAction(NotifyActionReceiver.replyAction(context, sid, id))
            }
        }
        return runCatching { nm.notify(id, b.build()) }.isSuccess
    }

    private fun whenFor(id: Int, n: NotifyIntent, now: Long): Long {
        val w = notificationWhen(n, now, postedWhen[id])
        postedWhen[id] = w
        return w
    }

    companion object {
        /**
         * The `when` of a notification: when the moment happened on the computer (never in the
         * future, so a computer clock running ahead does not pin it to the top); an in-place update
         * of the same moment ([NotifyIntent.alertOnce]) keeps the time already shown ([previous]).
         */
        internal fun notificationWhen(n: NotifyIntent, now: Long, previous: Long?): Long =
            if (n.alertOnce && previous != null) previous else minOf(n.atMs ?: now, now)

        const val SERVICE_CHANNEL = "service"
        private const val CONNECTION_NOTIFICATION_ID = 0x5357_0C0A
        private const val CONNECTION_LOST_GRACE_MS = 30_000L
        private const val RECONNECTED_VISIBLE_MS = 8_000L

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
