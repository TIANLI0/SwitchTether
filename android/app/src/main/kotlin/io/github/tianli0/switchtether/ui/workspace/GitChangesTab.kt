package io.github.tianli0.switchtether.ui.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Archive
import com.adamglin.phosphoricons.regular.Minus
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.cardListPositionOf
import io.github.suqi8.coui.kmp.preference.SwitchPreference
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.tianli0.coui17.patterns.ActionTone
import io.github.tianli0.coui17.patterns.ConfirmDialog
import io.github.tianli0.coui17.patterns.GroupCard
import io.github.tianli0.coui17.patterns.LinkButton
import io.github.tianli0.coui17.patterns.MenuEntry
import io.github.tianli0.coui17.patterns.PageDefaults
import io.github.tianli0.coui17.patterns.SmallButton
import io.github.tianli0.coui17.patterns.StatusPill
import io.github.tianli0.coui17.patterns.TextInput
import io.github.tianli0.coui17.patterns.ToolbarIcon
import io.github.tianli0.coui17.patterns.pageListPadding
import io.github.tianli0.coui17.patterns.pageScroll
import io.github.tianli0.coui17.patterns.rememberPageListState
import io.github.tianli0.switchtether.R
import io.github.tianli0.switchtether.remote.protocol.GitOps
import io.github.tianli0.switchtether.remote.protocol.GitStatusEntry
import io.github.tianli0.switchtether.remote.protocol.RepoStatus
import io.github.tianli0.switchtether.ui.icons.AppIcons
import io.github.tianli0.switchtether.ui.theme.LocalDevColors

/**
 * 改动: the branch and its sync state, a stopped merge, the staged and unstaged files (stage /
 * unstage per row and per section, discard from the row's long-press menu after a confirmation),
 * the commit box (with "amend") and the stashes.
 */
