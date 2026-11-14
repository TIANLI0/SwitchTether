package io.github.tianli0.switchtether.ui.workspace

import io.github.tianli0.switchtether.ui.icons.AppIcons

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.basic.SmallTitle
import io.github.suqi8.coui.kmp.basic.TabRowWithContour
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.cardListPositionOf
import io.github.suqi8.coui.kmp.theme.COUITheme
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
import io.github.tianli0.switchtether.ui.common.Page
import io.github.tianli0.switchtether.ui.common.TextInput
import io.github.tianli0.switchtether.ui.common.ToolbarIcon
import io.github.tianli0.switchtether.ui.common.pageListPadding
import io.github.tianli0.switchtether.ui.containerViewModel
import io.github.tianli0.switchtether.ui.diff.DiffView
import io.github.tianli0.switchtether.ui.theme.DevType

/** Workspace: Terminals | Files | Git (Phase B). */
@Composable
fun WorkspaceScreen() {
    val container = appContainer()
    val context = LocalContext.current
    val conn by container.connection.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val termVm = containerViewModel { c -> TerminalViewModel(c.connection, c.stepUpGate, { c.pairingStoreStepUpEnabled() }, { id -> c.noteLocalAction(id, io.github.tianli0.switchtether.remote.notify.LocalAction.KILL_TERMINAL) }) }
    val openTerminal by container.openTerminalRequest.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(openTerminal) {
        val id = openTerminal ?: return@LaunchedEffect
        container.openTerminalRequest.value = null
        tab = 0
        termVm.refresh()
        termVm.select(id) // attaching goes through the usual terminal step-up
    }
    val filesVm = containerViewModel { c ->
        FilesViewModel(c.connection, c.stepUpGate, { context.applicationContext.contentResolver }) {
            c.sessions.list(SessionQuery()).items.firstOrNull { it.isLive && it.cwd != null }?.cwd
        }
    }
    Page(title = stringResource(R.string.tab_workspace), large = false, modifier = Modifier.imePadding()) {
        ConnectionBanner(conn, { container.connection.retryNow() })
        TabRowWithContour(
            tabs = listOf(stringResource(R.string.ws_terminals), stringResource(R.string.ws_files), stringResource(R.string.ws_git)),
            selectedTabIndex = tab,
            onTabSelected = { tab = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        )
        when (tab) {
            0 -> {
                val st by termVm.state.collectAsStateWithLifecycle()
                TerminalPane(st, termVm)
            }
            1 -> {
                val st by filesVm.state.collectAsStateWithLifecycle()
                FilesPane(st, filesVm)
            }
            else -> {
                val st by filesVm.state.collectAsStateWithLifecycle()
                GitPane(st, filesVm)
            }
        }
    }
}

@Composable
fun GitPane(state: FilesUiState, vm: FilesViewModel) {
    if (state.git == null && state.repo != null) androidx.compose.runtime.LaunchedEffect(state.repo) { vm.loadGit() }
    val detail = state.gitDiff ?: state.gitShow
    if (detail != null) {
        BackHandler { vm.closeGitDetail() }
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ToolbarIcon(AppIcons.Back, stringResource(R.string.action_back), vm::closeGitDetail)
                Text(detail.first, style = DevType.code, color = COUITheme.colorScheme.onSurface, maxLines = 1)
            }
            LoadableContent(detail.second, onRetry = vm::closeGitDetail) { text ->
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp)) {
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
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            var repo by rememberSaveable(state.repo) { androidx.compose.runtime.mutableStateOf(state.repo.orEmpty()) }
            TextInput(repo, { repo = it }, stringResource(R.string.git_repo), modifier = Modifier.weight(1f),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Go),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = { vm.setRepo(repo.trim()) }))
            ToolbarIcon(AppIcons.Refresh, stringResource(R.string.action_refresh), { vm.setRepo(repo.trim()) })
        }
        val git = state.git ?: return
        LoadableContent(git, onRetry = vm::loadGit) { st ->
            LazyColumn(Modifier.fillMaxSize(), contentPadding = pageListPadding(top = 4.dp)) {
                item {
                    Text(
                        listOfNotNull(st.branch?.let { "⎇ $it" }, st.upstream, if (st.ahead > 0) "↑${st.ahead}" else null, if (st.behind > 0) "↓${st.behind}" else null).joinToString(" · "),
                        style = DevType.code, color = COUITheme.colorScheme.onSurface, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                    )
                }
                item { SmallTitle(stringResource(R.string.git_changes, st.entries.size)) }
                item {
                    GroupCard {
                        if (st.entries.isEmpty()) BasicComponent(title = stringResource(R.string.git_clean))
                        st.entries.forEachIndexed { i, e ->
                            BasicComponent(
                                title = e.path,
                                summary = (if (e.untracked) "??" else e.index + e.worktree) + (if (e.conflicted) " · conflict" else "") + (e.origPath?.let { " · from $it" } ?: ""),
                                onClick = { vm.gitDiff(e.path, staged = e.index.isNotBlank() && e.worktree.isBlank()) },
                                cardListPosition = cardListPositionOf(i, st.entries.size),
                            )
                        }
                    }
                }
                (state.gitLog as? Loadable.Ready)?.value?.let { commits ->
                    item { SmallTitle(stringResource(R.string.git_log)) }
                    item {
                        GroupCard {
                            commits.take(50).forEachIndexed { i, c ->
                                BasicComponent(
                                    title = c.subject,
                                    summary = "${c.hash.take(8)} · ${c.author} · ${io.github.tianli0.switchtether.ui.common.relativeTime(c.timeMs)}",
                                    cardListPosition = cardListPositionOf(i, minOf(50, commits.size)),
                                    onClick = { st.entries.firstOrNull()?.let { e -> vm.gitShow(c.hash, e.path) } },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
