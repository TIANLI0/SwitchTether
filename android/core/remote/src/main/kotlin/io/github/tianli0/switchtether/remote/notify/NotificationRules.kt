package io.github.tianli0.switchtether.remote.notify

import io.github.tianli0.switchtether.remote.protocol.AgentEvent
import io.github.tianli0.switchtether.remote.protocol.EventKinds
import io.github.tianli0.switchtether.remote.protocol.SessionSummary
import io.github.tianli0.switchtether.remote.protocol.ControlLevel
import io.github.tianli0.switchtether.remote.protocol.arr
import io.github.tianli0.switchtether.remote.protocol.bool
import io.github.tianli0.switchtether.remote.protocol.obj
import io.github.tianli0.switchtether.remote.protocol.str
import kotlinx.serialization.json.JsonObject

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
    /** An approval request: the notification offers allow / deny (phone unlocked). */
    val requestId: String? = null,
    /** A single question that can be answered from the notification. */
    val prompt: QuestionPrompt? = null,
    /** The phone controls this live session: the notification offers an inline reply. */
    val replyable: Boolean = false,
    /** The same "needs you" moment was already announced: update the notification without a second alert. */
    val alertOnce: Boolean = false,
    /**
     * When the reported moment happened on the computer (event `ts` / summary `lastActiveAtMs`, epoch
     * ms); null = now. Becomes the notification's `when`, so the shade orders by when things happened,
     * not by when the phone got to post them.
     */
    val atMs: Long? = null,
)

/**
 * A question answerable inline: one question, single choice, not secret. [labels] are offered as
 * choices; [values] are what is sent for them (`value` when the option has one, else the label).
 * [freeText]: other answers may be typed.
 */
