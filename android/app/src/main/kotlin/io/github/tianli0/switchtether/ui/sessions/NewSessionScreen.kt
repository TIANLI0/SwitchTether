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
import io.github.suqi8.coui.kmp.basic.Button
import io.github.suqi8.coui.kmp.basic.ButtonDefaults
import io.github.suqi8.coui.kmp.basic.TabRow
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

data class NewSessionState(
    val agent: String = MANAGED_AGENTS.first(),
    val cwd: String? = null,
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
                if (s is ConnectionState.Online && _state.value.roots !is Loadable.Ready) loadRoots()
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

@Composable
fun NewSessionScreen(onBack: () -> Unit, onCreated: (String, String?) -> Unit) {
    val container = appContainer()
    val vm = containerViewModel { NewSessionViewModel(it.connection, it.sessions) }
    val state by vm.state.collectAsStateWithLifecycle()
    val prompt by vm.commands.prompt.collectAsStateWithLifecycle()
    val busy by vm.commands.busy.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }
    val startLabel = stringResource(R.string.new_session_start)
    state.created?.let { created ->
        androidx.compose.runtime.LaunchedEffect(created.id) { onCreated(created.id, created.title) }
    }
    Page(
        title = stringResource(R.string.new_session_title),
        subtitle = stringResource(R.string.new_session_subtitle),
        onBack = onBack,
        modifier = Modifier.imePadding(),
    ) {
        ConnectionBanner(state.connection, { container.connection.retryNow() })
        NewSessionContent(
            state = state,
            busy = busy != null,
            onAgent = vm::setAgent,
            onBrowse = vm::browse,
            onSelect = vm::select,
            onReloadRoots = vm::loadRoots,
            onPrompt = vm::setPrompt,
            onModel = vm::setModel,
            onPermissionMode = vm::setPermissionMode,
            onStart = { confirm = true },
        )
    }
    ConfirmDialog(
        show = confirm,
        title = stringResource(R.string.new_session_confirm_title),
        message = stringResource(R.string.new_session_confirm_body, state.agent, state.cwd.orEmpty()),
        confirmText = startLabel,
        onConfirm = {
            confirm = false
            vm.start(startLabel)
        },
        onDismiss = { confirm = false },
    )
    CommandPromptDialog(prompt, vm.commands::checkStatus, vm.commands::retry, vm.commands::dismiss)
}

