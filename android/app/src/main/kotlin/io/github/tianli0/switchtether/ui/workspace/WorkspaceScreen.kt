package io.github.tianli0.switchtether.ui.workspace

import io.github.tianli0.switchtether.ui.common.MenuEntry
import io.github.tianli0.switchtether.ui.common.AppMenu
import io.github.tianli0.switchtether.ui.icons.AppIcons

import io.github.tianli0.switchtether.ui.nav.Route
import io.github.tianli0.switchtether.ui.common.pageScroll
import io.github.tianli0.switchtether.ui.common.rememberPageScrollState
import io.github.tianli0.switchtether.ui.common.rememberPageListState
import io.github.suqi8.coui.kmp.basic.CardListPosition



import io.github.suqi8.coui.kmp.window.WindowListPopup
import io.github.suqi8.coui.kmp.basic.ListPopupColumn
import io.github.suqi8.coui.kmp.basic.DropdownImpl
import io.github.tianli0.switchtether.ui.home.RowIcon
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.basic.ChipTabData
import io.github.suqi8.coui.kmp.basic.ChipTabGroup
import io.github.suqi8.coui.kmp.basic.EmptyState
import io.github.suqi8.coui.kmp.basic.SmallTitle
import io.github.suqi8.coui.kmp.basic.TabRowWithContour
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.cardListPositionOf
import io.github.suqi8.coui.kmp.layout.DialogButtonBar
import io.github.suqi8.coui.kmp.layout.DialogButtonBarAction
import io.github.suqi8.coui.kmp.preference.ArrowPreference
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.window.WindowDialog
import io.github.tianli0.switchtether.R
import io.github.tianli0.switchtether.domain.SessionQuery
import io.github.tianli0.switchtether.ui.appContainer
import io.github.tianli0.switchtether.ui.code.CodeBlockView
import io.github.tianli0.switchtether.ui.code.SyntaxHighlighter
import io.github.tianli0.switchtether.ui.common.ConnectionBanner
import io.github.tianli0.switchtether.ui.common.Formatters
import io.github.tianli0.switchtether.ui.common.GroupCard
import io.github.tianli0.switchtether.ui.common.Loadable
import io.github.tianli0.switchtether.ui.common.LoadableContent
import io.github.tianli0.switchtether.ui.common.LocalBottomBarSpace
import io.github.tianli0.switchtether.ui.common.Page
import io.github.tianli0.switchtether.ui.common.TextInput
import io.github.tianli0.switchtether.ui.common.ToolbarIcon
import io.github.tianli0.switchtether.ui.common.pageListPadding
import io.github.tianli0.switchtether.ui.components.StatusPill
import io.github.tianli0.switchtether.ui.containerViewModel
import io.github.tianli0.switchtether.ui.diff.DiffView
import io.github.tianli0.switchtether.ui.theme.DevType
import io.github.tianli0.switchtether.ui.theme.LocalDevColors

@Composable
private fun terminalViewModel(): TerminalViewModel =
    containerViewModel { c -> TerminalViewModel(
            c.connection,
            c.stepUpGate,
            { c.pairingStoreStepUpEnabled() },
            { id -> c.noteLocalAction(id, io.github.tianli0.switchtether.remote.notify.LocalAction.KILL_TERMINAL) },
            initialFontSp = c.terminalPrefs.getInt("font_sp", 12),
            saveFontSp = { sp -> c.terminalPrefs.edit().putInt("font_sp", sp).apply() },
            initialColumns = c.terminalPrefs.getInt("columns", 0),
            saveColumns = { n -> c.terminalPrefs.edit().putInt("columns", n).apply() },
        )
    }

@Composable
private fun filesViewModel(): FilesViewModel {
    val context = LocalContext.current
    return containerViewModel { c ->
        FilesViewModel(c.connection, c.stepUpGate, { context.applicationContext.contentResolver }, c.fileStore) {
            c.sessions.list(SessionQuery()).items.firstOrNull { it.isLive && it.cwd != null }?.cwd
        }
    }
}

