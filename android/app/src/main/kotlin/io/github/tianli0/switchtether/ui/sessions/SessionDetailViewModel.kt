package io.github.tianli0.switchtether.ui.sessions

import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.tianli0.switchtether.connection.ConnectionController
import io.github.tianli0.switchtether.connection.ConnectionState
import io.github.tianli0.switchtether.data.DraftStore
import io.github.tianli0.switchtether.domain.SessionChange
import io.github.tianli0.switchtether.domain.SessionRepository
import io.github.tianli0.switchtether.domain.createdSession
import io.github.tianli0.switchtether.connection.ConnectionManager
import io.github.tianli0.switchtether.remote.command.CommandOutcome
import io.github.tianli0.switchtether.remote.command.newCommandId
import io.github.tianli0.switchtether.remote.protocol.AgentEvent
import io.github.tianli0.switchtether.remote.protocol.CapStatus
import io.github.tianli0.switchtether.remote.protocol.Capability
import io.github.tianli0.switchtether.remote.protocol.SessionDetail
import io.github.tianli0.switchtether.remote.protocol.str
import io.github.tianli0.switchtether.remote.session.AgentEventLog
import io.github.tianli0.switchtether.remote.session.ClaudeTools
import io.github.tianli0.switchtether.remote.session.RenderItem
import io.github.tianli0.switchtether.ui.common.CommandTracker
import io.github.tianli0.switchtether.ui.common.Loadable
import io.github.tianli0.switchtether.ui.common.UiError
import io.github.tianli0.switchtether.ui.common.loadable
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/** System subtypes that carry no information for the user (bookkeeping from the agent runtime). */
val HIDDEN_SYSTEM_SUBTYPES = setOf("thinking_tokens", "commands_changed", "status", "dev_intent", "hook_response", "hook_started")

/** `system` rows without text (or whose text only repeats the subtype) and known noise are hidden. */
fun isLowValueSystem(e: AgentEvent): Boolean {
    if (e.kind != "system") return false
    val subtype = e.data.str("subtype").orEmpty()
    val text = e.data.str("text").orEmpty().trim()
    return subtype in HIDDEN_SYSTEM_SUBTYPES || text.isEmpty() || text == subtype
}

/** Id of the rolling status event the desktop updates in place (shown in the header). */
const val ROLLING_STATUS_ID = "status:current"

data class PayloadState(val text: String, val loading: Boolean, val complete: Boolean, val error: String?)

/** "Rewind files to before this message": dry run first, then the confirmed restore. */
data class RewindState(
    val userMessageId: String,
    val loading: Boolean = true,
    val preview: io.github.tianli0.switchtether.remote.session.RewindResult? = null,
    val error: String? = null,
    val applying: Boolean = false,
)

/** One-shot feedback of a Claude Code session tool used from the conversation (shown as a toast). */
sealed interface ToolNotice {
    data object Withdrawn : ToolNotice
    data object WithdrawTooLate : ToolNotice
    data object Backgrounded : ToolNotice
    data object NotBackgrounded : ToolNotice
    data class Rewound(val files: Int) : ToolNotice
    data class Failed(val message: String) : ToolNotice
}

