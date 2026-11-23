package io.github.tianli0.switchtether.ui.sessions

import io.github.suqi8.coui.kmp.basic.cardListPositionOf
import io.github.suqi8.coui.kmp.basic.SmallTitle
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import io.github.suqi8.coui.kmp.basic.Button
import io.github.suqi8.coui.kmp.basic.CardListPosition
import androidx.compose.runtime.saveable.rememberSaveable
import io.github.tianli0.switchtether.domain.SessionQuery
import io.github.tianli0.switchtether.ui.common.Page
import io.github.tianli0.switchtether.ui.icons.AppIcons
import io.github.tianli0.switchtether.ui.home.RowIcon
import io.github.suqi8.coui.kmp.basic.ButtonDefaults
import io.github.suqi8.coui.kmp.basic.TabRowWithContour
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.preference.RadioButtonPreference
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.tianli0.switchtether.R
import io.github.tianli0.switchtether.ui.common.GroupCard
import io.github.tianli0.switchtether.ui.common.pageListPadding
import io.github.tianli0.switchtether.ui.common.pageScroll
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
import io.github.tianli0.switchtether.ui.common.ConfirmDialog
import io.github.tianli0.switchtether.ui.common.ConnectionBanner
import io.github.tianli0.switchtether.ui.common.Loadable
import io.github.tianli0.switchtether.ui.common.Page
import io.github.tianli0.switchtether.ui.common.TextInput
import io.github.tianli0.switchtether.ui.common.loadable
import io.github.tianli0.switchtether.ui.common.title
import io.github.tianli0.switchtether.ui.components.Banner
import io.github.tianli0.switchtether.ui.components.FilterChip
import io.github.tianli0.switchtether.ui.components.PageCard
import io.github.tianli0.switchtether.ui.components.SectionHeader
import io.github.tianli0.switchtether.ui.components.StatusPill
import io.github.tianli0.switchtether.ui.containerViewModel
import io.github.tianli0.switchtether.ui.theme.DevType
import io.github.tianli0.switchtether.ui.theme.LocalDevColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Agents that support SwitchTether-managed sessions (desktop: `create_managed`). */
val MANAGED_AGENTS = listOf("claude_code", "codex", "dsh")

/** Claude Code permission modes offered from the phone (bypassPermissions is deliberately not offered). */
val CLAUDE_PERMISSION_MODES = listOf("default", "acceptEdits", "plan")

/** A folder an agent was recently used in (from the session list), newest first. */
data class RecentProject(val path: String, val name: String)

data class NewSessionState(
    val agent: String = MANAGED_AGENTS.first(),
    val cwd: String? = null,
    val recent: List<RecentProject> = emptyList(),
    val roots: Loadable<List<FsEntry>> = Loadable.Loading,
    val listing: Loadable<FsListing>? = null,
    val prompt: String = "",
    val model: String = "",
    /** Effort / reasoning level; "" = the agent's default. */
    val effort: String = "",
    /** Models and effort levels the desktop offers for [agent] (null until loaded / older desktop). */
    val modelOptions: io.github.tianli0.switchtether.remote.protocol.ModelOptions? = null,
    val permissionMode: String = "default",
    /** Models used before with this agent (one-tap chips under the model field). */
    val recentModels: List<String> = emptyList(),
    val connection: ConnectionState = ConnectionState.Unpaired,
    val created: SessionSummary? = null,
) {
    val canStart: Boolean get() = cwd != null && connection is ConnectionState.Online
}

/** The last launcher choices (agent, model per agent, permission mode), so a new session starts where the last one did. */
interface NewSessionPrefs {
    var agent: String?
    /** Working directory of the last session started from the phone. */
    var cwd: String?
    var permissionMode: String?
    fun model(agent: String): String
    fun setModel(agent: String, model: String)
    /** Models used before with [agent], newest first. */
    fun recentModels(agent: String): List<String>
    fun effort(agent: String): String = ""
    fun setEffort(agent: String, effort: String) {}

