package io.github.tianli0.switchtether.ui.workspace

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.SmallTitle
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.tianli0.coui17.patterns.ConfirmDialog
import io.github.tianli0.coui17.patterns.FormDialog
import io.github.tianli0.coui17.patterns.GlassMenu
import io.github.tianli0.coui17.patterns.MenuEntry
import io.github.tianli0.coui17.patterns.Page
import io.github.tianli0.coui17.patterns.PageDefaults
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
import io.github.tianli0.switchtether.ui.common.rememberPredictiveBack
import io.github.tianli0.switchtether.ui.diff.DiffView
import io.github.tianli0.switchtether.ui.icons.AppIcons
import io.github.tianli0.switchtether.ui.theme.DevColors
import io.github.tianli0.switchtether.ui.theme.LocalDevColors

/*
 * The Git page, laid out like the phone Git clients people already use (PuppyGit, MGit, GitHub
 * Mobile's "Files changed") in the app's ColorOS look: one title bar with round keys, three tabs
 * — 改动 (staged / unstaged files and the commit box), 历史 (commits → changed files → diff) and
 * 分支 (search, switch, merge, delete, create) — and flat rows: a status letter in its colour,
 * the file name over its folder, "+a −d", and the one action the row needs. A diff, a file at a
 * commit or a commit's file list replaces the page body and takes over the title bar's back key,
 * so there is never a second back bar.
 */

@Composable
fun GitPage(onBack: () -> Unit) {
    val vm = filesViewModel()
    val st by vm.state.collectAsStateWithLifecycle()
    GitScaffold(st, vm, stringResource(R.string.ws_git), onBack)
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
    GitScaffold(st, vm, stringResource(R.string.git_review_title), onBack)
}

/** Dialogs the title bar's menu opens inside the page body. */
@Stable
class GitUi {
    var tab by mutableIntStateOf(GitTab.CHANGES)
    var repoDialog by mutableStateOf(false)
    var stashDialog by mutableStateOf(false)
}

object GitTab {
    const val CHANGES = 0
    const val HISTORY = 1
    const val BRANCHES = 2
}

@Composable
fun rememberGitUi(): GitUi = remember { GitUi() }

@Composable
private fun GitScaffold(st: FilesUiState, vm: FilesViewModel, pageTitle: String, onBack: () -> Unit) {
    val ui = rememberGitUi()
    val detailKey = (st.gitDiff ?: st.gitShow)?.first
    val commit = st.gitCommit
    val status = (st.git as? Loadable.Ready)?.value
    val title: String
    val subtitle: String
    val back: () -> Unit
    when {
        detailKey != null -> {
            // "rev:path" for a file at / changed in a commit; a plain path for the working tree.
            val rev = detailKey.substringBefore(':', "").takeIf { it.length in 7..40 && it.all { c -> c.isLetterOrDigit() } }
            val path = if (rev != null) detailKey.substringAfter(':') else detailKey
            title = entryName(path)
            subtitle = listOfNotNull(rev?.take(8), entryFolder(path)).joinToString(" · ")
            back = vm::closeGitDetail
        }
        commit != null -> {
            val d = (commit.second as? Loadable.Ready)?.value
            // The subject is the first line of the card below; as a marquee title it was cut
            // mid-word. The bar names the commit, like a file viewer names the file.
            title = stringResource(R.string.git_commit_title, commit.first.take(8))
            subtitle = listOfNotNull(
                d?.author,
                d?.timeMs?.takeIf { it > 0 }?.let { io.github.tianli0.switchtether.ui.common.relativeTime(it) },
            ).joinToString(" · ")
            back = vm::closeCommit
        }
        else -> {
            title = pageTitle
            subtitle = listOfNotNull(st.repo?.let(::folderLabel), status?.branch).joinToString(" · ")
            back = onBack
        }
    }
    Page(
        title = title,
        subtitle = subtitle,
        onBack = back,
        large = false,
        immersive = true,
        // Edge-to-edge window: the page makes room for the keyboard (commit message field).
        modifier = Modifier.imePadding(),
        actions = { if (detailKey == null && commit == null) GitActions(st, vm, ui) },
    ) {
        GitPane(st, vm, ui)
    }
}

