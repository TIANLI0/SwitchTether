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

/**
 * Turns live events into notifications. Stateful only for de-duplication and status transitions;
 * the caller provides the session metadata it knows (for per-agent/per-project rules).
 */
class NotificationEngine(private val prefs: () -> NotifyPrefs, private val minuteOfDay: () -> Int, private val nowMs: () -> Long = System::currentTimeMillis) {
    private val seen = LinkedHashSet<String>()
    private val lastStatus = HashMap<String, String>()
    private val busySince = HashMap<String, Long>()
    private val longRunningNotified = HashSet<String>()

    fun onSessionEvent(e: AgentEvent, session: SessionSummary?): NotifyIntent? {
        val name = sessionName(session, e.sessionId)
        return when (e.kind) {
            EventKinds.APPROVAL_REQUEST -> if (e.data.bool("resolved") == true) null else build(
                NotifyType.WAITING_APPROVAL, "approval:${e.data.str("requestId") ?: e.id}", session, e.sessionId,
                "$name needs approval", e.data.str("title") ?: "Approval requested",
            )
            EventKinds.QUESTION -> if (e.data.bool("resolved") == true) null else build(
                NotifyType.WAITING_INPUT, "question:${e.data.str("requestId") ?: e.id}", session, e.sessionId,
                "$name asks a question", "Answer in SwitchTether or on the computer",
            )
            EventKinds.STATUS -> {
                val state = e.data.str("state") ?: return null
                val prev = lastStatus.put("evt:${e.sessionId}", state)
                if (prev == state) return null
                val key = "status:${e.sessionId}:${e.seq}:$state"
                when (state) {
                    "running" -> build(NotifyType.TASK_STARTED, key, session, e.sessionId, "$name started working", "")
                    "completed" -> build(NotifyType.TASK_COMPLETED, key, session, e.sessionId, "$name finished", e.data.str("detail") ?: "")
                    "idle" -> if (prev == "running") build(NotifyType.TASK_COMPLETED, key, session, e.sessionId, "$name finished", "") else null
                    "error" -> build(NotifyType.TASK_FAILED, key, session, e.sessionId, "$name failed", e.data.str("detail") ?: "")
                    "interrupted" -> build(NotifyType.SESSION_INTERRUPTED, key, session, e.sessionId, "$name was interrupted", "")
                    "waiting_input" -> build(NotifyType.WAITING_INPUT, key, session, e.sessionId, "$name is waiting for input", "")
                    else -> null
                }
            }
            EventKinds.TOOL_CALL -> if (e.data.str("status") == "running") build(
                NotifyType.TOOL_CALL, "tool:${e.id}", session, e.sessionId, "$name: ${e.data.str("name") ?: "tool"}", e.data.str("title") ?: "",
            ) else null
            EventKinds.ASSISTANT_MESSAGE -> if (!e.streaming) build(
                NotifyType.AGENT_REPLY, "reply:${e.id}", session, e.sessionId, "$name replied", (e.data.str("text") ?: "").take(140),
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
                    if (approval) "$name needs approval" else "$name is waiting for you",
                    s.waitingFor ?: "",
                )
            }
            s.status == "error" && prev != null -> build(NotifyType.TASK_FAILED, "upsert:${s.id}:$sig", s, s.id, "$name failed", "")
            else -> null
        }
    }

    /** Call periodically: one reminder when a session has been busy longer than the threshold. */
    fun longRunning(sessions: Collection<SessionSummary>): List<NotifyIntent> {
        val limit = prefs().longRunningMinutes * 60_000L
        return sessions.mapNotNull { s ->
            val since = busySince[s.id] ?: return@mapNotNull null
            if (s.status != "busy" || nowMs() - since < limit || !longRunningNotified.add(s.id)) return@mapNotNull null
            build(NotifyType.LONG_RUNNING, "long:${s.id}:$since", s, s.id, "${sessionName(s, s.id)} is still running", "Running for ${(nowMs() - since) / 60_000} min")
        }
    }

    fun connection(lost: Boolean, desktop: String): NotifyIntent? =
        build(NotifyType.CONNECTION, "conn:$lost:${nowMs() / 60_000}", null, null, if (lost) "Connection to $desktop lost" else "Reconnected to $desktop", "")

    private fun build(type: NotifyType, key: String, s: SessionSummary?, sessionId: String?, title: String, body: String): NotifyIntent? {
        val p = prefs()
        if (type !in p.enabled) return null
        if (s != null && (s.agent in p.mutedAgents || (s.project != null && s.project in p.mutedProjects))) return null
        if (!seen.add(key)) return null
        if (seen.size > 2000) seen.remove(seen.first())
        val public = when (type) {
            NotifyType.WAITING_APPROVAL -> "An agent needs your approval"
            NotifyType.WAITING_INPUT -> "An agent is waiting for you"
            NotifyType.CONNECTION -> title
            else -> "Agent activity"
        }
        return NotifyIntent(type, key, sessionId, title, body, public, silent = p.inQuietHours(minuteOfDay()))
    }

    private fun sessionName(s: SessionSummary?, id: String): String =
        s?.title?.takeIf { it.isNotBlank() } ?: s?.project ?: id.substringAfter(':').take(8)
}
