package io.github.tianli0.switchtether.remote.live

import io.github.tianli0.switchtether.remote.notify.isApprovalWait
import io.github.tianli0.switchtether.remote.protocol.AgentEvent
import io.github.tianli0.switchtether.remote.protocol.EventKinds
import io.github.tianli0.switchtether.remote.protocol.SessionSummary
import io.github.tianli0.switchtether.remote.protocol.bool
import io.github.tianli0.switchtether.remote.protocol.str

/**
 * "Pin a task" → one Android 16+ Live Update per pinned session (shown by ColorOS 16 as Fluid
 * Cloud / 流体云, by Pixel as a status-bar chip). This file is the platform-free part: the state
 * of a pinned session derived from the event stream, and the mapping to what the notification
 * shows ([LiveUpdateSpec]). The app turns a spec into a `Notification`.
 */
enum class LivePhase { RUNNING, WAITING_APPROVAL, WAITING_INPUT, DONE, FAILED, INTERRUPTED, OFFLINE, ENDED }

data class PendingRequest(val requestId: String, val title: String, val isQuestion: Boolean)

/** What kind of work a step is, so the card says "运行命令 npm test", not the raw tool name. */
enum class StepKind { COMMAND, EDIT, READ, SEARCH, WEB_FETCH, WEB_SEARCH, SUBAGENT, PLAN, MESSAGE, OTHER }

data class Step(val kind: StepKind, val detail: String? = null)

data class PinnedSessionState(
    val sessionId: String,
    val title: String,
    val agent: String,
    val phase: LivePhase = LivePhase.RUNNING,
    val project: String? = null,
    /** What the agent is doing right now ("运行命令 npm test"); null → "思考中…". */
    val step: Step? = null,
    val steps: Int = 0,
    /** The agent's latest words this turn (first line of its newest message), shown as the card's second line. */
    val note: String? = null,
    /** Plan progress (the agent's todo list): done / total and the item in progress. */
    val planDone: Int = 0,
    val planTotal: Int = 0,
    val planCurrent: String? = null,
    /** Distinct files changed this turn, and tokens used this turn. */
    val files: Set<String> = emptySet(),
    val tokens: Long = 0,
    val turnStartedMs: Long = 0,
    val phaseSinceMs: Long = 0,
    val pending: PendingRequest? = null,
    /** The phone's own answer was refused because the computer answered first (409). */
    val resolvedElsewhere: Boolean = false,
    /** The user swiped the Live Update away: do not repost until the next phase change. */
    val dismissed: Boolean = false,
    /** One-off message from the phone's own action ("not sent: offline"); cleared on the next phase change. */
    val notice: String? = null,
    /** The current turn has stopped at least once for the user (shows the 待确认 milestone). */
    val waitedThisTurn: Boolean = false,
    /** Phase before OFFLINE, restored on reconnect (with the time it was entered, so a reconnect does not reorder cards). */
    val beforeOffline: LivePhase? = null,
    val beforeOfflineSinceMs: Long = 0,
    /**
     * When the current turn finished (DONE / FAILED / INTERRUPTED) on this phone's clock, and the
     * computer's own time of that outcome (event `ts`, or the summary's `lastActiveAtMs`); 0 = unknown.
     * Stragglers of the finished turn (a transcript line tailed after the outcome, a request read
     * over REST before it) must not restart it.
     */
    val finishedAtMs: Long = 0,
    val finishedAtServerMs: Long = 0,
    /**
     * The turn is over and the card has become an ordinary result notification ("已完成 · 12 步"):
     * not promoted, swipeable, tap opens the session. The pin slot is already free.
     */
    val settled: Boolean = false,
    /**
     * Background work of the session (Claude Code `run_in_background` shells, background agents),
     * which keeps going after the turn ends: the count the computer reports in `status`
     * (`backgroundTasks`, managed sessions; null = never reported) and the background rows seen
     * running in the stream (observed sessions, whose status cannot report it). Not reset by a new turn.
     */
    val bgReported: Int? = null,
    val bgIds: Set<String> = emptySet(),
    /** Tool calls of this turn already counted: a row updated in place is not a new step (nor a new turn). */
    val callIds: Set<String> = emptySet(),
    /** Bumped on every visible change; the throttle posts only when it moved. */
    val version: Int = 0,
) {
    /** Background tasks still running. */
    val background: Int get() = bgReported?.coerceAtLeast(0) ?: bgIds.size
}