/** The main view's round keys: refresh, and ⋮ for the repository, stash and sync actions. */
@Composable
private fun RowScope.GitActions(st: FilesUiState, vm: FilesViewModel, ui: GitUi) {
    io.github.tianli0.switchtether.ui.common.RefreshToolbarIcon({ vm.loadGit(); if (ui.tab == GitTab.BRANCHES) vm.loadBranches() })
    var menu by remember { mutableStateOf(false) }
    Box {
        ToolbarIcon(AppIcons.More, stringResource(R.string.git_more), { menu = true })
        val status = (st.git as? Loadable.Ready)?.value
        val idle = st.gitBusy == null
        val entries = buildList {
            add(MenuEntry(stringResource(R.string.git_choose_repo), onClick = { ui.repoDialog = true }))
            if (st.gitWritable && status != null) {
                add(MenuEntry(stringResource(R.string.git_fetch), enabled = idle, onClick = vm::fetch))
                if (status.upstream != null) add(MenuEntry(stringResource(R.string.git_pull), enabled = idle, onClick = vm::pull))
                if (status.branch != null) {
                    val publish = status.upstream == null
                    add(MenuEntry(stringResource(if (publish) R.string.git_publish else R.string.git_push), enabled = idle && (publish || status.ahead > 0), onClick = vm::push))
                }
                if (st.gitManage && status.entries.isNotEmpty()) {
                    add(MenuEntry(stringResource(R.string.git_stash), enabled = idle, onClick = { ui.stashDialog = true }))
                }
            }
        }
        GlassMenu(show = menu, entries = entries, onDismiss = { menu = false })
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
        GitOps.BRANCH_DELETE -> R.string.git_branch_delete
        GitOps.MERGE -> R.string.git_op_merge
        GitOps.MERGE_ABORT -> R.string.git_merge_abort
        GitOps.STASH_PUSH -> R.string.git_stash
        GitOps.STASH_POP -> R.string.git_stash_pop
        else -> R.string.git_checkout
    },
)

/**
 * The page body: a diff / file / commit when one is open, otherwise the tabs. [ui] carries the
 * tab and the dialogs the title bar opens (tests and embedders may leave it out).
 */
@Composable
fun GitPane(state: FilesUiState, vm: FilesViewModel, ui: GitUi = rememberGitUi()) {
    if (state.git == null && state.repo != null) LaunchedEffect(state.repo) { vm.loadGit() }
    GitNoticeHost(state, vm)
    val detail = state.gitDiff ?: state.gitShow
    val back = rememberPredictiveBack(enabled = detail != null || state.gitCommit != null) {
        if (detail != null) vm.closeGitDetail() else vm.closeCommit()
    }
    Box(Modifier.fillMaxSize().then(back)) {
        when {
            detail != null -> GitDetailBody(state, vm, detail)
            state.gitCommit != null -> CommitDetailBody(state, vm)
            else -> GitMain(state, vm, ui)
        }
    }
}

/** A working-tree diff, a commit's diff of one file, or a file as it was at a commit. */
@Composable
private fun GitDetailBody(state: FilesUiState, vm: FilesViewModel, detail: Pair<String, Loadable<String>>) {
    LoadableContent(detail.second, onRetry = vm::retryGitDetail) { text ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberPageScrollState())
                .padding(pageListPadding(top = 8.dp))
                .padding(horizontal = PageDefaults.CardGutter),
        ) {
            if (state.gitDiff != null) {
                if (text.isBlank()) {
                    Text(stringResource(R.string.git_no_changes), color = COUITheme.colorScheme.onSurfaceVariantSummary)
                } else {
                    DiffView(text)
                }
            } else if (text == GIT_SHOW_BINARY) {
                Text(stringResource(R.string.git_show_binary), color = COUITheme.colorScheme.onSurfaceVariantSummary)
            } else {
                CodeBlockView(text, SyntaxHighlighter.languageForPath(detail.first.substringAfter(':')))
            }
        }
    }
}

