package io.github.tianli0.switchtether.ui.sessions

import io.github.suqi8.coui.kmp.basic.Chip
import io.github.tianli0.coui17.patterns.LazyGroupRow
import io.github.tianli0.coui17.patterns.ExpandBody
import io.github.tianli0.coui17.patterns.PageDefaults
import io.github.suqi8.coui.kmp.basic.cardListPositionOf
import io.github.suqi8.coui.kmp.basic.SmallTitle
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.basic.CardListPosition
import androidx.compose.runtime.saveable.rememberSaveable
import io.github.tianli0.switchtether.ui.common.rememberPageScrollState
import io.github.tianli0.switchtether.domain.SessionQuery
import io.github.tianli0.switchtether.ui.common.Page
import io.github.tianli0.switchtether.ui.icons.AppIcons
import io.github.tianli0.switchtether.ui.home.RowIcon
import io.github.suqi8.coui.kmp.basic.TabRowWithContour
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.preference.ArrowPreference
import io.github.suqi8.coui.kmp.preference.RadioButtonPreference
import io.github.tianli0.switchtether.ui.common.relativeTime
import io.github.tianli0.switchtether.ui.common.sessionDisplayTitle
import io.github.tianli0.switchtether.ui.common.sessionProjectName
import io.github.tianli0.coui17.patterns.SheetConfirm
import io.github.tianli0.coui17.patterns.ActionTone
import io.github.tianli0.coui17.patterns.LinkButton
import io.github.tianli0.coui17.patterns.SmallButton
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.tianli0.switchtether.R
import io.github.tianli0.coui17.patterns.GroupCard
import io.github.tianli0.coui17.patterns.pageListPadding
import io.github.tianli0.coui17.patterns.pageScroll
import io.github.tianli0.switchtether.connection.ConnectionController
import io.github.tianli0.switchtether.connection.ConnectionState
import io.github.tianli0.switchtether.domain.NewSessionRequest
import io.github.tianli0.switchtether.domain.SessionRepository
import io.github.tianli0.switchtether.domain.createdSession
import io.github.tianli0.switchtether.remote.protocol.FsEntry
import io.github.tianli0.switchtether.remote.protocol.FsListing
import io.github.tianli0.switchtether.remote.protocol.SessionSummary
import io.github.tianli0.switchtether.ui.appContainer
import io.github.tianli0.switchtether.ui.common.CommandPromptDialog
import io.github.tianli0.switchtether.ui.common.CommandTracker
import io.github.tianli0.coui17.patterns.ConfirmDialog
import io.github.tianli0.switchtether.ui.common.ConnectionBanner
import io.github.tianli0.switchtether.ui.common.Loadable
import io.github.tianli0.coui17.patterns.TextInput
import io.github.tianli0.switchtether.ui.common.loadable
import io.github.tianli0.switchtether.ui.common.title
import io.github.tianli0.coui17.patterns.NoticeBanner
import io.github.tianli0.coui17.patterns.SectionHeader
import io.github.tianli0.coui17.patterns.StatusPill
import io.github.tianli0.switchtether.ui.containerViewModel
import io.github.tianli0.switchtether.ui.theme.LocalDevColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Agents that support SwitchTether-managed sessions (desktop: `create_managed`). */
val MANAGED_AGENTS = listOf("claude_code", "codex", "dsh", "agy")

/** Agents every desktop with managed sessions can start; the others need a desktop feature ([AGENT_FEATURES]). */
val BASE_MANAGED_AGENTS = listOf("claude_code", "codex", "dsh")

/** The `GET /v1/capabilities` feature a desktop lists when it can start [MANAGED_AGENTS] beyond the base ones. */
val AGENT_FEATURES = mapOf("agy" to "managed_agy")

/** The agents the launcher offers on a desktop with [features]. */
fun launcherAgents(features: Set<String>): List<String> =
    MANAGED_AGENTS.filter { it in BASE_MANAGED_AGENTS || AGENT_FEATURES[it] in features }

/** Claude Code permission modes an older desktop accepts at start (a current one sends its own list). */
val CLAUDE_PERMISSION_MODES = listOf("default", "acceptEdits", "plan")

/** The modes a new session of [agent] can start in: the desktop's list, else what older desktops accepted. */
fun startPermissionModes(agent: String, options: io.github.tianli0.switchtether.remote.protocol.ModelOptions?): List<String> =
    options?.permissionModes?.map { it.id }?.takeIf { it.isNotEmpty() } ?: when (agent) {
        "claude_code" -> CLAUDE_PERMISSION_MODES
        "codex" -> listOf("default", "untrusted")
        else -> emptyList()
    }

/** A folder an agent was recently used in (from the session list), newest first. */
data class RecentProject(val path: String, val name: String)

/** How many earlier conversations the launcher offers to continue. */
const val RESUME_CANDIDATE_LIMIT = 5

/** The launcher's agent id for a session's agent (the session list says `claude` for Claude Code). */
private fun launcherAgent(agent: String) = if (agent == "claude") "claude_code" else agent

private fun samePath(a: String?, b: String?): Boolean {
    if (a.isNullOrBlank() || b.isNullOrBlank()) return false
    return a.trimEnd('/', '\\').equals(b.trimEnd('/', '\\'), ignoreCase = true)
}

