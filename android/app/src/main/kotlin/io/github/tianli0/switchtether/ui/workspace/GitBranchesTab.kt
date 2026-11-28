package io.github.tianli0.switchtether.ui.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.tianli0.coui17.patterns.ActionTone
import io.github.tianli0.coui17.patterns.ConfirmDialog
import io.github.tianli0.coui17.patterns.LinkButton
import io.github.tianli0.coui17.patterns.MenuEntry
import io.github.tianli0.coui17.patterns.PageDefaults
import io.github.tianli0.coui17.patterns.SmallButton
import io.github.tianli0.coui17.patterns.StatusPill
import io.github.tianli0.coui17.patterns.TextInput
import io.github.tianli0.coui17.patterns.pageListPadding
import io.github.tianli0.coui17.patterns.pageScroll
import io.github.tianli0.coui17.patterns.rememberPageListState
import io.github.tianli0.switchtether.R
import io.github.tianli0.switchtether.remote.protocol.GitBranch
import io.github.tianli0.switchtether.remote.protocol.RepoStatus
import io.github.tianli0.switchtether.ui.common.Loadable
import io.github.tianli0.switchtether.ui.common.relativeTime
import io.github.tianli0.switchtether.ui.icons.AppIcons
import io.github.tianli0.switchtether.ui.theme.LocalDevColors

/** Remote branches listed at once (the filter finds the rest). */
private const val REMOTE_BRANCH_LIMIT = 50

/** What a branch row asks before it acts. */
private sealed interface BranchAsk {
    data class Switch(val name: String) : BranchAsk
    data class Merge(val name: String) : BranchAsk
    data class Delete(val name: String, val force: Boolean) : BranchAsk
}

/**
 * 分支: a filter, the local branches (current first, with its check mark, upstream and
 * ahead / behind), the remote ones, and "new branch" at the end. A tap switches (asking first
 * when there are uncommitted changes); a long press offers switch, merge into the current branch
 * and delete (feature `git_manage`).
 */