/**
 * Workspace hub: what exists on the computer (terminals, folders, repositories). Every item opens
 * its own page ([TerminalPage], [FilesPage], [GitPage]); nothing here runs a terminal or edits a file.
 */
@Composable
fun WorkspaceScreen(onOpen: (Route) -> Unit) {
    val container = appContainer()
    val conn by container.connection.state.collectAsStateWithLifecycle()
    val termVm = terminalViewModel()
    val filesVm = filesViewModel()
    val term by termVm.state.collectAsStateWithLifecycle()
    val files by filesVm.state.collectAsStateWithLifecycle()
    val openTerminal by container.openTerminalRequest.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(openTerminal) {
        val id = openTerminal ?: return@LaunchedEffect
        container.openTerminalRequest.value = null
        termVm.refresh()
        onOpen(Route.Terminal(id)) // attaching goes through the usual terminal step-up
    }
    androidx.compose.runtime.LaunchedEffect(term.online) { if (term.online) termVm.refresh() }
    var showShells by remember { mutableStateOf(false) }
    val sizeEstimate = rememberTerminalSizeEstimate(term.fontSp, term.columns)
    Page(
        title = stringResource(R.string.tab_workspace),
        actions = {
            ToolbarIcon(AppIcons.Refresh, stringResource(R.string.action_refresh), {
                termVm.refresh()
                filesVm.loadRoots()
            })
        },
        immersive = true,
    ) {
        LazyColumn(state = rememberPageListState(), modifier = Modifier.fillMaxSize().pageScroll(), contentPadding = pageListPadding()) {
            item { ConnectionBanner(conn, { container.connection.retryNow() }) }
            item { SmallTitle(stringResource(R.string.ws_terminals)) }
            item {
                GroupCard {
                    term.terminals.forEachIndexed { i, t ->
                        ArrowPreference(
                            title = shortShellName(t.title.ifBlank { t.shell.ifBlank { t.id.take(8) } }),
                            summary = listOfNotNull(t.shell.takeIf { it.isNotBlank() && it != t.title }, t.cwd.takeIf { it.isNotBlank() }?.substringAfterLast('/')?.substringAfterLast('\\')).joinToString(" · ").ifEmpty { null },
                            startAction = { RowIcon(AppIcons.Terminal) },
                            endActions = { if (t.exited) StatusPill(stringResource(R.string.ws_exited), LocalDevColors.current.neutral) },
                            onClick = { onOpen(Route.Terminal(t.id)) },
                            cardListPosition = cardListPositionOf(i, term.terminals.size + 1),
                        )
                    }
                    Box {
                        BasicComponent(
                            title = stringResource(R.string.term_new),
                            summary = if (term.terminals.isEmpty()) stringResource(R.string.workspace_security) else null,
                            startAction = { RowIcon(AppIcons.Add) },
                            enabled = term.online && !term.busy,
                            onClick = { showShells = true },
                            cardListPosition = cardListPositionOf(term.terminals.size, term.terminals.size + 1),
                        )
                        val shells = term.shells.ifEmpty { listOf(io.github.tianli0.switchtether.remote.protocol.ShellInfo("", stringResource(R.string.term_default_shell), "")) }
                        AppMenu(
                            show = showShells,
                            entries = shells.map { sh ->
                                MenuEntry(sh.name.ifBlank { sh.id }) {
                                    termVm.create(sh.id.ifBlank { null }, sizeEstimate)
                                    onOpen(Route.Terminal(null))
                                }
                            },
                            onDismiss = { showShells = false },
                        )
                    }
                }
            }
            item { SmallTitle(stringResource(R.string.ws_files)) }
            item {
                val roots = (files.roots as? Loadable.Ready)?.value.orEmpty()
                GroupCard {
                    if (roots.isEmpty()) {
                        ArrowPreference(
                            title = stringResource(R.string.ws_files_open),
                            startAction = { RowIcon(AppIcons.Files) },
                            onClick = { onOpen(Route.Files) },
                            cardListPosition = CardListPosition.Full,
                        )
                    }
                    roots.forEachIndexed { i, r ->
                        ArrowPreference(
                            // Desktop roots named after their full path read as the path twice: show the folder name.
                            title = r.name.takeIf { it.isNotBlank() && it != r.path }
                                ?: r.path.trimEnd('\\', '/').substringAfterLast('\\').substringAfterLast('/').ifBlank { r.path },
                            summary = r.path,
                            startAction = { RowIcon(AppIcons.Files) },
                            onClick = {
                                filesVm.browse(r.path)
                                onOpen(Route.Files)
                            },
                            cardListPosition = cardListPositionOf(i, roots.size),
                        )
                    }
                }
            }
            item { SmallTitle(stringResource(R.string.forward_title)) }
            item {
                val forwards by container.portForwards.active.collectAsStateWithLifecycle()
                GroupCard {
                    ArrowPreference(
                        title = if (forwards.isEmpty()) stringResource(R.string.forward_title)
                        else forwards.joinToString(" · ") { "localhost:${it.localPort}" },
                        summary = if (forwards.isEmpty()) stringResource(R.string.forward_ws_summary)
                        else androidx.compose.ui.res.pluralStringResource(R.plurals.forward_ws_active, forwards.size, forwards.size),
                        startAction = { RowIcon(AppIcons.PortForward) },
                        onClick = { onOpen(Route.PortForward) },
                        cardListPosition = CardListPosition.Full,
                    )
                }
            }
            item { SmallTitle(stringResource(R.string.ws_git)) }
            item {
                GroupCard {
                    ArrowPreference(
                        title = files.repo?.substringAfterLast('\\')?.substringAfterLast('/')?.ifBlank { null } ?: stringResource(R.string.ws_git_open),
                        summary = files.repo ?: stringResource(R.string.ws_git_hint),
                        startAction = { RowIcon(AppIcons.Git) },
                        onClick = { onOpen(Route.Git) },
                        cardListPosition = CardListPosition.Full,
                    )
                }
            }
        }
    }
}