data class SessionDetailState(
    val sessionId: String,
    val detail: Loadable<SessionDetail> = Loadable.Loading,
    val events: Loadable<Unit> = Loadable.Loading,
    val log: AgentEventLog = AgentEventLog.EMPTY,
    val items: List<RenderItem> = emptyList(),
    /** [items] folded into chat rows and "N steps" blocks (see [buildTimeline]). */
    val timeline: List<TimelineRow> = emptyList(),
    /** Keys of approval/question rows still waiting for an answer. */
    val pending: List<String> = emptyList(),
    val collapsed: Set<String> = emptySet(),
    val expandedSteps: Set<String> = emptySet(),
    val hasMoreBefore: Boolean = false,
    val loadingOlder: Boolean = false,
    val connection: ConnectionState = ConnectionState.Unpaired,
    val draft: String = "",
    /** Images attached to the current draft (encoded base64, sent once on send). */
    val attachedImages: List<io.github.tianli0.switchtether.remote.protocol.ImageInput> = emptyList(),
    /** Files attached to the current draft, uploaded to the computer as soon as they are picked. */
    val draftAttachments: List<DraftAttachment> = emptyList(),
    /** Managed session created by "continue on phone" (navigate to it once). */
    val continuedAs: io.github.tianli0.switchtether.remote.protocol.SessionSummary? = null,
    /** Messages sent from this phone and not echoed by the desktop yet (optimistic bubbles). */
    val outgoing: List<OutgoingMessage> = emptyList(),
    /** "正在回复…" row after the user's latest message. */
    val typing: Boolean = false,
    /** Bumped on every send: the list jumps to the newest message. */
    val sendTick: Int = 0,
    /** Our message was echoed and the agent has not answered yet. */
    val awaitingReply: Boolean = false,
    /** Models / effort levels for the model sheet (loaded when it opens). */
    val modelOptions: io.github.tianli0.switchtether.remote.protocol.ModelOptions? = null,
    /** Permission mode being switched to (Claude Code session tools). */
    val permissionPending: String? = null,
    val permissionError: String? = null,
    /** Tool calls being moved to the background (tool use ids). */
    val backgrounding: Set<String> = emptySet(),
    val rewind: RewindState? = null,
    val notice: ToolNotice? = null,
) {
    val online: Boolean get() = connection is ConnectionState.Online
    fun cap(c: Capability): CapStatus = detail.valueOrNull?.capabilities?.status(c) ?: CapStatus.UNKNOWN
    val canSend: Boolean get() = online && cap(Capability.SEND_MESSAGE).usable

    /** Offline but the session could take messages when last seen: a send waits for the connection. */
    val canQueue: Boolean get() = !online && connection !is ConnectionState.Unpaired && cap(Capability.SEND_MESSAGE).usable &&
        detail.valueOrNull?.summary?.isLive == true
    val canInterrupt: Boolean get() = online && cap(Capability.INTERRUPT).usable

    /** Model and effort can be changed from the phone: a session it controls (also one taken over
     *  with the opt-in hook), or a conversation that is not running, for the resume that the next
     *  message starts. The desktop's capability decides. */
    val canChangeModel: Boolean get() = online && cap(Capability.CHANGE_MODEL).usable

    /** Not running anywhere: the next message resumes the conversation on the computer (same id). */
    val resumesOnSend: Boolean get() = canSend && detail.valueOrNull?.summary?.let { !it.isLive } == true
    val rollingStatus: AgentEvent? get() = log[ROLLING_STATUS_ID]

    /** Claude Code session tools (permission mode, context, plan, MCP, /btw, rewind, …) are offered. */
    val claudeTools: Boolean get() = detail.valueOrNull?.let { it.claudeTools && it.summary.isLive } == true

    /** Observed Claude Code session: only the read-only tools (plan, export, task output). */
    val claudeReadTools: Boolean get() = !claudeTools && detail.valueOrNull?.claudeReadTools == true

    /** Not running anywhere: the permission mode chosen now applies when a message resumes it. */
    val resumable: Boolean get() = detail.valueOrNull?.resumable == true

    /** Tools that act on the running process also need the connection. */
    val canUseClaudeTools: Boolean get() = online && claudeTools

    /**
     * "Continue on phone": offered only when the desktop advertises `capabilities.continueSession`
     * (external Claude Code / Codex sessions) and the phone cannot already drive the session.
     */
    val canOfferContinue: Boolean get() {
        val d = detail.valueOrNull ?: return false
        return d.capabilities.continueSession != null && !canSend && d.summary.controlLevel != io.github.tianli0.switchtether.remote.protocol.ControlLevel.FULL
    }

    /** Claude sessions may also be forked (a copy that leaves the original transcript untouched). */
    val canFork: Boolean get() {
        val d = detail.valueOrNull ?: return false
        return online && d.capabilities.continueSession != null && d.summary.agent.let { it == "claude_code" || it == "claude" } &&
            d.summary.controlLevel != io.github.tianli0.switchtether.remote.protocol.ControlLevel.FULL
    }

    /** The desktop refuses to fork a session whose original is mid-turn. */
    val originalBusy: Boolean get() = detail.valueOrNull?.summary?.let { it.isLive && it.status == "busy" } == true

    /** Rebuild derived rows after the log / collapse / expansion state changed. */
    fun derive(
        log: AgentEventLog = this.log,
        collapsed: Set<String> = this.collapsed,
        expandedSteps: Set<String> = this.expandedSteps,
        outgoing: List<OutgoingMessage> = this.outgoing,
    ): SessionDetailState {
        val items = log.renderItems(collapsed).filter { it.event.id != ROLLING_STATUS_ID && !isLowValueSystem(it.event) }
        val pendingOut = reconcileOutgoing(outgoing, log)
        val busy = detail.valueOrNull?.summary?.let { it.isLive && it.status == "busy" } == true
        // One of our bubbles was just echoed: keep "正在回复…" even if the summary still says idle.
        val echoed = pendingOut.size < outgoing.size
        val awaiting = (awaitingReply || echoed) && !agentRepliedAfterLastUser(items)
        return copy(
            outgoing = pendingOut,
            awaitingReply = awaiting,
            typing = showTypingRow(items, pendingOut, busy || awaiting),
            log = log,
            collapsed = collapsed,
            expandedSteps = expandedSteps,
            items = items,
            timeline = buildTimeline(items, expandedSteps),
            pending = pendingRequestKeys(items),
        )
    }
}

