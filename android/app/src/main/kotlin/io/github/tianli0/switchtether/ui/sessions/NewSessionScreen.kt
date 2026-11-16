package io.github.tianli0.switchtether.ui.sessions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
val MANAGED_AGENTS = listOf("claude_code", "codex")

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
    val permissionMode: String = "default",
    val connection: ConnectionState = ConnectionState.Unpaired,
    val created: SessionSummary? = null,
) {
    val canStart: Boolean get() = cwd != null && connection is ConnectionState.Online
}

class NewSessionViewModel(private val conn: ConnectionController, private val repo: SessionRepository) : ViewModel() {
    private val _state = MutableStateFlow(NewSessionState(connection = conn.state.value))
    val state: StateFlow<NewSessionState> = _state.asStateFlow()
    val commands = CommandTracker(viewModelScope) { repo.commandStatus(it) }

    init {
        viewModelScope.launch {
            conn.state.distinctUntilChangedBy { it::class }.collect { s ->
                _state.update { it.copy(connection = s) }
                if (s is ConnectionState.Online) {
                    if (_state.value.roots !is Loadable.Ready) loadRoots()
                    loadRecent()
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
    fun setAgent(a: String) = _state.update { it.copy(agent = a) }
    fun setPrompt(t: String) = _state.update { it.copy(prompt = t) }
    fun setModel(t: String) = _state.update { it.copy(model = t) }
    fun setPermissionMode(m: String) = _state.update { it.copy(permissionMode = m) }

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
        )
        commands.run(label, onSuccess = { out -> _state.update { it.copy(created = out.createdSession()) } }) { id -> repo.createSession(req, id) }
    }
}

private fun folderName(path: String) = path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').ifBlank { path }

/** Full-screen new-session page (kept for deep use / tests); the app normally uses [NewSessionSheet]. */
@Composable
fun NewSessionScreen(onBack: () -> Unit, onPickFolder: () -> Unit, onCreated: (String, String?) -> Unit) {
    val container = appContainer()
    val vm = containerViewModel { NewSessionViewModel(it.connection, it.sessions) }
    val state by vm.state.collectAsStateWithLifecycle()
    val prompt by vm.commands.prompt.collectAsStateWithLifecycle()
    val busy by vm.commands.busy.collectAsStateWithLifecycle()
    val startLabel = stringResource(R.string.new_session_start)
    state.created?.let { created ->
        androidx.compose.runtime.LaunchedEffect(created.id) { onCreated(created.id, created.title) }
    }
    Page(
        title = stringResource(R.string.new_session_title),
        onBack = onBack,
        modifier = Modifier.imePadding(),
    ) {
        ConnectionBanner(state.connection, { container.connection.retryNow() })
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
        )
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
                    state.model.ifBlank { null } ?: stringResource(R.string.new_session_model_default),
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
            TextInput(
                value = state.model,
                onValueChange = onModel,
                label = stringResource(R.string.new_session_model_hint),
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
        Text(stringResource(R.string.new_session_note), style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurfaceVariantSummary)
    }
}

/** Folder browser on its own page; "Use this folder" returns to the new-session sheet. */
@Composable
fun PickFolderScreen(onBack: () -> Unit) {
    val container = appContainer()
    val vm = containerViewModel(key = "new-session-sheet") { NewSessionViewModel(it.connection, it.sessions) }
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
    }
    val listing = (state.listing as? Loadable.Ready)?.value
    androidx.activity.compose.BackHandler(enabled = listing?.parent != null) { listing?.parent?.let(vm::browse) }
    Page(title = stringResource(R.string.new_session_cwd), onBack = done, large = false) {
        Column(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = pageListPadding()) {
                when (val l = state.listing) {
                    null -> {
                        val roots = (state.roots as? Loadable.Ready)?.value.orEmpty()
                        item {
                            GroupCard {
                                roots.forEachIndexed { i, r ->
                                    BasicComponent(title = r.name.ifBlank { r.path }, summary = r.path, startAction = { RowIcon(AppIcons.Files) }, onClick = { vm.browse(r.path) }, cardListPosition = io.github.suqi8.coui.kmp.basic.cardListPositionOf(i, roots.size))
                                }
                            }
                        }
                    }
                    Loadable.Loading -> item { Text(stringResource(R.string.state_loading), color = dev.gutter, modifier = Modifier.padding(16.dp)) }
                    is Loadable.Failed -> item { Text(l.error.title() + (l.error.detail?.let { "\n$it" } ?: ""), style = COUITheme.textStyles.footnote1, color = dev.danger, modifier = Modifier.padding(16.dp)) }
                    is Loadable.Ready -> {
                        val dirs = l.value.entries.filter { it.isDir }
                        item { Text(l.value.path, style = DevType.codeSmall, color = COUITheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
                        item {
                            GroupCard {
                                val rows = buildList<Triple<String, String, Boolean>> {
                                    l.value.parent?.let { add(Triple("..", it, false)) }
                                    dirs.take(300).forEach { add(Triple(it.name, it.path, it.sensitive)) }
                                }
                                if (rows.isEmpty()) BasicComponent(title = stringResource(R.string.new_session_no_subdirs))
                                rows.forEachIndexed { i, (name, path, sensitive) ->
                                    BasicComponent(
                                        title = name,
                                        startAction = { RowIcon(AppIcons.Files) },
                                        endActions = if (sensitive) ({ StatusPill(stringResource(R.string.new_session_sensitive), dev.warn) }) else null,
                                        onClick = { vm.browse(path) },
                                        cardListPosition = io.github.suqi8.coui.kmp.basic.cardListPositionOf(i, rows.size),
                                    )
                                }
                            }
                        }
                        if (l.value.truncated || l.value.blocked > 0) {
                            item { Text(stringResource(R.string.new_session_listing_note, l.value.blocked), style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(16.dp)) }
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
                Text(stringResource(R.string.new_session_use_folder), color = COUITheme.colorScheme.onPrimary)
            }
        }
    }
}

/** "New SwitchTether-managed session" as a coui bottom sheet (opened from the capsule "+"). */
@Composable
fun NewSessionSheet(show: Boolean, onDismiss: () -> Unit, onPickFolder: () -> Unit, onCreated: (String, String?) -> Unit) {
    val vm = containerViewModel(key = "new-session-sheet") { NewSessionViewModel(it.connection, it.sessions) }
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
            Column(Modifier.pageScroll()) {
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
                )
            }
        }
    }
    CommandPromptDialog(prompt, vm.commands::checkStatus, vm.commands::retry, vm.commands::dismiss)
}