/** One terminal (or the terminal just created) on its own page. */
@Composable
fun TerminalPage(initialId: String?, onBack: () -> Unit) {
    val vm = terminalViewModel()
    val st by vm.state.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(initialId) {
        vm.refresh()
        initialId?.let(vm::select)
    }
    val current = st.terminals.firstOrNull { it.id == st.selectedId }
    val controller = remember { TerminalController() }
    Page(
        title = current?.let { t -> shortShellName(t.title.ifBlank { t.shell }) } ?: stringResource(R.string.ws_terminals),
        subtitle = current?.cwd.orEmpty(),
        onBack = onBack,
        large = false,
        actions = { TerminalActions(st, vm, controller) },
        modifier = Modifier.imePadding(),
    ) {
        TerminalPane(st, vm, controller)
    }
}

@Composable
fun FilesPage(onBack: () -> Unit, onOpenDownloads: () -> Unit = {}) {
    val vm = filesViewModel()
    val st by vm.state.collectAsStateWithLifecycle()
    val back: () -> Unit = {
        when {
            st.viewer != null -> vm.closeViewer()
            st.file != null -> vm.requestCloseFile()
            else -> onBack()
        }
    }
    Page(title = stringResource(R.string.ws_files), onBack = back, large = false, modifier = Modifier.imePadding()) {
        FilesPane(st, vm, onOpenDownloads)
    }
}

@Composable
fun GitPage(onBack: () -> Unit) {
    val vm = filesViewModel()
    val st by vm.state.collectAsStateWithLifecycle()
    Page(title = stringResource(R.string.ws_git), onBack = onBack, large = false) {
        GitPane(st, vm)
    }
}