/**
 * Conversations of [agent] the launcher can continue (`POST /v1/sessions {resumeFrom, mode: "continue"}`):
 * ended ones only — the desktop refuses a session that is live, busy or waiting — that have a folder,
 * the ones in [cwd] first, then by last activity, at most [RESUME_CANDIDATE_LIMIT].
 */
fun resumeCandidates(sessions: List<SessionSummary>, agent: String, cwd: String?): List<SessionSummary> =
    sessions.asSequence()
        .filter { launcherAgent(it.agent) == agent }
        .filter { !it.isLive && it.status != "busy" && it.status != "waiting" && it.waitingFor == null }
        .filter { !it.cwd.isNullOrBlank() }
        .distinctBy { it.id }
        .sortedWith(compareByDescending<SessionSummary> { samePath(it.cwd, cwd) }.thenByDescending { it.lastActiveAtMs ?: 0L })
        .take(RESUME_CANDIDATE_LIMIT)
        .toList()

data class NewSessionState(
    val agent: String = MANAGED_AGENTS.first(),
    /** Agents the connected desktop can start (tabs). */
    val agents: List<String> = BASE_MANAGED_AGENTS,
    val cwd: String? = null,
    val recent: List<RecentProject> = emptyList(),
    /** The session list the recent folders came from; [resumable] picks the conversations to continue from it. */
    val sessions: List<SessionSummary> = emptyList(),
    /** The earlier conversation to continue; null = a new conversation. */
    val resume: SessionSummary? = null,
    val roots: Loadable<List<FsEntry>> = Loadable.Loading,
    val listing: Loadable<FsListing>? = null,
    val prompt: String = "",
    val model: String = "",
    /** Effort / reasoning level; "" = the agent's default. */
    val effort: String = "",
    /** Models and effort levels the desktop offers for [agent] (null until loaded / older desktop). */
    val modelOptions: io.github.tianli0.switchtether.remote.protocol.ModelOptions? = null,
    /** [modelOptions] is being read / could not be read (the chooser says so instead of showing only "Default"). */
    val modelOptionsLoading: Boolean = false,
    val modelOptionsFailed: Boolean = false,
    val permissionMode: String = "default",
    /** Models used before with this agent (one-tap chips under the model field). */
    val recentModels: List<String> = emptyList(),
    val connection: ConnectionState = ConnectionState.Unpaired,
    val created: SessionSummary? = null,
) {
    /** Conversations offered under the folder row (ordered by the chosen folder, not by the selection). */
    val resumable: List<SessionSummary> get() = resumeCandidates(sessions, agent, cwd)

    /** Where the session will run: a continued conversation stays in its own folder. */
    val effectiveCwd: String? get() = resume?.cwd?.takeIf { it.isNotBlank() } ?: cwd
    val canStart: Boolean get() = effectiveCwd != null && connection is ConnectionState.Online

    /** Permission modes offered for [agent]; [permissionMode] counts only when it is one of them. */
    val permissionModes: List<String> get() = startPermissionModes(agent, modelOptions)
    val effectivePermissionMode: String? get() = permissionMode.takeIf { it in permissionModes && it != "default" }
}

/** The last launcher choices (agent, model per agent, permission mode), so a new session starts where the last one did. */
interface NewSessionPrefs {
    var agent: String?
    /** Working directory of the last session started from the phone. */
    var cwd: String?
    var permissionMode: String?
    /** The mode last chosen for [agent] (each agent keeps its own; Claude Code falls back to the old shared one). */
    fun permissionMode(agent: String): String? = permissionMode
    fun setPermissionMode(agent: String, mode: String) {
        permissionMode = mode
    }
    fun model(agent: String): String
    fun setModel(agent: String, model: String)
    /** Keep [model] as the current choice for [agent] without adding it to [recentModels]. */
    fun setCurrentModel(agent: String, model: String) = setModel(agent, model)
    /** Models used before with [agent], newest first. */
    fun recentModels(agent: String): List<String>
    fun effort(agent: String): String = ""
    fun setEffort(agent: String, effort: String) {}

    /** In-memory fallback (tests). */
    class Memory : NewSessionPrefs {
        override var agent: String? = null
        override var cwd: String? = null
        override var permissionMode: String? = null
        private val modes = mutableMapOf<String, String>()
        override fun permissionMode(agent: String) = modes[agent]
        override fun setPermissionMode(agent: String, mode: String) {
            modes[agent] = mode
        }
        private val models = mutableMapOf<String, String>()
        private val recents = mutableMapOf<String, List<String>>()
        override fun model(agent: String) = models[agent].orEmpty()
        override fun setModel(agent: String, model: String) {
            models[agent] = model
            if (model.isNotBlank()) recents[agent] = (listOf(model) + recentModels(agent).filter { it != model }).take(5)
        }
        override fun recentModels(agent: String) = recents[agent].orEmpty()
        override fun setCurrentModel(agent: String, model: String) {
            models[agent] = model
        }
        private val efforts = mutableMapOf<String, String>()
        override fun effort(agent: String) = efforts[agent].orEmpty()
        override fun setEffort(agent: String, effort: String) { efforts[agent] = effort }
    }
}