/** Pure reducer: stream input → new pinned state. Returns the same instance when nothing visible changed. */
object LiveReducer {
    /**
     * @param fromRest the summary was read over REST (reconnect / pin refresh): it may be older than
     * the stream, so a `busy` that is not newer than the turn's outcome cannot restart a finished card.
     */
    fun onUpsert(s: PinnedSessionState, summary: SessionSummary, now: Long, fromRest: Boolean = false): PinnedSessionState {
        var n = s.copy(title = summary.title?.takeIf { it.isNotBlank() } ?: summary.project ?: s.title, agent = summary.agent, project = summary.project ?: s.project)
        n = when {
            !summary.isLive -> n.endSession(now)
            summary.status == "waiting" || summary.waitingFor != null -> {
                val approval = isApprovalWait(summary.waitingFor)
                // Keep an already-known request (with its title) if the event arrived first.
                if (n.pending != null) n.enter(if (n.pending!!.isQuestion) LivePhase.WAITING_INPUT else LivePhase.WAITING_APPROVAL, now)
                else n.enter(if (approval || summary.waitingFor == null) LivePhase.WAITING_APPROVAL else LivePhase.WAITING_INPUT, now)
            }
            summary.status == "busy" -> if (fromRest && n.isStaleBusy(summary, now)) n else n.startTurnIfIdle(now)
            // Like the status event: an outcome already shown (or an ended session) is not replaced.
            summary.status == "error" -> if (n.phase in IN_FLIGHT) n.finish(LivePhase.FAILED, now, summary.lastActiveAtMs) else n
            summary.status == "idle" || summary.status == "completed" ->
                if (n.phase in IN_FLIGHT) n.finish(LivePhase.DONE, now, summary.lastActiveAtMs) else n
            else -> n
        }
        return n.bumpIfChanged(s)
    }

