package io.github.tianli0.switchtether.ui.workspace

import io.github.tianli0.coui17.patterns.FormDialog
import io.github.tianli0.coui17.patterns.MenuEntry
import io.github.tianli0.coui17.patterns.GlassMenu
import io.github.tianli0.switchtether.ui.icons.AppIcons

import io.github.tianli0.switchtether.ui.nav.Route
import io.github.tianli0.coui17.patterns.pageScroll
import io.github.tianli0.coui17.patterns.rememberPageScrollState
import io.github.tianli0.coui17.patterns.rememberPageListState
import io.github.suqi8.coui.kmp.basic.CardListPosition



import io.github.suqi8.coui.kmp.window.WindowListPopup
import io.github.suqi8.coui.kmp.basic.ListPopupColumn
import io.github.suqi8.coui.kmp.basic.DropdownImpl
import io.github.tianli0.switchtether.ui.home.RowIcon
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.background
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
import io.github.tianli0.coui17.patterns.GroupCard
import io.github.tianli0.switchtether.ui.common.Loadable
import io.github.tianli0.switchtether.ui.common.LoadableContent
import io.github.tianli0.coui17.patterns.LocalBottomBarSpace
import io.github.tianli0.coui17.patterns.Page
import io.github.tianli0.coui17.patterns.TextInput
import io.github.tianli0.coui17.patterns.ToolbarIcon
import io.github.tianli0.coui17.patterns.pageListPadding
import io.github.tianli0.coui17.patterns.StatusPill
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
internal fun filesViewModel(): FilesViewModel {
    val context = LocalContext.current
    return containerViewModel { c ->
        FilesViewModel(c.connection, c.stepUpGate, { context.applicationContext.contentResolver }, c.fileStore, c.transfers) {
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
        // The same small title bar as Home and Sessions: the tabs cross-fade, so a large title here
        // made the header grow and shrink on every switch.
        large = false,
        actions = {
            io.github.tianli0.switchtether.ui.common.RefreshToolbarIcon({
                termVm.refresh()
                filesVm.loadRoots()
            })
        },
        immersive = true,
    ) {
        LazyColumn(state = rememberPageListState(), flingBehavior = io.github.tianli0.coui17.patterns.pageFlingBehavior(), modifier = Modifier.fillMaxSize().pageScroll(), contentPadding = pageListPadding()) {
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
                            summary = when {
                                // Disabled offline: say why instead of a dead row.
                                !term.online && conn !is io.github.tianli0.switchtether.connection.ConnectionState.Unpaired -> stringResource(R.string.offline_needs_computer)
                                term.terminals.isEmpty() -> stringResource(R.string.workspace_security)
                                else -> null
                            },
                            startAction = { RowIcon(AppIcons.Add) },
                            enabled = term.online && !term.busy,
                            onClick = { showShells = true },
                            cardListPosition = cardListPositionOf(term.terminals.size, term.terminals.size + 1),
                        )
                        val shells = term.shells.ifEmpty { listOf(io.github.tianli0.switchtether.remote.protocol.ShellInfo("", stringResource(R.string.term_default_shell), "")) }
                        GlassMenu(
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
            item { SmallTitle(stringResource(R.string.forward_title)) }
            item {
                val forwards by container.portForwards.active.collectAsStateWithLifecycle()
                val dshWeb = rememberDshWebAvailable()
                GroupCard {
                    ArrowPreference(
                        title = if (forwards.isEmpty()) stringResource(R.string.forward_title)
                        else forwards.joinToString(" · ") { "localhost:${it.localPort}" },
                        summary = if (forwards.isEmpty()) stringResource(R.string.forward_ws_summary)
                        else androidx.compose.ui.res.pluralStringResource(R.plurals.forward_ws_active, forwards.size, forwards.size),
                        startAction = { RowIcon(AppIcons.PortForward) },
                        onClick = { onOpen(Route.PortForward) },
                        cardListPosition = if (dshWeb) CardListPosition.Head else CardListPosition.Full,
                    )
                    if (dshWeb) DshWebWorkspaceRow(onOpen = { onOpen(Route.DshWeb) }, position = CardListPosition.Tail)
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
            // The folders come last: their list grows with the projects, the rows above stay put.
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
                            // Folder name for a project root, the phone's words for Home / Desktop / ….
                            title = rootLabel(r),
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
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        // On a phone an open file (or preview) has its own title bar with back; the Files page's
        // "← 文件" bar above it was a second back key and a quarter of the screen of chrome. Side
        // by side (tablet) the folder list keeps the page bar; a conflict has no bar of its own.
        // The folder browser draws its own ColorOS-style header (folder name, "共 N 项", + / search /
        // ⋮) with back, so only a conflict, which has no bar of its own, keeps the page bar.
        val ownHeader = st.conflict == null || (maxWidth >= FILES_SPLIT_MIN_WIDTH && st.file != null)
        if (ownHeader) {
            Box(
                Modifier.fillMaxSize().background(io.github.tianli0.coui17.patterns.pageContainerColor())
                    .statusBarsPadding().navigationBarsPadding().imePadding(),
            ) { FilesPane(st, vm, onOpenDownloads, onBack = onBack) }
        } else {
            Page(title = stringResource(R.string.ws_files), onBack = back, large = false, modifier = Modifier.imePadding()) {
                FilesPane(st, vm, onOpenDownloads)
            }
        }
    }
}