/** [NewSessionPrefs] in SharedPreferences (only agent ids, model ids and a mode; nothing secret). */
class SharedNewSessionPrefs(context: android.content.Context) : NewSessionPrefs {
    private val p = context.getSharedPreferences("new_session", android.content.Context.MODE_PRIVATE)
    override var agent: String?
        get() = p.getString("agent", null)
        set(v) = p.edit().putString("agent", v).apply()
    override var cwd: String?
        get() = p.getString("cwd", null)
        set(v) = p.edit().putString("cwd", v).apply()
    override var permissionMode: String?
        get() = p.getString("permission_mode", null)
        set(v) = p.edit().putString("permission_mode", v).apply()
    override fun permissionMode(agent: String): String? =
        p.getString("permission_mode.$agent", null) ?: if (agent == "claude_code") permissionMode else null
    override fun setPermissionMode(agent: String, mode: String) {
        p.edit().putString("permission_mode.$agent", mode).apply()
    }
    override fun model(agent: String): String = p.getString("model.$agent", "").orEmpty()
    override fun setModel(agent: String, model: String) {
        val recent = if (model.isBlank()) recentModels(agent) else (listOf(model) + recentModels(agent).filter { it != model }).take(5)
        p.edit().putString("model.$agent", model).putString("recent_models.$agent", recent.joinToString("\n")).apply()
    }
    override fun recentModels(agent: String): List<String> =
        p.getString("recent_models.$agent", "").orEmpty().split('\n').filter { it.isNotBlank() }
    override fun setCurrentModel(agent: String, model: String) {
        p.edit().putString("model.$agent", model).apply()
    }
    override fun effort(agent: String): String = p.getString("effort.$agent", "").orEmpty()
    override fun setEffort(agent: String, effort: String) {
        p.edit().putString("effort.$agent", effort).apply()
    }
}