@OptIn(FlowPreview::class)
class SessionDetailViewModel(
    private val sessionId: String,
    private val conn: ConnectionController,
    private val repo: SessionRepository,
    private val drafts: DraftStore,
    /** Tells the notifier about the phone's own actions (no echo notification). */
    private val onLocalAction: (String, io.github.tianli0.switchtether.remote.notify.LocalAction) -> Unit = { _, _ -> },
    /** Reads picked files for upload (tests run it on their own dispatcher). */
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO,
) : ViewModel() {
    private val _state = MutableStateFlow(SessionDetailState(sessionId, connection = conn.state.value))
    val state: StateFlow<SessionDetailState> = _state.asStateFlow()

    /** Full outputs loaded on demand via the payload endpoint (memory only). */
    val payloads = mutableStateMapOf<String, PayloadState>()

    /** Branch lines of Claude Code sub-agents (agent id → latest steps), see [loadSubagentPreview]. */
    val subagentPreviews = mutableStateMapOf<String, io.github.tianli0.switchtether.ui.sessions.render.SubagentPreview>()
    private val previewFetchedAt = HashMap<String, Long>()
    private val previewInFlight = HashSet<String>()

    /** Fetches a sub-agent's latest steps; at most once per 2.5 s per agent, never twice at once. */
    fun loadSubagentPreview(agentId: String) {
        val now = System.currentTimeMillis()
        if (agentId in previewInFlight || now - (previewFetchedAt[agentId] ?: 0L) < 2_500) return
        previewInFlight += agentId
        previewFetchedAt[agentId] = now
        viewModelScope.launch {
            try {
                val page = repo.subagentEvents(sessionId, agentId, 200)
                subagentPreviews[agentId] = io.github.tianli0.switchtether.ui.sessions.render.SubagentPreview.of(page.events)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (subagentPreviews[agentId] == null) {
                    subagentPreviews[agentId] = io.github.tianli0.switchtether.ui.sessions.render.SubagentPreview(emptyList(), 0, error = true)
                }
            } finally {
                previewInFlight -= agentId
            }
        }
    }

    private val uploadJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val outgoingAttachments = java.util.concurrent.ConcurrentHashMap<String, List<String>>()

    /**
     * Attaches a picked file: it is uploaded to the computer at once (progress on its chip), so
     * sending is instant. The agent receives it as a path on the computer.
     */
    fun addAttachment(name: String, size: Long, mediaType: String?, localUri: String? = null, open: () -> java.io.InputStream?) {
        val st = _state.value
        if (st.draftAttachments.size >= MAX_ATTACHMENTS) return
        val a = DraftAttachment(newCommandId(), name.ifBlank { "file" }, size, mediaType, localUri = localUri)
        if (size > MAX_ATTACHMENT_BYTES || size <= 0) {
            val failure = if (size <= 0) AttachmentFailure.EMPTY else AttachmentFailure.TOO_LARGE
            _state.update { it.copy(draftAttachments = it.draftAttachments + a.copy(failure = failure)) }
            return
        }
        _state.update { it.copy(draftAttachments = it.draftAttachments + a) }
        uploadJobs[a.localId] = viewModelScope.launch {
            try {
                val begun = repo.beginAttachment(sessionId, a.name, size, mediaType)
                updateAttachment(a.localId) { it.copy(attachmentId = begun.attachmentId) }
                var complete = false
                kotlinx.coroutines.withContext(ioDispatcher) {
                    (open() ?: throw java.io.IOException("cannot read the file")).use { input ->
                        val buf = ByteArray(ATTACHMENT_CHUNK)
                        var offset = 0L
                        while (true) {
                            var n = 0
                            while (n < buf.size) {
                                val r = input.read(buf, n, buf.size - n)
                                if (r < 0) break
                                n += r
                            }
                            if (n == 0) break
                            val v = repo.uploadAttachmentChunk(sessionId, begun.attachmentId, offset, buf.copyOf(n))
                            offset = v.received
                            complete = v.complete
                            updateAttachment(a.localId) { it.copy(sent = v.received) }
                            if (complete || n < buf.size) break
                        }
                    }
                }
                updateAttachment(a.localId) {
                    if (complete) it.copy(ready = true) else it.copy(failure = AttachmentFailure.FAILED, detail = "the file changed while it was read")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val old = (e as? io.github.tianli0.switchtether.remote.api.ApiException)?.httpStatus == 404
                val detail = UiError.from(e).detail ?: e.message
                updateAttachment(a.localId) { it.copy(failure = if (old) AttachmentFailure.OLD_DESKTOP else AttachmentFailure.FAILED, detail = detail) }
            } finally {
                uploadJobs.remove(a.localId)
            }
        }
    }

    fun removeAttachment(localId: String) {
        val a = _state.value.draftAttachments.firstOrNull { it.localId == localId } ?: return
        uploadJobs.remove(localId)?.cancel()
        _state.update { st -> st.copy(draftAttachments = st.draftAttachments.filterNot { it.localId == localId }) }
        a.attachmentId?.let { id -> viewModelScope.launch { repo.cancelAttachment(sessionId, id) } }
    }

    private fun updateAttachment(localId: String, f: (DraftAttachment) -> DraftAttachment) {
        _state.update { st -> st.copy(draftAttachments = st.draftAttachments.map { if (it.localId == localId) f(it) else it }) }
    }

    /** In-memory cache of downloaded local images (never written to disk). */
    private val imageCache = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
    private val outgoingImages = java.util.concurrent.ConcurrentHashMap<String, List<io.github.tianli0.switchtether.remote.protocol.ImageInput>>()

    val commands = CommandTracker(viewModelScope) { repo.commandStatus(it) }

    private val draftFlow = MutableStateFlow<String?>(null)
    private var loadJob: Job? = null

    init {
        // Where the conversation stood last time, at once (replaced by the first successful load).
        viewModelScope.launch {
            val cachedSummary = runCatching { repo.cachedList() }.getOrNull()?.firstOrNull { it.id == sessionId }
            val cached = runCatching { repo.cachedEvents(sessionId) }.getOrNull()
            _state.update { st ->
                var n = st
                if (n.detail !is Loadable.Ready && cachedSummary != null) {
                    n = n.copy(detail = Loadable.Ready(SessionDetail(cachedSummary, io.github.tianli0.switchtether.remote.protocol.SessionCapabilities(readHistory = "VERIFIED"), emptyList())))
                }
                if (n.events !is Loadable.Ready && cached != null && cached.events.isNotEmpty()) {
                    n = n.derive(log = AgentEventLog.of(cached.events)).copy(events = Loadable.Ready(Unit), hasMoreBefore = cached.hasMoreBefore)
                }
                n
            }
        }
        viewModelScope.launch {
            val saved = drafts.draft(sessionId).first()
            _state.update { if (it.draft.isEmpty()) it.copy(draft = saved) else it }
        }
        viewModelScope.launch {
            draftFlow.drop(1).debounce(400).collect { text -> if (text != null) drafts.setDraft(sessionId, text) }
        }
        viewModelScope.launch {
            conn.state.distinctUntilChangedBy { it::class }.collect { s ->
                _state.update { it.copy(connection = s) }
                if (s is ConnectionState.Online) load()
            }
        }
        viewModelScope.launch { conn.state.collect { s -> _state.update { it.copy(connection = s) } } }
        viewModelScope.launch { repo.refetch.collect { reason -> if (reason == "epoch_changed") load() else catchUp() } }
        viewModelScope.launch {
            repo.changes.collect { ch ->
                when (ch) {
                    is SessionChange.Event -> if (ch.event.sessionId == sessionId) applyEvents(listOf(ch.event))
                    is SessionChange.Upserted -> if (ch.session.id == sessionId) {
                        val before = _state.value.detail.valueOrNull?.summary
                        if (before != null && (before.liveness != ch.session.liveness || before.control != ch.session.control)) {
                            // Resumed on the computer (or ended / taken over): capabilities and the
                            // live tail change with it, so reload instead of patching the summary.
                            load()
                            return@collect
                        }
                        _state.update { st ->
                            val d = st.detail.valueOrNull ?: return@update st
                            // A turn that ends without output (interrupted, failed) ends the wait too.
                            val turnEnded = d.summary.status == "busy" && ch.session.status != "busy"
                            st.copy(detail = Loadable.Ready(d.copy(summary = ch.session)), awaitingReply = st.awaitingReply && !turnEnded && ch.session.isLive).derive()
                        }
                    }
                    // An observed process exited: the conversation stays readable as history.
                    is SessionChange.Removed -> if (ch.id == sessionId && _state.value.detail.valueOrNull?.summary?.isLive == true) load()
                    else -> Unit
                }
            }
        }
    }

    /** Fresh load: detail + newest page. On reconnect/resync the log is replaced (seq may change). */
    fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val prevDetail = _state.value.detail.valueOrNull
            val d = loadable { repo.detail(sessionId) }
            if (d is Loadable.Failed && sessionId.startsWith("managed:") &&
                d.error.kind == UiError.Kind.NOT_FOUND && prevDetail != null
            ) {
                // Desktop restarted: managed processes lived inside the old process and are now ended.
                val endedSummary = prevDetail.summary.copy(liveness = "ended", status = "ended", control = "history", waitingFor = null)
                val endedCaps = io.github.tianli0.switchtether.remote.protocol.SessionCapabilities(
                    readHistory = "VERIFIED",
                    sendMessage = "BLOCKED",
                    approve = "BLOCKED",
                    answerQuestion = "BLOCKED",
                    interrupt = "BLOCKED",
                    reason = prevDetail.capabilities.reason,
                )
                _state.update {
                    it.copy(
                        detail = Loadable.Ready(SessionDetail(endedSummary, endedCaps, emptyList())),
                        awaitingReply = false,
                    ).derive()
                }
                return@launch
            }
            // A failed reload keeps what is shown (cached or earlier) instead of an empty page.
            _state.update { if (d is Loadable.Failed && it.detail is Loadable.Ready) it else it.copy(detail = d) }
            try {
                val page = repo.events(sessionId, limit = PAGE)
                val log = AgentEventLog.of(page.events)
                _state.update { it.derive(log = log).copy(hasMoreBefore = page.hasMoreBefore, events = Loadable.Ready(Unit)) }
                d.valueOrNull?.pendingRequests?.let { applyEvents(it) }
                flushWaiting()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { if (it.events is Loadable.Ready && it.log.size > 0) it else it.copy(events = Loadable.Failed(UiError.from(e))) }
            }
        }
    }

    /**
     * After a reconnect/resync within the same desktop epoch: fetch everything newer than the last
     * seen seq (`after=` pages) AND refresh the newest page so in-place updates (subagent/tool
     * completion, resolved approvals/questions) during disconnect are recovered cleanly.
     */
    fun catchUp() {
        val max = _state.value.log.maxSeq ?: return load()
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val d = loadable { repo.detail(sessionId) }
            if (d is Loadable.Ready) _state.update { it.copy(detail = d) }
            try {
                val latest = repo.events(sessionId, limit = PAGE)
                applyEvents(latest.events)
                var after = max
                var pages = 0
                while (pages < CATCH_UP_MAX_PAGES) {
                    val page = repo.events(sessionId, after = after, limit = PAGE)
                    applyEvents(page.events)
                    pages++
                    val newMax = page.events.maxOfOrNull { it.seq } ?: break
                    if (!page.hasMoreBefore || newMax <= after) break // with after=, hasMore means "more newer"
                    after = newMax
                }
                d.valueOrNull?.pendingRequests?.let { applyEvents(it) }
                if (pages >= CATCH_UP_MAX_PAGES) load() // too far behind: start from the newest page
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                load()
            }
        }
    }

    fun loadOlder() {
        val st = _state.value
        val min = st.log.minSeq ?: return
        if (!st.hasMoreBefore || st.loadingOlder) return
        _state.update { it.copy(loadingOlder = true) }
        viewModelScope.launch {
            try {
                val page = repo.events(sessionId, before = min, limit = PAGE)
                _state.update {
                    val log = it.log.upsert(page.events)
                    it.derive(log = log).copy(hasMoreBefore = page.hasMoreBefore, loadingOlder = false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loadingOlder = false) }
            }
        }
    }

    /** Loads the models / effort levels the running agent offers (built-in aliases on failure). */
    fun loadModelOptions() {
        val s = _state.value.detail.valueOrNull?.summary ?: return
        viewModelScope.launch {
            val o = runCatching { repo.modelOptions(s.agent, sessionId) }.getOrNull()
            if (o != null) _state.update { it.copy(modelOptions = o) }
        }
    }

    /** Switch model (`""` = the agent's default) of the running session. */
    fun setModel(model: String, label: String) = setOptions(model = model, effort = null, label = label)

    /** Switch effort level (`""` = the default) of the running session. */
    fun setEffort(effort: String, label: String) = setOptions(model = null, effort = effort, label = label)

    private fun setOptions(model: String?, effort: String?, label: String) {
        commands.run(label, onSuccess = { out ->
            val updated = runCatching {
                io.github.tianli0.switchtether.remote.protocol.ProtocolJson.decodeFromJsonElement(
                    io.github.tianli0.switchtether.remote.protocol.SessionSummary.serializer(), out.response.body,
                )
            }.getOrNull()
            _state.update { st ->
                val d = st.detail.valueOrNull ?: return@update st
                val s = updated ?: d.summary.copy(
                    model = model?.ifBlank { null } ?: d.summary.model,
                    effort = if (effort != null) effort.ifBlank { null } else d.summary.effort,
                )
                st.copy(detail = Loadable.Ready(d.copy(summary = s)))
            }
        }) { id -> repo.setOptions(sessionId, model, effort, id) }
    }

    /** Live updates replace by event id (§10.2). */
    fun applyEvents(events: List<AgentEvent>) {
        _state.update { st ->
            val log = st.log.upsert(events)
            if (log === st.log) st else st.derive(log = log)
        }
    }

    fun toggleCollapse(parentId: String) {
        _state.update { st ->
            val c = if (parentId in st.collapsed) st.collapsed - parentId else st.collapsed + parentId
            st.derive(collapsed = c)
        }
    }

    fun toggleSteps(groupId: String) {
        _state.update { st ->
            val e = if (groupId in st.expandedSteps) st.expandedSteps - groupId else st.expandedSteps + groupId
            st.derive(expandedSteps = e)
        }
    }

    /**
     * Continue (or, for Claude, fork) this externally started session as a SwitchTether-managed one
     * with the same history (`POST /v1/sessions {resumeFrom, mode}`). Never retried silently; a 409
     * (original busy on the computer / Codex thread open elsewhere) shows the desktop's message.
     */
    fun continueOnPhone(label: String, fork: Boolean = false) {
        val s = _state.value.detail.valueOrNull?.summary ?: return
        val req = io.github.tianli0.switchtether.domain.NewSessionRequest(
            agent = if (s.agent == "claude") "claude_code" else s.agent,
            cwd = s.cwd.orEmpty(), // optional: the desktop defaults to the original's directory
            prompt = null,
            model = null,
            permissionMode = null,
            resumeFrom = sessionId,
            mode = if (fork) "fork" else "continue",
        )
        commands.run(label, onSuccess = { out ->
            _state.update { it.copy(continuedAs = out.createdSession()) }
        }) { id -> repo.createSession(req, id) }
    }

    fun consumeContinued() {
        _state.update { it.copy(continuedAs = null) }
    }

    fun loadPayload(eventId: String) {
        val cur = payloads[eventId]
        if (cur?.loading == true || cur?.complete == true) return
        payloads[eventId] = PayloadState(cur?.text.orEmpty(), loading = true, complete = false, error = null)
        viewModelScope.launch {
            try {
                var text = cur?.text.orEmpty()
                var offset = text.toByteArray().size.toLong()
                var complete = false
                var rounds = 0
                // Up to 1 MiB per tap; more can be loaded with another tap.
                while (!complete && rounds < 16) {
                    val chunk = repo.payload(sessionId, eventId, offset)
                    text += chunk.text
                    offset = chunk.offset + chunk.text.toByteArray().size
                    complete = chunk.eof || chunk.text.isEmpty()
                    rounds++
                }
                payloads[eventId] = PayloadState(text, loading = false, complete = complete, error = null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                payloads[eventId] = PayloadState(cur?.text.orEmpty(), loading = false, complete = false, error = UiError.from(e).detail ?: e.message)
            }
        }
    }

    fun setDraft(text: String) {
        _state.update { it.copy(draft = text) }
        draftFlow.value = text
    }

    fun addImage(img: io.github.tianli0.switchtether.remote.protocol.ImageInput) {
        _state.update { st ->
            if (st.attachedImages.size >= 4) st else st.copy(attachedImages = st.attachedImages + img)
        }
    }

    fun removeImage(index: Int) {
        _state.update { st ->
            if (index !in st.attachedImages.indices) st
            else st.copy(attachedImages = st.attachedImages.filterIndexed { i, _ -> i != index })
        }
    }

    suspend fun loadImageBytes(path: String): ByteArray? {
        imageCache[path]?.let { return it }
        val bytes = repo.downloadFile(path) ?: return null
        if (imageCache.size > 32) imageCache.clear()
        imageCache[path] = bytes
        return bytes
    }

    /**
     * Optimistic send: the bubble appears at once ("发送中"), the composer clears, and the list jumps
     * to the bottom. The desktop's `user_message` echo replaces the bubble ([reconcileOutgoing]).
     * Offline (session usable when last seen) it waits for the connection with the same commandId.
     */
    fun send() {
        val st = _state.value
        val text = st.draft.trim()
        val imgs = st.attachedImages
        val files = st.draftAttachments
        // Every picked file must be on the computer first; a failed one is removed by the user.
        if (files.any { !it.ready }) return
        if ((text.isEmpty() && imgs.isEmpty() && files.isEmpty()) || !(st.canSend || st.canQueue)) return
        val cmdId = newCommandId()
        if (imgs.isNotEmpty()) outgoingImages[cmdId] = imgs
        if (files.isNotEmpty()) outgoingAttachments[cmdId] = files.mapNotNull { it.attachmentId }
        val msg = OutgoingMessage(
            commandId = cmdId,
            text = text.ifEmpty { if (files.isNotEmpty()) "" else "[image]" },
            attachmentNames = files.map { it.name },
            status = if (st.canSend) OutgoingStatus.SENDING else OutgoingStatus.WAITING_CONNECTION,
            afterSeq = st.log.maxSeq ?: 0,
        )
        _state.update { it.derive(outgoing = it.outgoing + msg).copy(draft = "", attachedImages = emptyList(), draftAttachments = emptyList(), sendTick = it.sendTick + 1) }
        draftFlow.value = ""
        viewModelScope.launch { drafts.setDraft(sessionId, "") }
        if (st.canSend) deliver(msg.commandId)
    }

    /** Tap on a failed bubble: same commandId, so the desktop runs it at most once. */
    fun retrySend(commandId: String) {
        val st = _state.value
        if (st.outgoing.none { it.commandId == commandId }) return
        if (st.canSend) deliver(commandId) else setOutgoing(commandId, OutgoingStatus.WAITING_CONNECTION, null)
    }

    /** A failed message goes back into the composer (files sent with it have to be picked again). */
    fun editOutgoing(commandId: String) {
        val m = _state.value.outgoing.firstOrNull { it.commandId == commandId } ?: return
        deleteOutgoing(commandId)
        val current = _state.value.draft
        setDraft(if (current.isBlank()) m.text else current.trimEnd() + "\n" + m.text)
    }

    fun deleteOutgoing(commandId: String) {
        _state.update { st -> st.derive(outgoing = st.outgoing.filterNot { it.commandId == commandId }) }
    }

    private fun setOutgoing(commandId: String, status: OutgoingStatus, error: String?) {
        _state.update { st ->
            if (st.outgoing.none { it.commandId == commandId }) st // already echoed: nothing to update
            else st.derive(outgoing = st.outgoing.map { if (it.commandId == commandId) it.copy(status = status, error = error) else it })
        }
    }

    private fun deliver(commandId: String) {
        val msg = _state.value.outgoing.firstOrNull { it.commandId == commandId } ?: return
        val imgs = outgoingImages[commandId].orEmpty()
        val files = outgoingAttachments[commandId].orEmpty()
        setOutgoing(commandId, OutgoingStatus.SENDING, null)
        viewModelScope.launch {
            when (val out = repo.sendMessage(sessionId, if (msg.text == "[image]" && imgs.isNotEmpty()) "" else msg.text, commandId, imgs, files)) {
                is CommandOutcome.Succeeded, is CommandOutcome.InProgress -> {
                    outgoingImages.remove(commandId)
                    outgoingAttachments.remove(commandId)
                    setOutgoing(commandId, OutgoingStatus.DELIVERED, null)
                    // Claude Code queues a message sent while it works: keep its id so it can be withdrawn.
                    val uuid = (out as? CommandOutcome.Succeeded)?.let { queuedMessageUuid(it.response.body) }
                    if (uuid != null) {
                        _state.update { st ->
                            val queued = st.claudeTools && st.detail.valueOrNull?.summary?.status == "busy"
                            if (!queued) st
                            else st.derive(outgoing = st.outgoing.map { if (it.commandId == commandId) it.copy(messageUuid = uuid) else it })
                        }
                    }
                }
                is CommandOutcome.Rejected -> setOutgoing(commandId, OutgoingStatus.FAILED, out.error.body.message.ifBlank { out.error.code })
                is CommandOutcome.NotSent ->
                    if (_state.value.online) setOutgoing(commandId, OutgoingStatus.FAILED, ConnectionManager.describe(out.cause))
                    else setOutgoing(commandId, OutgoingStatus.WAITING_CONNECTION, null)
                // Sent but unconfirmed (§5): ask the desktop first (read-only); retry only on the user's tap.
                is CommandOutcome.Unknown -> when (val st = runCatching { repo.commandStatus(commandId) }.getOrNull()) {
                    is io.github.tianli0.switchtether.remote.command.CommandStatus.Succeeded,
                    is io.github.tianli0.switchtether.remote.command.CommandStatus.Pending -> setOutgoing(commandId, OutgoingStatus.DELIVERED, null)
                    is io.github.tianli0.switchtether.remote.command.CommandStatus.Failed ->
                        setOutgoing(commandId, OutgoingStatus.FAILED, (st.record.error as? kotlinx.serialization.json.JsonObject)?.get("message")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content })
                    else -> setOutgoing(commandId, OutgoingStatus.FAILED, ConnectionManager.describe(out.cause))
                }
            }
        }
    }

    /** Back online with a fresh detail: send what the user queued, or say why it cannot go. */
    private fun flushWaiting() {
        val st = _state.value
        st.outgoing.filter { it.status == OutgoingStatus.WAITING_CONNECTION }.forEach { m ->
            when {
                st.canSend -> deliver(m.commandId)
                st.online && st.detail.valueOrNull?.summary?.isLive == false -> setOutgoing(m.commandId, OutgoingStatus.FAILED, SESSION_ENDED)
                st.online -> setOutgoing(m.commandId, OutgoingStatus.FAILED, null)
            }
        }
    }

    // ---- Claude Code session tools (only when the detail says `claudeTools`) -------------------------

    private suspend fun claudeTool(action: String, params: JsonObject = JsonObject(emptyMap())): Result<kotlinx.serialization.json.JsonElement> =
        repo.runClaudeTool(sessionId, action, params)

    /** Switch the permission mode of the running Claude Code process (like Shift+Tab). */
    fun setPermissionMode(mode: String) {
        if (_state.value.permissionPending != null) return
        _state.update { it.copy(permissionPending = mode, permissionError = null) }
        viewModelScope.launch {
            val r = claudeTool(ClaudeTools.PERMISSION_MODE, jsonOf("mode" to mode))
            _state.update { st ->
                val d = st.detail.valueOrNull
                r.fold(
                    onSuccess = { body ->
                        val now = ClaudeTools.parseMode(body, mode)
                        st.copy(permissionPending = null, detail = if (d != null) Loadable.Ready(d.copy(permissionMode = now)) else st.detail)
                    },
                    onFailure = { st.copy(permissionPending = null, permissionError = toolErrorMessage(it)) },
                )
            }
        }
    }

    /** Rename the session (Claude Code's `/rename`); [done] gets null or the error. */
    fun renameSession(title: String, done: (String?) -> Unit) {
        val t = title.trim()
        if (t.isEmpty()) return
        viewModelScope.launch {
            val r = claudeTool(ClaudeTools.RENAME, jsonOf("title" to t))
            if (r.isSuccess) {
                _state.update { st ->
                    val d = st.detail.valueOrNull ?: return@update st
                    st.copy(detail = Loadable.Ready(d.copy(summary = d.summary.copy(title = t))))
                }
            }
            done(r.exceptionOrNull()?.let(::toolErrorMessage))
        }
    }

    /** Move a running foreground Bash / Agent call to the background (Ctrl+B in the IDE). */
    fun runInBackground(toolUseId: String) {
        if (toolUseId in _state.value.backgrounding) return
        _state.update { it.copy(backgrounding = it.backgrounding + toolUseId) }
        viewModelScope.launch {
            val r = claudeTool(ClaudeTools.RUN_IN_BACKGROUND, jsonOf("toolUseId" to toolUseId))
            val notice = r.fold(
                onSuccess = { if (ClaudeTools.parseFlag(it, "backgrounded")) ToolNotice.Backgrounded else ToolNotice.NotBackgrounded },
                onFailure = { ToolNotice.Failed(toolErrorMessage(it)) },
            )
            _state.update { it.copy(backgrounding = it.backgrounding - toolUseId, notice = notice) }
        }
    }

    /** Withdraw a message queued behind a busy turn; it is then never answered or echoed. */
    fun withdrawOutgoing(commandId: String) {
        val m = _state.value.outgoing.firstOrNull { it.commandId == commandId } ?: return
        val uuid = m.messageUuid ?: return
        if (m.withdrawing) return
        updateOutgoing(commandId) { it.copy(withdrawing = true) }
        viewModelScope.launch {
            val r = claudeTool(ClaudeTools.CANCEL_MESSAGE, jsonOf("messageUuid" to uuid))
            val cancelled = r.getOrNull()?.let { ClaudeTools.parseFlag(it, "cancelled") } == true
            if (cancelled) {
                _state.update { st -> st.derive(outgoing = st.outgoing.filterNot { it.commandId == commandId }).copy(notice = ToolNotice.Withdrawn) }
            } else {
                // Too late (the agent took it) or failed: it stays a normal delivered message.
                updateOutgoing(commandId) { it.copy(withdrawing = false, messageUuid = if (r.isSuccess) null else it.messageUuid) }
                val notice = r.exceptionOrNull()?.let { ToolNotice.Failed(toolErrorMessage(it)) } ?: ToolNotice.WithdrawTooLate
                _state.update { it.copy(notice = notice) }
            }
        }
    }

    private fun updateOutgoing(commandId: String, f: (OutgoingMessage) -> OutgoingMessage) {
        _state.update { st ->
            if (st.outgoing.none { it.commandId == commandId }) st
            else st.derive(outgoing = st.outgoing.map { if (it.commandId == commandId) f(it) else it })
        }
    }

    /** Step 1 of "rewind files": what would change (nothing is touched yet). */
    fun previewRewind(userMessageId: String) {
        _state.update { it.copy(rewind = RewindState(userMessageId)) }
        viewModelScope.launch {
            val r = claudeTool(ClaudeTools.REWIND, rewindParams(userMessageId, dryRun = true))
            _state.update { st ->
                if (st.rewind?.userMessageId != userMessageId) return@update st
                st.copy(
                    rewind = r.fold(
                        onSuccess = { RewindState(userMessageId, loading = false, preview = ClaudeTools.parseRewind(it)) },
                        onFailure = { RewindState(userMessageId, loading = false, error = toolErrorMessage(it)) },
                    ),
                )
            }
        }
    }

    /** Step 2: restore the files (only after the user confirmed the dry run). */
    fun confirmRewind() {
        val cur = _state.value.rewind ?: return
        if (cur.applying || cur.preview?.canRewind != true) return
        _state.update { it.copy(rewind = cur.copy(applying = true, error = null)) }
        viewModelScope.launch {
            val r = claudeTool(ClaudeTools.REWIND, rewindParams(cur.userMessageId, dryRun = false))
            val result = r.getOrNull()?.let { ClaudeTools.parseRewind(it) }
            _state.update { st ->
                if (result != null && result.error == null) {
                    st.copy(rewind = null, notice = ToolNotice.Rewound(result.filesChanged.size))
                } else {
                    st.copy(rewind = cur.copy(applying = false, error = result?.error ?: r.exceptionOrNull()?.let(::toolErrorMessage)))
                }
            }
        }
    }

    fun dismissRewind() {
        _state.update { it.copy(rewind = null) }
    }

    fun consumeNotice() {
        _state.update { it.copy(notice = null) }
    }

    fun clearPermissionError() {
        _state.update { it.copy(permissionError = null) }
    }

    fun interrupt(label: String) {
        onLocalAction(sessionId, io.github.tianli0.switchtether.remote.notify.LocalAction.INTERRUPT)
        commands.run(label) { id -> repo.interrupt(sessionId, id) }
    }

    fun resolveApproval(label: String, requestId: String, decision: String) {
        commands.run(label) { id -> repo.resolveApproval(sessionId, requestId, decision, id) }
    }

    fun answer(label: String, requestId: String, answers: JsonObject?, action: String? = null) {
        commands.run(label) { id -> repo.answer(sessionId, requestId, answers, id, action) }
    }

    companion object {
        /** [OutgoingMessage.error] marker: the session ended while the message waited. */
        const val SESSION_ENDED = "session_ended"
        const val PAGE = 300
        const val CATCH_UP_MAX_PAGES = 20
    }
}
