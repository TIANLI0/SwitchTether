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
    /** Phase before OFFLINE, restored on reconnect. */
    val beforeOffline: LivePhase? = null,
    /** Bumped on every visible change; the throttle posts only when it moved. */
    val version: Int = 0,
)

/** Pure reducer: stream input → new pinned state. Returns the same instance when nothing visible changed. */
object LiveReducer {
    fun onUpsert(s: PinnedSessionState, summary: SessionSummary, now: Long): PinnedSessionState {
        var n = s.copy(title = summary.title?.takeIf { it.isNotBlank() } ?: summary.project ?: s.title, agent = summary.agent, project = summary.project ?: s.project)
        n = when {
            !summary.isLive -> n.enter(LivePhase.ENDED, now)
            summary.status == "waiting" || summary.waitingFor != null -> {
                val approval = isApprovalWait(summary.waitingFor)
                // Keep an already-known request (with its title) if the event arrived first.
                if (n.pending != null) n.enter(if (n.pending!!.isQuestion) LivePhase.WAITING_INPUT else LivePhase.WAITING_APPROVAL, now)
                else n.enter(if (approval || summary.waitingFor == null) LivePhase.WAITING_APPROVAL else LivePhase.WAITING_INPUT, now)
            }
            summary.status == "busy" -> n.startTurnIfIdle(now)
            summary.status == "error" -> n.enter(LivePhase.FAILED, now)
            summary.status == "idle" || summary.status == "completed" ->
                if (n.phase == LivePhase.RUNNING || n.phase == LivePhase.WAITING_APPROVAL || n.phase == LivePhase.WAITING_INPUT) n.enter(LivePhase.DONE, now).copy(pending = null) else n
            else -> n
        }
        return n.bumpIfChanged(s)
    }

