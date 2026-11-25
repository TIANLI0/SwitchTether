package io.github.tianli0.switchtether.ui.workspace

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.basic.ChipTabData
import io.github.suqi8.coui.kmp.basic.ChipTabGroup
import io.github.suqi8.coui.kmp.basic.EmptyState
import io.github.suqi8.coui.kmp.basic.SmallTitle
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.cardListPositionOf
import io.github.suqi8.coui.kmp.preference.ArrowPreference
import io.github.suqi8.coui.kmp.preference.SwitchPreference
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.tianli0.coui17.patterns.ActionTone
import io.github.tianli0.coui17.patterns.ConfirmDialog
import io.github.tianli0.coui17.patterns.FormDialog
import io.github.tianli0.coui17.patterns.GroupCard
import io.github.tianli0.coui17.patterns.LinkButton
import io.github.tianli0.coui17.patterns.LocalBottomBarSpace
import io.github.tianli0.coui17.patterns.Page
import io.github.tianli0.coui17.patterns.PageDefaults
import io.github.tianli0.coui17.patterns.PanelSheet
import io.github.tianli0.coui17.patterns.SmallButton
import io.github.tianli0.coui17.patterns.StatusPill
import io.github.tianli0.coui17.patterns.TextInput
import io.github.tianli0.coui17.patterns.ToolbarIcon
import io.github.tianli0.coui17.patterns.pageListPadding
import io.github.tianli0.coui17.patterns.rememberPageScrollState
import io.github.tianli0.switchtether.R
import io.github.tianli0.switchtether.remote.protocol.GitOps
import io.github.tianli0.switchtether.remote.protocol.GitStatusEntry
import io.github.tianli0.switchtether.remote.protocol.RepoStatus
import io.github.tianli0.switchtether.remote.session.TurnFiles
import io.github.tianli0.switchtether.ui.code.CodeBlockView
import io.github.tianli0.switchtether.ui.code.SyntaxHighlighter
import io.github.tianli0.switchtether.ui.common.Loadable
import io.github.tianli0.switchtether.ui.common.LoadableContent
import io.github.tianli0.switchtether.ui.diff.DiffView
import io.github.tianli0.switchtether.ui.icons.AppIcons
import io.github.tianli0.switchtether.ui.theme.DevType
import io.github.tianli0.switchtether.ui.theme.LocalDevColors

@Composable
fun GitPage(onBack: () -> Unit) {
    val vm = filesViewModel()
    val st by vm.state.collectAsStateWithLifecycle()
    Page(title = stringResource(R.string.ws_git), onBack = onBack, large = false) {
        GitPane(st, vm)
    }
}

/**
 * "本轮改动" from a session: the Git page for the session's folder with the files its latest turn
 * edited marked, sorted first and (by default) the only ones shown.
 */
@Composable
fun GitReviewPage(cwd: String, turnFiles: List<String>, onBack: () -> Unit) {
    val vm = filesViewModel()
    val st by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(cwd, turnFiles) { vm.openReview(cwd, turnFiles) }
    Page(title = stringResource(R.string.git_review_title), onBack = onBack, large = false) {
        GitPane(st, vm)
    }
}

