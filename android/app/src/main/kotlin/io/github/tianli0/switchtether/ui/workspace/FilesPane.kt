package io.github.tianli0.switchtether.ui.workspace

import io.github.suqi8.coui.kmp.basic.Chip
import io.github.tianli0.coui17.patterns.LazyGroupRow
import io.github.tianli0.coui17.patterns.FormDialog
import io.github.tianli0.coui17.patterns.MenuEntry
import io.github.tianli0.coui17.patterns.GlassMenu
import io.github.tianli0.coui17.patterns.PageDefaults
import io.github.tianli0.coui17.patterns.ActionTone
import io.github.tianli0.coui17.patterns.LinkButton
import io.github.tianli0.coui17.patterns.SmallButton
import io.github.tianli0.switchtether.ui.icons.AppIcons

import io.github.tianli0.switchtether.ui.common.resolve

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowUp
import com.adamglin.phosphoricons.regular.SortAscending
import com.adamglin.phosphoricons.regular.SortDescending
import io.github.suqi8.coui.kmp.basic.CardListPosition
import io.github.tianli0.switchtether.files.Crumb
import io.github.tianli0.switchtether.files.FileIcon
import io.github.tianli0.switchtether.files.FileListing
import io.github.tianli0.switchtether.files.FileTypes
import io.github.tianli0.switchtether.files.SortKey
import io.github.tianli0.switchtether.ui.files.TextFileBody
import io.github.tianli0.switchtether.ui.files.fileIconVector
import kotlinx.coroutines.launch
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.basic.BasicComponentDefaults
import io.github.suqi8.coui.kmp.basic.BreadcrumbBar
import io.github.suqi8.coui.kmp.basic.BreadcrumbItem
import io.github.suqi8.coui.kmp.basic.ChipTabData
import io.github.suqi8.coui.kmp.basic.ChipTabGroup
import io.github.suqi8.coui.kmp.basic.DropdownImpl
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.LinearProgressIndicator
import io.github.suqi8.coui.kmp.basic.ListPopupColumn
import io.github.suqi8.coui.kmp.basic.SearchBar
import io.github.suqi8.coui.kmp.basic.SmallTitle
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.cardListPositionOf
import io.github.suqi8.coui.kmp.layout.DialogButtonBar
import io.github.suqi8.coui.kmp.layout.DialogButtonBarAction
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.window.WindowDialog
import io.github.suqi8.coui.kmp.window.WindowListPopup
import io.github.tianli0.switchtether.R
import io.github.tianli0.switchtether.remote.protocol.FsEntry
import io.github.tianli0.coui17.patterns.PanelSheet
import io.github.tianli0.coui17.patterns.ConfirmDialog
import io.github.tianli0.switchtether.ui.common.Formatters
import io.github.tianli0.coui17.patterns.GroupCard
import io.github.tianli0.switchtether.ui.common.Loadable
import io.github.tianli0.switchtether.ui.common.LoadableContent
import io.github.tianli0.switchtether.ui.common.NoticeVisibility
import io.github.tianli0.switchtether.ui.common.rememberLast
import io.github.tianli0.coui17.patterns.TextInput
import io.github.tianli0.coui17.patterns.ToolbarIcon
import io.github.tianli0.coui17.patterns.pageListPadding
import io.github.tianli0.coui17.patterns.StatusPill
import io.github.tianli0.switchtether.ui.diff.DiffView
import io.github.tianli0.switchtether.ui.files.RemoteFileRef
import io.github.tianli0.switchtether.ui.files.RemoteFileViewer
import io.github.tianli0.switchtether.ui.theme.DevType
import io.github.tianli0.switchtether.ui.theme.LocalDevColors