@Composable
fun GitPane(state: FilesUiState, vm: FilesViewModel) {
    if (state.git == null && state.repo != null) androidx.compose.runtime.LaunchedEffect(state.repo) { vm.loadGit() }
    val detail = state.gitDiff ?: state.gitShow
    if (detail != null) {
        BackHandler { vm.closeGitDetail() }
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                ToolbarIcon(AppIcons.Back, stringResource(R.string.action_back), vm::closeGitDetail)
                Text(
                    detail.first,
                    style = DevType.code,
                    color = COUITheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            LoadableContent(detail.second, onRetry = vm::retryGitDetail) { text ->
                Column(Modifier.fillMaxSize().verticalScroll(rememberPageScrollState()).padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 24.dp + LocalBottomBarSpace.current)) {
                    if (state.gitDiff != null) {
                        if (text.isBlank()) Text(stringResource(R.string.git_no_changes), color = COUITheme.colorScheme.onSurfaceVariantSummary) else DiffView(text)
                    } else {
                        CodeBlockView(text, SyntaxHighlighter.languageForPath(detail.first.substringAfter(':')))
                    }
                }
            }
        }
        return
    }
    val currentListingPath = (state.listing as? Loadable.Ready)?.value?.path
    val rootPaths = (state.roots as? Loadable.Ready)?.value.orEmpty()
    androidx.compose.runtime.LaunchedEffect(state.repo, currentListingPath, rootPaths) {
        if (state.repo.isNullOrBlank()) {
            val fallback = currentListingPath?.takeIf { it.isNotBlank() } ?: rootPaths.firstOrNull()?.path
            if (!fallback.isNullOrBlank()) vm.setRepo(fallback)
        }
    }
    val repoChoices = remember(state.repo, currentListingPath, rootPaths) {
        buildList {
            state.repo?.takeIf { it.isNotBlank() }?.let { add(it.substringAfterLast('\\').substringAfterLast('/').ifBlank { it } to it) }
            currentListingPath?.takeIf { it.isNotBlank() && it != state.repo }?.let { add(it.substringAfterLast('\\').substringAfterLast('/').ifBlank { it } to it) }
            rootPaths.forEach { r ->
                if (none { it.second == r.path }) {
                    add(r.name.ifBlank { r.path.substringAfterLast('\\').substringAfterLast('/').ifBlank { r.path } } to r.path)
                }
            }
        }
    }
    var showRepoDialog by rememberSaveable { mutableStateOf(false) }
    if (showRepoDialog) {
        var repoDraft by remember(state.repo) { mutableStateOf(state.repo.orEmpty()) }
        WindowDialog(
            show = true,
            title = stringResource(R.string.git_repo),
            onDismissRequest = { showRepoDialog = false },
        ) {
            Column(Modifier.fillMaxWidth()) {
                Box(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                    TextInput(repoDraft, { repoDraft = it }, stringResource(R.string.git_repo))
                }
                DialogButtonBar(
                    negative = DialogButtonBarAction(text = stringResource(R.string.action_cancel), onClick = { showRepoDialog = false }),
                    positive = DialogButtonBarAction(
                        text = stringResource(R.string.action_ok),
                        enabled = repoDraft.trim().isNotEmpty(),
                        onClick = {
                            vm.setRepo(repoDraft.trim())
                            showRepoDialog = false
                        },
                    ),
                )
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                if (repoChoices.isNotEmpty()) {
                    ChipTabGroup(
                        tabs = repoChoices.map { ChipTabData(it.first) },
                        selectedIndex = repoChoices.indexOfFirst { it.second == state.repo }.coerceAtLeast(0),
                        onSelectedChange = { vm.setRepo(repoChoices[it].second) },
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }
            ToolbarIcon(AppIcons.Edit, stringResource(R.string.git_repo), { showRepoDialog = true })
            ToolbarIcon(AppIcons.Refresh, stringResource(R.string.action_refresh), { state.repo?.let(vm::setRepo) })
        }
        val git = state.git
        if (git == null) {
            GroupCard(Modifier.padding(top = 8.dp)) {
                EmptyState(
                    title = stringResource(R.string.git_no_repo),
                    subtitle = stringResource(R.string.git_no_repo_hint),
                    actionText = stringResource(R.string.git_choose_repo),
                    onActionClick = { showRepoDialog = true },
                    modifier = Modifier.padding(24.dp),
                )
            }
            return
        }
        LoadableContent(git, onRetry = vm::loadGit) { st ->
            val dev = LocalDevColors.current
            val stagedEntries = remember(st.entries) { st.entries.filter { it.index.isNotBlank() && it.index != "?" } }
            val worktreeEntries = remember(st.entries) { st.entries.filter { it.untracked || it.worktree.isNotBlank() || it.conflicted || it.index.isBlank() } }
            val openFileRel = (state.file as? Loadable.Ready)?.value?.path?.let { fp ->
                val base = state.repo?.trimEnd('/', '\\').orEmpty()
                if (base.isNotEmpty() && fp.startsWith(base)) fp.removePrefix(base).trimStart('/', '\\') else null
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = pageListPadding(top = 4.dp)) {
                item {
                    GroupCard {
                        ArrowPreference(
                            title = st.branch?.let { "⎇ $it" } ?: "HEAD",
                            summary = listOfNotNull(
                                state.repo,
                                st.upstream,
                                if (st.ahead > 0) "↑${st.ahead}" else null,
                                if (st.behind > 0) "↓${st.behind}" else null,
                            ).joinToString(" · ").ifEmpty { null },
                            onClick = { showRepoDialog = true },
                            cardListPosition = cardListPositionOf(0, 1),
                        )
                    }
                }
                item { SmallTitle(stringResource(R.string.git_changes, st.entries.size)) }
                item {
                    GroupCard {
                        if (st.entries.isEmpty()) {
                            BasicComponent(title = stringResource(R.string.git_clean))
                        } else {
                            val ordered = stagedEntries.map { it to true } + worktreeEntries.map { it to false }
                            ordered.forEachIndexed { i, (e, staged) ->
                                val badge = when {
                                    e.conflicted -> "!" to dev.danger
                                    e.untracked -> "??" to dev.warn
                                    staged -> "${e.index.trim()} · staged" to dev.ok
                                    else -> e.worktree.trim().ifEmpty { e.index.trim() } to dev.info
                                }
                                BasicComponent(
                                    title = e.path,
                                    summary = e.origPath?.let { "from $it" },
                                    endActions = { StatusPill(badge.first, badge.second) },
                                    onClick = { vm.gitDiff(e.path, staged = staged) },
                                    cardListPosition = cardListPositionOf(i, ordered.size),
                                )
                            }
                        }
                    }
                }
                (state.gitLog as? Loadable.Ready)?.value?.let { commits ->
                    item { SmallTitle(stringResource(R.string.git_log)) }
                    item {
                        GroupCard {
                            val targetPath = openFileRel ?: st.entries.firstOrNull()?.path
                            val showFileLabel = stringResource(R.string.git_show_file_at)
                            commits.take(50).forEachIndexed { i, c ->
                                BasicComponent(
                                    title = c.subject,
                                    // There is no per-commit diff endpoint: say which file a tap shows at that commit.
                                    summary = "${c.hash.take(8)} · ${c.author} · ${io.github.tianli0.switchtether.ui.common.relativeTime(c.timeMs)}" +
                                        (targetPath?.let { "\n" + showFileLabel.format(it.substringAfterLast('/').substringAfterLast('\\')) } ?: ""),
                                    cardListPosition = cardListPositionOf(i, minOf(50, commits.size)),
                                    onClick = if (targetPath != null) ({ vm.gitShow(c.hash, targetPath) }) else null,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
