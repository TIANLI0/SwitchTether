package io.github.tianli0.switchtether.remote.notify

import io.github.tianli0.switchtether.remote.protocol.AgentEvent
import io.github.tianli0.switchtether.remote.protocol.EventKinds
import io.github.tianli0.switchtether.remote.protocol.SessionSummary
import io.github.tianli0.switchtether.remote.protocol.bool
import io.github.tianli0.switchtether.remote.protocol.str

/** Notification categories; each maps to one Android channel. */
enum class NotifyType(val defaultOn: Boolean) {
    WAITING_APPROVAL(true),
    WAITING_INPUT(true),
    TASK_STARTED(false),
    TASK_COMPLETED(true),
    TASK_FAILED(true),
    SESSION_INTERRUPTED(true),
    TOOL_CALL(false),
    AGENT_REPLY(false),
    LONG_RUNNING(true),
    CONNECTION(true),
}

data class NotifyPrefs(
    val enabled: Set<NotifyType> = NotifyType.entries.filter { it.defaultOn }.toSet(),
    val mutedAgents: Set<String> = emptySet(),
    val mutedProjects: Set<String> = emptySet(),
    val quietHoursEnabled: Boolean = false,
    /** Minutes after local midnight; the range may wrap midnight (22:00–07:00). */
    val quietStartMin: Int = 22 * 60,
    val quietEndMin: Int = 7 * 60,
    val longRunningMinutes: Int = 30,
) {
    fun inQuietHours(minuteOfDay: Int): Boolean {
        if (!quietHoursEnabled || quietStartMin == quietEndMin) return false
        return if (quietStartMin < quietEndMin) minuteOfDay in quietStartMin until quietEndMin
        else minuteOfDay >= quietStartMin || minuteOfDay < quietEndMin
    }
}

/**
 * A notification to post. [body] may contain the tool title / message preview (shown only when
 * the device is unlocked: VISIBILITY_PRIVATE); [publicBody] is the redacted lock-screen version and
 * never contains code, commands or message text.
 */
data class NotifyIntent(
    val type: NotifyType,
    val key: String,
    val sessionId: String?,
    val title: String,
    val body: String,
    val publicBody: String,
    /** Quiet hours: still shown, but without sound/vibration. */
    val silent: Boolean,
)

/** Notification wording, kept out of the rules so the app can localize it. */
interface NotifyText {
    fun needsApproval(name: String): String
    fun approvalRequested(): String
    fun asksQuestion(name: String): String
    fun answerHint(): String
    fun started(name: String): String
    fun finished(name: String): String
    fun failed(name: String): String
    fun interrupted(name: String): String
    fun waitingInput(name: String): String
    fun waitingForYou(name: String): String
    fun toolCall(name: String, tool: String?): String
    fun replied(name: String): String
    fun stillRunning(name: String): String
    fun runningFor(minutes: Long): String
    fun connectionLost(desktop: String): String
    fun reconnected(desktop: String): String
    fun publicApproval(): String
    fun publicWaiting(): String
    fun publicActivity(): String

    object English : NotifyText {
        override fun needsApproval(name: String) = "$name needs approval"
        override fun approvalRequested() = "Approval requested"
        override fun asksQuestion(name: String) = "$name asks a question"
        override fun answerHint() = "Answer in SwitchTether or on the computer"
        override fun started(name: String) = "$name started working"
        override fun finished(name: String) = "$name finished"
        override fun failed(name: String) = "$name failed"
        override fun interrupted(name: String) = "$name was interrupted"
        override fun waitingInput(name: String) = "$name is waiting for input"
        override fun waitingForYou(name: String) = "$name is waiting for you"
        override fun toolCall(name: String, tool: String?) = "$name: ${tool ?: "tool"}"
        override fun replied(name: String) = "$name replied"
        override fun stillRunning(name: String) = "$name is still running"
        override fun runningFor(minutes: Long) = "Running for $minutes min"
        override fun connectionLost(desktop: String) = "Connection to $desktop lost"
        override fun reconnected(desktop: String) = "Reconnected to $desktop"
        override fun publicApproval() = "An agent needs your approval"
        override fun publicWaiting() = "An agent is waiting for you"
        override fun publicActivity() = "Agent activity"
    }
}

/**
 * Turns live events into notifications. Stateful only for de-duplication and status transitions;
 * the caller provides the session metadata it knows (for per-agent/per-project rules).
 */