    fun onEvent(s: PinnedSessionState, e: AgentEvent, now: Long): PinnedSessionState {
        // Nested agent activity cannot close or restart the parent's turn.
        if (e.parentId != null && e.kind !in setOf(EventKinds.APPROVAL_REQUEST, EventKinds.QUESTION, EventKinds.APPROVAL_RESOLVED, EventKinds.FILE_CHANGE, EventKinds.SUBAGENT)) return s
        val d = e.data
        val at = eventTimeMs(e)
        // Older than the outcome that closed the turn (on the computer's clock): stale, whatever it is.
        val older = s.phase in FINISHED && s.finishedAtServerMs > 0 && at != null && at <= s.finishedAtServerMs
        // Work steps / requests right after the outcome without a new user message or `running`
        // status: the tail of the finished turn (observed sessions take their status from the
        // registry and their steps from the transcript, so the two can arrive out of order).
        val straggler = older || (s.phase in FINISHED && s.finishedAtMs > 0 && now - s.finishedAtMs < STRAGGLER_GRACE_MS)
        val n = when (e.kind) {
            EventKinds.TOOL_CALL -> {
                val callId = d.str("callId") ?: e.id
                val running = d.str("status") == "running" || d.str("status") == "pending"
                // A background shell stays `running` after its turn; its task notification ends it in place.
                val b = if (d.bool("background") == true) s.trackBackground(callId, running) else s
                when {
                    // The same row again (in-place update, a background shell's "started" result): not a new step.
                    callId in s.callIds -> b
                    straggler || !running -> b
                    else -> b.startTurnIfIdle(now).let {
                        it.copy(step = classifyStep(d.str("name"), d.str("title"), d.str("category"), d["input"] as? kotlinx.serialization.json.JsonObject), steps = it.steps + 1, callIds = (it.callIds + callId).bounded())
                    }
                }
            }
            EventKinds.SUBAGENT -> if (d.bool("background") == true) s.trackBackground("sa:" + (d.str("agentId") ?: e.id), d.str("status") == "running") else s
            EventKinds.REASONING -> if (straggler) s else s.startTurnIfIdle(now).copy(step = null)
            // A new prompt opens the next turn (before any step of it arrives).
            EventKinds.USER_MESSAGE -> if (e.parentId == null && !older && s.phase in FINISHED) s.startTurnIfIdle(now) else s
            EventKinds.ASSISTANT_MESSAGE -> d.str("text")?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }
                ?.let { line -> s.copy(note = if (line.length > 90) line.take(89) + "…" else line) } ?: s
            EventKinds.PLAN -> {
                val list = (d["items"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { it as? kotlinx.serialization.json.JsonObject }
                val current = list.firstOrNull { it.str("status") == "in_progress" }?.str("text", "content")?.lineSequence()?.firstOrNull()?.trim()
                    ?.let { if (it.length > 60) it.take(59) + "…" else it }
                s.copy(planDone = list.count { it.str("status") == "completed" }, planTotal = list.size, planCurrent = current)
            }
            EventKinds.FILE_CHANGE -> d.str("path")?.let { s.copy(files = s.files + it) } ?: s
            EventKinds.USAGE -> s.copy(tokens = s.tokens + ((d["inputTokens"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: 0L) +
                ((d["outputTokens"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: 0L))
            EventKinds.APPROVAL_REQUEST, EventKinds.QUESTION -> {
                val rid = d.str("requestId") ?: e.id
                val resolved = d.bool("resolved") == true
                val q = e.kind == EventKinds.QUESTION
                if (resolved) {
                    if (s.pending?.requestId == rid) s.copy(pending = null).enter(LivePhase.RUNNING, now) else s
                } else if (straggler) {
                    s
                } else {
                    val title = d.str("title")?.takeIf { it.isNotBlank() }
                        ?: d["questions"]?.let { runCatching { (it as kotlinx.serialization.json.JsonArray).firstOrNull()?.let { x -> (x as kotlinx.serialization.json.JsonObject).str("question") } }.getOrNull() }
                        ?: ""
                    s.copy(pending = PendingRequest(rid, title, q), resolvedElsewhere = false)
                        .enter(if (q) LivePhase.WAITING_INPUT else LivePhase.WAITING_APPROVAL, now)
                }
            }
            EventKinds.APPROVAL_RESOLVED -> if (s.pending?.requestId == d.str("requestId")) s.copy(pending = null).enter(LivePhase.RUNNING, now) else s
            EventKinds.STATUS -> s.copy(bgReported = (d["backgroundTasks"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: s.bgReported).let { s -> when (d.str("state")) {
                "running" -> if (older) s else s.startTurnIfIdle(now)
                "idle", "completed" -> if (s.phase in IN_FLIGHT) s.finish(LivePhase.DONE, now, at) else s
                // An outcome already shown (or an ended session) is not replaced by a late status of the same turn.
                "error" -> if (s.phase in IN_FLIGHT) s.finish(LivePhase.FAILED, now, at) else s
                "interrupted" -> if (s.phase in IN_FLIGHT) s.finish(LivePhase.INTERRUPTED, now, at) else s
                else -> s
            } }
            // Also while OFFLINE: a turn that failed or was stopped meanwhile is not restored as running.
            EventKinds.ERROR -> if (s.phase in IN_FLIGHT) s.finish(LivePhase.FAILED, now, at) else s
            EventKinds.SYSTEM -> if (d.str("subtype") == "turn_stopped" && s.phase in IN_FLIGHT) s.finish(LivePhase.INTERRUPTED, now, at) else s
            else -> s
        }
        return n.bumpIfChanged(s)
    }

    /**
     * The connection dropped (the app debounces this, so a blip is never shown) or came back. Only a
     * turn in flight shows OFFLINE: an idle or finished card has nothing to lose and stays as it is.
     */
    fun onConnection(s: PinnedSessionState, online: Boolean, now: Long): PinnedSessionState {
        val n = if (!online) {
            if (s.phase == LivePhase.OFFLINE || s.phase !in ACTIVE) s else s.copy(phase = LivePhase.OFFLINE, beforeOffline = s.phase, beforeOfflineSinceMs = s.phaseSinceMs, phaseSinceMs = now, dismissed = false)
        } else if (s.phase == LivePhase.OFFLINE) {
            // Back in the phase it was in, since when it was in it: a reconnect is not news, so the
            // card keeps its place (and a turn that finished long ago is not promoted again).
            val back = s.beforeOffline ?: LivePhase.RUNNING
            s.copy(phase = back, beforeOffline = null, phaseSinceMs = if (s.beforeOffline != null && s.beforeOfflineSinceMs > 0) s.beforeOfflineSinceMs else now, beforeOfflineSinceMs = 0, dismissed = false)
        } else s
        return n.bumpIfChanged(s)
    }

    /** The phone's approve/deny got 409: the computer answered first. */
    fun onResolvedElsewhere(s: PinnedSessionState, now: Long): PinnedSessionState =
        s.copy(pending = null, resolvedElsewhere = true).enter(LivePhase.RUNNING, now).bumpIfChanged(s)

    fun onDismissed(s: PinnedSessionState): PinnedSessionState = s.copy(dismissed = true)

    /** The session's process exited (`session.removed`, or a summary that is no longer live). */
    fun onEnded(s: PinnedSessionState, now: Long): PinnedSessionState = s.endSession(now).bumpIfChanged(s)

    /** The final card has been shown: it becomes an ordinary, swipeable result notification. */
    fun onSettled(s: PinnedSessionState): PinnedSessionState = if (s.settled) s else s.copy(settled = true).bumpIfChanged(s)

    fun isFinished(p: LivePhase) = p in FINISHED

    /** Shows [message] in place of the step text until the next phase change. */
    fun onNotice(s: PinnedSessionState, message: String): PinnedSessionState = s.copy(notice = message, dismissed = false).bumpIfChanged(s)

    private val ACTIVE = setOf(LivePhase.RUNNING, LivePhase.WAITING_APPROVAL, LivePhase.WAITING_INPUT)
    /** A turn in flight, also while the connection is down (OFFLINE is only ever entered from [ACTIVE]): its outcome still applies. */
    private val IN_FLIGHT = ACTIVE + LivePhase.OFFLINE
    private val FINISHED = setOf(LivePhase.DONE, LivePhase.FAILED, LivePhase.INTERRUPTED)

    /** How long after an outcome work steps without a new prompt / `running` status count as the finished turn's tail. */
    const val STRAGGLER_GRACE_MS = 10_000L

    private fun eventTimeMs(e: AgentEvent): Long? = runCatching { java.time.Instant.parse(e.ts).toEpochMilli() }.getOrNull()

    /** Ends the turn in [p]; remembers when (phone clock and, if known, the computer's clock). */
    private fun PinnedSessionState.finish(p: LivePhase, now: Long, serverMs: Long?): PinnedSessionState =
        if (phase == p) copy(pending = null)
        else enter(p, now).copy(pending = null, finishedAtMs = now, finishedAtServerMs = serverMs ?: 0L, beforeOffline = null, beforeOfflineSinceMs = 0)

    private fun PinnedSessionState.enter(p: LivePhase, now: Long): PinnedSessionState =
        if (phase == p) this else copy(
            phase = p,
            phaseSinceMs = now,
            dismissed = false,
            notice = null,
            waitedThisTurn = waitedThisTurn || p == LivePhase.WAITING_APPROVAL || p == LivePhase.WAITING_INPUT,
        )

    /** A finished turn keeps its outcome ("已完成 · 12 步") when the process exits afterwards. */
    private fun PinnedSessionState.endSession(now: Long): PinnedSessionState =
        if (phase in FINISHED) copy(pending = null) else enter(LivePhase.ENDED, now).copy(pending = null)

    private fun PinnedSessionState.trackBackground(id: String, running: Boolean): PinnedSessionState =
        copy(bgIds = if (running) (bgIds + id).bounded() else bgIds - id)

    private fun Set<String>.bounded(): Set<String> = if (size > 200) toList().takeLast(200).toSet() else this

    private fun PinnedSessionState.isStaleBusy(summary: SessionSummary, now: Long): Boolean {
        if (phase !in FINISHED) return false
        val at = summary.lastActiveAtMs
        if (finishedAtServerMs > 0 && at != null) return at <= finishedAtServerMs
        return finishedAtMs > 0 && now - finishedAtMs < STRAGGLER_GRACE_MS
    }

    private fun PinnedSessionState.startTurnIfIdle(now: Long): PinnedSessionState = when (phase) {
        LivePhase.RUNNING -> if (turnStartedMs == 0L) copy(turnStartedMs = now) else this
        LivePhase.WAITING_APPROVAL, LivePhase.WAITING_INPUT -> this
        else -> copy(phase = LivePhase.RUNNING, phaseSinceMs = now, turnStartedMs = now, steps = 0, step = null, note = null, planDone = 0, planTotal = 0, planCurrent = null, files = emptySet(), tokens = 0, dismissed = false, resolvedElsewhere = false, waitedThisTurn = false, settled = false, notice = null, callIds = emptySet())
    }

    private fun PinnedSessionState.bumpIfChanged(old: PinnedSessionState): PinnedSessionState =
        if (copy(version = old.version, phaseSinceMs = old.phaseSinceMs) == old) old else copy(version = old.version + 1)

    /**
     * Tool call → human step: Bash → 运行命令 <command>, Edit/Write → 编辑 <file>, Read → 读取 <file>,
     * WebFetch → 访问网页 <host>, …; unknown tools → 处理中 (never the raw tool name).
     */
    fun classifyStep(name: String?, title: String?, category: String?, input: kotlinx.serialization.json.JsonObject?): Step {
        val n = name.orEmpty().lowercase().replace("-", "_")
        fun arg(vararg keys: String): String? = keys.firstNotNullOfOrNull { k ->
            (input?.get(k) as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
        }
        fun file(vararg keys: String) = arg(*keys)?.trimEnd('/', '\\')?.substringAfterLast('/')?.substringAfterLast('\\')
        fun short(v: String?) = v?.lineSequence()?.firstOrNull()?.trim()?.let { if (it.length > 40) it.take(39) + "…" else it }
        return when {
            n in setOf("bash", "shell", "local_shell", "exec", "exec_command", "run_command", "powershell") || category == "shell" ->
                Step(StepKind.COMMAND, short(arg("command", "cmd") ?: title))
            n in setOf("edit", "write", "multiedit", "notebookedit", "apply_patch", "str_replace", "create_file") || category == "edit" ->
                Step(StepKind.EDIT, file("file_path", "path", "notebook_path"))
            n in setOf("read", "notebookread", "view", "read_file") || category == "read" -> Step(StepKind.READ, file("file_path", "path"))
            n in setOf("grep", "glob", "ls", "search", "find", "list_files") || category == "search" -> Step(StepKind.SEARCH, null)
            n in setOf("webfetch", "web_fetch", "fetch") -> Step(StepKind.WEB_FETCH, arg("url")?.substringAfter("://")?.substringBefore('/'))
            n in setOf("websearch", "web_search") -> Step(StepKind.WEB_SEARCH, short(arg("query")))
            n in setOf("task", "agent", "subagent") -> Step(StepKind.SUBAGENT, short(arg("description") ?: title))
            n in setOf("todowrite", "update_plan") -> Step(StepKind.PLAN, null)
            n == "sendmessage" || n == "send_message" -> Step(StepKind.MESSAGE, null)
            else -> Step(StepKind.OTHER, null)
        }
    }
}

enum class LiveAction { APPROVE, DENY, OPEN, ANSWER, UNPIN }

/** Localized wording; the app supplies resources (tests use [English]). */
interface LiveText {
    fun chipRunning(): String
    fun chipWaiting(): String
    fun chipDone(): String
    fun chipFailed(): String
    fun chipInterrupted(): String
    fun chipOffline(): String
    fun chipEnded(): String
    fun thinking(): String
    /** Capsule text while running, by what the agent is doing ("命令", "编辑", …; ≤ 5 characters). */
    fun chipStep(kind: StepKind?): String
    /** "已执行 12 步" */
    fun stepsDone(n: Int): String
    /** "计划 3/5 · 正在：写单元测试" */
    fun plan(done: Int, total: Int, current: String?): String
    /** "改动 4 个文件" */
    fun filesChanged(n: Int): String
    /** "12.3K tokens" */
    fun tokensUsed(tokens: String): String
    /** "运行命令 npm test" / "编辑 Login.kt" / "处理中". */
    fun step(step: Step): String
    fun waitingApproval(): String
    fun waitingInput(): String
    fun done(): String
    fun failed(): String
    fun interrupted(): String
    fun offline(): String
    fun ended(): String
    /** Capsule text while only background work is left ("后台"). */
    fun chipBackground(): String
    /** "后台 2 个任务运行中" */
    fun backgroundRunning(n: Int): String
    fun resolvedElsewhere(): String
    fun publicText(): String

    object English : LiveText {
        override fun chipRunning() = "Busy"
        override fun chipWaiting() = "Ask"
        override fun chipDone() = "Done"
        override fun chipFailed() = "Error"
        override fun chipInterrupted() = "Stop"
        override fun chipOffline() = "Off"
        override fun chipEnded() = "Ended"
        override fun thinking() = "Thinking…"
        override fun chipStep(kind: StepKind?) = when (kind) {
            null -> "Think"
            StepKind.COMMAND -> "Run"
            StepKind.EDIT -> "Edit"
            StepKind.READ -> "Read"
            StepKind.SEARCH -> "Find"
            StepKind.WEB_FETCH, StepKind.WEB_SEARCH -> "Web"
            StepKind.SUBAGENT -> "Agent"
            StepKind.PLAN -> "Plan"
            StepKind.MESSAGE -> "Reply"
            StepKind.OTHER -> "Busy"
        }
        override fun stepsDone(n: Int) = if (n == 1) "1 step" else "$n steps"
        override fun plan(done: Int, total: Int, current: String?) = "Plan $done/$total" + (current?.let { " · now: $it" } ?: "")
        override fun filesChanged(n: Int) = if (n == 1) "1 file changed" else "$n files changed"
        override fun tokensUsed(tokens: String) = "$tokens tokens"
        override fun step(step: Step) = when (step.kind) {
            StepKind.COMMAND -> "Running " + (step.detail ?: "a command")
            StepKind.EDIT -> "Editing " + (step.detail ?: "files")
            StepKind.READ -> "Reading " + (step.detail ?: "files")
            StepKind.SEARCH -> "Searching files"
            StepKind.WEB_FETCH -> "Opening " + (step.detail ?: "a web page")
            StepKind.WEB_SEARCH -> "Searching the web"
            StepKind.SUBAGENT -> "Sub-task" + (step.detail?.let { ": $it" } ?: "")
            StepKind.PLAN -> "Updating the plan"
            StepKind.MESSAGE -> "Sending a message"
            StepKind.OTHER -> "Working…"
        }
        override fun waitingApproval() = "Waiting for your approval"
        override fun waitingInput() = "Waiting for your answer"
        override fun done() = "Turn finished"
        override fun failed() = "The turn failed"
        override fun interrupted() = "The turn was stopped"
        override fun offline() = "Connection to the computer lost, reconnecting…"
        override fun ended() = "Session ended"
        override fun chipBackground() = "Bg"
        override fun backgroundRunning(n: Int) = if (n == 1) "1 background task running" else "$n background tasks running"
        override fun resolvedElsewhere() = "Handled on the computer"
        override fun publicText() = "SwitchTether"
    }
}

/** What one pinned session's notification shows. */
data class LiveUpdateSpec(
    val sessionId: String,
    val title: String,
    val text: String,
    /** Capsule text (OPPO capsule [B] / Android status chip): at most [LiveUpdateRenderer.MAX_CHIP] characters. */
    val chip: String,
    /** Chronometer base (turn start) for the chip while running; null when not counting. */
    val chronometerBaseMs: Long?,
    val progress: Progress,
    /** Ongoing + promoted (Live Update / Fluid Cloud). False = demoted to a normal notification. */
    val promoted: Boolean,
    val actions: List<LiveAction>,
    val requestId: String?,
    /** Lock screen: never the command / request text. */
    val publicText: String,
    /** Sound/vibration on this post (only when entering a waiting state). */
    val alert: Boolean,
    /**
     * Stage track (OPPO "进度可视化"): one coloured segment per stage — 运行 | 待确认 | 完成 when the
     * turn waited for the user, else 运行 | 完成 — summing to 100, with no points. [progressValue] is
     * the tracker position inside the current stage (always determinate).
     */
    val progressValue: Int = 0,
    val segments: List<Int> = emptyList(),
    val tone: Tone = Tone.NORMAL,
    val phase: LivePhase = LivePhase.RUNNING,
    /** What the agent is doing now; picks the card's own icon (terminal, pencil, magnifier, …). */
    val stepKind: StepKind? = null,
    /** Expanded body: the step, the agent's latest words and the step count. Empty when there is nothing more to say. */
    val detail: String = "",
    /** The short status shown next to the elapsed time ("12 steps"). */
    val progressNote: String = "",
    /**
     * The notification's `when` (phone clock), which is also what the shade orders by: the turn start
     * while running (the chronometer base), else when the current phase began. Stable across updates
     * of one phase, so a step or a re-post never reshuffles the cards; a new phase (waiting for you,
     * finished) moves the card up. Null: unknown (the channel keeps the time of its first post).
     */
    val whenMs: Long? = null,
    /** An ordinary result notification: swipeable and cleared when tapped (the turn is over, the pin released). */
    val dismissible: Boolean = false,
) {
    /** What the collapsed card / Fluid Cloud capsule shows; a change here is worth a prompt re-post. */
    fun headline(): List<Any?> = listOf(title, text, chip, chronometerBaseMs, promoted, actions, requestId, tone, phase, stepKind, dismissible)
    /**
     * Never indeterminate: ColorOS Fluid Cloud renders no bar for an indeterminate ProgressStyle.
     * STAGE: tracker inside the current stage; FULL: done; ERROR: stopped where the turn was.
     */
    enum class Progress { STAGE, FULL, ERROR, NONE }
    enum class Tone { NORMAL, WARNING, ERROR }
}

object LiveUpdateRenderer {
    /**
     * Running: inside 运行 (or inside 完成 once the turn has passed 待确认), from 15 % of the segment,
     * approaching but never reaching 90 % as steps accumulate.
     */
    internal fun runningPosition(s: PinnedSessionState, segments: List<Int>): Int {
        val resumed = s.waitedThisTurn && segments.size == 3
        val start = if (resumed) segments[0] + segments[1] else 0
        val len = if (resumed) segments[2] else segments[0]
        val f = 0.15 + 0.75 * (1 - Math.pow(0.8, s.steps.toDouble()))
        return start + (len * f).toInt().coerceIn(1, len - 1)
    }

    /** A request title for the one-line card: a bare path → its file name; long text ellipsized. */
    fun shortSubject(title: String): String {
        val t = title.trim()
        val pathLike = t.none { it.isWhitespace() } && (t.contains('/') || t.contains('\\'))
        val s = if (pathLike) t.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\') else t
        return if (s.length > 60) s.take(59) + "…" else s
    }

    private val ACTIVE_PHASES = setOf(LivePhase.RUNNING, LivePhase.WAITING_APPROVAL, LivePhase.WAITING_INPUT)

    /** OPPO Fluid Cloud capsule text [B] shows at most 5 characters (longer gets "…"). */
    const val MAX_CHIP = 5

    /**
     * A finished turn shows its outcome in the capsule this long; then the card becomes an ordinary
     * result notification and the pin slot is released (the next turn of the session is followed again).
     */
    const val DONE_PROMOTED_MS = 8_000L

    /** @param promotedMs how long a finished turn's outcome stays promoted. */
    fun render(s: PinnedSessionState, now: Long, t: LiveText, previousPhase: LivePhase?, promotedMs: Long = DONE_PROMOTED_MS): LiveUpdateSpec {
        val waiting = s.phase == LivePhase.WAITING_APPROVAL || s.phase == LivePhase.WAITING_INPUT
        val offlineWhileWaiting = s.phase == LivePhase.OFFLINE && (s.beforeOffline == LivePhase.WAITING_APPROVAL || s.beforeOffline == LivePhase.WAITING_INPUT)
        // 运行 | 待确认 | 完成 once the turn has asked the user something, else 运行 | 完成.
        val staged = s.waitedThisTurn || waiting || offlineWhileWaiting
        val segments = if (staged) listOf(33, 33, 34) else listOf(50, 50)
        val finished = s.phase == LivePhase.DONE || s.phase == LivePhase.FAILED || s.phase == LivePhase.INTERRUPTED
        // The turn is over but background work goes on (a dev server, a background agent).
        val bg = if (finished && !s.settled) s.background else 0
        val chip = if (bg > 0) t.chipBackground().take(MAX_CHIP) else when (s.phase) {
            LivePhase.RUNNING -> if (s.step != null || s.steps > 0) t.chipStep(s.step?.kind) else t.chipRunning()
            LivePhase.WAITING_APPROVAL, LivePhase.WAITING_INPUT -> t.chipWaiting()
            LivePhase.DONE -> t.chipDone()
            LivePhase.FAILED -> t.chipFailed()
            LivePhase.INTERRUPTED -> t.chipInterrupted()
            LivePhase.OFFLINE -> t.chipOffline()
            LivePhase.ENDED -> t.chipEnded()
        }.take(MAX_CHIP)
        val text = s.notice ?: when (s.phase) {
            LivePhase.RUNNING -> if (s.resolvedElsewhere && s.step == null) t.resolvedElsewhere() else s.step?.let { t.step(it) } ?: t.thinking()
            // "需要你批准 · tracker.txt": what kind of wait first, then a short subject (a bare path
            // becomes its file name).
            LivePhase.WAITING_APPROVAL -> s.pending?.title?.takeIf { it.isNotBlank() }?.let { t.waitingApproval() + " · " + shortSubject(it) } ?: t.waitingApproval()
            LivePhase.WAITING_INPUT -> s.pending?.title?.takeIf { it.isNotBlank() } ?: t.waitingInput()
            LivePhase.DONE -> if (bg > 0) t.done() + " · " + t.backgroundRunning(bg) else if (s.steps > 0) t.done() + " · " + t.stepsDone(s.steps) else t.done()
            LivePhase.FAILED -> t.failed()
            LivePhase.INTERRUPTED -> t.interrupted()
            LivePhase.OFFLINE -> t.offline()
            LivePhase.ENDED -> t.ended()
        }
        // Expanded body, one fact per line: what it is doing, what it just said, where the plan stands,
        // and a totals line (steps · files · tokens).
        val totals = listOfNotNull(
            if (s.steps > 0) t.stepsDone(s.steps) else null,
            if (s.files.isNotEmpty()) t.filesChanged(s.files.size) else null,
            if (s.tokens > 0) t.tokensUsed(io.github.tianli0.switchtether.remote.usage.UsageFormat.tokens(s.tokens)) else null,
        ).joinToString(" \u00b7 ")
        val detail = if (s.notice == null && s.phase in setOf(LivePhase.RUNNING, LivePhase.DONE, LivePhase.WAITING_APPROVAL, LivePhase.WAITING_INPUT)) {
            listOfNotNull(
                if (s.phase == LivePhase.RUNNING) text else null,
                s.note,
                if (s.planTotal > 0) t.plan(s.planDone, s.planTotal, s.planCurrent.takeIf { s.phase == LivePhase.RUNNING }) else null,
                totals.takeIf { it.isNotEmpty() },
            ).joinToString("\n").takeIf { it != text } ?: ""
        } else ""
        val dismissible = s.settled || s.phase == LivePhase.ENDED
        val actions = buildList {
            if (s.phase == LivePhase.WAITING_APPROVAL && s.pending != null) { add(LiveAction.APPROVE); add(LiveAction.DENY) }
            if (s.phase == LivePhase.WAITING_INPUT) add(LiveAction.ANSWER) else add(LiveAction.OPEN)
            // A result notification is no longer pinned: nothing to unpin.
            if (!dismissible) add(LiveAction.UNPIN)
        }.let { if (it.size > 3) it.filter { a -> a != LiveAction.OPEN } else it } // Android shows at most 3 actions
        val promoted = when {
            dismissible -> false
            // Losing the connection only matters while a turn was in flight; an idle pin stays quiet.
            s.phase == LivePhase.OFFLINE -> s.beforeOffline in ACTIVE_PHASES
            finished -> now - s.phaseSinceMs < promotedMs
            else -> true
        }
        return LiveUpdateSpec(
            sessionId = s.sessionId,
            title = s.title,
            text = text,
            chip = chip,
            chronometerBaseMs = if (s.phase == LivePhase.RUNNING && s.turnStartedMs > 0) s.turnStartedMs else null,
            progress = LiveUpdateSpec.Progress.NONE,
            promoted = promoted,
            actions = actions,
            requestId = s.pending?.requestId,
            publicText = t.publicText() + " · " + chip,
            alert = waiting && previousPhase != s.phase,
            progressValue = 0,
            segments = emptyList(),
            tone = when (s.phase) {
                LivePhase.WAITING_APPROVAL, LivePhase.WAITING_INPUT, LivePhase.OFFLINE -> LiveUpdateSpec.Tone.WARNING
                LivePhase.FAILED -> LiveUpdateSpec.Tone.ERROR
                else -> LiveUpdateSpec.Tone.NORMAL
            },
            phase = s.phase,
            stepKind = if (s.phase == LivePhase.RUNNING) s.step?.kind else null,
            detail = detail,
            whenMs = (if (s.phase == LivePhase.RUNNING && s.turnStartedMs > 0) s.turnStartedMs else s.phaseSinceMs).takeIf { it > 0 },
            dismissible = dismissible,
            progressNote = if (s.planTotal > 0) "${s.planDone}/${s.planTotal}" + (if (s.steps > 0) " \u00b7 " + t.stepsDone(s.steps) else "") else if (s.steps > 0) t.stepsDone(s.steps) else "",
        )
    }
}