/** The entries of [st] split for the page, with the latest turn's files first (pure; tested). */
data class GitReviewModel(
    val staged: List<GitStatusEntry>,
    val unstaged: List<GitStatusEntry>,
    /** Repository-relative paths the latest turn edited that are still changed. */
    val turnPaths: Set<String>,
    /** How many of the turn's files lie in this repository at all. */
    val turnInRepo: Int,
) {
    fun isTurn(e: GitStatusEntry): Boolean = isTurnPath(e.path, turnPaths)

    companion object {
        fun of(st: RepoStatus, turnFiles: List<String>, turnCwd: String?, onlyTurn: Boolean): GitReviewModel {
            val rel = turnFiles.mapNotNull { TurnFiles.relativize(st.root, it, turnCwd) }.toSet()
            val inTurn = { e: GitStatusEntry -> isTurnPath(e.path, rel) }
            val shown = if (onlyTurn && rel.isNotEmpty()) st.entries.filter(inTurn) else st.entries
            val order = compareBy<GitStatusEntry> { !inTurn(it) }
            return GitReviewModel(
                staged = shown.filter { it.staged }.sortedWith(order),
                unstaged = shown.filter { it.unstaged }.sortedWith(order),
                turnPaths = st.entries.filter(inTurn).map { it.path.replace('\\', '/').trimEnd('/') }.toSet(),
                turnInRepo = rel.size,
            )
        }

        /** [path] is one of [turn], or an untracked folder (`dir/`) holding one of them. */
        private fun isTurnPath(path: String, turn: Set<String>): Boolean {
            val p = path.replace('\\', '/')
            if (p.trimEnd('/') in turn) return true
            return p.endsWith("/") && turn.any { it.startsWith(p) }
        }
    }
}

@Composable
private fun opLabel(op: String): String = stringResource(
    when (op) {
        GitOps.STAGE -> R.string.git_stage
        GitOps.UNSTAGE -> R.string.git_unstage
        GitOps.DISCARD -> R.string.git_discard
        GitOps.COMMIT -> R.string.git_commit
        GitOps.PUSH -> R.string.git_push
        GitOps.PULL -> R.string.git_pull
        GitOps.FETCH -> R.string.git_fetch
        else -> R.string.git_checkout
    },
)