@Composable
fun FilesPane(state: FilesUiState, vm: FilesViewModel, onOpenDownloads: () -> Unit = {}) {
    val file = state.file
    val viewer = state.viewer
    val opened: (@Composable () -> Unit)? = when {
        viewer != null -> {
            {
                BackHandler(onBack = vm::closeViewer)
                RemoteFileViewer(
                    ref = RemoteFileRef(viewer.path, viewer.name, viewer.kind, viewer.size, viewer.modifiedMs),
                    onBack = vm::closeViewer,
                    onManageDownloads = onOpenDownloads,
                )
            }
        }
        state.conflict != null -> { { ConflictScreen(state, vm) } }
        file != null -> { { FileScreen(state, vm) } }
        else -> null
    }
    // Wide enough (a tablet's detail pane, §26): the folder stays on the left while a file is open.
    // The opened file's back handler is composed after the browser's, so back closes the file first.
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        when {
            opened == null -> Browser(state, vm, onOpenDownloads)
            maxWidth >= FILES_SPLIT_MIN_WIDTH -> io.github.tianli0.switchtether.ui.nav.ListDetailPanes(
                list = { Browser(state, vm, onOpenDownloads) },
                detail = { opened() },
                listWidth = (maxWidth * 0.38f).coerceIn(280.dp, 380.dp),
            )
            else -> opened()
        }
    }
    state.message?.let { msg ->
        if (msg.res in QUIET_MESSAGES) {
            // Success needs no decision: a toast, not a two-button dialog.
            val text = msg.resolve()
            val context = androidx.compose.ui.platform.LocalContext.current
            androidx.compose.runtime.LaunchedEffect(msg) {
                android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
                vm.clearMessage()
            }
        } else {
            ConfirmDialog(
                show = true,
                title = stringResource(R.string.files_title),
                message = msg.resolve(),
                confirmText = stringResource(R.string.action_ok),
                onConfirm = vm::clearMessage,
                onDismiss = vm::clearMessage,
            )
        }
    }
    ConfirmDialog(
        show = state.confirmDiscard,
        title = stringResource(R.string.files_discard_title),
        message = stringResource(R.string.files_discard_body),
        confirmText = stringResource(R.string.files_discard),
        onConfirm = vm::discardEdits,
        onDismiss = vm::keepEditing,
    )
    state.pendingSensitive?.let { e ->
        ConfirmDialog(
            show = true,
            title = stringResource(R.string.files_sensitive_title),
            message = stringResource(R.string.files_sensitive_body, e.name),
            confirmText = stringResource(R.string.files_open_anyway),
            onConfirm = { vm.open(e, confirmedSensitive = true) },
            onDismiss = vm::dismissSensitive,
        )
    }
}