@Composable
internal fun GitBranchesTab(state: FilesUiState, st: RepoStatus, vm: FilesViewModel, header: @Composable () -> Unit) {
    LaunchedEffect(state.repo) { if (state.gitBranches == null) vm.loadBranches() }
    val dev = LocalDevColors.current
    val writable = state.gitWritable
    val idle = state.gitBusy == null
    var filter by rememberSaveable(state.repo) { mutableStateOf("") }
    var newBranch by rememberSaveable(state.repo) { mutableStateOf("") }
    var ask by remember { mutableStateOf<BranchAsk?>(null) }
    val dirty = st.entries.count { !it.untracked }
    val current = st.branch ?: "HEAD"
    fun switchTo(name: String) {
        if (dirty > 0) ask = BranchAsk.Switch(name) else vm.checkout(name, create = false)
    }
    when (val a = ask) {
        is BranchAsk.Switch -> ConfirmDialog(
            show = true,
            title = stringResource(R.string.git_checkout_dirty_title, a.name),
            message = stringResource(R.string.git_checkout_dirty_body, dirty, a.name),
            confirmText = stringResource(R.string.git_checkout_confirm),
            onConfirm = { ask = null; vm.checkout(a.name, create = false) },
            onDismiss = { ask = null },
        )
        is BranchAsk.Merge -> ConfirmDialog(
            show = true,
            title = stringResource(R.string.git_merge_confirm_title, a.name),
            message = stringResource(R.string.git_merge_confirm_body, a.name, current),
            confirmText = stringResource(R.string.git_merge, current),
            onConfirm = { ask = null; vm.merge(a.name) },
            onDismiss = { ask = null },
        )
        is BranchAsk.Delete -> ConfirmDialog(
            show = true,
            title = stringResource(if (a.force) R.string.git_branch_delete_force else R.string.git_branch_delete),
            message = stringResource(if (a.force) R.string.git_branch_delete_force_confirm else R.string.git_branch_delete_confirm, a.name),
            confirmText = stringResource(if (a.force) R.string.git_branch_delete_force else R.string.git_branch_delete),
            onConfirm = { ask = null; vm.deleteBranch(a.name, a.force) },
            onDismiss = { ask = null },
            destructive = true,
        )
        null -> Unit
    }
    val switchLabel = stringResource(R.string.git_branch_switch)
    val mergeLabel = stringResource(R.string.git_merge, current)
    val deleteLabel = stringResource(R.string.git_branch_delete)
    val forceLabel = stringResource(R.string.git_branch_delete_force)
    val currentLabel = stringResource(R.string.git_branch_current)
    val goneLabel = stringResource(R.string.git_branch_gone)
    val branches = state.gitBranches ?: Loadable.Loading
    LazyColumn(
        state = rememberPageListState(),
        modifier = Modifier.fillMaxSize().pageScroll().testTag("git-branches"),
        flingBehavior = io.github.tianli0.coui17.patterns.pageFlingBehavior(),
        contentPadding = pageListPadding(top = 4.dp),
    ) {
        item(key = "header") { header() }
        item(key = "filter") {
            // COUI17's animated search bar: tapping the capsule expands it and slides cancel in;
            // cancel collapses it and clears the filter.
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
                            label = stringResource(R.string.git_branch_filter),
                            modifier = Modifier.testTag("git-branch-filter"),
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
        when (branches) {
            is Loadable.Ready -> {
                val b = branches.value
                val q = filter.trim()
                val local = b.local.filter { q.isEmpty() || it.name.contains(q, ignoreCase = true) }
                val remoteAll = b.remote.filter { q.isEmpty() || it.name.contains(q, ignoreCase = true) }
                val remote = remoteAll.take(REMOTE_BRANCH_LIMIT)
                if (local.isNotEmpty()) {
                    item(key = "local-title") { GitSectionHeader(stringResource(R.string.git_branches_local)) }
                    itemsIndexed(local, key = { _, br -> "l:" + br.name }) { i, br ->
                        val menu = buildList {
                            if (writable && !br.current) {
                                add(MenuEntry(switchLabel, enabled = idle, onClick = { switchTo(br.name) }))
                                if (state.gitManage) {
                                    if (!st.merging && st.branch != null) add(MenuEntry(mergeLabel, enabled = idle, onClick = { ask = BranchAsk.Merge(br.name) }))
                                    add(MenuEntry(deleteLabel, danger = true, enabled = idle, onClick = { ask = BranchAsk.Delete(br.name, force = false) }))
                                    add(MenuEntry(forceLabel, danger = true, enabled = idle, onClick = { ask = BranchAsk.Delete(br.name, force = true) }))
                                }
                            }
                        }
                        GitRow(
                            title = br.name,
                            // Accent only for the current branch.
                            titleColor = if (br.current) COUITheme.colorScheme.primary else COUITheme.colorScheme.onSurface,
                            summary = branchSummary(br),
                            summaryOverflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            leading = {
                                if (br.current) RowGlyph(AppIcons.Done, COUITheme.colorScheme.primary) else RowGlyph(AppIcons.Git)
                            },
                            divider = i < local.lastIndex,
                            position = io.github.suqi8.coui.kmp.basic.cardListPositionOf(i, local.size),
                            onClick = if (writable && !br.current && idle) ({ switchTo(br.name) }) else null,
                            menu = menu,
                            trailing = {
                                if (br.current) StatusPill(currentLabel, COUITheme.colorScheme.primary)
                                if (br.gone) StatusPill(goneLabel, dev.warn)
                            },
                        )
                    }
                }
                if (remote.isNotEmpty()) {
                    item(key = "remote-title") { GitSectionHeader(stringResource(R.string.git_branches_remote)) }
                    itemsIndexed(remote, key = { _, br -> "r:" + br.name }) { i, br ->
                        // `git switch other` creates the local branch tracking origin/other.
                        val localName = br.name.substringAfter('/')
                        GitRow(
                            title = br.name,
                            summary = br.timeMs.takeIf { it > 0 }?.let { relativeTime(it) },
                            leading = { RowGlyph(AppIcons.Web) },
                            divider = i < remote.lastIndex,
                            position = io.github.suqi8.coui.kmp.basic.cardListPositionOf(i, remote.size),
                            onClick = if (writable && idle) ({ switchTo(localName) }) else null,
                            menu = if (writable) {
                                buildList {
                                    add(MenuEntry(switchLabel, enabled = idle, onClick = { switchTo(localName) }))
                                    if (state.gitManage && !st.merging && st.branch != null) add(MenuEntry(mergeLabel, enabled = idle, onClick = { ask = BranchAsk.Merge(br.name) }))
                                }
                            } else {
                                emptyList()
                            },
                        )
                    }
                    if (remoteAll.size > remote.size) {
                        item(key = "remote-more") { GitNote(stringResource(R.string.git_branches_more, remoteAll.size - remote.size)) }
                    }
                }
                if (local.isEmpty() && remote.isEmpty() && q.isNotEmpty()) {
                    item(key = "nomatch") { GitNote(stringResource(R.string.git_branches_no_match)) }
                }
            }
            is Loadable.Failed -> item(key = "failed") {
                Column(Modifier.fillMaxWidth()) {
                    GitNote(branches.error.detail ?: stringResource(R.string.git_op_failed_generic))
                    LinkButton(stringResource(R.string.action_retry), vm::loadBranches, modifier = Modifier.padding(start = PageDefaults.TextGutter - 10.dp))
                }
            }
            else -> item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    io.github.suqi8.coui.kmp.basic.InfiniteProgressIndicator(Modifier.size(24.dp))
                }
            }
        }
        if (writable) {
            item(key = "new") {
                GitSectionHeader(stringResource(R.string.git_new_branch))
                Column(Modifier.fillMaxWidth().padding(horizontal = PageDefaults.CardGutter).padding(bottom = PageDefaults.GroupSpacing)) {
                    TextInput(
                        newBranch,
                        { newBranch = it.replace(" ", "-") },
                        stringResource(R.string.git_branch_name),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri, autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth().testTag("git-new-branch"),
                    )
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                        SmallButton(
                            stringResource(R.string.git_create_branch),
                            {
                                vm.checkout(newBranch.trim(), create = true)
                                newBranch = ""
                            },
                            enabled = newBranch.isNotBlank() && idle,
                            tone = ActionTone.Primary,
                        )
                    }
                }
            }
        }
    }
}

/** "→ origin/main · ↑1 ↓2 · 3 天前" for a branch row. */
@Composable
private fun branchSummary(br: GitBranch): String? = listOfNotNull(
    br.upstream?.let { "→ $it" }.takeIf { !br.gone },
    if (br.ahead > 0 || br.behind > 0) "↑${br.ahead} ↓${br.behind}" else null,
    br.timeMs.takeIf { it > 0 }?.let { relativeTime(it) },
).joinToString(" · ").ifEmpty { null }