@Composable
fun GitPane(state: FilesUiState, vm: FilesViewModel) {
    if (state.git == null && state.repo != null) LaunchedEffect(state.repo) { vm.loadGit() }
    GitNoticeHost(state, vm)
    val detail = state.gitDiff ?: state.gitShow
    if (detail != null) {
        val back = io.github.tianli0.switchtether.ui.common.rememberPredictiveBack { vm.closeGitDetail() }
        Column(Modifier.fillMaxSize().then(back)) {
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
    LaunchedEffect(state.repo, currentListingPath, rootPaths) {
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
        FormDialog(
            show = true,
            title = stringResource(R.string.git_repo),
            confirmText = stringResource(R.string.action_ok),
            confirmEnabled = repoDraft.trim().isNotEmpty(),
            onConfirm = {
                vm.setRepo(repoDraft.trim())
                showRepoDialog = false
            },
            onDismiss = { showRepoDialog = false },
        ) {
            TextInput(repoDraft, { repoDraft = it }, stringResource(R.string.git_repo))
        }
    }
    var showBranches by rememberSaveable { mutableStateOf(false) }
    BranchSheet(show = showBranches && state.gitWritable, state = state, vm = vm, onDismiss = { showBranches = false })
    var pendingDiscard by remember { mutableStateOf<List<String>?>(null) }
    pendingDiscard?.let { paths ->
        ConfirmDialog(
            show = true,
            title = stringResource(R.string.git_discard),
            message = stringResource(R.string.git_discard_confirm, if (paths.size == 1) paths.single() else paths.size.toString()),
            confirmText = stringResource(R.string.git_discard),
            onConfirm = {
                pendingDiscard = null
                vm.discard(paths)
            },
            onDismiss = { pendingDiscard = null },
        )
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
            ToolbarIcon(AppIcons.Refresh, stringResource(R.string.action_refresh), { vm.loadGit() })
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
            GitStatusList(
                state = state,
                st = st,
                vm = vm,
                onBranches = {
                    if (state.gitWritable) {
                        vm.loadBranches()
                        showBranches = true
                    } else {
                        showRepoDialog = true
                    }
                },
                onDiscard = { pendingDiscard = it },
            )
        }
    }
}

@Composable
private fun GitStatusList(
    state: FilesUiState,
    st: RepoStatus,
    vm: FilesViewModel,
    onBranches: () -> Unit,
    onDiscard: (List<String>) -> Unit,
) {
    val dev = LocalDevColors.current
    val model = remember(st, state.turnFiles, state.turnCwd, state.onlyTurn) { GitReviewModel.of(st, state.turnFiles, state.turnCwd, state.onlyTurn) }
    val writable = state.gitWritable
    val busy = state.gitBusy
    val openFileRel = (state.file as? Loadable.Ready)?.value?.path?.let { fp ->
        val base = state.repo?.trimEnd('/', '\\').orEmpty()
        if (base.isNotEmpty() && fp.startsWith(base)) fp.removePrefix(base).trimStart('/', '\\') else null
    }
    val turnLabel = stringResource(R.string.git_turn_badge)
    LazyColumn(Modifier.fillMaxSize().testTag("git-list"), contentPadding = pageListPadding(top = 4.dp)) {
        item(key = "branch") {
            GroupCard {
                ArrowPreference(
                    title = st.branch?.let { "⎇ $it" } ?: "HEAD",
                    summary = listOfNotNull(
                        state.repo,
                        st.upstream,
                        if (st.ahead > 0) "↑${st.ahead}" else null,
                        if (st.behind > 0) "↓${st.behind}" else null,
                    ).joinToString(" · ").ifEmpty { null },
                    onClick = onBranches,
                    cardListPosition = cardListPositionOf(0, 1),
                )
            }
            if (writable) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = PageDefaults.CardGutter).padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SmallButton(stringResource(R.string.git_fetch), vm::fetch, enabled = busy == null, loading = busy == GitOps.FETCH, modifier = Modifier.testTag("git-fetch"))
                    SmallButton(
                        stringResource(R.string.git_pull) + if (st.behind > 0) " ↓${st.behind}" else "",
                        vm::pull,
                        enabled = busy == null && st.upstream != null,
                        loading = busy == GitOps.PULL,
                        modifier = Modifier.testTag("git-pull"),
                    )
                    val publish = st.branch != null && st.upstream == null
                    SmallButton(
                        if (publish) stringResource(R.string.git_publish) else stringResource(R.string.git_push) + if (st.ahead > 0) " ↑${st.ahead}" else "",
                        vm::push,
                        enabled = busy == null && st.branch != null && (publish || st.ahead > 0),
                        loading = busy == GitOps.PUSH,
                        modifier = Modifier.testTag("git-push"),
                    )
                }
            }
        }
        if (state.turnFiles.isNotEmpty()) {
            item(key = "turn") {
                GroupCard {
                    SwitchPreference(
                        checked = state.onlyTurn,
                        onCheckedChange = vm::setOnlyTurn,
                        title = stringResource(R.string.git_only_turn),
                        summary = stringResource(R.string.git_turn_summary, model.turnInRepo, model.turnPaths.size),
                        modifier = Modifier.testTag("git-only-turn"),
                        cardListPosition = cardListPositionOf(0, 1),
                    )
                }
            }
        }
        if (model.staged.isEmpty() && model.unstaged.isEmpty()) {
            item(key = "clean") {
                GroupCard {
                    BasicComponent(title = stringResource(if (state.onlyTurn && state.turnFiles.isNotEmpty()) R.string.git_turn_clean else R.string.git_clean))
                }
            }
        }
        if (model.staged.isNotEmpty()) {
            item(key = "staged-title") {
                SectionHeader(stringResource(R.string.git_staged_title, model.staged.size)) {
                    if (writable) LinkButton(stringResource(R.string.git_unstage_all), { vm.unstage(model.staged.map { it.path }) }, enabled = busy == null)
                }
            }
            item(key = "staged") {
                GroupCard {
                    model.staged.forEachIndexed { i, e ->
                        BasicComponent(
                            title = e.path,
                            summary = e.origPath?.let { stringResource(R.string.git_renamed_from, it) },
                            endActions = {
                                if (model.isTurn(e)) StatusPill(turnLabel, dev.info)
                                StatusPill(e.index.trim(), dev.ok)
                                if (writable) ToolbarIcon(AppIcons.Close, stringResource(R.string.git_unstage), { vm.unstage(listOf(e.path)) }, enabled = busy == null)
                            },
                            onClick = { vm.gitDiff(e.path, staged = true) },
                            cardListPosition = cardListPositionOf(i, model.staged.size),
                        )
                    }
                }
            }
        }
        if (model.unstaged.isNotEmpty()) {
            item(key = "unstaged-title") {
                SectionHeader(stringResource(R.string.git_unstaged_title, model.unstaged.size)) {
                    if (writable) {
                        LinkButton(
                            stringResource(R.string.git_stage_all),
                            {
                                // "All" means what is shown: only the turn's files while that filter is on.
                                if (state.onlyTurn && state.turnFiles.isNotEmpty()) vm.stage(model.unstaged.map { it.path }) else vm.stageAll()
                            },
                            enabled = busy == null,
                            modifier = Modifier.testTag("git-stage-all"),
                        )
                    }
                }
            }
            item(key = "unstaged") {
                GroupCard {
                    model.unstaged.forEachIndexed { i, e ->
                        val badge = when {
                            e.conflicted -> "!" to dev.danger
                            e.untracked -> "??" to dev.warn
                            else -> e.worktree.trim() to dev.info
                        }
                        BasicComponent(
                            title = e.path,
                            summary = e.origPath?.let { stringResource(R.string.git_renamed_from, it) },
                            endActions = {
                                if (model.isTurn(e)) StatusPill(turnLabel, dev.info)
                                StatusPill(badge.first, badge.second)
                                if (writable) {
                                    if (!e.conflicted) ToolbarIcon(AppIcons.Rewind, stringResource(R.string.git_discard), { onDiscard(listOf(e.path)) }, enabled = busy == null)
                                    ToolbarIcon(AppIcons.Add, stringResource(R.string.git_stage), { vm.stage(listOf(e.path)) }, enabled = busy == null)
                                }
                            },
                            // An untracked folder has nothing to show; an untracked file has no diff yet,
                            // so it opens as the file itself.
                            onClick = when {
                                e.path.endsWith("/") -> null
                                e.untracked -> ({ vm.openPath(st.root.trimEnd('/', '\\') + "/" + e.path) })
                                else -> ({ vm.gitDiff(e.path, staged = false) })
                            },
                            cardListPosition = cardListPositionOf(i, model.unstaged.size),
                        )
                    }
                }
            }
        }
        if (writable) {
            item(key = "commit") {
                val stagedTotal = st.entries.count { it.staged }
                SmallTitle(stringResource(R.string.git_commit))
                Column(Modifier.fillMaxWidth().padding(horizontal = PageDefaults.CardGutter).padding(bottom = PageDefaults.GroupSpacing)) {
                    TextInput(
                        state.commitMessage,
                        vm::setCommitMessage,
                        stringResource(R.string.git_commit_message),
                        singleLine = false,
                        maxLines = 5,
                        modifier = Modifier.fillMaxWidth().testTag("git-commit-message"),
                    )
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                        SmallButton(
                            stringResource(R.string.git_commit_n, stagedTotal),
                            vm::commit,
                            enabled = busy == null && stagedTotal > 0 && state.commitMessage.isNotBlank(),
                            loading = busy == GitOps.COMMIT,
                            tone = ActionTone.Primary,
                            modifier = Modifier.testTag("git-commit"),
                        )
                    }
                }
            }
        } else if (state.git is Loadable.Ready) {
            item(key = "readonly") {
                Text(
                    stringResource(R.string.git_read_only),
                    style = COUITheme.textStyles.footnote1,
                    color = COUITheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = PageDefaults.TextGutter, vertical = 8.dp),
                )
            }
        }
        (state.gitLog as? Loadable.Ready)?.value?.takeIf { it.isNotEmpty() }?.let { commits ->
            item(key = "log-title") { SmallTitle(stringResource(R.string.git_log)) }
            item(key = "log") {
                GroupCard {
                    // A commit shows one file as it was then: a tracked file, never an untracked one or a
                    // folder (git refused "exists on disk, but not in <commit>").
                    val targetPath = openFileRel ?: st.entries.firstOrNull { !it.untracked && !it.path.endsWith("/") }?.path
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

@Composable
private fun SectionHeader(title: String, action: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(end = PageDefaults.CardGutter), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { SmallTitle(title) }
        action()
    }
}

/** Switch to a local or remote branch, or create one from HEAD. */
@Composable
private fun BranchSheet(show: Boolean, state: FilesUiState, vm: FilesViewModel, onDismiss: () -> Unit) {
    var newBranch by rememberSaveable { mutableStateOf("") }
    PanelSheet(show = show, title = stringResource(R.string.git_checkout), onDismissRequest = onDismiss) {
        val branches = state.gitBranches ?: Loadable.Loading
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            LoadableContent(branches, onRetry = vm::loadBranches) { b ->
                Column {
                    SmallTitle(stringResource(R.string.git_branches_local))
                    GroupCard {
                        b.local.forEachIndexed { i, br ->
                            BasicComponent(
                                title = br.name,
                                summary = br.upstream,
                                endActions = { if (br.current) StatusPill(stringResource(R.string.git_branch_current), COUITheme.colorScheme.primary) },
                                onClick = if (br.current || state.gitBusy != null) null else ({
                                    vm.checkout(br.name, create = false)
                                    onDismiss()
                                }),
                                cardListPosition = cardListPositionOf(i, b.local.size),
                            )
                        }
                    }
                    if (b.remote.isNotEmpty()) {
                        SmallTitle(stringResource(R.string.git_branches_remote))
                        GroupCard {
                            b.remote.take(50).forEachIndexed { i, br ->
                                BasicComponent(
                                    title = br.name,
                                    onClick = if (state.gitBusy != null) null else ({
                                        // `git switch other` creates the local branch tracking origin/other.
                                        vm.checkout(br.name.substringAfter('/'), create = false)
                                        onDismiss()
                                    }),
                                    cardListPosition = cardListPositionOf(i, minOf(50, b.remote.size)),
                                )
                            }
                        }
                    }
                }
            }
            SmallTitle(stringResource(R.string.git_new_branch))
            Column(Modifier.fillMaxWidth().padding(horizontal = PageDefaults.CardGutter)) {
                TextInput(newBranch, { newBranch = it.replace(" ", "-") }, stringResource(R.string.git_branch_name), modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    SmallButton(
                        stringResource(R.string.git_create_branch),
                        {
                            vm.checkout(newBranch.trim(), create = true)
                            newBranch = ""
                            onDismiss()
                        },
                        enabled = newBranch.isNotBlank() && state.gitBusy == null,
                        tone = ActionTone.Primary,
                    )
                }
            }
        }
    }
}

/** Success: a toast. Failure: a dialog with git's own message (credentials, conflicts, hooks …). */
@Composable
private fun GitNoticeHost(state: FilesUiState, vm: FilesViewModel) {
    val notice = state.gitNotice ?: return
    val label = opLabel(notice.op)
    if (notice.ok) {
        val text = stringResource(R.string.git_op_done, label)
        val context = LocalContext.current
        LaunchedEffect(notice) {
            android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
            vm.clearGitNotice()
        }
    } else {
        ConfirmDialog(
            show = true,
            title = stringResource(R.string.git_op_failed, label),
            message = notice.text ?: stringResource(R.string.git_op_failed_generic),
            confirmText = stringResource(R.string.action_ok),
            onConfirm = vm::clearGitNotice,
            onDismiss = vm::clearGitNotice,
        )
    }
}
