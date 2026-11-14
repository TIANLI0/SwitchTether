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
import io.github.tianli0.switchtether.remote.protocol.AgentEvent
import io.github.tianli0.switchtether.remote.protocol.CapStatus
import io.github.tianli0.switchtether.remote.protocol.Capability
import io.github.tianli0.switchtether.remote.protocol.SessionDetail
import io.github.tianli0.switchtether.remote.protocol.str
import io.github.tianli0.switchtether.remote.session.AgentEventLog
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
    /** Managed session created by "continue on phone" (navigate to it once). */
    val continuedAs: io.github.tianli0.switchtether.remote.protocol.SessionSummary? = null,
) {
    val online: Boolean get() = connection is ConnectionState.Online
    fun cap(c: Capability): CapStatus = detail.valueOrNull?.capabilities?.status(c) ?: CapStatus.UNKNOWN
    val canSend: Boolean get() = online && cap(Capability.SEND_MESSAGE).usable
    val canInterrupt: Boolean get() = online && cap(Capability.INTERRUPT).usable
    val rollingStatus: AgentEvent? get() = log[ROLLING_STATUS_ID]

    /**
     * "Continue on phone": offered only when the desktop advertises `capabilities.continueSession`
     * (external Claude Code / Codex sessions) and the phone cannot already drive the session.
     */
    val canOfferContinue: Boolean get() {
        val d = detail.valueOrNull ?: return false
        return d.capabilities.continueSession != null && !canSend && d.summary.controlLevel != io.github.tianli0.switchtether.remote.protocol.ControlLevel.FULL
    }

    /** Claude sessions may also be forked (a copy that leaves the original transcript untouched). */
    val canFork: Boolean get() = canOfferContinue && detail.valueOrNull?.summary?.agent.let { it == "claude_code" || it == "claude" }

    /** The desktop refuses to fork a session whose original is mid-turn. */
    val originalBusy: Boolean get() = detail.valueOrNull?.summary?.let { it.isLive && it.status == "busy" } == true

    /** Rebuild derived rows after the log / collapse / expansion state changed. */
    fun derive(log: AgentEventLog = this.log, collapsed: Set<String> = this.collapsed, expandedSteps: Set<String> = this.expandedSteps): SessionDetailState {
        val items = log.renderItems(collapsed).filter { it.event.id != ROLLING_STATUS_ID && !isLowValueSystem(it.event) }
        return copy(
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
) : ViewModel() {
    private val _state = MutableStateFlow(SessionDetailState(sessionId, connection = conn.state.value))
    val state: StateFlow<SessionDetailState> = _state.asStateFlow()

    /** Full outputs loaded on demand via the payload endpoint (memory only). */
    val payloads = mutableStateMapOf<String, PayloadState>()

    val commands = CommandTracker(viewModelScope) { repo.commandStatus(it) }

    private val draftFlow = MutableStateFlow<String?>(null)
    private var loadJob: Job? = null

    init {
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
                        _state.update { st ->
                            val d = st.detail.valueOrNull ?: return@update st
                            st.copy(detail = Loadable.Ready(d.copy(summary = ch.session)))
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    /** Fresh load: detail + newest page. On reconnect/resync the log is replaced (seq may change). */
    fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val d = loadable { repo.detail(sessionId) }
            _state.update { it.copy(detail = d) }
            try {
                val page = repo.events(sessionId, limit = PAGE)
                val log = AgentEventLog.of(page.events)
                _state.update { it.derive(log = log).copy(hasMoreBefore = page.hasMoreBefore, events = Loadable.Ready(Unit)) }
                d.valueOrNull?.pendingRequests?.let { applyEvents(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(events = Loadable.Failed(UiError.from(e))) }
            }
        }
    }

    /**
     * After a reconnect/resync within the same desktop epoch: fetch everything newer than the last
     * seen seq (`after=` pages until hasMore=false) instead of dropping the loaded history.
     */
    fun catchUp() {
        val max = _state.value.log.maxSeq ?: return load()
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val d = loadable { repo.detail(sessionId) }
            if (d is Loadable.Ready) _state.update { it.copy(detail = d) }
            try {
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

    fun send(label: String) {
        val text = _state.value.draft.trim()
        if (text.isEmpty() || !_state.value.canSend) return
        commands.run(label, onSuccess = {
            _state.update { it.copy(draft = "") }
            viewModelScope.launch { drafts.setDraft(sessionId, "") }
        }) { id -> repo.sendMessage(sessionId, text, id) }
    }

    fun interrupt(label: String) {
        commands.run(label) { id -> repo.interrupt(sessionId, id) }
    }

    fun resolveApproval(label: String, requestId: String, decision: String) {
        commands.run(label) { id -> repo.resolveApproval(sessionId, requestId, decision, id) }
    }

    fun answer(label: String, requestId: String, answers: JsonObject) {
        commands.run(label) { id -> repo.answer(sessionId, requestId, answers, id) }
    }

    companion object {
        const val PAGE = 300
        const val CATCH_UP_MAX_PAGES = 20
    }
}
