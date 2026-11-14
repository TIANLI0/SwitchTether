package io.github.tianli0.switchtether.remote.notify

import io.github.tianli0.switchtether.remote.protocol.AgentEvent
import io.github.tianli0.switchtether.remote.protocol.EventKinds
import io.github.tianli0.switchtether.remote.protocol.SessionSummary
import io.github.tianli0.switchtether.remote.protocol.bool
import io.github.tianli0.switchtether.remote.protocol.str

/** Notification categories; each maps to one Android channel and one Settings toggle. */
enum class NotifyType(val defaultOn: Boolean) {
    WAITING_APPROVAL(true),
    WAITING_INPUT(true),
    TASK_STARTED(false),
    TASK_COMPLETED(true),
    TASK_FAILED(true),
    SESSION_INTERRUPTED(true),
    SESSION_EXITED(true),
    TERMINAL_EXITED(true),
    TOOL_CALL(false),
    AGENT_REPLY(false),
    LONG_RUNNING(true),
    CONNECTION(true),
    /** Live Update (status chip / ColorOS Fluid Cloud) of pinned tasks; not produced by the engine. */
    LIVE_UPDATE(true),
}

/** Which Android notification slot an intent replaces: one "needs you" and one "status" per session. */
enum class NotifySlot { ATTENTION, STATUS, OTHER }