class NewSessionViewModel(
    private val conn: ConnectionController,
    private val repo: SessionRepository,
    private val prefs: NewSessionPrefs = NewSessionPrefs.Memory(),
    /** Biometric step-up for a scope (true = granted); null (tests) leaves it to the desktop. */
    private val stepUp: (suspend (String) -> Boolean)? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(
        (prefs.agent?.takeIf { it in MANAGED_AGENTS } ?: MANAGED_AGENTS.first()).let { agent ->
            NewSessionState(
                connection = conn.state.value,
                agent = agent,
                // Where the last phone-started session ran; else the newest session's folder (loadRecent).
                cwd = prefs.cwd,
                model = prefs.model(agent),
                effort = prefs.effort(agent),
                recentModels = prefs.recentModels(agent),
                permissionMode = prefs.permissionMode(agent) ?: "default",
            )
        },
    )
    val state: StateFlow<NewSessionState> = _state.asStateFlow()
    val commands = CommandTracker(viewModelScope) { repo.commandStatus(it) }

    init {
        viewModelScope.launch {
            conn.state.distinctUntilChangedBy { it::class }.collect { s ->
                _state.update { it.copy(connection = s) }
                if (s is ConnectionState.Online) {
                    if (_state.value.roots !is Loadable.Ready) loadRoots()
                    loadRecent()
                    loadModelOptions()
                    loadAgents()
                }
            }
        }
    }

    /** Folders of the newest sessions: starting "where I worked last" is one tap, not a folder browser. */
    private fun loadRecent() {
        viewModelScope.launch {
            val items = runCatching { repo.list(SessionQuery(), limit = 100).items }.getOrNull() ?: return@launch
            val recent = items.sortedByDescending { it.lastActiveAtMs ?: 0L }
                .mapNotNull { s -> s.cwd?.takeIf { it.isNotBlank() }?.let { it to (s.project ?: it.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')) } }
                .distinctBy { it.first }
                .take(6)
                .map { RecentProject(it.first, it.second) }
            _state.update { st ->
                val next = st.copy(recent = recent, sessions = items, cwd = st.cwd ?: recent.firstOrNull()?.path)
                // A conversation that went live (or left the list) meanwhile can no longer be continued.
                next.copy(resume = next.resume?.let { r -> next.resumable.firstOrNull { it.id == r.id } })
            }
        }
    }

    /** Which agents this desktop can start (Antigravity needs a desktop that lists `managed_agy`). */
    private fun loadAgents() {
        viewModelScope.launch {
            val features = runCatching { conn.apiOrNull()?.features() }.getOrNull() ?: return@launch
            val agents = launcherAgents(features)
            _state.update { it.copy(agents = agents) }
            if (_state.value.agent !in agents) setAgent(agents.first())
        }
    }

    /** Models / effort levels for the selected agent (older desktops: built-in aliases only). */
    fun loadModelOptions() {
        val agent = _state.value.agent
        _state.update { it.copy(modelOptionsLoading = true, modelOptionsFailed = false) }
        viewModelScope.launch {
            val o = runCatching { repo.modelOptions(agent) }.getOrNull()
            _state.update {
                if (it.agent != agent) it
                else it.copy(modelOptions = o ?: it.modelOptions, modelOptionsLoading = false, modelOptionsFailed = o == null && it.modelOptions == null)
            }
        }
    }

    fun loadRoots() {
        viewModelScope.launch {
            val r = loadable { repo.fsRoots() }
            _state.update { it.copy(roots = r) }
        }
    }

    fun browse(path: String) {
        _state.update { it.copy(listing = Loadable.Loading) }
        viewModelScope.launch {
            val r = loadable { repo.fsList(path) }
            _state.update { it.copy(listing = r) }
        }
    }

    fun closeBrowser() = _state.update { it.copy(listing = null) }
    fun select(path: String) = _state.update { it.copy(cwd = path, resume = null) }

    /** Continue [session] instead of starting an empty conversation (null = a new conversation). */
    fun setResume(session: SessionSummary?) = _state.update { st ->
        st.copy(resume = session?.takeIf { s -> st.resumable.any { it.id == s.id } })
    }
    fun setAgent(a: String) {
        prefs.agent = a
        _state.update {
            it.copy(agent = a, model = prefs.model(a), effort = prefs.effort(a), recentModels = prefs.recentModels(a), modelOptions = null, modelOptionsLoading = false, modelOptionsFailed = false, resume = if (a == it.agent) it.resume else null, permissionMode = prefs.permissionMode(a) ?: "default")
        }
        if (_state.value.connection is ConnectionState.Online) loadModelOptions()
    }
    fun setPrompt(t: String) = _state.update { it.copy(prompt = t) }
    // Kept as soon as they are chosen (like the permission mode), not only when a session starts:
    // a launcher closed without starting must not forget the choice.
    fun setModel(t: String) {
        _state.update { it.copy(model = t) }
        prefs.setCurrentModel(_state.value.agent, t.trim())
    }
    fun setEffort(e: String) {
        _state.update { it.copy(effort = e) }
        prefs.setEffort(_state.value.agent, e)
    }
    fun setPermissionMode(m: String) {
        prefs.setPermissionMode(_state.value.agent, m)
        _state.update { it.copy(permissionMode = m) }
    }

    fun consumeCreated() = _state.update { it.copy(created = null, prompt = "", resume = null) }

    fun start(label: String) {
        val s = _state.value
        val cwd = s.effectiveCwd ?: return
        val req = NewSessionRequest(
            agent = s.agent,
            cwd = cwd,
            prompt = s.prompt,
            model = s.model,
            permissionMode = s.effectivePermissionMode,
            effort = s.effort.takeIf { it.isNotBlank() && supportsEffort(s.agent) },
            // Same history, as a session SwitchTether runs; forking is offered on the session's own page.
            resumeFrom = s.resume?.id,
            mode = if (s.resume != null) "continue" else null,
        )
        // Remember the model (an empty one too: "agent default" is a choice).
        prefs.setModel(s.agent, s.model.trim())
        prefs.setEffort(s.agent, s.effort)
        prefs.cwd = cwd
        _state.update { it.copy(recentModels = prefs.recentModels(s.agent)) }
        val dangerous = req.permissionMode?.let { m -> isDangerousPermissionMode(m, s.modelOptions?.permissionModes.orEmpty()) } == true
        viewModelScope.launch {
            // Starting without approvals is as powerful as a remote terminal: same biometric step-up.
            if (dangerous && stepUp?.invoke(io.github.tianli0.switchtether.remote.protocol.StepUpScopes.TERMINAL) == false) return@launch
            commands.run(label, onSuccess = { out -> _state.update { it.copy(created = out.createdSession()) } }) { id -> repo.createSession(req, id) }
        }
    }
}

/** One launcher state for the sheet, the page and the folder picker (a picked folder must reach the launcher). */
@Composable
fun newSessionViewModel(): NewSessionViewModel =
    containerViewModel(key = "new-session-sheet") { NewSessionViewModel(it.connection, it.sessions, it.newSessionPrefs, stepUp = { scope -> it.stepUpGate.ensure(scope).isSuccess }) }

private fun folderName(path: String) = path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').ifBlank { path }

/** Full-screen new-session page (kept for deep use / tests); the app normally uses [NewSessionSheet]. */
@Composable
fun NewSessionScreen(onBack: () -> Unit, onPickFolder: () -> Unit, onCreated: (String, String?) -> Unit) {
    val container = appContainer()
    val vm = newSessionViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val prompt by vm.commands.prompt.collectAsStateWithLifecycle()
    val busy by vm.commands.busy.collectAsStateWithLifecycle()
    val startLabel = stringResource(R.string.new_session_start)
    state.created?.let { created ->
        androidx.compose.runtime.LaunchedEffect(created.id) {
            vm.consumeCreated()
            onCreated(created.id, created.title)
        }
    }
    Page(
        title = stringResource(R.string.new_session_title),
        onBack = onBack,
        modifier = Modifier.imePadding(),
        actions = {
            SmallButton(
                text = stringResource(if (busy != null) R.string.state_loading else R.string.new_session_start_short),
                onClick = { vm.start(startLabel) },
                enabled = state.canStart && busy == null,
                tone = ActionTone.Primary,
                modifier = Modifier.padding(end = 12.dp).testTag("start-session"),
            )
        },
        header = { ConnectionBanner(state.connection, { container.connection.retryNow() }) },
    ) {
        // The options fold out and the keyboard takes half the screen: the launcher must scroll.
        Column(
            Modifier.weight(1f, fill = false).pageScroll()
                .verticalScroll(rememberPageScrollState(), flingBehavior = io.github.tianli0.coui17.patterns.pageFlingBehavior())
                .padding(top = io.github.tianli0.coui17.patterns.LocalPageTopInset.current),
        ) {
            NewSessionContent(
                state = state,
                busy = busy != null,
                onAgent = vm::setAgent,
                onSelect = vm::select,
                onPickFolder = onPickFolder,
                onPrompt = vm::setPrompt,
                onModel = vm::setModel,
                onPermissionMode = vm::setPermissionMode,
                onEffort = vm::setEffort,
                onResume = vm::setResume,
                onRetryModels = vm::loadModelOptions,
            )
        }
    }
    CommandPromptDialog(prompt, vm.commands::checkStatus, vm.commands::retry, vm.commands::dismiss)
}

/**
 * The launcher: agent, project (last-used folders one tap away, the folder browser on its own page),
 * the first message, and — folded away — model and permission mode. The start action is not part of
 * the form: the panel shows it as its toolbar's confirm capsule, the page as a top-bar action.
 */
@Composable
fun NewSessionContent(
    state: NewSessionState,
    busy: Boolean,
    onAgent: (String) -> Unit,
    onSelect: (String) -> Unit,
    onPickFolder: () -> Unit,
    onPrompt: (String) -> Unit,
    onModel: (String) -> Unit,
    onPermissionMode: (String) -> Unit,
    onEffort: (String) -> Unit = {},
    onResume: (SessionSummary?) -> Unit = {},
    /** DeepSeek Harness: its own web UI instead of a SwitchTether-managed session (null = not offered). */
    onOpenDshWeb: (() -> Unit)? = null,
    /** Reads the model list again after it failed. */
    onRetryModels: (() -> Unit)? = null,
) {
    val dev = LocalDevColors.current
    var options by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        TabRowWithContour(
            tabs = state.agents.map { io.github.tianli0.switchtether.ui.common.Formatters.agentLabel(it) },
            selectedTabIndex = state.agents.indexOf(state.agent).coerceAtLeast(0),
            onTabSelected = { onAgent(state.agents[it]) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 4.dp),
        )
        if (state.agent == "dsh" && onOpenDshWeb != null) {
            Box(Modifier.padding(top = 12.dp)) { io.github.tianli0.switchtether.ui.workspace.DshWebNewSessionRow(onOpenDshWeb) }
        }
        SmallTitle(stringResource(R.string.new_session_cwd))
        val shownCwd = state.effectiveCwd
        val others = state.recent.filter { it.path != shownCwd }
        GroupCard(bottom = if (others.isNotEmpty()) 10.dp else 16.dp) {
            ArrowPreference(
                title = shownCwd?.let(::folderName) ?: stringResource(R.string.new_session_cwd_none),
                summary = shownCwd,
                startAction = { RowIcon(AppIcons.Files) },
                onClick = onPickFolder,
                cardListPosition = CardListPosition.Full,
                modifier = Modifier.testTag("selected-cwd"),
            )
        }
        if (others.isNotEmpty()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                modifier = Modifier.padding(bottom = 16.dp),
            ) {
                items(others, key = { it.path }) { r -> Chip(selected = false, onClick = { onSelect(r.path) }, label = r.name, modifier = Modifier.animateItem()) }
            }
        }
        val resumable = state.resumable
        if (resumable.isNotEmpty()) {
            SmallTitle(stringResource(R.string.new_session_resume))
            val count = resumable.size + 1
            GroupCard(bottom = 16.dp) {
                RadioButtonPreference(
                    title = stringResource(R.string.new_session_resume_none),
                    selected = state.resume == null,
                    onClick = { onResume(null) },
                    cardListPosition = cardListPositionOf(0, count),
                    modifier = Modifier.testTag("resume-none"),
                )
                resumable.forEachIndexed { i, s ->
                    RadioButtonPreference(
                        title = io.github.tianli0.switchtether.domain.SessionNaming.rowTitle(sessionDisplayTitle(s)),
                        summary = listOfNotNull(sessionProjectName(s) ?: s.cwd?.let(::folderName), relativeTime(s.lastActiveAtMs)).joinToString(" · "),
                        selected = state.resume?.id == s.id,
                        onClick = { onResume(s) },
                        cardListPosition = cardListPositionOf(i + 1, count),
                        modifier = Modifier.testTag("resume:" + s.id),
                    )
                }
            }
        }
        TextInput(
            value = state.prompt,
            onValueChange = onPrompt,
            label = stringResource(R.string.new_session_prompt_hint),
            singleLine = false,
            maxLines = 5,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp),
        )
        // Model and permission mode: rarely changed, so folded into one summary row.
        val permLabel = permissionModeLabel(state.effectivePermissionMode ?: "default", state.agent)
        val expanded = stringResource(R.string.state_expanded)
        val collapsed = stringResource(R.string.state_collapsed)
        val turn by androidx.compose.animation.core.animateFloatAsState(if (options) 180f else 0f, label = "options-chevron")
        GroupCard {
            BasicComponent(
                title = stringResource(R.string.new_session_options),
                summary = listOfNotNull(
                    if (state.permissionModes.isNotEmpty()) permLabel else null,
                    modelEffortSummary(state.model, state.effort.takeIf { supportsEffort(state.agent) }),
                ).joinToString(" · "),
                endActions = {
                    io.github.suqi8.coui.kmp.basic.Icon(
                        AppIcons.ChevronDown,
                        contentDescription = null,
                        tint = COUITheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = turn },
                    )
                },
                onClick = { options = !options },
                cardListPosition = CardListPosition.Full,
                modifier = Modifier.semantics { stateDescription = if (options) expanded else collapsed },
            )
        }
        ExpandBody(options) {
            if (state.permissionModes.isNotEmpty()) {
                // Same list, words and "run without asking?" confirmation as inside a session.
                SmallTitle(stringResource(R.string.tools_permission))
                PermissionModeOptions(
                    current = state.effectivePermissionMode ?: "default",
                    pending = null,
                    enabled = true,
                    onSelect = onPermissionMode,
                    modes = state.modelOptions?.permissionModes?.takeIf { it.isNotEmpty() }
                        ?: state.permissionModes.map { io.github.tianli0.switchtether.remote.protocol.PermissionModeOption(it) },
                    agent = state.agent,
                )
            }
            ModelEffortChooser(
                agent = state.agent,
                options = state.modelOptions,
                model = state.model,
                effort = state.effort,
                onModel = onModel,
                onEffort = onEffort,
                recent = state.recentModels,
                modifier = Modifier.fillMaxWidth(),
                optionsLoading = state.modelOptionsLoading,
                optionsFailed = state.modelOptionsFailed,
                onRetryOptions = onRetryModels,
            )
        }
        // Say why Start is disabled instead of leaving a grey button.
        if (!state.canStart && !busy) {
            Text(
                stringResource(if (state.connection !is ConnectionState.Online) R.string.new_session_need_connection else R.string.new_session_need_folder),
                style = COUITheme.textStyles.footnote1,
                color = dev.warn,
                modifier = Modifier.padding(horizontal = PageDefaults.TextGutter).padding(bottom = 6.dp),
            )
        }
        Text(
            stringResource(R.string.new_session_note),
            style = COUITheme.textStyles.footnote1,
            color = COUITheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(horizontal = PageDefaults.TextGutter),
        )
    }
}