class NotificationEngine(
    private val prefs: () -> NotifyPrefs,
    private val minuteOfDay: () -> Int,
    private val nowMs: () -> Long = System::currentTimeMillis,
    /** User-visible texts; the app supplies localized resources, tests use [NotifyText.English]. */
    private val t: NotifyText = NotifyText.English,
) {
    private val seen = LinkedHashSet<String>()
    private val lastStatus = HashMap<String, String>()
    private val busySince = HashMap<String, Long>()
    private val longRunningNotified = HashSet<String>()

    fun onSessionEvent(e: AgentEvent, session: SessionSummary?): NotifyIntent? {
        val name = sessionName(session, e.sessionId)
        return when (e.kind) {
            EventKinds.APPROVAL_REQUEST -> if (e.data.bool("resolved") == true) null else build(
                NotifyType.WAITING_APPROVAL, "approval:${e.data.str("requestId") ?: e.id}", session, e.sessionId,
                t.needsApproval(name), e.data.str("title") ?: t.approvalRequested(),
            )
            EventKinds.QUESTION -> if (e.data.bool("resolved") == true) null else build(
                NotifyType.WAITING_INPUT, "question:${e.data.str("requestId") ?: e.id}", session, e.sessionId,
                t.asksQuestion(name), t.answerHint(),
            )
            EventKinds.STATUS -> {
                val state = e.data.str("state") ?: return null
                val prev = lastStatus.put("evt:${e.sessionId}", state)
                if (prev == state) return null
                val key = "status:${e.sessionId}:${e.seq}:$state"
                when (state) {
                    "running" -> build(NotifyType.TASK_STARTED, key, session, e.sessionId, t.started(name), "")
                    "completed" -> build(NotifyType.TASK_COMPLETED, key, session, e.sessionId, t.finished(name), e.data.str("detail") ?: "")
                    "idle" -> if (prev == "running") build(NotifyType.TASK_COMPLETED, key, session, e.sessionId, t.finished(name), "") else null
                    "error" -> build(NotifyType.TASK_FAILED, key, session, e.sessionId, t.failed(name), e.data.str("detail") ?: "")
                    "interrupted" -> build(NotifyType.SESSION_INTERRUPTED, key, session, e.sessionId, t.interrupted(name), "")
                    "waiting_input" -> build(NotifyType.WAITING_INPUT, key, session, e.sessionId, t.waitingInput(name), "")
                    else -> null
                }
            }
            EventKinds.TOOL_CALL -> if (e.data.str("status") == "running") build(
                NotifyType.TOOL_CALL, "tool:${e.id}", session, e.sessionId, t.toolCall(name, e.data.str("name")), e.data.str("title") ?: "",
            ) else null
            EventKinds.ASSISTANT_MESSAGE -> if (!e.streaming) build(
                NotifyType.AGENT_REPLY, "reply:${e.id}", session, e.sessionId, t.replied(name), (e.data.str("text") ?: "").take(140),
            ) else null
            else -> null
        }
    }

    /** `session.upserted`: status/waitingFor transitions (also pushed for external live sessions). */
    fun onSessionUpsert(s: SessionSummary): NotifyIntent? {
        val sig = "${s.status}|${s.waitingFor}"
        val prev = lastStatus.put("sum:${s.id}", sig)
        if (s.status == "busy") busySince.putIfAbsent(s.id, nowMs()) else { busySince.remove(s.id); longRunningNotified.remove(s.id) }
        if (prev == sig) return null
        val name = sessionName(s, s.id)
        return when {
            s.status == "waiting" || s.waitingFor != null -> {
                val approval = s.waitingFor?.contains("permission", ignoreCase = true) == true
                build(
                    if (approval) NotifyType.WAITING_APPROVAL else NotifyType.WAITING_INPUT,
                    "upsert:${s.id}:$sig", s, s.id,
                    if (approval) t.needsApproval(name) else t.waitingForYou(name),
                    s.waitingFor ?: "",
                )
            }
            s.status == "error" && prev != null -> build(NotifyType.TASK_FAILED, "upsert:${s.id}:$sig", s, s.id, t.failed(name), "")
            else -> null
        }
    }

    /** Call periodically: one reminder when a session has been busy longer than the threshold. */
    fun longRunning(sessions: Collection<SessionSummary>): List<NotifyIntent> {
        val limit = prefs().longRunningMinutes * 60_000L
        return sessions.mapNotNull { s ->
            val since = busySince[s.id] ?: return@mapNotNull null
            if (s.status != "busy" || nowMs() - since < limit || !longRunningNotified.add(s.id)) return@mapNotNull null
            build(NotifyType.LONG_RUNNING, "long:${s.id}:$since", s, s.id, t.stillRunning(sessionName(s, s.id)), t.runningFor((nowMs() - since) / 60_000))
        }
    }

    fun connection(lost: Boolean, desktop: String): NotifyIntent? =
        build(NotifyType.CONNECTION, "conn:$lost:${nowMs() / 60_000}", null, null, if (lost) t.connectionLost(desktop) else t.reconnected(desktop), "")

    private fun build(type: NotifyType, key: String, s: SessionSummary?, sessionId: String?, title: String, body: String): NotifyIntent? {
        val p = prefs()
        if (type !in p.enabled) return null
        if (s != null && (s.agent in p.mutedAgents || (s.project != null && s.project in p.mutedProjects))) return null
        if (!seen.add(key)) return null
        if (seen.size > 2000) seen.remove(seen.first())
        val public = when (type) {
            NotifyType.WAITING_APPROVAL -> t.publicApproval()
            NotifyType.WAITING_INPUT -> t.publicWaiting()
            NotifyType.CONNECTION -> title
            else -> t.publicActivity()
        }
        return NotifyIntent(type, key, sessionId, title, body, public, silent = p.inQuietHours(minuteOfDay()))
    }

    private fun sessionName(s: SessionSummary?, id: String): String =
        s?.title?.takeIf { it.isNotBlank() } ?: s?.project ?: id.substringAfter(':').take(8)
}