val NotifyType.slot: NotifySlot
    get() = when (this) {
        NotifyType.WAITING_APPROVAL, NotifyType.WAITING_INPUT -> NotifySlot.ATTENTION
        NotifyType.CONNECTION, NotifyType.TERMINAL_EXITED -> NotifySlot.OTHER
        else -> NotifySlot.STATUS
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
    /** For [NotifyType.TERMINAL_EXITED]: the terminal to open on tap. */
    val terminalId: String? = null,
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
    fun stopped(name: String): String
    fun exited(name: String): String
    fun terminalExited(title: String, code: Long?): String
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
        override fun stopped(name: String) = "$name stopped before finishing"
        override fun exited(name: String) = "$name ended unexpectedly"
        override fun terminalExited(title: String, code: Long?) = "Terminal $title exited" + (code?.let { " ($it)" } ?: "")
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

/** What the phone itself just did (its own actions are never notified back to it). */
enum class LocalAction { INTERRUPT, STOP, KILL_TERMINAL }

/** True for `waitingFor` values that mean a tool/permission approval (Claude: "permission prompt"). */
fun isApprovalWait(waitingFor: String?): Boolean =
    waitingFor != null && (waitingFor.contains("permission", true) || waitingFor.contains("approv", true))

/**
 * Turns live events into notifications.
 *
 * Sources: `session.upserted` (status busy/idle/waiting/completed/error, waitingFor, liveness) and
 * the session event log (`status` events running/idle/waiting_approval/waiting_input/interrupted/
 * error/completed, `error` events, `system` subtype `turn_stopped`, approval requests, questions),
 * plus `terminal.exited`. Both sources describe the same turn, so outcomes are de-duplicated per
 * **turn**: a session that became busy/running opens a turn; the first completion, failure or
 * interruption closes it and notifies once. Managed, attached (control full) and observed sessions
 * are treated the same.
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
    private val lastLive = HashMap<String, Boolean>()
    private val busySince = HashMap<String, Long>()
    private val longRunningNotified = HashSet<String>()

    /** Session id → turn counter; incremented whenever a new turn starts. */
    private val turn = HashMap<String, Int>()
    /** Session ids whose current turn is open (started and not yet closed by an outcome). */
    private val openTurn = HashSet<String>()
    private val localActions = HashMap<String, Pair<LocalAction, Long>>()
    private val terminalTitles = HashMap<String, String>()

    /** The phone just did [action] on [target] (session or terminal id): don't notify the echo. */
    fun noteLocalAction(target: String, action: LocalAction) {
        localActions[target] = action to nowMs()
    }

    private fun recentLocal(target: String?, vararg actions: LocalAction): Boolean {
        val (a, at) = localActions[target ?: return false] ?: return false
        return a in actions && nowMs() - at < LOCAL_ACTION_GRACE_MS
    }

    private fun startTurn(sid: String) {
        if (openTurn.add(sid)) turn[sid] = (turn[sid] ?: 0) + 1
    }

    /** Closes the open turn; returns its number, or null when no turn was open (nothing to report). */
    private fun closeTurn(sid: String): Int? = if (openTurn.remove(sid)) turn[sid] ?: 0 else null

    fun onSessionEvent(e: AgentEvent, session: SessionSummary?): NotifyIntent? {
        val sid = e.sessionId
        val name = sessionName(session, sid)
        return when (e.kind) {
            EventKinds.APPROVAL_REQUEST -> if (e.data.bool("resolved") == true) null else build(
                NotifyType.WAITING_APPROVAL, "approval:${e.data.str("requestId") ?: e.id}", session, sid,
                t.needsApproval(name), e.data.str("title") ?: t.approvalRequested(),
            )
            EventKinds.QUESTION -> if (e.data.bool("resolved") == true) null else build(
                NotifyType.WAITING_INPUT, "question:${e.data.str("requestId") ?: e.id}", session, sid,
                t.asksQuestion(name), t.answerHint(),
            )
            EventKinds.STATUS -> {
                val state = e.data.str("state") ?: return null
                val prev = lastStatus.put("evt:$sid", state)
                if (prev == state) return null
                when (state) {
                    "running" -> {
                        val fresh = !openTurn.contains(sid)
                        startTurn(sid)
                        if (fresh) build(NotifyType.TASK_STARTED, "start:$sid:${turn[sid]}", session, sid, t.started(name), "") else null
                    }
                    "completed", "idle" -> closeTurn(sid)?.let { n ->
                        build(NotifyType.TASK_COMPLETED, "done:$sid:$n", session, sid, t.finished(name), e.data.str("detail").orEmpty())
                    }
                    "error" -> failTurn(sid, session, name, e.data.str("detail").orEmpty())
                    "interrupted" -> interruptTurn(sid, session, name, t.interrupted(name), e.data.str("detail").orEmpty())
                    "waiting_input" -> build(NotifyType.WAITING_INPUT, "wait-input:$sid:${turn[sid] ?: 0}", session, sid, t.waitingInput(name), "")
                    // waiting_approval is announced by the approval_request event (with its title).
                    else -> null
                }
            }
            EventKinds.ERROR -> failTurn(sid, session, name, e.data.str("message", "text").orEmpty().take(140))
            EventKinds.SYSTEM -> if (e.data.str("subtype") == "turn_stopped") {
                interruptTurn(sid, session, name, t.stopped(name), "")
            } else {
                null
            }
            EventKinds.TOOL_CALL -> if (e.data.str("status") == "running") build(
                NotifyType.TOOL_CALL, "tool:${e.id}", session, sid, t.toolCall(name, e.data.str("name")), e.data.str("title") ?: "",
            ) else null
            EventKinds.ASSISTANT_MESSAGE -> if (!e.streaming) build(
                NotifyType.AGENT_REPLY, "reply:${e.id}", session, sid, t.replied(name), (e.data.str("text") ?: "").take(140),
            ) else null
            else -> null
        }
    }

    private fun failTurn(sid: String, s: SessionSummary?, name: String, detail: String): NotifyIntent? {
        // `status: error`, `error` events and `session.upserted status=error` describe the same failed
        // turn: the key is the turn number, so only the first source notifies.
        val n = closeTurn(sid) ?: (turn[sid] ?: 0)
        return build(NotifyType.TASK_FAILED, "fail:$sid:$n", s, sid, t.failed(name), detail)
    }

    private fun interruptTurn(sid: String, s: SessionSummary?, name: String, title: String, detail: String): NotifyIntent? {
        val n = closeTurn(sid) ?: (turn[sid] ?: 0)
        if (recentLocal(sid, LocalAction.INTERRUPT, LocalAction.STOP)) return null
        return build(NotifyType.SESSION_INTERRUPTED, "interrupt:$sid:$n", s, sid, title, detail)
    }

    /** `session.upserted`: status/waitingFor/liveness transitions (also pushed for external sessions). */
    fun onSessionUpsert(s: SessionSummary): NotifyIntent? {
        val sig = "${s.status}|${s.waitingFor}"
        val prev = lastStatus.put("sum:${s.id}", sig)
        val wasLive = lastLive.put(s.id, s.isLive)
        if (s.status == "busy") busySince.putIfAbsent(s.id, nowMs()) else { busySince.remove(s.id); longRunningNotified.remove(s.id) }
        val name = sessionName(s, s.id)
        // Process gone while the session was working or waiting: report it (unless the phone stopped it).
        if (wasLive == true && !s.isLive) {
            val n = closeTurn(s.id)
            val prevStatus = prev?.substringBefore('|')
            if ((n != null || prevStatus == "busy" || prevStatus == "waiting") && !recentLocal(s.id, LocalAction.STOP, LocalAction.INTERRUPT)) {
                return build(NotifyType.SESSION_EXITED, "exit:${s.id}:${n ?: turn[s.id] ?: 0}", s, s.id, t.exited(name), "")
            }
            return null
        }
        if (prev == sig) return null
        val prevStatus = prev?.substringBefore('|')
        return when {
            s.status == "waiting" || s.waitingFor != null -> {
                val approval = isApprovalWait(s.waitingFor)
                build(
                    if (approval) NotifyType.WAITING_APPROVAL else NotifyType.WAITING_INPUT,
                    "wait:${s.id}:$sig:${turn[s.id] ?: 0}", s, s.id,
                    if (approval) t.needsApproval(name) else t.waitingForYou(name),
                    if (approval) t.approvalRequested() else "",
                )
            }
            s.status == "busy" -> {
                startTurn(s.id)
                null
            }
            s.status == "error" && prev != null -> failTurn(s.id, s, name, "")
            (s.status == "idle" || s.status == "completed") && (prevStatus == "busy" || prevStatus == "waiting" || s.id in openTurn) ->
                closeTurn(s.id)?.let { n -> build(NotifyType.TASK_COMPLETED, "done:${s.id}:$n", s, s.id, t.finished(name), "") }
            else -> null
        }
    }

    /** `terminal.created`: remembers the title for a later exit notification. */
    fun onTerminalCreated(id: String, title: String?) {
        if (!title.isNullOrBlank()) terminalTitles[id] = title
    }

    /** `terminal.exited {id, exitCode}`: not reported when the phone killed the terminal itself. */
    fun onTerminalExited(id: String, exitCode: Long?): NotifyIntent? {
        if (recentLocal(id, LocalAction.KILL_TERMINAL)) return null
        val title = terminalTitles.remove(id) ?: id.take(8)
        return build(NotifyType.TERMINAL_EXITED, "term:$id", null, null, t.terminalExited(title, exitCode), "")?.copy(terminalId = id)
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
        if (!seen.add(key)) return null
        if (seen.size > 2000) seen.remove(seen.first())
        val p = prefs()
        if (type !in p.enabled) return null
        if (s != null && (s.agent in p.mutedAgents || (s.project != null && s.project in p.mutedProjects))) return null
        val public = when (type) {
            NotifyType.WAITING_APPROVAL -> t.publicApproval()
            NotifyType.WAITING_INPUT -> t.publicWaiting()
            NotifyType.CONNECTION -> title
            else -> t.publicActivity()
        }
        return NotifyIntent(type, key, sessionId, title, body, public, silent = p.inQuietHours(minuteOfDay()))
    }

    private fun sessionName(s: SessionSummary?, id: String): String =
        s?.title?.takeIf { it.isNotBlank() } ?: s?.project?.takeIf { it.isNotBlank() }
            ?: s?.cwd?.trimEnd('/', '\\')?.substringAfterLast('/')?.substringAfterLast('\\')?.takeIf { it.isNotBlank() }
            ?: id.substringAfter(':').take(8)

    companion object {
        /** How long an interrupt/stop/kill done from the phone suppresses the echo notification. */
        const val LOCAL_ACTION_GRACE_MS = 60_000L
    }
}