@Composable
private fun GitMain(state: FilesUiState, vm: FilesViewModel, ui: GitUi) {
    val currentListingPath = (state.listing as? Loadable.Ready)?.value?.path
    val rootPaths = (state.roots as? Loadable.Ready)?.value.orEmpty()
    LaunchedEffect(state.repo, currentListingPath, rootPaths) {
        if (state.repo.isNullOrBlank()) {
            val fallback = currentListingPath?.takeIf { it.isNotBlank() } ?: rootPaths.firstOrNull()?.path
            if (!fallback.isNullOrBlank()) vm.setRepo(fallback)
        }
    }
    // One chip per repository: the open one (its root, once the desktop reported it), the folder
    // open in Files and the shared roots — never a folder inside the open repository again.
    val repoChoices = remember(state.repo, currentListingPath, rootPaths) {
        buildList<Pair<String, String>> {
            fun addRepo(path: String?) {
                if (path.isNullOrBlank() || any { samePath(it.second, path) || isInside(path, it.second) }) return
                add(folderLabel(path) to path)
            }
            addRepo(state.repo)
            addRepo(currentListingPath)
            // Shared roots that are projects; a drive ("C:\\") or the home / Desktop / Documents /
            // Downloads folders are places to browse, not repositories, and read as noise here.
            rootPaths.filterNot { r -> r.name in WELL_KNOWN_ROOTS || DRIVE_ROOT.matches(r.path) || r.path == "/" }.forEach { addRepo(it.path) }
        }
    }
    GitDialogs(state, vm, ui)
    val git = state.git
    Column(Modifier.fillMaxSize()) {
        if (git == null) {
            Column(Modifier.fillMaxSize().padding(pageListPadding())) {
                io.github.tianli0.coui17.patterns.GroupCard {
                    io.github.suqi8.coui.kmp.basic.EmptyState(
                        title = stringResource(R.string.git_no_repo),
                        subtitle = stringResource(R.string.git_no_repo_hint),
                        actionText = stringResource(R.string.git_choose_repo),
                        onActionClick = { ui.repoDialog = true },
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
            return
        }
        LoadableContent(git, onRetry = vm::loadGit) { st ->
            // The repository chips and the tabs head every tab's list, so they scroll away under
            // the glass title bar with it (no hard edge where a pinned row used to cut the list).
            val header: @Composable () -> Unit = {
                GitListHeader(repoChoices, state, vm, ui)
            }
            when (ui.tab) {
                GitTab.HISTORY -> GitHistoryTab(state, st, vm, header)
                GitTab.BRANCHES -> GitBranchesTab(state, st, vm, header)
                else -> GitChangesTab(state, st, vm, ui, header)
            }
        }
    }
}

@Composable
private fun GitListHeader(repoChoices: List<Pair<String, String>>, state: FilesUiState, vm: FilesViewModel, ui: GitUi) {
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        if (repoChoices.size > 1) {
            Box(Modifier.fillMaxWidth().endFade()) {
                io.github.suqi8.coui.kmp.basic.ChipTabGroup(
                    tabs = repoChoices.map { io.github.suqi8.coui.kmp.basic.ChipTabData(it.first) },
                    selectedIndex = repoChoices.indexOfFirst { samePath(it.second, state.repo) }.coerceAtLeast(0),
                    onSelectedChange = { vm.setRepo(repoChoices[it].second) },
                    contentPadding = PaddingValues(horizontal = PageDefaults.CardGutter),
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }
        }
        io.github.suqi8.coui.kmp.basic.TabRowWithContour(
            tabs = listOf(stringResource(R.string.git_tab_changes), stringResource(R.string.git_tab_history), stringResource(R.string.git_tab_branches)),
            selectedTabIndex = ui.tab,
            onTabSelected = { ui.tab = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = PageDefaults.CardGutter).testTag("git-tabs"),
        )
    }
}

@Composable
private fun GitDialogs(state: FilesUiState, vm: FilesViewModel, ui: GitUi) {
    if (ui.repoDialog) {
        var repoDraft by rememberSaveable(state.repo) { mutableStateOf(state.repo.orEmpty()) }
        FormDialog(
            show = true,
            title = stringResource(R.string.git_repo),
            confirmText = stringResource(R.string.action_ok),
            confirmEnabled = repoDraft.trim().isNotEmpty(),
            onConfirm = {
                vm.setRepo(repoDraft.trim())
                ui.repoDialog = false
            },
            onDismiss = { ui.repoDialog = false },
        ) {
            TextInput(repoDraft, { repoDraft = it }, stringResource(R.string.git_repo))
        }
    }
    if (ui.stashDialog) {
        var msg by rememberSaveable { mutableStateOf("") }
        FormDialog(
            show = true,
            title = stringResource(R.string.git_stash),
            confirmText = stringResource(R.string.git_stash),
            confirmEnabled = state.gitBusy == null,
            onConfirm = {
                vm.stashPush(msg)
                msg = ""
                ui.stashDialog = false
            },
            onDismiss = { ui.stashDialog = false },
        ) {
            TextInput(msg, { msg = it }, stringResource(R.string.git_stash_message))
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

// ---- shared rows -------------------------------------------------------------------------------

/** The colour of a git status letter: added green, modified amber, deleted red, renamed blue. */
internal fun statusColor(letter: String, dev: DevColors): Color = when (letter.trim().uppercase()) {
    "A", "U", "?", "??" -> dev.ok
    "D" -> dev.danger
    "!" -> dev.danger
    "R", "C" -> dev.info
    else -> dev.warn
}

/** A status letter in its colour, in the place a file list puts the file's icon. */
@Composable
internal fun StatusBadge(letter: String, color: Color) {
    Box(
        Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(letter, style = COUITheme.textStyles.body1, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1)
    }
}

/** "+a −d" in the diff colours (nothing for binary or unknown counts). */
@Composable
internal fun LineCounts(added: Long?, removed: Long?) {
    if (added == null && removed == null) return
    val dev = LocalDevColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("+${added ?: 0}", style = COUITheme.textStyles.footnote1, color = dev.addedText, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(6.dp))
        Text("−${removed ?: 0}", style = COUITheme.textStyles.footnote1, color = dev.removedText, fontWeight = FontWeight.Medium)
    }
}

/**
 * One flat row on the page (ColorOS list row, no card): a leading slot, the title over a quiet
 * summary (and an optional extra line), trailing content, and a divider that starts at the text.
 * A long press opens [menu] next to the row.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun GitRow(
    title: String,
    summary: String?,
    leading: @Composable () -> Unit,
    divider: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    titleColor: Color = COUITheme.colorScheme.onSurface,
    titleMaxLines: Int = 1,
    /** Paths keep their end (the file's folder); other summaries their start. */
    summaryOverflow: TextOverflow = TextOverflow.StartEllipsis,
    extra: (@Composable () -> Unit)? = null,
    menu: List<MenuEntry> = emptyList(),
    trailing: @Composable RowScope.() -> Unit = {},
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box(modifier) {
        Column(
            Modifier.fillMaxWidth().then(
                if (onClick != null || menu.isNotEmpty()) {
                    Modifier.combinedClickable(
                        onClick = { onClick?.invoke() ?: run { menuOpen = true } },
                        onLongClick = if (menu.isNotEmpty()) ({ menuOpen = true }) else null,
                    )
                } else {
                    Modifier
                },
            ),
        ) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = PageDefaults.TextGutter, end = 12.dp, top = 9.dp, bottom = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { leading() }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = COUITheme.textStyles.body1, color = titleColor, maxLines = titleMaxLines, overflow = TextOverflow.Ellipsis)
                    if (!summary.isNullOrBlank()) {
                        Text(
                            summary,
                            style = COUITheme.textStyles.footnote1,
                            color = COUITheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 1,
                            overflow = summaryOverflow,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    extra?.let { Box(Modifier.padding(top = 3.dp)) { it() } }
                }
                Row(verticalAlignment = Alignment.CenterVertically, content = trailing)
            }
            if (divider) {
                Box(
                    Modifier.fillMaxWidth().padding(start = PageDefaults.TextGutter + 54.dp, end = 16.dp).height(0.5.dp)
                        .background(COUITheme.colorScheme.onSurface.copy(alpha = 0.08f)),
                )
            }
        }
        if (menu.isNotEmpty()) GlassMenu(show = menuOpen, entries = menu, onDismiss = { menuOpen = false })
    }
}

/** A section title with an optional link on the right ("全部暂存"). */
@Composable
internal fun GitSectionHeader(title: String, action: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(end = PageDefaults.CardGutter, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { SmallTitle(title) }
        action()
    }
}

/** A plain note at the page's text inset. */
@Composable
internal fun GitNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = COUITheme.textStyles.footnote1,
        color = COUITheme.colorScheme.onSurfaceVariantSummary,
        modifier = modifier.padding(horizontal = PageDefaults.TextGutter, vertical = 10.dp),
    )
}