    /** In-memory fallback (tests). */
    class Memory : NewSessionPrefs {
        override var agent: String? = null
        override var cwd: String? = null
        override var permissionMode: String? = null
        private val models = mutableMapOf<String, String>()
        private val recents = mutableMapOf<String, List<String>>()
        override fun model(agent: String) = models[agent].orEmpty()
        override fun setModel(agent: String, model: String) {
            models[agent] = model
            if (model.isNotBlank()) recents[agent] = (listOf(model) + recentModels(agent).filter { it != model }).take(5)
        }
        override fun recentModels(agent: String) = recents[agent].orEmpty()
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
    override fun model(agent: String): String = p.getString("model.$agent", "").orEmpty()
    override fun setModel(agent: String, model: String) {
        val recent = if (model.isBlank()) recentModels(agent) else (listOf(model) + recentModels(agent).filter { it != model }).take(5)
        p.edit().putString("model.$agent", model).putString("recent_models.$agent", recent.joinToString("\n")).apply()
    }
    override fun recentModels(agent: String): List<String> =
        p.getString("recent_models.$agent", "").orEmpty().split('\n').filter { it.isNotBlank() }
    override fun effort(agent: String): String = p.getString("effort.$agent", "").orEmpty()
    override fun setEffort(agent: String, effort: String) {
        p.edit().putString("effort.$agent", effort).apply()
    }
}

class NewSessionViewModel(
    private val conn: ConnectionController,
    private val repo: SessionRepository,
    private val prefs: NewSessionPrefs = NewSessionPrefs.Memory(),
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
                permissionMode = prefs.permissionMode?.takeIf { it in CLAUDE_PERMISSION_MODES } ?: "default",
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
            _state.update { st -> st.copy(recent = recent, cwd = st.cwd ?: recent.firstOrNull()?.path) }
        }
    }

    /** Models / effort levels for the selected agent (older desktops: built-in aliases only). */
    private fun loadModelOptions() {
        val agent = _state.value.agent
        viewModelScope.launch {
            val o = runCatching { repo.modelOptions(agent) }.getOrNull()
            _state.update { if (it.agent == agent) it.copy(modelOptions = o) else it }
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
    fun select(path: String) = _state.update { it.copy(cwd = path) }
    fun setAgent(a: String) {
        prefs.agent = a
        _state.update { it.copy(agent = a, model = prefs.model(a), effort = prefs.effort(a), recentModels = prefs.recentModels(a), modelOptions = null) }
        if (_state.value.connection is ConnectionState.Online) loadModelOptions()
    }
    fun setPrompt(t: String) = _state.update { it.copy(prompt = t) }
    fun setModel(t: String) = _state.update { it.copy(model = t) }
    fun setEffort(e: String) = _state.update { it.copy(effort = e) }
    fun setPermissionMode(m: String) {
        prefs.permissionMode = m
        _state.update { it.copy(permissionMode = m) }
    }

    fun consumeCreated() = _state.update { it.copy(created = null, prompt = "") }

    fun start(label: String) {
        val s = _state.value
        val cwd = s.cwd ?: return
        val req = NewSessionRequest(
            agent = s.agent,
            cwd = cwd,
            prompt = s.prompt,
            model = s.model,
            permissionMode = if (s.agent == "claude_code") s.permissionMode else null,
            effort = s.effort.takeIf { it.isNotBlank() && supportsEffort(s.agent) },
        )
        // Remember the model (an empty one too: "agent default" is a choice).
        prefs.setModel(s.agent, s.model.trim())
        prefs.setEffort(s.agent, s.effort)
        prefs.cwd = cwd
        _state.update { it.copy(recentModels = prefs.recentModels(s.agent)) }
        commands.run(label, onSuccess = { out -> _state.update { it.copy(created = out.createdSession()) } }) { id -> repo.createSession(req, id) }
    }
}

/** One launcher state for the sheet, the page and the folder picker (a picked folder must reach the launcher). */
@Composable
fun newSessionViewModel(): NewSessionViewModel =
    containerViewModel(key = "new-session-sheet") { NewSessionViewModel(it.connection, it.sessions, it.newSessionPrefs) }

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
    ) {
        ConnectionBanner(state.connection, { container.connection.retryNow() })
        // The options fold out and the keyboard takes half the screen: the launcher must scroll.
        Column(Modifier.weight(1f, fill = false).pageScroll().verticalScroll(rememberScrollState())) {
            NewSessionContent(
                state = state,
                busy = busy != null,
                onAgent = vm::setAgent,
                onSelect = vm::select,
                onPickFolder = onPickFolder,
                onPrompt = vm::setPrompt,
                onModel = vm::setModel,
                onPermissionMode = vm::setPermissionMode,
                onStart = { vm.start(startLabel) },
                onEffort = vm::setEffort,
            )
        }
    }
    CommandPromptDialog(prompt, vm.commands::checkStatus, vm.commands::retry, vm.commands::dismiss)
}