    fun onEvent(s: PinnedSessionState, e: AgentEvent, now: Long): PinnedSessionState {
        val d = e.data
        val n = when (e.kind) {
            EventKinds.TOOL_CALL -> if (d.str("status") == "running" || d.str("status") == "pending") {
                s.startTurnIfIdle(now).copy(step = classifyStep(d.str("name"), d.str("title"), d.str("category"), d["input"] as? kotlinx.serialization.json.JsonObject), steps = s.steps + 1)
            } else s
            EventKinds.REASONING -> s.startTurnIfIdle(now).copy(step = null)
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
                } else {
                    val title = d.str("title")?.takeIf { it.isNotBlank() }
                        ?: d["questions"]?.let { runCatching { (it as kotlinx.serialization.json.JsonArray).firstOrNull()?.let { x -> (x as kotlinx.serialization.json.JsonObject).str("question") } }.getOrNull() }
                        ?: ""
                    s.copy(pending = PendingRequest(rid, title, q), resolvedElsewhere = false)
                        .enter(if (q) LivePhase.WAITING_INPUT else LivePhase.WAITING_APPROVAL, now)
                }
            }
            EventKinds.APPROVAL_RESOLVED -> if (s.pending?.requestId == d.str("requestId")) s.copy(pending = null).enter(LivePhase.RUNNING, now) else s
            EventKinds.STATUS -> when (d.str("state")) {
                "running" -> s.startTurnIfIdle(now)
                "idle", "completed" -> if (s.phase in ACTIVE) s.enter(LivePhase.DONE, now).copy(pending = null) else s
                "error" -> s.enter(LivePhase.FAILED, now).copy(pending = null)
                "interrupted" -> s.enter(LivePhase.INTERRUPTED, now).copy(pending = null)
                else -> s
            }
            EventKinds.ERROR -> if (s.phase in ACTIVE) s.enter(LivePhase.FAILED, now).copy(pending = null) else s
            EventKinds.SYSTEM -> if (d.str("subtype") == "turn_stopped" && s.phase in ACTIVE) s.enter(LivePhase.INTERRUPTED, now).copy(pending = null) else s
            else -> s
        }
        return n.bumpIfChanged(s)
    }

    fun onConnection(s: PinnedSessionState, online: Boolean, now: Long): PinnedSessionState {
        val n = if (!online) {
            if (s.phase == LivePhase.OFFLINE) s else s.copy(phase = LivePhase.OFFLINE, beforeOffline = s.phase, phaseSinceMs = now, dismissed = false)
        } else if (s.phase == LivePhase.OFFLINE) {
            s.copy(phase = s.beforeOffline ?: LivePhase.RUNNING, beforeOffline = null, phaseSinceMs = now, dismissed = false)
        } else s
        return n.bumpIfChanged(s)
    }

    /** The phone's approve/deny got 409: the computer answered first. */
    fun onResolvedElsewhere(s: PinnedSessionState, now: Long): PinnedSessionState =
        s.copy(pending = null, resolvedElsewhere = true).enter(LivePhase.RUNNING, now).bumpIfChanged(s)

    fun onDismissed(s: PinnedSessionState): PinnedSessionState = s.copy(dismissed = true)

    /** Shows [message] in place of the step text until the next phase change. */
    fun onNotice(s: PinnedSessionState, message: String): PinnedSessionState = s.copy(notice = message, dismissed = false).bumpIfChanged(s)

    private val ACTIVE = setOf(LivePhase.RUNNING, LivePhase.WAITING_APPROVAL, LivePhase.WAITING_INPUT)

    private fun PinnedSessionState.enter(p: LivePhase, now: Long): PinnedSessionState =
        if (phase == p) this else copy(
            phase = p,
            phaseSinceMs = now,
            dismissed = false,
            notice = null,
            waitedThisTurn = waitedThisTurn || p == LivePhase.WAITING_APPROVAL || p == LivePhase.WAITING_INPUT,
        )

    private fun PinnedSessionState.startTurnIfIdle(now: Long): PinnedSessionState = when (phase) {
        LivePhase.RUNNING -> if (turnStartedMs == 0L) copy(turnStartedMs = now) else this
        LivePhase.WAITING_APPROVAL, LivePhase.WAITING_INPUT -> this
        else -> copy(phase = LivePhase.RUNNING, phaseSinceMs = now, turnStartedMs = now, steps = 0, step = null, note = null, files = emptySet(), tokens = 0, dismissed = false, resolvedElsewhere = false, waitedThisTurn = false)
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
) {
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

    /** Keep finished turns promoted this long, then demote (pin stays; next turn re-promotes). */
    const val DONE_PROMOTED_MS = 60_000L

    fun render(s: PinnedSessionState, now: Long, t: LiveText, previousPhase: LivePhase?): LiveUpdateSpec {
        val waiting = s.phase == LivePhase.WAITING_APPROVAL || s.phase == LivePhase.WAITING_INPUT
        val offlineWhileWaiting = s.phase == LivePhase.OFFLINE && (s.beforeOffline == LivePhase.WAITING_APPROVAL || s.beforeOffline == LivePhase.WAITING_INPUT)
        // 运行 | 待确认 | 完成 once the turn has asked the user something, else 运行 | 完成.
        val staged = s.waitedThisTurn || waiting || offlineWhileWaiting
        val segments = if (staged) listOf(33, 33, 34) else listOf(50, 50)
        val finished = s.phase == LivePhase.DONE || s.phase == LivePhase.FAILED || s.phase == LivePhase.INTERRUPTED
        val chip = when (s.phase) {
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
            LivePhase.DONE -> if (s.steps > 0) t.done() + " · " + t.stepsDone(s.steps) else t.done()
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
        val actions = buildList {
            if (s.phase == LivePhase.WAITING_APPROVAL && s.pending != null) { add(LiveAction.APPROVE); add(LiveAction.DENY) }
            if (s.phase == LivePhase.WAITING_INPUT) add(LiveAction.ANSWER) else add(LiveAction.OPEN)
            if (s.phase != LivePhase.ENDED) add(LiveAction.UNPIN)
        }.let { if (it.size > 3) it.filter { a -> a != LiveAction.OPEN } else it } // Android shows at most 3 actions
        val promoted = when {
            s.phase == LivePhase.ENDED -> false
            // Losing the connection only matters while a turn was in flight; an idle pin stays quiet.
            s.phase == LivePhase.OFFLINE -> s.beforeOffline in ACTIVE_PHASES
            finished -> now - s.phaseSinceMs < DONE_PROMOTED_MS
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
            progressNote = if (s.planTotal > 0) "${s.planDone}/${s.planTotal}" + (if (s.steps > 0) " \u00b7 " + t.stepsDone(s.steps) else "") else if (s.steps > 0) t.stepsDone(s.steps) else "",
        )
    }
}