data class QuestionPrompt(
    val requestId: String,
    val key: String,
    val labels: List<String>,
    val values: List<String>,
    val freeText: Boolean,
) {
    /** What to send for a typed or chosen reply: a chosen label maps to its value. */
    fun valueFor(reply: String): String = labels.indexOf(reply).takeIf { it >= 0 }?.let { values[it] } ?: reply
}

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
    // REST hydration / reconnect replay must never announce a historical turn again.
    private val startedAtMs = nowMs()
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

    /**
     * Sessions whose last turn has been closed and no new one started (no new prompt, `running` or
     * busy since), with the outcome notification produced for it (null: none, e.g. type disabled).
     * A tool call or reply that arrives now is the tail of that turn (observed sessions take their
     * status from the registry and their steps from the transcript, which can lag behind) and must not
     * replace "finished" in the session's status slot.
     */
    private val settled = HashMap<String, NotifyIntent?>()

    private fun settle(sid: String, outcome: NotifyIntent?): NotifyIntent? {
        settled[sid] = outcome
        return outcome
    }
    private val terminalTitles = HashMap<String, String>()

    /**
     * Per session, how the current "needs you" moment was announced: `true` by the request event
     * (with what is asked), `false` by the generic status change. Both describe the same wait, so
     * only the first alerts; a later request event still replaces the generic text silently.
     */
    private val attentionBy = HashMap<String, Boolean>()

    /**
     * Session id → how many "needs you" moments have ended. Part of the generic wait keys: one turn
     * can stop for the user several times, and each of those waits is announced.
     */
    private val waitNo = HashMap<String, Int>()

    private fun waitEnded(sid: String) {
        waitNo[sid] = (waitNo[sid] ?: 0) + 1
    }

    private inline fun attention(sid: String, make: () -> NotifyIntent?): NotifyIntent? {
        val n = make() ?: return null
        val genericShown = attentionBy[sid] == false
        attentionBy[sid] = true
        return if (genericShown) n.copy(alertOnce = true) else n
    }

    /** A message can be sent to the session from the phone (managed / attached, still running). */
    private fun canReply(s: SessionSummary?): Boolean = s != null && s.isLive && s.controlLevel == ControlLevel.FULL

    private fun questions(e: AgentEvent): List<JsonObject> = e.data["questions"].arr().orEmpty().mapNotNull { it.obj() }

    /** The question(s) as asked, for the notification body. */
    private fun questionText(e: AgentEvent): String =
        questions(e).mapNotNull { it.str("question", "header") }.joinToString("  ·  ").ifBlank { e.data.str("question", "text").orEmpty() }.take(300)

    /** One single-choice, non-secret question: answerable inline (choices and, if allowed, free text). */
    private fun questionPrompt(e: AgentEvent): QuestionPrompt? {
        val requestId = e.data.str("requestId") ?: return null
        val q = questions(e).singleOrNull() ?: return null
        if (q.bool("multiSelect") == true || q.bool("isSecret") == true) return null
        val options = q["options"].arr().orEmpty().mapNotNull { it.obj() }.mapNotNull { o -> o.str("label")?.let { it to (o.str("value") ?: it) } }
        val freeText = options.isEmpty() || q.bool("allowOther") == true
        return QuestionPrompt(requestId, q.str("id") ?: q.str("question") ?: "", options.map { it.first }, options.map { it.second }, freeText)
    }

    /** The phone just did [action] on [target] (session or terminal id): don't notify the echo. */
    fun noteLocalAction(target: String, action: LocalAction) {
        localActions[target] = action to nowMs()
    }

    private fun recentLocal(target: String?, vararg actions: LocalAction): Boolean {
        val (a, at) = localActions[target ?: return false] ?: return false
        return a in actions && nowMs() - at < LOCAL_ACTION_GRACE_MS
    }

    private fun startTurn(sid: String) {
        settled.remove(sid)
        if (openTurn.add(sid)) turn[sid] = (turn[sid] ?: 0) + 1
    }

    /** Closes the open turn; returns its number, or null when no turn was open (nothing to report). */
    private fun closeTurn(sid: String): Int? = if (openTurn.remove(sid)) turn[sid] ?: 0 else null

    fun onSessionEvent(e: AgentEvent, session: SessionSummary?): NotifyIntent? {
        // A child agent finishing is not the parent task finishing. Child requests still need attention.
        if (e.parentId != null && e.kind !in setOf(EventKinds.APPROVAL_REQUEST, EventKinds.QUESTION, EventKinds.APPROVAL_RESOLVED)) return null
        val at = runCatching { java.time.Instant.parse(e.ts).toEpochMilli() }.getOrNull()
        if (at != null && at < startedAtMs) return null
        val sid = e.sessionId
        val name = sessionName(session, sid)
        return eventIntent(e, sid, session, name)?.let { if (it.atMs == null && at != null) it.copy(atMs = at) else it }
    }

    private fun eventIntent(e: AgentEvent, sid: String, session: SessionSummary?, name: String): NotifyIntent? {
        return when (e.kind) {
            // A new prompt: what follows belongs to the next turn.
            EventKinds.USER_MESSAGE -> {
                if (e.parentId == null) settled.remove(sid)
                null
            }
            EventKinds.APPROVAL_REQUEST, EventKinds.QUESTION ->
                if (e.data.bool("resolved") == true) null else attention(sid) { requestIntent(e, sid, session, name, dedupe = true) }
            EventKinds.STATUS -> {
                val state = e.data.str("state") ?: return null
                val prev = lastStatus.put("evt:$sid", state)
                // The same state again says nothing new, unless a turn was opened since by the other
                // source (`session.upserted` busy): then it is that turn's outcome.
                if (prev == state && !(state in OUTCOME_STATES && sid in openTurn)) return null
                if (prev == "waiting_input" && state != prev) waitEnded(sid)
                when (state) {
                    "running" -> {
                        val fresh = !openTurn.contains(sid)
                        startTurn(sid)
                        if (fresh) build(NotifyType.TASK_STARTED, "start:$sid:${turn[sid]}", session, sid, t.started(name), "") else null
                    }
                    "completed", "idle" -> closeTurn(sid)?.let { n ->
                        settle(sid, build(NotifyType.TASK_COMPLETED, "done:$sid:$n", session, sid, t.finished(name), e.data.str("detail").orEmpty())
                            ?.copy(replyable = canReply(session), atMs = eventMs(e)))
                    }
                    "error" -> failTurn(sid, session, name, e.data.str("detail").orEmpty(), eventMs(e))
                    "interrupted" -> interruptTurn(sid, session, name, t.interrupted(name), e.data.str("detail").orEmpty(), eventMs(e))
                    "waiting_input" -> build(NotifyType.WAITING_INPUT, "wait-input:$sid:${turn[sid] ?: 0}:${waitNo[sid] ?: 0}", session, sid, t.waitingInput(name), "")
                        ?.copy(replyable = canReply(session))
                    // waiting_approval is announced by the approval_request event (with its title).
                    else -> null
                }
            }
            EventKinds.ERROR -> failTurn(sid, session, name, e.data.str("message", "text").orEmpty().take(140), eventMs(e))
            EventKinds.SYSTEM -> if (e.data.str("subtype") == "turn_stopped") {
                interruptTurn(sid, session, name, t.stopped(name), "", eventMs(e))
            } else {
                null
            }
            EventKinds.TOOL_CALL -> if (e.data.str("status") == "running" && sid !in settled) build(
                NotifyType.TOOL_CALL, "tool:${e.id}", session, sid, t.toolCall(name, e.data.str("name")), e.data.str("title") ?: "",
            ) else null
            EventKinds.ASSISTANT_MESSAGE -> if (e.streaming) null else if (sid in settled) lateReply(e, sid, session, name) else build(
                NotifyType.AGENT_REPLY, "reply:${e.id}", session, sid, t.replied(name), (e.data.str("text") ?: "").take(140),
            )
            else -> null
        }
    }

    /** The "needs you" notification of an approval request / question: what is asked, with its buttons. */
    private fun requestIntent(e: AgentEvent, sid: String, session: SessionSummary?, name: String, dedupe: Boolean): NotifyIntent? =
        when (e.kind) {
            EventKinds.APPROVAL_REQUEST -> build(
                NotifyType.WAITING_APPROVAL, "approval:${e.data.str("requestId") ?: e.id}", session, sid,
                t.needsApproval(name), e.data.str("title") ?: t.approvalRequested(), dedupe,
            )?.copy(requestId = e.data.str("requestId"))
            // The question itself, not "answer in the app": often it can be answered right here.
            EventKinds.QUESTION -> build(
                NotifyType.WAITING_INPUT, "question:${e.data.str("requestId") ?: e.id}", session, sid,
                t.asksQuestion(name), questionText(e).ifBlank { t.answerHint() }, dedupe,
            )?.copy(prompt = questionPrompt(e))
            else -> null
        }

    /**
     * Another request of the session was resolved while [request] (its opening `approval_request` /
     * `question` event) is still open: the session's "needs you" notification, rebuilt for [request]
     * (its title, detail and buttons), so nothing in the shade acts on the resolved one. Replaces the
     * shown notification quietly; null for a resolved request or one filtered by the preferences.
     */
    fun stillOpenRequest(request: AgentEvent, session: SessionSummary?): NotifyIntent? {
        if (request.data.bool("resolved") == true) return null
        val sid = request.sessionId
        val n = requestIntent(request, sid, session, sessionName(session, sid), dedupe = false) ?: return null
        attentionBy[sid] = true
        return n.copy(alertOnce = true, atMs = eventMs(request))
    }

    /**
     * The final reply of a turn that has already been reported finished (its transcript line arrived
     * after the status). It must not replace "finished" with "replied": with replies switched on, the
     * finished notification is updated quietly with the reply text instead; otherwise nothing.
     */
    private fun lateReply(e: AgentEvent, sid: String, session: SessionSummary?, name: String): NotifyIntent? {
        val outcome = settled[sid]
        val text = (e.data.str("text") ?: "").take(140)
        // No outcome notification is showing: an ordinary reply notification does not hide anything.
        if (outcome == null) return build(NotifyType.AGENT_REPLY, "reply:${e.id}", session, sid, t.replied(name), text)
        if (NotifyType.AGENT_REPLY !in prefs().enabled || text.isBlank()) return null
        if (outcome.type != NotifyType.TASK_COMPLETED || !seen.add("late-reply:${e.id}")) return null
        return outcome.copy(key = "late-reply:${e.id}", body = text, alertOnce = true).also { settled[sid] = it }
    }

    private fun failTurn(sid: String, s: SessionSummary?, name: String, detail: String, atMs: Long? = null): NotifyIntent? {
        // `status: error`, `error` events and `session.upserted status=error` describe the same failed
        // turn: the key is the turn number, so only the first source notifies.
        val n = closeTurn(sid) ?: (turn[sid] ?: 0)
        val key = "fail:$sid:$n"
        if (key in seen) return null
        return settle(sid, build(NotifyType.TASK_FAILED, key, s, sid, t.failed(name), detail)?.copy(atMs = atMs))
    }

    private fun interruptTurn(sid: String, s: SessionSummary?, name: String, title: String, detail: String, atMs: Long? = null): NotifyIntent? {
        val n = closeTurn(sid) ?: return null
        if (recentLocal(sid, LocalAction.INTERRUPT, LocalAction.STOP)) return settle(sid, null)
        return settle(sid, build(NotifyType.SESSION_INTERRUPTED, "interrupt:$sid:$n", s, sid, title, detail)?.copy(atMs = atMs))
    }

    private fun eventMs(e: AgentEvent): Long? = runCatching { java.time.Instant.parse(e.ts).toEpochMilli() }.getOrNull()

    /** `session.upserted`: status/waitingFor/liveness transitions (also pushed for external sessions). */
    fun onSessionUpsert(s: SessionSummary): NotifyIntent? {
        val sig = "${s.status}|${s.waitingFor}"
        val prev = lastStatus.put("sum:${s.id}", sig)
        val wasLive = lastLive.put(s.id, s.isLive)
        if (s.status == "busy") busySince.putIfAbsent(s.id, nowMs()) else { busySince.remove(s.id); longRunningNotified.remove(s.id) }
        val name = sessionName(s, s.id)
        if (s.liveness == "ended" && wasLive != true) return null
        // Process gone while the session was working or waiting: report it (unless the phone stopped it).
        if (wasLive == true && !s.isLive) {
            settled.remove(s.id)
            val n = closeTurn(s.id)
            val prevStatus = prev?.substringBefore('|')
            if ((n != null || prevStatus == "busy" || prevStatus == "waiting") && !recentLocal(s.id, LocalAction.STOP, LocalAction.INTERRUPT)) {
                return build(NotifyType.SESSION_EXITED, "exit:${s.id}:${n ?: turn[s.id] ?: 0}", s, s.id, t.exited(name), "")?.copy(atMs = s.lastActiveAtMs)
            }
            return null
        }
        val waiting = s.status == "waiting" || s.waitingFor != null
        // The "needs you" moment is over: the next one is announced afresh.
        if (!waiting) attentionBy.remove(s.id)
        // Unchanged, unless it is a wait nobody was told about (muted or switched off when it began):
        // its key is still free, so it is announced once the user lets it through.
        if (prev == sig && !(waiting && !attentionBy.containsKey(s.id))) return null
        val prevStatus = prev?.substringBefore('|')
        if (!waiting && prev != null && (prevStatus == "waiting" || prev.substringAfter('|') != "null")) waitEnded(s.id)
        return when {
            waiting -> {
                // The request event (with what is asked) already announced this wait: no second alert.
                if (attentionBy.containsKey(s.id)) return null
                val approval = isApprovalWait(s.waitingFor)
                build(
                    if (approval) NotifyType.WAITING_APPROVAL else NotifyType.WAITING_INPUT,
                    "wait:${s.id}:$sig:${turn[s.id] ?: 0}:${waitNo[s.id] ?: 0}", s, s.id,
                    if (approval) t.needsApproval(name) else t.waitingForYou(name),
                    if (approval) t.approvalRequested() else "",
                )?.copy(atMs = s.lastActiveAtMs)?.let { n ->
                    attentionBy[s.id] = false
                    if (approval) n else n.copy(replyable = canReply(s))
                }
            }
            s.status == "busy" -> {
                startTurn(s.id)
                null
            }
            s.status == "error" && prev != null -> failTurn(s.id, s, name, "", s.lastActiveAtMs)
            (s.status == "idle" || s.status == "completed") && (prevStatus == "busy" || prevStatus == "waiting" || s.id in openTurn) ->
                closeTurn(s.id)?.let { n -> settle(s.id, build(NotifyType.TASK_COMPLETED, "done:${s.id}:$n", s, s.id, t.finished(name), "")?.copy(replyable = canReply(s), atMs = s.lastActiveAtMs)) }
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
            if (s.status != "busy" || nowMs() - since < limit || s.id in longRunningNotified) return@mapNotNull null
            build(NotifyType.LONG_RUNNING, "long:${s.id}:$since", s, s.id, t.stillRunning(sessionName(s, s.id)), t.runningFor((nowMs() - since) / 60_000))
                ?.also { longRunningNotified.add(s.id) }
        }
    }

    fun connection(lost: Boolean, desktop: String): NotifyIntent? =
        build(NotifyType.CONNECTION, "conn:$lost:${nowMs() / 60_000}", null, null, if (lost) t.connectionLost(desktop) else t.reconnected(desktop), "")

    private fun build(type: NotifyType, key: String, s: SessionSummary?, sessionId: String?, title: String, body: String, dedupe: Boolean = true): NotifyIntent? {
        if (dedupe && key in seen) return null
        val p = prefs()
        if (type !in p.enabled) return null
        if (s != null && (s.agent in p.mutedAgents || (s.project != null && s.project in p.mutedProjects))) return null
        // Recorded only when really produced: a mute or a disabled type filters, it does not use the
        // key up (a request still open after unmuting is announced when it is seen again).
        seen.add(key)
        if (seen.size > 2000) seen.remove(seen.first())
        val public = when (type) {
            NotifyType.WAITING_APPROVAL -> t.publicApproval()
            NotifyType.WAITING_INPUT -> t.publicWaiting()
            NotifyType.CONNECTION -> title
            else -> t.publicActivity()
        }
        return NotifyIntent(type, key, sessionId, title, body, public, silent = p.inQuietHours(minuteOfDay()))
    }

    /** The cleaned first line of the title (raw titles can be injected markup), else the project / folder. */
    private fun sessionName(s: SessionSummary?, id: String): String =
        s?.title?.let { io.github.tianli0.switchtether.remote.session.InjectedText.clean(it).text }
            ?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() && !it.startsWith("<") }
            ?: s?.project?.takeIf { it.isNotBlank() }
            ?: s?.cwd?.trimEnd('/', '\\')?.substringAfterLast('/')?.substringAfterLast('\\')?.takeIf { it.isNotBlank() }
            ?: id.substringAfter(':').take(8)

    companion object {
        /** How long an interrupt/stop/kill done from the phone suppresses the echo notification. */
        const val LOCAL_ACTION_GRACE_MS = 60_000L

        /** `status` states that close a turn. */
        private val OUTCOME_STATES = setOf("completed", "idle", "error", "interrupted")
    }
}