@Composable
private fun Browser(state: FilesUiState, vm: FilesViewModel, onOpenDownloads: () -> Unit) {
    var nameDialog by remember { mutableStateOf<String?>(null) } // "file" | "folder" | "rename"
    var target by remember { mutableStateOf<FsEntry?>(null) }
    var confirmDelete by remember { mutableStateOf<FsEntry?>(null) }
    var actionsFor by remember { mutableStateOf<FsEntry?>(null) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::upload) }
    val listing = (state.listing as? Loadable.Ready)?.value
    val roots = (state.roots as? Loadable.Ready)?.value.orEmpty()
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboard.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // Back: leave search results, then clear the quick filter, then go up a folder.
    BackHandler(enabled = state.results != null || state.query.isNotEmpty() || listing?.parent != null) {
        when {
            state.results != null -> vm.clearSearch().also { searchExpanded = false }
            state.query.isNotEmpty() -> vm.setQuery("")
            else -> listing?.parent?.let(vm::browse)
        }
    }
    Column(Modifier.fillMaxSize()) {
        roots.takeIf { it.size > 1 }?.let { rs ->
            val currentPath = listing?.path.orEmpty()
            val selectedRoot = rs.indexOfFirst { FileListing.isInside(it.path, currentPath) }.coerceAtLeast(0)
            ChipTabGroup(
                tabs = rs.map { ChipTabData(it.name.ifBlank { it.path }) },
                selectedIndex = selectedRoot,
                onSelectedChange = { vm.browse(rs[it].path) },
                contentPadding = PaddingValues(horizontal = 16.dp),
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SearchBar(
                query = state.query,
                onQueryChange = vm::setQuery,
                onSearch = { vm.search() },
                expanded = searchExpanded || state.query.isNotEmpty() || state.results != null,
                onExpandedChange = { searchExpanded = it },
                label = stringResource(R.string.files_filter_hint),
                cancelText = stringResource(R.string.action_cancel),
                onCancel = {
                    vm.clearSearch()
                    searchExpanded = false
                },
                insideMargin = DpSize(0.dp, 4.dp),
                modifier = Modifier.weight(1f).testTag("files-filter"),
            )
            if (searchExpanded || state.query.isNotEmpty()) {
                Spacer(Modifier.size(6.dp))
                Chip(
                    label = stringResource(R.string.files_search_content),
                    selected = state.searchContent,
                    onClick = { vm.setSearchContent(!state.searchContent) },
                )
            } else {
                Box {
                    ToolbarIcon(
                        if (state.sort.descending) PhosphorIcons.Regular.SortDescending else PhosphorIcons.Regular.SortAscending,
                        stringResource(R.string.files_sort), { showSortMenu = true },
                    )
                    val keys = listOf(
                        SortKey.NAME to stringResource(R.string.files_sort_name),
                        SortKey.MODIFIED to stringResource(R.string.files_sort_modified),
                        SortKey.SIZE to stringResource(R.string.files_sort_size),
                        SortKey.TYPE to stringResource(R.string.files_sort_type),
                    )
                    GlassMenu(
                        show = showSortMenu,
                        entries = keys.map { (key, label) ->
                            val active = state.sort.key == key
                            MenuEntry(if (active) label + if (state.sort.descending) "  ↓" else "  ↑" else label, selected = active) { vm.sortBy(key) }
                        },
                        onDismiss = { showSortMenu = false },
                    )
                }
                Box {
                    ToolbarIcon(AppIcons.More, stringResource(R.string.files_more), { showMoreMenu = true })
                    GlassMenu(
                        show = showMoreMenu,
                        entries = listOf(
                            MenuEntry(stringResource(R.string.files_new_file)) { if (listing != null) nameDialog = "file" },
                            MenuEntry(stringResource(R.string.files_new_folder)) { if (listing != null) nameDialog = "folder" },
                            MenuEntry(stringResource(R.string.files_upload)) { if (listing != null) picker.launch(arrayOf("*/*")) },
                            MenuEntry(stringResource(R.string.files_hidden), selected = state.showHidden) { vm.toggleHidden() },
                            MenuEntry(stringResource(R.string.files_downloads_manage)) { onOpenDownloads() },
                        ),
                        onDismiss = { showMoreMenu = false },
                    )
                }
            }
        }
        // Path bar: always visible (not scrolled away with the list); every segment opens that folder.
        if (listing != null && state.results == null) {
            val crumbs = remember(listing.path, roots) { FileListing.breadcrumbs(listing.path, roots.map { Crumb(it.name, it.path) }) }
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                ToolbarIcon(PhosphorIcons.Regular.ArrowUp, stringResource(R.string.files_up), { listing.parent?.let(vm::browse) }, enabled = listing.parent != null)
                androidx.compose.runtime.key(listing.path) {
                    BreadcrumbBar(
                        items = crumbs.map { BreadcrumbItem(path = it.path, text = it.label) },
                        onItemClick = { idx -> crumbs.getOrNull(idx)?.let { if (it.path != listing.path) vm.browse(it.path) } },
                        modifier = Modifier.weight(1f).testTag("files-path"),
                    )
                }
            }
        }
        NoticeVisibility(state.navigatingTo != null && listing != null) {
            LinearProgressIndicator(progress = null, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        }
        // The transfer card folds away when it is closed, still showing what it showed.
        val transfer = rememberLast(state.transfer)
        NoticeVisibility(state.transfer != null) {
            if (transfer != null) TransferRow(transfer, vm, onOpenDownloads)
        }
        val results = state.results
        if (results != null) {
            LoadableContent(results, onRetry = vm::search) { hits ->
                LazyColumn(Modifier.fillMaxSize().testTag("search-results"), contentPadding = pageListPadding(top = 4.dp)) {
                    item(key = "h") { SmallTitle(stringResource(R.string.files_results, hits.size)) }
                    if (hits.isEmpty()) item(key = "none") { EmptyNote(stringResource(R.string.files_search_none)) }
                    itemsIndexed(hits, key = { i, h -> "$i:${h.path}:${h.line}" }) { i, h ->
                        CardRow(cardListPositionOf(i, hits.size)) {
                            BasicComponent(
                                title = h.name,
                                summary = listOfNotNull(
                                    FileListing.relativeTo(listing?.path, h.path),
                                    h.line?.let { stringResource(R.string.files_line_n, it) },
                                    h.snippet?.trim()?.take(160),
                                ).joinToString(" · "),
                                startAction = { EntryIcon(FileTypes.iconOf(h.name, isDir = false)) },
                                onClick = { vm.openPath(h.path) },
                                cardListPosition = cardListPositionOf(i, hits.size),
                            )
                        }
                    }
                }
            }
            return
        }
        when (val l = state.listing) {
            null -> Unit
            else -> LoadableContent(l, onRetry = { vm.currentDir()?.let(vm::browse) ?: vm.loadRoots() }) { list ->
                val visible = remember(list, state.query, state.sort) { FileListing.sort(FileListing.filter(list.entries, state.query), state.sort) }
                // Each folder remembers where it was scrolled to (also across opening a file), so going back lands where you left.
                val positions = vm.scrollPositions
                val listState = remember(list.path) { positions[list.path]?.let { LazyListState(it.first, it.second) } ?: LazyListState() }
                androidx.compose.runtime.DisposableEffect(list.path) {
                    onDispose { positions[list.path] = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
                }
                io.github.suqi8.coui.kmp.basic.BindScrollToTop(listState)
                val query = state.query.trim()
                io.github.suqi8.coui.kmp.basic.PullToRefresh(isRefreshing = state.refreshing, onRefresh = vm::refreshListing, topAppBarScrollBehavior = null) {
                    LazyColumn(Modifier.fillMaxSize().testTag("file-list"), state = listState, contentPadding = pageListPadding(top = 4.dp)) {
                        if (visible.isEmpty()) {
                            item(key = "empty") {
                                if (query.isEmpty()) {
                                    EmptyNote(stringResource(R.string.files_empty_folder))
                                } else {
                                    Column {
                                        EmptyNote(stringResource(R.string.files_filter_none))
                                        LinkButton(stringResource(R.string.files_search_subfolders, query), vm::search, modifier = Modifier.padding(horizontal = 14.dp))
                                    }
                                }
                            }
                        }
                        itemsIndexed(visible, key = { _, e -> e.path }) { i, e ->
                            val pos = cardListPositionOf(i, visible.size)
                            CardRow(pos) {
                                BasicComponent(
                                    title = e.name,
                                    summary = entrySummary(e),
                                    startAction = { EntryIcon(FileTypes.iconOf(e.name, e.isDir, e.sensitive)) },
                                    endActions = {
                                        if (e.sensitive) StatusPill(stringResource(R.string.new_session_sensitive), LocalDevColors.current.warn)
                                        ToolbarIcon(AppIcons.More, stringResource(R.string.files_actions, e.name), { actionsFor = e })
                                    },
                                    onClick = { vm.open(e) },
                                    onClickLabel = e.name,
                                    cardListPosition = pos,
                                )
                            }
                        }
                        if (query.isNotEmpty() && visible.isNotEmpty()) {
                            item(key = "deep") {
                                LinkButton(stringResource(R.string.files_search_subfolders, query), vm::search, modifier = Modifier.padding(horizontal = 14.dp))
                            }
                        }
                        item(key = "count") {
                            val dirs = list.entries.count { it.isDir }
                            Text(
                                stringResource(R.string.files_count, dirs, list.entries.size - dirs) +
                                    if (list.truncated || list.blocked > 0) " · " + stringResource(R.string.new_session_listing_note, list.blocked) else "",
                                style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(horizontal = PageDefaults.TextGutter, vertical = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
    actionsFor?.let { e ->
        val copied = stringResource(R.string.files_copied)
        PanelSheet(show = true, title = e.name, onDismissRequest = { actionsFor = null }) {
            val opts = buildList {
                if (!e.isDir) add(stringResource(R.string.files_download) to { actionsFor = null; vm.download(e) })
                add(stringResource(R.string.files_copy_path) to {
                    actionsFor = null
                    scope.launch { clipboard.setClipEntry(androidx.compose.ui.platform.ClipEntry(android.content.ClipData.newPlainText(e.name, e.path))) }
                    android.widget.Toast.makeText(context, copied, android.widget.Toast.LENGTH_SHORT).show()
                })
                add(stringResource(R.string.files_rename) to { actionsFor = null; target = e; nameDialog = "rename" })
                add(stringResource(R.string.files_delete) to { actionsFor = null; confirmDelete = e })
            }
            GroupCard {
                opts.forEachIndexed { i, (label, act) ->
                    BasicComponent(
                        title = label,
                        titleColor = BasicComponentDefaults.titleColor(
                            color = if (i == opts.lastIndex) LocalDevColors.current.danger else COUITheme.colorScheme.onSurface,
                        ),
                        onClick = act,
                        cardListPosition = cardListPositionOf(i, opts.size),
                    )
                }
            }
        }
    }
    nameDialog?.let { kind ->
        var name by remember(kind) { mutableStateOf(if (kind == "rename") target?.name.orEmpty() else "") }
        val invalidName = name.contains('/') || name.contains('\\')
        FormDialog(
            show = true,
            title = stringResource(if (kind == "folder") R.string.files_new_folder else if (kind == "file") R.string.files_new_file else R.string.files_rename),
            confirmText = stringResource(R.string.action_ok),
            confirmEnabled = name.trim().isNotEmpty() && !invalidName,
            onConfirm = {
                            val n = name.trim()
                            if (n.isNotEmpty() && !n.contains('/') && !n.contains('\\')) {
                                when (kind) {
                                    "folder" -> vm.mkdir(n)
                                    "file" -> vm.newFile(n)
                                    else -> target?.let { vm.rename(it, n) }
                                }
                            }
                            nameDialog = null
                        },
            onDismiss = { nameDialog = null },
        ) {
            Column {
                TextInput(name, { name = it }, stringResource(R.string.files_name))
                NoticeVisibility(invalidName) {
                    Text(stringResource(R.string.files_name_invalid), style = COUITheme.textStyles.footnote1, color = LocalDevColors.current.danger, modifier = Modifier.padding(top = 4.dp, start = 4.dp))
                }
            }

        }
    }
    confirmDelete?.let { e ->
        ConfirmDialog(true, stringResource(R.string.files_delete), stringResource(R.string.files_delete_confirm, e.name), stringResource(R.string.files_delete), { confirmDelete = null; vm.delete(e) }, { confirmDelete = null })
    }
}

/** "12 KiB · 3 min ago" for files, the modification time for folders. */
@Composable
private fun entrySummary(e: FsEntry): String? {
    val time = e.modifiedMs?.let { io.github.tianli0.switchtether.ui.common.relativeTime(it) }
    return if (e.isDir) time else listOfNotNull(Formatters.bytes(e.size), time).joinToString(" · ")
}

@Composable
private fun EntryIcon(icon: FileIcon) {
    val tint = when (icon) {
        FileIcon.FOLDER -> COUITheme.colorScheme.primary
        FileIcon.LOCKED -> LocalDevColors.current.warn
        else -> COUITheme.colorScheme.onSurfaceVariantSummary
    }
    Icon(imageVector = fileIconVector(icon), contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
}

/** One row of a card group laid out lazily (each row its own item, so folders with thousands of entries scroll smoothly). */
@Composable
private fun CardRow(position: CardListPosition, content: @Composable () -> Unit) {
    LazyGroupRow(position, bottom = 8.dp, content = content)
}

@Composable
private fun EmptyNote(text: String) {
    Text(text, style = COUITheme.textStyles.body2, color = COUITheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(horizontal = PageDefaults.TextGutter, vertical = 16.dp))
}
@Composable
private fun TransferRow(t: TransferState, vm: FilesViewModel, onOpenDownloads: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = io.github.tianli0.switchtether.ui.appContainer().fileStore
    val noApp = stringResource(R.string.viewer_no_app)
    GroupCard {
        Column(Modifier.padding(start = 16.dp, end = 6.dp, top = 14.dp, bottom = 6.dp)) {
            Text(t.label + " · " + Formatters.bytes(t.done) + (t.total?.let { " / " + Formatters.bytes(it) } ?: ""), style = COUITheme.textStyles.body2, color = COUITheme.colorScheme.onSurface)
            if (t.error == null && !t.finished) LinearProgressIndicator(progress = t.total?.let { if (it > 0) t.done.toFloat() / it else null }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp, end = 10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
                t.error?.let { Text(it, style = COUITheme.textStyles.footnote1, color = LocalDevColors.current.danger, modifier = Modifier.weight(1f)) }
                if (t.finished) Text(stringResource(R.string.files_done), style = COUITheme.textStyles.footnote1, color = LocalDevColors.current.ok, modifier = Modifier.weight(1f))
                t.downloaded?.let { r ->
                    LinkButton(stringResource(R.string.files_transfer_open), { io.github.tianli0.switchtether.ui.files.startSafely(context, store.viewIntent(store.asLocal(r)), noApp) })
                    LinkButton(stringResource(R.string.viewer_manage), onOpenDownloads)
                }
                if (t.error == null && !t.finished) LinkButton(stringResource(R.string.action_cancel), vm::cancelTransfer) else LinkButton(stringResource(R.string.action_close), vm::dismissTransfer)
            }
        }
    }
}

@Composable
private fun FileScreen(state: FilesUiState, vm: FilesViewModel) {
    BackHandler(onBack = vm::requestCloseFile)
    Column(Modifier.fillMaxSize()) {
        val f = (state.file as? Loadable.Ready)?.value
        val name = f?.path?.let(FileTypes::fileName).orEmpty()
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            ToolbarIcon(AppIcons.Back, stringResource(R.string.action_back), vm::requestCloseFile)
            Column(Modifier.weight(1f)) {
                Text(name, style = COUITheme.textStyles.body1, color = COUITheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                if (f != null) {
                    Text(
                        f.path, style = COUITheme.textStyles.footnote2, color = COUITheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1, overflow = TextOverflow.StartEllipsis,
                    )
                }
            }
            if (f != null && !state.editing) ToolbarIcon(AppIcons.Edit, stringResource(R.string.files_edit), vm::startEdit, enabled = f.text != null && !f.truncated)
            if (f != null && !state.editing) ToolbarIcon(AppIcons.Download, stringResource(R.string.files_download), { vm.download(FsEntry(name, f.path, "file", f.size)) })
            if (state.editing) {
                SmallButton(
                    text = if (state.saving) "…" else stringResource(R.string.files_save),
                    onClick = vm::save,
                    enabled = !state.saving,
                    tone = ActionTone.Primary,
                    modifier = Modifier.padding(end = 12.dp).testTag("save"),
                )
            }
        }
        state.transfer?.let { TransferRow(it, vm, {}) }
        LoadableContent(state.file!!, onRetry = { (state.file as? Loadable.Ready)?.value?.path?.let(vm::openPath) }) { file ->
            val dev = LocalDevColors.current
            Text(
                listOfNotNull(
                    file.encoding,
                    file.lineEnding.uppercase(),
                    Formatters.bytes(file.size),
                    file.modifiedMs?.let { io.github.tianli0.switchtether.ui.common.relativeTime(it) },
                    file.sha256.take(12).takeIf { it.isNotEmpty() },
                ).joinToString(" · "),
                style = DevType.label, color = dev.gutter, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
            if (state.editing) {
                BasicTextField(
                    value = state.editText,
                    onValueChange = vm::setEditText,
                    textStyle = DevType.code.copy(color = dev.codeText),
                    cursorBrush = SolidColor(COUITheme.colorScheme.primary),
                    modifier = Modifier.fillMaxSize().padding(8.dp).background(dev.codeBackground).padding(8.dp).testTag("editor"),
                )
            } else {
                TextFileBody(
                    file,
                    Modifier.fillMaxSize(),
                    onOpenPath = { vm.openPath(it) },
                    onDownload = { vm.download(FsEntry(FileTypes.fileName(file.path), file.path, "file", file.size)) },
                    onOpenBinary = { vm.openViewer(file.path, FileTypes.fileName(file.path), file.size.takeIf { it > 0 }, file.modifiedMs) },
                )
            }
        }
    }
}
@Composable
private fun ConflictScreen(state: FilesUiState, vm: FilesViewModel) {
    val c = state.conflict ?: return
    var confirmOverwrite by remember { mutableStateOf(false) }
    BackHandler { vm.dismissConflict() }
    LazyColumn(Modifier.fillMaxSize().testTag("conflict"), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { SmallTitle(stringResource(R.string.files_conflict_title)) }
        item {
            GroupCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.files_conflict_body, c.path), style = COUITheme.textStyles.body2, color = COUITheme.colorScheme.onSurface)
                    Text(stringResource(R.string.files_conflict_legend), style = DevType.label, color = LocalDevColors.current.gutter)
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                val d = c.diff
                if (d.isBlank()) Text(stringResource(R.string.files_conflict_same), color = COUITheme.colorScheme.onSurfaceVariantSummary) else DiffView(d)
            }
        }
        item {
            GroupCard(Modifier.padding(top = 12.dp)) {
                listOf(
                    stringResource(R.string.files_keep_copy, c.suggestedCopyPath.substringAfterLast('\\').substringAfterLast('/')) to vm::keepMineAsCopy,
                    stringResource(R.string.files_reload_desktop) to vm::reloadDesktop,
                    stringResource(R.string.files_manual_merge) to vm::startManualMerge,
                    stringResource(R.string.files_overwrite) to { confirmOverwrite = true },
                ).forEachIndexed { i, (label, action) ->
                    BasicComponent(title = label, onClick = action, cardListPosition = cardListPositionOf(i, 4), enabled = !state.saving)
                }
            }
        }
    }
    ConfirmDialog(confirmOverwrite, stringResource(R.string.files_overwrite), stringResource(R.string.files_overwrite_confirm), stringResource(R.string.files_overwrite), { confirmOverwrite = false; vm.overwrite() }, { confirmOverwrite = false })
}

/** Outcome messages that only report success: shown as a toast instead of a dialog. */
private val QUIET_MESSAGES = setOf(R.string.files_msg_saved, R.string.files_msg_saved_as, R.string.files_msg_ok, R.string.files_msg_cancelled)

/** Width from which an open file shows beside its folder instead of replacing it (§26). */
private val FILES_SPLIT_MIN_WIDTH = 720.dp