@Composable
internal fun GitChangesTab(state: FilesUiState, st: RepoStatus, vm: FilesViewModel, ui: GitUi, header: @Composable () -> Unit) {
    val dev = LocalDevColors.current
    val model = remember(st, state.turnFiles, state.turnCwd, state.onlyTurn) { GitReviewModel.of(st, state.turnFiles, state.turnCwd, state.onlyTurn) }
    val writable = state.gitWritable
    val busy = state.gitBusy
    val idle = busy == null
    val turnLabel = stringResource(R.string.git_turn_badge)
    var pendingDiscard by remember { mutableStateOf<List<String>?>(null) }
    var pendingPop by remember { mutableStateOf<io.github.tianli0.switchtether.remote.protocol.GitStash?>(null) }
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
            destructive = true,
        )
    }
    pendingPop?.let { s ->
        ConfirmDialog(
            show = true,
            title = stringResource(R.string.git_stash_pop),
            message = stringResource(R.string.git_stash_pop_confirm, s.message.ifBlank { "stash@{${s.index}}" }),
            confirmText = stringResource(R.string.git_stash_pop),
            onConfirm = {
                pendingPop = null
                vm.stashPop(s.index)
            },
            onDismiss = { pendingPop = null },
        )
    }
    val viewDiff = stringResource(R.string.git_view_diff)
    val openFile = stringResource(R.string.git_open_file)
    val discardLabel = stringResource(R.string.git_discard)
    val root = st.root.trimEnd('/', '\\')
    LazyColumn(
        state = rememberPageListState(),
        modifier = Modifier.fillMaxSize().pageScroll().testTag("git-list"),
        flingBehavior = io.github.tianli0.coui17.patterns.pageFlingBehavior(),
        contentPadding = pageListPadding(top = 4.dp),
    ) {
        item(key = "header") { header() }
        item(key = "branch") {
            val branchTitle = st.branch ?: "HEAD"
            GitRow(
                title = branchTitle,
                summary = listOfNotNull(
                    st.upstream?.let { "→ $it" },
                    if (st.ahead > 0 || st.behind > 0) "↑${st.ahead} ↓${st.behind}" else null,
                ).joinToString(" · ").ifEmpty { null },
                leading = { RowGlyph(AppIcons.Git) },
                divider = false,
                onClick = { ui.tab = GitTab.BRANCHES; vm.loadBranches() },
                trailing = {
                    io.github.suqi8.coui.kmp.basic.Icon(
                        AppIcons.ChevronRight,
                        contentDescription = null,
                        tint = COUITheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.6f),
                        modifier = Modifier.padding(end = 4.dp),
                    )
                },
            )
            if (writable) {
                Row(
                    Modifier.fillMaxWidth().padding(start = PageDefaults.TextGutter + 54.dp, end = PageDefaults.CardGutter, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SmallButton(stringResource(R.string.git_fetch), vm::fetch, enabled = idle, loading = busy == GitOps.FETCH, modifier = Modifier.testTag("git-fetch"))
                    SmallButton(
                        stringResource(R.string.git_pull) + if (st.behind > 0) " ↓${st.behind}" else "",
                        vm::pull,
                        enabled = idle && st.upstream != null,
                        loading = busy == GitOps.PULL,
                        modifier = Modifier.testTag("git-pull"),
                    )
                    val publish = st.branch != null && st.upstream == null
                    SmallButton(
                        if (publish) stringResource(R.string.git_publish) else stringResource(R.string.git_push) + if (st.ahead > 0) " ↑${st.ahead}" else "",
                        vm::push,
                        enabled = idle && st.branch != null && (publish || st.ahead > 0),
                        loading = busy == GitOps.PUSH,
                        modifier = Modifier.testTag("git-push"),
                    )
                }
            }
        }
        if (st.merging) {
            item(key = "merging") {
                GroupCard(Modifier.padding(top = 4.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.git_merging_title), style = COUITheme.textStyles.body1, color = dev.danger)
                        Text(stringResource(R.string.git_merging_body), style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurfaceVariantSummary)
                        if (writable && state.gitManage) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                SmallButton(stringResource(R.string.git_merge_abort), vm::mergeAbort, enabled = idle, loading = busy == GitOps.MERGE_ABORT, modifier = Modifier.testTag("git-merge-abort"))
                            }
                        }
                    }
                }
            }
        }
        if (state.turnFiles.isNotEmpty()) {
            item(key = "turn") {
                GroupCard(Modifier.padding(top = 4.dp)) {
                    SwitchPreference(
                        checked = state.onlyTurn,
                        onCheckedChange = vm::setOnlyTurn,
                        title = stringResource(R.string.git_only_turn),
                        summary = if (model.turnPaths.isEmpty()) {
                            stringResource(R.string.git_turn_summary_clean, model.turnInRepo)
                        } else {
                            stringResource(R.string.git_turn_summary, model.turnInRepo, model.turnPaths.size)
                        },
                        modifier = Modifier.testTag("git-only-turn"),
                        cardListPosition = cardListPositionOf(0, 1),
                    )
                }
            }
        }
        if (model.staged.isEmpty() && model.unstaged.isEmpty()) {
            item(key = "clean") {
                GitNote(stringResource(if (state.onlyTurn && state.turnFiles.isNotEmpty()) R.string.git_turn_clean else R.string.git_clean), Modifier.padding(top = 8.dp))
            }
        }
        if (model.staged.isNotEmpty()) {
            item(key = "staged-title") {
                GitSectionHeader(stringResource(R.string.git_staged_title, model.staged.size)) {
                    if (writable) LinkButton(stringResource(R.string.git_unstage_all), { vm.unstage(model.staged.map { it.path }) }, enabled = idle)
                }
            }
            itemsIndexed(model.staged, key = { _, e -> "s:" + e.path }) { i, e ->
                FileChangeRow(
                    e = e,
                    letter = e.index.trim(),
                    added = e.stagedAdded,
                    removed = e.stagedRemoved,
                    turn = if (model.isTurn(e)) turnLabel else null,
                    divider = i < model.staged.lastIndex,
                    onClick = { vm.gitDiff(e.path, staged = true) },
                    menu = listOf(MenuEntry(viewDiff, onClick = { vm.gitDiff(e.path, staged = true) })),
                ) {
                    if (writable) RowAction(PhosphorIcons.Regular.Minus, stringResource(R.string.git_unstage), { vm.unstage(listOf(e.path)) }, enabled = idle)
                }
            }
        }
        if (model.unstaged.isNotEmpty()) {
            item(key = "unstaged-title") {
                GitSectionHeader(stringResource(R.string.git_unstaged_title, model.unstaged.size)) {
                    if (writable) {
                        LinkButton(
                            stringResource(R.string.git_stage_all),
                            {
                                // "All" means what is shown: only the turn's files while that filter is on.
                                if (state.onlyTurn && state.turnFiles.isNotEmpty()) vm.stage(model.unstaged.map { it.path }) else vm.stageAll()
                            },
                            enabled = idle,
                            modifier = Modifier.testTag("git-stage-all"),
                        )
                    }
                }
            }
            itemsIndexed(model.unstaged, key = { _, e -> "u:" + e.path }) { i, e ->
                val letter = when {
                    e.conflicted -> "!"
                    e.untracked -> "U"
                    else -> e.worktree.trim()
                }
                val fullPath = "$root/${e.path}"
                // An untracked folder has nothing to show; an untracked file has no diff yet, so it
                // opens as the file itself.
                val open: (() -> Unit)? = when {
                    e.path.endsWith("/") -> null
                    e.untracked -> ({ vm.openPath(fullPath) })
                    else -> ({ vm.gitDiff(e.path, staged = false) })
                }
                val menu = buildList {
                    if (!e.untracked && !e.path.endsWith("/")) add(MenuEntry(viewDiff, onClick = { vm.gitDiff(e.path, staged = false) }))
                    if (!e.path.endsWith("/") && letter != "D") add(MenuEntry(openFile, onClick = { vm.openPath(fullPath) }))
                    if (writable && !e.conflicted) add(MenuEntry(discardLabel, danger = true, enabled = idle, onClick = { pendingDiscard = listOf(e.path) }))
                }
                FileChangeRow(
                    e = e,
                    letter = letter,
                    added = e.added,
                    removed = e.removed,
                    turn = if (model.isTurn(e)) turnLabel else null,
                    divider = i < model.unstaged.lastIndex,
                    onClick = open,
                    menu = menu,
                ) {
                    if (writable) RowAction(AppIcons.Add, stringResource(R.string.git_stage), { vm.stage(listOf(e.path)) }, enabled = idle)
                }
            }
        }
        if (writable) {
            item(key = "commit") { CommitBox(state, st, vm) }
        } else if (state.git is io.github.tianli0.switchtether.ui.common.Loadable.Ready) {
            item(key = "readonly") { GitNote(stringResource(R.string.git_read_only)) }
        }
        val stashes = (state.gitStashes as? io.github.tianli0.switchtether.ui.common.Loadable.Ready)?.value.orEmpty()
        if (state.gitManage && stashes.isNotEmpty()) {
            item(key = "stash-title") { GitSectionHeader(stringResource(R.string.git_stashes_title, stashes.size)) }
            itemsIndexed(stashes, key = { _, s -> "st:" + s.index }) { i, s ->
                GitRow(
                    title = s.message.ifBlank { "stash@{${s.index}}" },
                    summary = listOfNotNull("stash@{${s.index}}", s.timeMs.takeIf { it > 0 }?.let { io.github.tianli0.switchtether.ui.common.relativeTime(it) }).joinToString(" · "),
                    leading = { RowGlyph(PhosphorIcons.Regular.Archive) },
                    divider = i < stashes.lastIndex,
                    onClick = if (writable) ({ pendingPop = s }) else null,
                )
            }
        }
    }
}