/** The neutral leading icon of a row that is not a file (branch, commit, stash). */
@Composable
internal fun RowGlyph(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color = COUITheme.colorScheme.onSurfaceVariantSummary) {
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
}

/** Same folder on the computer (separators, a trailing slash and a drive letter's case ignored). */
internal fun samePath(a: String?, b: String?): Boolean =
    if (a == null || b == null) a == b else io.github.tianli0.switchtether.files.FileTypes.samePath(a, b)

/** [path] lies strictly inside the folder [root] (same rules as [samePath]). */
internal fun isInside(path: String, root: String): Boolean {
    val norm = { p: String -> p.replace('\\', '/').trimEnd('/').let { if (it.length >= 2 && it[1] == ':') it[0].lowercaseChar() + it.substring(1) else it } }
    val p = norm(path)
    val r = norm(root)
    return r.isNotEmpty() && p.length > r.length && p.startsWith("$r/")
}

/** The last folder name of [path] (the path itself for a root such as `D:\`). */
internal fun folderLabel(path: String): String = path.trimEnd('/', '\\').substringAfterLast('\\').substringAfterLast('/').ifBlank { path }

internal fun entryName(path: String): String = path.trimEnd('/').substringAfterLast('/').ifBlank { path } + if (path.endsWith("/")) "/" else ""

internal fun entryFolder(path: String): String? = path.trimEnd('/').substringBeforeLast('/', "").ifBlank { null }

/** Fades the content out over its last 24dp (a chip row running off the screen edge). */
internal fun Modifier.endFade(): Modifier =
    graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val w = 24.dp.toPx().coerceAtMost(size.width / 2)
            drawRect(
                androidx.compose.ui.graphics.Brush.horizontalGradient(0f to Color.Black, 1f to Color.Transparent, startX = size.width - w, endX = size.width),
                topLeft = androidx.compose.ui.geometry.Offset(size.width - w, 0f),
                size = androidx.compose.ui.geometry.Size(w, size.height),
                blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
            )
        }

/** The desktop's names for the system folders it shares (switchtether-core `files::roots`). */
private val WELL_KNOWN_ROOTS = setOf("Home", "Desktop", "Documents", "Downloads")

/** "C:\\", "D:/", "E:" — a whole drive. */
private val DRIVE_ROOT = Regex("^[A-Za-z]:[\\\\/]?$")