/**
 * The launcher: agent, project (last-used folders one tap away, the folder browser on its own page),
 * the first message, and — folded away — model and permission mode. Starting needs no extra dialog.
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
    onStart: () -> Unit,
    onEffort: (String) -> Unit = {},
) {
    val dev = LocalDevColors.current
    var options by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        TabRowWithContour(
            tabs = MANAGED_AGENTS.map { io.github.tianli0.switchtether.ui.common.Formatters.agentLabel(it) },
            selectedTabIndex = MANAGED_AGENTS.indexOf(state.agent).coerceAtLeast(0),
            onTabSelected = { onAgent(MANAGED_AGENTS[it]) },
            modifier = Modifier.fillMaxWidth(),
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            GroupCard {
                BasicComponent(
                    title = state.cwd?.let(::folderName) ?: stringResource(R.string.new_session_cwd_none),
                    summary = state.cwd ?: stringResource(R.string.new_session_cwd),
                    startAction = { RowIcon(AppIcons.Files) },
                    endActions = { io.github.suqi8.coui.kmp.basic.Icon(AppIcons.ChevronRight, contentDescription = null, tint = COUITheme.colorScheme.onSurfaceVariantSummary) },
                    onClick = onPickFolder,
                    cardListPosition = CardListPosition.Full,
                    modifier = Modifier.testTag("selected-cwd"),
                )
            }
            val others = state.recent.filter { it.path != state.cwd }
            if (others.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(others, key = { it.path }) { r -> FilterChip(r.name, false, { onSelect(r.path) }) }
                }
            }
        }
        TextInput(
            value = state.prompt,
            onValueChange = onPrompt,
            label = stringResource(R.string.new_session_prompt_hint),
            singleLine = false,
            maxLines = 5,
            modifier = Modifier.fillMaxWidth(),
        )
        // Model and permission mode: rarely changed, so folded into one summary row.
        val permLabel = when (state.permissionMode) {
            "acceptEdits" -> stringResource(R.string.perm_accept_edits_short)
            "plan" -> stringResource(R.string.perm_plan_short)
            else -> stringResource(R.string.perm_default_short)
        }
        GroupCard {
            BasicComponent(
                title = stringResource(R.string.new_session_options),
                summary = listOfNotNull(
                    if (state.agent == "claude_code") permLabel else null,
                    modelEffortSummary(state.model, state.effort.takeIf { supportsEffort(state.agent) }),
                ).joinToString(" · "),
                endActions = { Text(stringResource(if (options) R.string.state_expanded else R.string.state_collapsed), style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurfaceVariantSummary) },
                onClick = { options = !options },
                cardListPosition = CardListPosition.Full,
            )
        }
        if (options) {
            if (state.agent == "claude_code") {
                TabRowWithContour(
                    tabs = CLAUDE_PERMISSION_MODES.map {
                        when (it) {
                            "acceptEdits" -> stringResource(R.string.perm_accept_edits_short)
                            "plan" -> stringResource(R.string.perm_plan_short)
                            else -> stringResource(R.string.perm_default_short)
                        }
                    },
                    selectedTabIndex = CLAUDE_PERMISSION_MODES.indexOf(state.permissionMode).coerceAtLeast(0),
                    onTabSelected = { onPermissionMode(CLAUDE_PERMISSION_MODES[it]) },
                    modifier = Modifier.fillMaxWidth(),
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
            )
        }
        Button(
            onClick = onStart,
            enabled = state.canStart && !busy,
            colors = ButtonDefaults.buttonColorsPrimary(),
            modifier = Modifier.fillMaxWidth().testTag("start-session"),
        ) {
            Text(if (busy) stringResource(R.string.state_loading) else stringResource(R.string.new_session_start), color = COUITheme.colorScheme.onPrimary)
        }
        // Say why Start is disabled instead of leaving a grey button.
        if (!state.canStart && !busy) {
            Text(
                stringResource(if (state.connection !is ConnectionState.Online) R.string.new_session_need_connection else R.string.new_session_need_folder),
                style = COUITheme.textStyles.footnote1,
                color = dev.warn,
            )
        }
        Text(stringResource(R.string.new_session_note), style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurfaceVariantSummary)
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
    androidx.activity.compose.BackHandler(onBack = goBack)
    Page(title = stringResource(R.string.new_session_cwd), onBack = done, large = false) {
        Column(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = pageListPadding()) {
                when (val l = state.listing) {
                    null -> when (val roots = state.roots) {
                        Loadable.Loading -> item { Text(stringResource(R.string.state_loading), color = dev.gutter, modifier = Modifier.padding(16.dp)) }
                        is Loadable.Failed -> item {
                            GroupCard {
                                BasicComponent(
                                    title = roots.error.title(),
                                    summary = roots.error.detail,
                                    endActions = { TextButton(text = stringResource(R.string.action_retry), onClick = vm::loadRoots) },
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
                            itemsIndexed(roots.value, key = { _, r -> "root:" + r.path }) { i, r ->
                                FolderRow(r.name.ifBlank { r.path }, r.path.takeIf { it != r.name }, false, cardListPositionOf(i, roots.value.size)) { vm.browse(r.path) }
                            }
                        }
                    }
                    Loadable.Loading -> item { Text(stringResource(R.string.state_loading), color = dev.gutter, modifier = Modifier.padding(16.dp)) }
                    is Loadable.Failed -> item {
                        GroupCard {
                            BasicComponent(
                                title = l.error.title(),
                                summary = l.error.detail,
                                endActions = { TextButton(text = stringResource(R.string.action_back), onClick = goBack) },
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
                                Text(l.value.path, style = DevType.codeSmall, color = COUITheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.weight(1f))
                                TextButton(text = stringResource(R.string.new_session_all_locations), onClick = vm::closeBrowser)
                            }
                        }
                        if (all.size > 12) {
                            item(key = "filter") {
                                TextInput(
                                    value = filter,
                                    onValueChange = { filter = it },
                                    label = stringResource(R.string.new_session_filter),
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp),
                                )
                            }
                        }
                        val parent = l.value.parent
                        val offset = if (parent != null) 1 else 0
                        val total = dirs.size + offset
                        if (total == 0) {
                            item(key = "none") { GroupCard { BasicComponent(title = stringResource(R.string.new_session_no_subdirs), cardListPosition = CardListPosition.Full) } }
                        }
                        if (parent != null) item(key = "..") { FolderRow("..", null, false, cardListPositionOf(0, total)) { vm.browse(parent) } }
                        itemsIndexed(dirs, key = { _, d -> d.path }) { i, d ->
                            FolderRow(d.name, null, d.sensitive, cardListPositionOf(i + offset, total)) { vm.browse(d.path) }
                        }
                        if (l.value.truncated || l.value.blocked > 0) {
                            item(key = "note") { Text(stringResource(R.string.new_session_listing_note, l.value.blocked), style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(16.dp)) }
                        }
                    }
                }
            }
            Button(
                onClick = {
                    listing?.path?.let(vm::select)
                    done()
                },
                enabled = listing != null,
                colors = ButtonDefaults.buttonColorsPrimary(),
                modifier = Modifier.fillMaxWidth().padding(16.dp).navigationBarsPadding(),
            ) {
                Text(
                    listing?.path?.let { stringResource(R.string.new_session_use_named_folder, folderName(it)) } ?: stringResource(R.string.new_session_pick_hint),
                    color = COUITheme.colorScheme.onPrimary,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * One folder row drawn as its own lazy item with the group card's background, rounded only on the
 * group's outer edges (large folders stay smooth instead of composing every row at once).
 */