@Composable
fun NewSessionContent(
    state: NewSessionState,
    busy: Boolean,
    onAgent: (String) -> Unit,
    onBrowse: (String) -> Unit,
    onSelect: (String) -> Unit,
    onReloadRoots: () -> Unit,
    onPrompt: (String) -> Unit,
    onModel: (String) -> Unit,
    onPermissionMode: (String) -> Unit,
    onStart: () -> Unit,
) {
    val dev = LocalDevColors.current
    LazyColumn(Modifier.fillMaxSize().pageScroll(), contentPadding = pageListPadding()) {
        item {
            Banner(stringResource(R.string.new_session_explainer), dev.info, Modifier.padding(horizontal = 12.dp, vertical = 6.dp).clip(RoundedCornerShape(10.dp)))
        }
        item { SectionHeader(stringResource(R.string.new_session_agent)) }
        item {
            TabRow(
                tabs = MANAGED_AGENTS.map { io.github.tianli0.switchtether.ui.common.Formatters.agentLabel(it) },
                selectedTabIndex = MANAGED_AGENTS.indexOf(state.agent).coerceAtLeast(0),
                onTabSelected = { onAgent(MANAGED_AGENTS[it]) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )
        }
        item { SectionHeader(stringResource(R.string.new_session_cwd)) }
        item {
            Text(
                state.cwd ?: stringResource(R.string.new_session_cwd_none),
                style = DevType.code,
                color = if (state.cwd != null) COUITheme.colorScheme.onSurface else dev.warn,
                modifier = Modifier.padding(horizontal = 16.dp).testTag("selected-cwd"),
            )
        }
        item {
            when (val r = state.roots) {
                Loadable.Loading -> Text(stringResource(R.string.state_loading), style = DevType.label, color = dev.gutter, modifier = Modifier.padding(16.dp))
                is Loadable.Failed -> Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(r.error.title(), style = COUITheme.textStyles.footnote1, color = dev.danger, modifier = Modifier.weight(1f))
                    TextButton(text = stringResource(R.string.action_retry), onClick = onReloadRoots)
                }
                is Loadable.Ready -> LazyRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(r.value, key = { it.path }) { root -> FilterChip(root.name.ifBlank { root.path }, false, { onBrowse(root.path) }) }
                }
            }
        }
        state.listing?.let { listing ->
            item { DirectoryBrowser(listing, busy, onBrowse, onSelect) }
        }
        item { SectionHeader(stringResource(R.string.new_session_prompt)) }
        item {
            TextInput(
                value = state.prompt,
                onValueChange = onPrompt,
                label = stringResource(R.string.new_session_prompt_hint),
                singleLine = false,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )
        }
        item { SectionHeader(stringResource(R.string.new_session_model)) }
        item {
            TextInput(
                value = state.model,
                onValueChange = onModel,
                label = stringResource(R.string.new_session_model_hint),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )
        }
        if (state.agent == "claude_code") {
            item { SectionHeader(stringResource(R.string.new_session_permission)) }
            item {
                PageCard {
                    CLAUDE_PERMISSION_MODES.forEach { m ->
                        RadioButtonPreference(
                            title = m,
                            summary = when (m) {
                                "default" -> stringResource(R.string.perm_default)
                                "acceptEdits" -> stringResource(R.string.perm_accept_edits)
                                else -> stringResource(R.string.perm_plan)
                            },
                            selected = state.permissionMode == m,
                            onClick = { onPermissionMode(m) },
                        )
                    }
                }
            }
        }
        item {
            Button(
                onClick = onStart,
                enabled = state.canStart && !busy,
                colors = ButtonDefaults.buttonColorsPrimary(),
                modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("start-session"),
            ) {
                Text(if (busy) stringResource(R.string.state_loading) else stringResource(R.string.new_session_start), color = COUITheme.colorScheme.onPrimary)
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun DirectoryBrowser(listing: Loadable<FsListing>, busy: Boolean, onBrowse: (String) -> Unit, onSelect: (String) -> Unit) {
    val dev = LocalDevColors.current
    PageCard {
        when (listing) {
            Loadable.Loading -> Text(stringResource(R.string.state_loading), style = DevType.label, color = dev.gutter, modifier = Modifier.padding(16.dp))
            is Loadable.Failed -> Text(listing.error.title() + (listing.error.detail?.let { "\n$it" } ?: ""), style = COUITheme.textStyles.footnote1, color = dev.danger, modifier = Modifier.padding(16.dp))
            is Loadable.Ready -> Column {
                val l = listing.value
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(l.path, style = DevType.codeSmall, color = COUITheme.colorScheme.onSurface, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    TextButton(text = stringResource(R.string.new_session_use_folder), onClick = { onSelect(l.path) }, enabled = !busy)
                }
                l.parent?.let { parent -> DirRow("..", sensitive = false) { onBrowse(parent) } }
                val dirs = l.entries.filter { it.isDir }
                dirs.take(200).forEach { e -> DirRow(e.name, e.sensitive) { onBrowse(e.path) } }
                if (dirs.isEmpty()) Text(stringResource(R.string.new_session_no_subdirs), style = DevType.label, color = dev.gutter, modifier = Modifier.padding(16.dp))
                if (l.truncated || l.blocked > 0) {
                    Text(stringResource(R.string.new_session_listing_note, l.blocked), style = DevType.label, color = dev.gutter, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun DirRow(name: String, sensitive: Boolean, onClick: () -> Unit) {
    val dev = LocalDevColors.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("▸ ", style = DevType.code, color = dev.gutter)
        Text(name, style = DevType.code, color = COUITheme.colorScheme.onSurface, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (sensitive) {
            Spacer(Modifier.size(6.dp))
            StatusPill(stringResource(R.string.new_session_sensitive), dev.warn)
        }
    }
}


/** "New SwitchTether-managed session" as a coui bottom sheet (opened from the capsule "+"). */
@Composable
fun NewSessionSheet(show: Boolean, onDismiss: () -> Unit, onCreated: (String, String?) -> Unit) {
    val vm = containerViewModel(key = "new-session-sheet") { NewSessionViewModel(it.connection, it.sessions) }
    val state by vm.state.collectAsStateWithLifecycle()
    val prompt by vm.commands.prompt.collectAsStateWithLifecycle()
    val busy by vm.commands.busy.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }
    val startLabel = stringResource(R.string.new_session_start)
    state.created?.let { created ->
        androidx.compose.runtime.LaunchedEffect(created.id) {
            vm.consumeCreated()
            onDismiss()
            onCreated(created.id, created.title)
        }
    }
    io.github.suqi8.coui.kmp.window.WindowBottomSheet(
        show = show,
        title = stringResource(R.string.new_session_subtitle),
        onDismissRequest = onDismiss,
    ) {
        Box(Modifier.fillMaxWidth().heightIn(max = 620.dp)) {
            NewSessionContent(
                state = state,
                busy = busy != null,
                onAgent = vm::setAgent,
                onBrowse = vm::browse,
                onSelect = vm::select,
                onReloadRoots = vm::loadRoots,
                onPrompt = vm::setPrompt,
                onModel = vm::setModel,
                onPermissionMode = vm::setPermissionMode,
                onStart = { confirm = true },
            )
        }
    }
    ConfirmDialog(
        show = confirm,
        title = stringResource(R.string.new_session_confirm_title),
        message = stringResource(R.string.new_session_confirm_body, state.agent, state.cwd.orEmpty()),
        confirmText = startLabel,
        onConfirm = {
            confirm = false
            vm.start(startLabel)
        },
        onDismiss = { confirm = false },
    )
    CommandPromptDialog(prompt, vm.commands::checkStatus, vm.commands::retry, vm.commands::dismiss)
}