@Composable
private fun FileChangeRow(
    e: GitStatusEntry,
    letter: String,
    added: Long?,
    removed: Long?,
    turn: String?,
    divider: Boolean,
    onClick: (() -> Unit)?,
    menu: List<MenuEntry>,
    action: @Composable () -> Unit,
) {
    val dev = LocalDevColors.current
    val color = statusColor(letter, dev)
    val folder = listOfNotNull(entryFolder(e.path), e.origPath?.let { stringResource(R.string.git_renamed_from, it) }).joinToString(" · ").ifEmpty { null }
    GitRow(
        title = entryName(e.path),
        summary = folder,
        leading = { StatusBadge(letter.ifEmpty { "M" }, color) },
        divider = divider,
        onClick = onClick,
        menu = menu,
        // "This turn" stays under the name; the line counts sit at the end beside the key, so a row
        // is two lines (name, folder) like a file manager row, not three.
        extra = turn?.let { t -> { StatusPill(t, dev.info) } },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (added != null || removed != null) {
                    LineCounts(added, removed)
                    Spacer(Modifier.width(4.dp))
                }
                action()
            }
        },
    )
}

/** The commit box at the end of the changes: message, "amend", and the commit key. */
@Composable
private fun CommitBox(state: FilesUiState, st: RepoStatus, vm: FilesViewModel) {
    val busy = state.gitBusy
    val stagedTotal = st.entries.count { it.staged }
    GitSectionHeader(stringResource(R.string.git_commit))
    Column(Modifier.fillMaxWidth().padding(horizontal = PageDefaults.CardGutter).padding(bottom = PageDefaults.GroupSpacing)) {
        TextInput(
            state.commitMessage,
            vm::setCommitMessage,
            stringResource(R.string.git_commit_message),
            singleLine = false,
            maxLines = 6,
            modifier = Modifier.fillMaxWidth().testTag("git-commit-message"),
        )
        if (state.gitManage) {
            GroupCard(Modifier.padding(top = 8.dp)) {
                SwitchPreference(
                    checked = state.amend,
                    onCheckedChange = vm::setAmend,
                    title = stringResource(R.string.git_amend),
                    summary = stringResource(R.string.git_amend_summary),
                    modifier = Modifier.testTag("git-amend"),
                    cardListPosition = cardListPositionOf(0, 1),
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.End) {
            SmallButton(
                if (state.amend) stringResource(R.string.git_commit_amend) else stringResource(R.string.git_commit_n, stagedTotal),
                vm::commit,
                enabled = busy == null && (stagedTotal > 0 || state.amend) && state.commitMessage.isNotBlank(),
                loading = busy == GitOps.COMMIT,
                tone = ActionTone.Primary,
                modifier = Modifier.testTag("git-commit"),
            )
        }
    }
}

/**
 * A row's own key (stage / unstage): a flat 40dp icon in the accent colour, no fill until pressed — not a floating 56dp toolbar circle, which made every file row look like a header.
 */
@Composable
internal fun RowAction(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit, enabled: Boolean = true) {
    io.github.suqi8.coui.kmp.basic.IconButton(onClick = onClick, enabled = enabled, minWidth = 40.dp, minHeight = 40.dp) {
        io.github.suqi8.coui.kmp.basic.Icon(
            icon,
            contentDescription = description,
            tint = if (enabled) io.github.suqi8.coui.kmp.theme.COUITheme.colorScheme.primary else io.github.suqi8.coui.kmp.theme.COUITheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.size(20.dp),
        )
    }
}
