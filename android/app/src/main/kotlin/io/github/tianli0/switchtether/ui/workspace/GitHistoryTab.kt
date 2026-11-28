package io.github.tianli0.switchtether.ui.workspace

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.tianli0.coui17.patterns.GroupCard
import io.github.tianli0.coui17.patterns.LinkButton
import io.github.tianli0.coui17.patterns.MenuEntry
import io.github.tianli0.coui17.patterns.PageDefaults
import io.github.tianli0.coui17.patterns.pageListPadding
import io.github.tianli0.coui17.patterns.pageScroll
import io.github.tianli0.coui17.patterns.rememberPageListState
import io.github.tianli0.switchtether.R
import io.github.tianli0.switchtether.remote.protocol.GitStatusEntry
import io.github.tianli0.switchtether.remote.protocol.RepoStatus
import io.github.tianli0.switchtether.ui.common.Loadable
import io.github.tianli0.switchtether.ui.common.LoadableContent
import io.github.tianli0.switchtether.ui.common.relativeTime
import io.github.tianli0.switchtether.ui.theme.DevType
import io.github.tianli0.switchtether.ui.theme.LocalDevColors

/**
 * 历史: commits newest first, message over "hash · author · time", paged ("load older"). A tap
 * opens the commit's changed files (feature `git_manage`); an older desktop still shows one file
 * as it was at that commit.
 */
@Composable
internal fun GitHistoryTab(state: FilesUiState, st: RepoStatus, vm: FilesViewModel, header: @Composable () -> Unit) {
    val log = state.gitLog ?: Loadable.Loading
    // Older desktop (no commit details): a tap shows one tracked file at that commit, as before.
    val tracked = { e: GitStatusEntry -> !e.untracked && !e.path.endsWith("/") }
    val openFileRel = (state.file as? Loadable.Ready)?.value?.path?.let { fp ->
        val base = state.repo?.trimEnd('/', '\\').orEmpty()
        if (base.isNotEmpty() && fp.startsWith(base)) fp.removePrefix(base).trimStart('/', '\\') else null
    }
    val fallbackPath = openFileRel ?: st.entries.firstOrNull(tracked)?.path
    val showFileLabel = stringResource(R.string.git_show_file_at)
    LazyColumn(
        state = rememberPageListState(),
        modifier = Modifier.fillMaxSize().pageScroll().testTag("git-history"),
        flingBehavior = io.github.tianli0.coui17.patterns.pageFlingBehavior(),
        contentPadding = pageListPadding(top = 4.dp),
    ) {
        item(key = "header") { header() }
        when (log) {
            is Loadable.Ready -> {
                val commits = log.value
                if (commits.isEmpty()) item(key = "empty") { GitNote(stringResource(R.string.git_history_empty)) }
                if (!state.gitManage && commits.isNotEmpty()) {
                    item(key = "old") { GitNote(stringResource(R.string.git_commit_old_desktop)) }
                }
                itemsIndexed(commits, key = { _, c -> c.hash }) { i, c ->
                    GitRow(
                        title = c.subject,
                        titleMaxLines = 2,
                        summary = listOf(c.hash.take(8), c.author, relativeTime(c.timeMs)).joinToString(" · "),
                        summaryOverflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        leading = { AuthorDot(c.author) },
                        divider = i < commits.lastIndex,
                        position = io.github.suqi8.coui.kmp.basic.cardListPositionOf(i, commits.size),
                        onClick = when {
                            state.gitManage -> ({ vm.openCommit(c.hash) })
                            fallbackPath != null -> ({ vm.gitShow(c.hash, fallbackPath) })
                            else -> null
                        },
                        extra = if (!state.gitManage && fallbackPath != null) {
                            { Text(showFileLabel.format(entryName(fallbackPath)), style = COUITheme.textStyles.footnote2, color = COUITheme.colorScheme.onSurfaceVariantSummary) }
                        } else {
                            null
                        },
                    )
                }
                if (state.gitLogMore) {
                    item(key = "more") {
                        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                            if (state.gitLogLoadingMore) {
                                io.github.suqi8.coui.kmp.basic.InfiniteProgressIndicator(Modifier.size(24.dp))
                            } else {
                                LinkButton(stringResource(R.string.git_history_more), vm::loadMoreLog, modifier = Modifier.testTag("git-history-more"))
                            }
                        }
                    }
                }
            }
            is Loadable.Failed -> item(key = "failed") {
                Column(Modifier.fillMaxWidth()) {
                    GitNote(log.error.detail ?: stringResource(R.string.git_op_failed_generic))
                    LinkButton(stringResource(R.string.action_retry), vm::loadGit, modifier = Modifier.padding(start = PageDefaults.TextGutter - 10.dp))
                }
            }
            else -> item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    io.github.suqi8.coui.kmp.basic.InfiniteProgressIndicator(Modifier.size(24.dp))
                }
            }
        }
    }
}