/**
 * Folder browser on its own page; "Use this folder" returns to the new-session sheet. Back goes up
 * one folder, then to the list of locations, then back to the sheet.
 */
@Composable
fun PickFolderScreen(onBack: () -> Unit) {
    val container = appContainer()
    val vm = newSessionViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val dev = LocalDevColors.current
    val done: () -> Unit = {
        vm.closeBrowser()
        container.newSessionReopen.value = true
        onBack()
    }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        val start = state.cwd
        if (start != null) vm.browse(start) else vm.closeBrowser()
        if (state.roots !is Loadable.Ready) vm.loadRoots()
    }
    var filter by rememberSaveable { mutableStateOf("") }
    val listing = (state.listing as? Loadable.Ready)?.value
    androidx.compose.runtime.LaunchedEffect(listing?.path) { filter = "" }
    val goBack: () -> Unit = {
        val parent = listing?.parent
        when {
            parent != null -> vm.browse(parent)
            state.listing != null -> vm.closeBrowser()
            else -> done()
        }
    }
    val back = io.github.tianli0.switchtether.ui.common.rememberPredictiveBack(onBack = goBack)
    Page(
        modifier = back,
        // Inside a folder the page is named after it, and the title bar's action picks that folder:
        // no full-width button under the list (and no disabled one while browsing the locations).
        title = listing?.path?.let(::folderName) ?: stringResource(R.string.new_session_cwd),
        onBack = done,
        large = false,
        actions = {
            if (listing != null) {
                SmallButton(
                    text = stringResource(R.string.new_session_use_folder),
                    onClick = {
                        vm.select(listing.path)
                        done()
                    },
                    tone = ActionTone.Primary,
                    modifier = Modifier.padding(end = 12.dp).testTag("use-folder"),
                )
            }
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth().pageScroll(), state = io.github.tianli0.switchtether.ui.common.rememberPageListState(), contentPadding = pageListPadding()) {
                when (val l = state.listing) {
                    null -> when (val roots = state.roots) {
                        Loadable.Loading -> item { Text(stringResource(R.string.state_loading), color = dev.gutter, modifier = Modifier.padding(16.dp)) }
                        is Loadable.Failed -> item {
                            GroupCard {
                                BasicComponent(
                                    title = roots.error.title(),
                                    summary = roots.error.detail,
                                    endActions = { SmallButton(stringResource(R.string.action_retry), vm::loadRoots) },
                                    cardListPosition = CardListPosition.Full,
                                )
                            }
                        }
                        is Loadable.Ready -> {
                            val recent = state.recent
                            if (recent.isNotEmpty()) {
                                item(key = "recent-h") { SmallTitle(stringResource(R.string.new_session_recent_folders)) }
                                itemsIndexed(recent, key = { _, r -> "recent:" + r.path }) { i, r ->
                                    FolderRow(r.name, r.path, false, cardListPositionOf(i, recent.size)) { vm.browse(r.path) }
                                }
                                item(key = "roots-h") { SmallTitle(stringResource(R.string.new_session_all_locations)) }
                            }
                            val rootsShown = roots.value.filter { r -> recent.none { samePath(it.path, r.path) } }
                            itemsIndexed(rootsShown, key = { _, r -> "root:" + r.path }) { i, r ->
                                val title = wellKnownFolderLabel(r.name)
                                    ?: if (r.name.isBlank() || r.name == r.path) io.github.tianli0.switchtether.ui.workspace.folderLabel(r.path) else r.name
                                FolderRow(title, r.path.takeIf { it != title }, false, cardListPositionOf(i, rootsShown.size)) { vm.browse(r.path) }
                            }
                        }
                    }
                    Loadable.Loading -> item { Text(stringResource(R.string.state_loading), color = dev.gutter, modifier = Modifier.padding(16.dp)) }
                    is Loadable.Failed -> item {
                        GroupCard {
                            BasicComponent(
                                title = l.error.title(),
                                summary = l.error.detail,
                                endActions = { SmallButton(stringResource(R.string.action_back), goBack) },
                                cardListPosition = CardListPosition.Full,
                            )
                        }
                    }
                    is Loadable.Ready -> {
                        val all = l.value.entries.filter { it.isDir }
                        val q = filter.trim()
                        val dirs = if (q.isEmpty()) all else all.filter { it.name.contains(q, ignoreCase = true) }
                        item(key = "path") {
                            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(l.value.path, style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.weight(1f))
                                LinkButton(stringResource(R.string.new_session_all_locations), vm::closeBrowser)
                            }
                        }
                        if (all.size > 12) {
                            item(key = "filter") {
                                // COUI17's animated search bar (expands on tap, cancel clears and collapses).
                                var filterExpanded by androidx.compose.runtime.remember { mutableStateOf(filter.isNotEmpty()) }
                                androidx.compose.runtime.CompositionLocalProvider(
                                    io.github.suqi8.coui.kmp.basic.LocalSearchBarKeyboardType provides androidx.compose.ui.text.input.KeyboardType.Ascii,
                                ) {
                                    io.github.suqi8.coui.kmp.basic.SearchBar(
                                        inputField = {
                                            io.github.suqi8.coui.kmp.basic.InputField(
                                                query = filter,
                                                onQueryChange = { filter = it },
                                                onSearch = {},
                                                expanded = filterExpanded,
                                                onExpandedChange = { filterExpanded = it },
                                                label = stringResource(R.string.new_session_filter),
                                            )
                                        },
                                        expanded = filterExpanded,
                                        onExpandedChange = { filterExpanded = it },
                                        outsideEndAction = {
                                            io.github.suqi8.coui.kmp.basic.SearchBarActionButton(stringResource(R.string.action_cancel), onClick = { filterExpanded = false })
                                        },
                                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                    ) {}
                                }
                            }
                        }
                        val parent = l.value.parent
                        val offset = if (parent != null) 1 else 0
                        val total = dirs.size + offset
                        if (total == 0) {
                            item(key = "none") { GroupCard { BasicComponent(title = stringResource(R.string.new_session_no_subdirs), cardListPosition = CardListPosition.Full) } }
                        }
                        if (parent != null) {
                            item(key = "..") {
                                FolderRow(stringResource(R.string.new_session_parent_folder), parent, false, cardListPositionOf(0, total), icon = AppIcons.Back) { vm.browse(parent) }
                            }
                        }
                        itemsIndexed(dirs, key = { _, d -> d.path }) { i, d ->
                            FolderRow(d.name, null, d.sensitive, cardListPositionOf(i + offset, total)) { vm.browse(d.path) }
                        }
                        if (l.value.truncated || l.value.blocked > 0) {
                            item(key = "note") {
                                // Cut short and blocked are different reasons; a cut-off list also limits the filter.
                                val note = listOfNotNull(
                                    if (l.value.truncated) stringResource(if (q.isEmpty()) R.string.new_session_listing_truncated else R.string.new_session_listing_truncated_filter) else null,
                                    if (l.value.blocked > 0) stringResource(R.string.new_session_listing_note, l.value.blocked) else null,
                                ).joinToString("\n")
                                Text(note, style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(horizontal = PageDefaults.TextGutter, vertical = 12.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * One folder row drawn as its own lazy item with the group card's background, rounded only on the
 * group's outer edges (large folders stay smooth instead of composing every row at once).
 */
@Composable
private fun FolderRow(name: String, path: String?, sensitive: Boolean, position: CardListPosition, icon: androidx.compose.ui.graphics.vector.ImageVector = AppIcons.Files, onClick: () -> Unit) {
    val dev = LocalDevColors.current
    LazyGroupRow(position) {
        BasicComponent(
            title = name,
            summary = path,
            startAction = { RowIcon(icon) },
            endActions = if (sensitive) ({ StatusPill(stringResource(R.string.new_session_sensitive), dev.warn) }) else null,
            onClick = onClick,
            cardListPosition = position,
        )
    }
}

/** "New SwitchTether-managed session" as a coui bottom sheet (opened from the capsule "+"). */
@Composable
fun NewSessionSheet(show: Boolean, onDismiss: () -> Unit, onPickFolder: () -> Unit, onCreated: (String, String?) -> Unit, onOpenDshWeb: (() -> Unit)? = null) {
    val vm = newSessionViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val prompt by vm.commands.prompt.collectAsStateWithLifecycle()
    val busy by vm.commands.busy.collectAsStateWithLifecycle()
    val startLabel = stringResource(R.string.new_session_start)
    state.created?.let { created ->
        androidx.compose.runtime.LaunchedEffect(created.id) {
            vm.consumeCreated()
            onDismiss()
            onCreated(created.id, created.title)
        }
    }
    io.github.tianli0.coui17.patterns.PanelSheet(
        show = show,
        title = stringResource(R.string.new_session_title),
        onDismissRequest = onDismiss,
        confirm = SheetConfirm(
            text = stringResource(if (busy != null) R.string.state_loading else R.string.new_session_start_short),
            enabled = state.canStart && busy == null,
            testTag = "start-session",
            onClick = { vm.start(startLabel) },
        ),
    ) {
        // A fling that runs back up to the top of the options ends there: its leftover must not reach
        // the sheet, which took it as a drag down and closed (losing the typed prompt). A finger
        // dragging down from the top still closes the sheet as before.
        val keepFlingInside = remember {
            object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
                override fun onPostScroll(
                    consumed: androidx.compose.ui.geometry.Offset,
                    available: androidx.compose.ui.geometry.Offset,
                    source: androidx.compose.ui.input.nestedscroll.NestedScrollSource,
                ): androidx.compose.ui.geometry.Offset =
                    if (source == androidx.compose.ui.input.nestedscroll.NestedScrollSource.SideEffect) available else androidx.compose.ui.geometry.Offset.Zero

                override suspend fun onPostFling(consumed: androidx.compose.ui.unit.Velocity, available: androidx.compose.ui.unit.Velocity) = available
            }
        }
        // Drops from other apps (§35): text joins the first message; files wait for the session and
        // are attached in its input box once it has started (the share inbox's "new session" path).
        val container = io.github.tianli0.switchtether.ui.appContainer()
        val inbox = container.shareInbox
        val pendingShare by inbox.state.collectAsStateWithLifecycle()
        val heldFiles = pendingShare?.takeIf { it.toNewSession }?.content?.items?.size ?: 0
        val dropContext = androidx.compose.ui.platform.LocalContext.current
        var dropHover by remember { mutableStateOf(false) }
        val gate = newSessionGate(state.connection is ConnectionState.Online)
        val onDrop: (DropPayload) -> Unit = { payload ->
            val plan = planIntake(payload, gate, heldFiles, io.github.tianli0.switchtether.share.ShareIntents.ownAuthority(dropContext.applicationContext))
            if (!plan.text.isNullOrBlank()) vm.setPrompt(dropIntoDraft(vm.state.value.prompt, plan.text))
            if (plan.accepted.isNotEmpty()) {
                val p = inbox.state.value
                if (p != null && p.toNewSession) {
                    inbox.updateContent { it.copy(items = it.items + plan.accepted) }
                } else {
                    // The newest content wins, as with a newer share (§32); the replaced one's cache copies go.
                    p?.content?.items?.forEach { it.cachedPath?.let { path -> java.io.File(path).parentFile?.deleteRecursively() } }
                    inbox.receive(io.github.tianli0.switchtether.share.SharedContent(text = null, items = plan.accepted))
                    inbox.pickNewSession()
                }
            }
            intakeReport(dropContext, plan)?.let { android.widget.Toast.makeText(dropContext, it, android.widget.Toast.LENGTH_LONG).show() }
        }
        Box(Modifier.fillMaxWidth().nestedScroll(keepFlingInside).contentDropTarget(onHover = { dropHover = it }, onDrop = onDrop)) {
            // Wrap short forms. The sheet itself caps the scrolling viewport at the status bar.
            Column(Modifier.verticalScroll(rememberPageScrollState(), flingBehavior = io.github.tianli0.coui17.patterns.pageFlingBehavior())) {
                if (heldFiles > 0) {
                    io.github.tianli0.coui17.patterns.NoticeBanner(
                        text = androidx.compose.ui.res.pluralStringResource(R.plurals.new_session_drop_files, heldFiles, heldFiles),
                        color = COUITheme.colorScheme.primary,
                        modifier = Modifier.testTag("new-session-files"),
                    )
                }
                NewSessionContent(
                    state = state,
                    busy = busy != null,
                    onAgent = vm::setAgent,
                    onSelect = vm::select,
                    onPickFolder = onPickFolder,
                    onPrompt = vm::setPrompt,
                    onModel = vm::setModel,
                    onPermissionMode = vm::setPermissionMode,
                    onEffort = vm::setEffort,
                onResume = vm::setResume,
                    onOpenDshWeb = onOpenDshWeb,
                    onRetryModels = vm::loadModelOptions,
                )
            }
            DropHighlight(
                visible = dropHover,
                label = intakeBlockText(gate.block) ?: stringResource(R.string.drop_here_new_session),
                blocked = gate.block != IntakeBlock.NONE,
            )
        }
    }
    CommandPromptDialog(prompt, vm.commands::checkStatus, vm.commands::retry, vm.commands::dismiss)
}

/**
 * The desktop names a few folders under the user's home with fixed English labels
 * (switchtether-core `files::roots`: "Home", "Desktop", "Documents", "Downloads"); shown in the
 * app's language. Other labels (project folders, drives) are names, not words, and stay as they are.
 */
@Composable
internal fun wellKnownFolderLabel(name: String): String? = when (name) {
    "Home" -> stringResource(R.string.folder_home)
    "Desktop" -> stringResource(R.string.folder_desktop)
    "Documents" -> stringResource(R.string.folder_documents)
    "Downloads" -> stringResource(R.string.folder_downloads)
    else -> null
}