@Composable
private fun FolderRow(name: String, path: String?, sensitive: Boolean, position: CardListPosition, onClick: () -> Unit) {
    val dev = LocalDevColors.current
    val r = io.github.suqi8.coui.kmp.basic.CardDefaults.GroupCornerRadius
    val shape = when (position) {
        CardListPosition.Full -> androidx.compose.foundation.shape.RoundedCornerShape(r)
        CardListPosition.Head -> androidx.compose.foundation.shape.RoundedCornerShape(topStart = r, topEnd = r)
        CardListPosition.Tail -> androidx.compose.foundation.shape.RoundedCornerShape(bottomStart = r, bottomEnd = r)
        else -> androidx.compose.ui.graphics.RectangleShape
    }
    val last = position == CardListPosition.Tail || position == CardListPosition.Full
    Box(
        Modifier.padding(horizontal = 16.dp).padding(bottom = if (last) 16.dp else 0.dp)
            .clip(shape).background(io.github.suqi8.coui.kmp.basic.CardDefaults.defaultColors().color),
    ) {
        BasicComponent(
            title = name,
            summary = path,
            startAction = { RowIcon(AppIcons.Files) },
            endActions = if (sensitive) ({ StatusPill(stringResource(R.string.new_session_sensitive), dev.warn) }) else null,
            onClick = onClick,
            cardListPosition = position,
        )
    }
}

/** "New SwitchTether-managed session" as a coui bottom sheet (opened from the capsule "+"). */
@Composable
fun NewSessionSheet(show: Boolean, onDismiss: () -> Unit, onPickFolder: () -> Unit, onCreated: (String, String?) -> Unit) {
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
    io.github.tianli0.switchtether.ui.common.AppBottomSheet(
        show = show,
        title = stringResource(R.string.new_session_title),
        onDismissRequest = onDismiss,
    ) {
        Box(Modifier.fillMaxWidth().heightIn(max = 620.dp)) {
            Column(Modifier.pageScroll().verticalScroll(rememberScrollState())) {
                NewSessionContent(
                    state = state,
                    busy = busy != null,
                    onAgent = vm::setAgent,
                    onSelect = vm::select,
                    onPickFolder = onPickFolder,
                    onPrompt = vm::setPrompt,
                    onModel = vm::setModel,
                    onPermissionMode = vm::setPermissionMode,
                    onStart = { vm.start(startLabel) },
            onEffort = vm::setEffort,
                )
            }
        }
    }
    CommandPromptDialog(prompt, vm.commands::checkStatus, vm.commands::retry, vm.commands::dismiss)
}