/** The author's initial in a neutral circle (a commit list's avatar, without fetching any). */
@Composable
private fun AuthorDot(author: String) {
    val dev = LocalDevColors.current
    Box(
        Modifier.size(34.dp).clip(CircleShape).background(COUITheme.colorScheme.onSurface.copy(alpha = if (dev.isDark) 0.12f else 0.07f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            author.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?",
            style = COUITheme.textStyles.body2,
            fontWeight = FontWeight.SemiBold,
            color = COUITheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

/** A commit opened from the history: its message, then its changed files (a tap shows the diff). */
@Composable
internal fun CommitDetailBody(state: FilesUiState, vm: FilesViewModel) {
    val commit = state.gitCommit ?: return
    val dev = LocalDevColors.current
    val versionLabel = stringResource(R.string.git_show_version)
    val diffLabel = stringResource(R.string.git_view_diff)
    LoadableContent(commit.second, onRetry = vm::retryCommit) { d ->
        LazyColumn(
            state = rememberPageListState(),
            modifier = Modifier.fillMaxSize().pageScroll().testTag("git-commit-detail"),
            flingBehavior = io.github.tianli0.coui17.patterns.pageFlingBehavior(),
            contentPadding = pageListPadding(top = 8.dp),
        ) {
            item(key = "message") {
                GroupCard {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(d.subject, style = COUITheme.textStyles.body1, fontWeight = FontWeight.Medium, color = COUITheme.colorScheme.onSurface)
                        if (d.body.isNotBlank()) {
                            Text(reflowCommitBody(d.body), style = COUITheme.textStyles.body2, color = COUITheme.colorScheme.onSurface)
                        }
                        Text(
                            listOfNotNull(d.author, d.email.ifBlank { null }, d.timeMs.takeIf { it > 0 }?.let { relativeTime(it) }).joinToString(" · "),
                            style = COUITheme.textStyles.footnote1,
                            color = COUITheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Row {
                            Text(d.hash, style = DevType.label, color = dev.gutter, maxLines = 1)
                        }
                    }
                }
            }
            item(key = "files-title") { GitSectionHeader(stringResource(R.string.git_commit_files, d.files.size)) }
            if (d.files.isEmpty()) item(key = "nofiles") { GitNote(stringResource(R.string.git_commit_no_files)) }
            itemsIndexed(d.files, key = { _, f -> f.path + (f.origPath ?: "") }) { i, f ->
                GitRow(
                    title = entryName(f.path),
                    summary = listOfNotNull(entryFolder(f.path), f.origPath?.let { stringResource(R.string.git_renamed_from, it) }).joinToString(" · ").ifEmpty { null },
                    leading = { StatusBadge(f.status, statusColor(f.status, dev)) },
                    divider = i < d.files.lastIndex,
                    position = io.github.suqi8.coui.kmp.basic.cardListPositionOf(i, d.files.size),
                    onClick = { vm.commitFileDiff(d.hash, f.path, f.origPath) },
                    menu = buildList {
                        add(MenuEntry(diffLabel, onClick = { vm.commitFileDiff(d.hash, f.path, f.origPath) }))
                        if (f.status != "D") add(MenuEntry(versionLabel, onClick = { vm.gitShow(d.hash, f.path) }))
                    },
                    extra = if (f.added != null || f.removed != null) ({ LineCounts(f.added, f.removed) }) else null,
                )
            }
            item(key = "end") { Box(Modifier.padding(bottom = PageDefaults.GroupSpacing)) }
        }
    }
}

/**
 * A commit body as a phone reads it: git wraps messages at ~72 columns, which on a narrow screen
 * left ragged half-lines. Lines of one paragraph are joined; blank lines, list items, indented
 * (code) lines and trailers ("Co-Authored-By: …") keep their own line — what GitHub shows.
 */
internal fun reflowCommitBody(body: String): String {
    val keep = Regex("""^(\s|[-*+] |\d+[.)] |[A-Za-z][A-Za-z-]*: )""")
    val out = StringBuilder()
    var prevJoinable = false
    for (line in body.trimEnd().lines()) {
        val blank = line.isBlank()
        val own = blank || keep.containsMatchIn(line)
        if (out.isNotEmpty()) out.append(if (prevJoinable && !own) " " else "\n")
        out.append(if (blank) "" else line.trimEnd())
        prevJoinable = !blank && !line.startsWith(" ") && !line.startsWith("\t")
    }
    return out.toString()
}
