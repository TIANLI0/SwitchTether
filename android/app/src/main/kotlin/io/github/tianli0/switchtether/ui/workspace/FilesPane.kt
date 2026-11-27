package io.github.tianli0.switchtether.ui.workspace

import io.github.suqi8.coui.kmp.icon.basic.Search
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.squircle.SquircleShape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import com.adamglin.phosphoricons.regular.Article
import com.adamglin.phosphoricons.regular.Code
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import io.github.tianli0.coui17.patterns.FormDialog
import io.github.tianli0.coui17.patterns.MenuEntry
import io.github.tianli0.coui17.patterns.GlassMenu
import io.github.tianli0.coui17.patterns.PageDefaults
import io.github.tianli0.coui17.patterns.ActionTone
import io.github.tianli0.coui17.patterns.LinkButton
import io.github.tianli0.coui17.patterns.SmallButton
import io.github.tianli0.switchtether.ui.icons.AppIcons

import io.github.tianli0.switchtether.ui.common.resolve

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import io.github.suqi8.coui.kmp.basic.CardListPosition
import io.github.tianli0.switchtether.files.Crumb
import io.github.tianli0.switchtether.files.FileIcon
import io.github.tianli0.switchtether.files.FileListing
import io.github.tianli0.switchtether.files.FileTypes
import io.github.tianli0.switchtether.files.SortKey
import io.github.tianli0.switchtether.files.SortOrder
import io.github.tianli0.switchtether.ui.files.TextFileBody
import io.github.tianli0.switchtether.ui.files.fileIconVector
import kotlinx.coroutines.launch
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.basic.BasicComponentDefaults
import io.github.suqi8.coui.kmp.basic.DropdownImpl
import io.github.suqi8.coui.kmp.basic.ExpandArrow
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.LinearProgressIndicator
import io.github.suqi8.coui.kmp.basic.ListPopupColumn
import io.github.suqi8.coui.kmp.basic.LocalSearchBarKeyboardType
import io.github.suqi8.coui.kmp.basic.SearchBar
import io.github.suqi8.coui.kmp.basic.SearchBarActionButton
import io.github.suqi8.coui.kmp.basic.SearchBarDefaults
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
import io.github.tianli0.switchtether.ui.common.rememberPredictiveBack
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
private fun FilesSortEntry(sort: SortOrder, onSort: (SortKey) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val keys = listOf(
        SortKey.NAME to stringResource(R.string.files_sort_name),
        SortKey.MODIFIED to stringResource(R.string.files_sort_modified),
        SortKey.SIZE to stringResource(R.string.files_sort_size),
        SortKey.TYPE to stringResource(R.string.files_sort_type),
    )
    val direction = if (sort.descending) " ↓" else " ↑"
    val activeLabel = keys.first { it.first == sort.key }.second
    Box {
        Row(
            modifier = Modifier
                .widthIn(max = 140.dp)
                .heightIn(min = 36.dp)
                .clip(SquircleShape(8.dp))
                .clickable(role = Role.Button, onClick = { open = true })
                .padding(horizontal = 12.dp)
                .testTag("files-sort"),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.files_sort_current, activeLabel + direction),
                style = COUITheme.textStyles.body2,
                color = COUITheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f, fill = false),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            ExpandArrow(expanded = open, modifier = Modifier.size(16.dp))
        }
        GlassMenu(
            show = open,
            entries = keys.map { (key, label) ->
                val active = key == sort.key
                MenuEntry(if (active) label + direction else label, selected = active) { onSort(key) }
            },
            onDismiss = { open = false },
        )
    }
}

@Composable
fun FilesPane(state: FilesUiState, vm: FilesViewModel, onOpenDownloads: () -> Unit = {}, onBack: (() -> Unit)? = null) {
    val file = state.file
    val viewer = state.viewer
    val opened: (@Composable () -> Unit)? = when {
        viewer != null -> {
            {
                val back = rememberPredictiveBack(onBack = vm::closeViewer)
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().then(back)) {
                    RemoteFileViewer(
                        ref = RemoteFileRef(viewer.path, viewer.name, viewer.kind, viewer.size, viewer.modifiedMs),
                        onBack = vm::closeViewer,
                        onManageDownloads = onOpenDownloads,
                    )
                }
            }
        }
        state.conflict != null -> { { ConflictScreen(state, vm) } }
        file != null -> { { FileScreen(state, vm, onOpenDownloads) } }
        else -> null
    }
    // Wide enough (a tablet's detail pane, §27): the folder stays on the left while a file is open.
    // The opened file's back handler is composed after the browser's, so back closes the file first.
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        when {
            opened == null -> Browser(state, vm, onOpenDownloads, onBack)
            maxWidth >= FILES_SPLIT_MIN_WIDTH -> io.github.tianli0.switchtether.ui.nav.ListDetailPanes(
                list = { Browser(state, vm, onOpenDownloads, onBack) },
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

/**
 * The folder browser, laid out like ColorOS 文件管理: its own header (back, the folder's name in large
 * type over "共 N 项", and three round keys: new, search, ⋮), a plain-text breadcrumb "全部文件 › … ›
 * current" with the current folder in the accent colour, and flat rows on the page — a large folder or
 * file-type icon, the name, "size | date", a chevron for folders — with dividers that start at the
 * text. Long-press a row for its actions. The path row includes the sort selector, and the search
 * toolbar replaces the folder header.
 *
 * [onBack] leaves the Files page; back first closes search, then goes up a folder,
 * then shows "全部文件" (the computer's shared roots), and only then leaves.
 */
@Composable
private fun Browser(state: FilesUiState, vm: FilesViewModel, onOpenDownloads: () -> Unit, onBack: (() -> Unit)? = null) {
    var nameDialog by rememberSaveable { mutableStateOf<String?>(null) } // "file" | "folder" | "rename"
    var target by rememberSaveable(stateSaver = FsEntrySaver) { mutableStateOf<FsEntry?>(null) }
    var confirmDelete by rememberSaveable(stateSaver = FsEntrySaver) { mutableStateOf<FsEntry?>(null) }
    var actionsFor by remember { mutableStateOf<FsEntry?>(null) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showNewMenu by remember { mutableStateOf(false) }
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    // "全部文件": the list of the computer's shared roots instead of a folder.
    var showRoots by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::upload) }
    val listing = (state.listing as? Loadable.Ready)?.value
    val roots = (state.roots as? Loadable.Ready)?.value.orEmpty()
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboard.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val searching = searchExpanded || state.query.isNotEmpty() || state.results != null
    val atRootTop = listing != null && roots.any { FileTypes.samePath(it.path, listing.path) }
    val closeSearch: () -> Unit = {
        vm.clearSearch()
        searchExpanded = false
    }
    val goBack: () -> Unit = {
        when {
            searching -> closeSearch()
            showRoots -> onBack?.invoke()
            atRootTop && roots.isNotEmpty() -> showRoots = true
            listing?.parent != null -> vm.browse(listing.parent!!)
            roots.isNotEmpty() -> showRoots = true
            else -> onBack?.invoke()
        }
    }
    val back = rememberPredictiveBack(
        enabled = searching || (!showRoots && (listing?.parent != null || roots.isNotEmpty())),
        onBack = goBack,
    )
    val allLabel = stringResource(R.string.files_all)
    Column(Modifier.fillMaxSize().then(back)) {
        if (searching && !showRoots) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CompositionLocalProvider(LocalSearchBarKeyboardType provides KeyboardType.Ascii) {
                    SearchBar(
                        query = state.query,
                        onQueryChange = vm::setQuery,
                        onSearch = { vm.search() },
                        expanded = true,
                        onExpandedChange = { if (it) searchExpanded = true else closeSearch() },
                        label = stringResource(R.string.files_filter_hint),
                        cancelText = stringResource(R.string.action_cancel),
                        onCancel = closeSearch,
                        insideMargin = DpSize(0.dp, 6.dp),
                        clearContentDescription = stringResource(R.string.files_filter_clear),
                        leadingIcon = {
                            Icon(
                                imageVector = COUIIcons.Basic.Search,
                                contentDescription = null,
                                tint = SearchBarDefaults.searchBarColors().iconColor,
                                modifier = Modifier.padding(
                                    start = SearchBarDefaults.LeadingIconStartPadding,
                                    end = SearchBarDefaults.LeadingIconEndPadding,
                                ),
                            )
                        },
                        modifier = Modifier.weight(1f).testTag("files-filter"),
                    )
                }
                SearchBarActionButton(
                    onClick = { vm.setSearchContent(!state.searchContent) },
                    modifier = Modifier.size(40.dp).semantics { selected = state.searchContent },
                ) {
                    Icon(
                        PhosphorIcons.Regular.Article,
                        contentDescription = stringResource(R.string.files_search_content),
                        tint = if (state.searchContent) COUITheme.colorScheme.primary else COUITheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        } else {
            // Header: back, folder name over "共 N 项", then + / search / ⋮.
            val title = if (showRoots || listing == null) allLabel else FileTypes.fileName(listing.path).ifBlank { listing.path }
            val count = if (showRoots) roots.size else listing?.entries?.size
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) {
                    io.github.tianli0.coui17.patterns.BackArrow(goBack)
                    Spacer(Modifier.size(12.dp))
                } else {
                    Spacer(Modifier.size(8.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        style = COUITheme.textStyles.main.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
                        color = COUITheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (count != null) {
                        Text(
                            pluralStringResource(R.plurals.files_item_total, count, count),
                            style = COUITheme.textStyles.footnote1,
                            color = COUITheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 1,
                        )
                    }
                }
                Spacer(Modifier.size(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!showRoots) {
                        Box {
                            ToolbarIcon(AppIcons.Add, stringResource(R.string.files_new), { showNewMenu = true }, enabled = listing != null)
                            GlassMenu(
                                show = showNewMenu,
                                entries = listOf(
                                    MenuEntry(stringResource(R.string.files_new_folder)) { if (listing != null) nameDialog = "folder" },
                                    MenuEntry(stringResource(R.string.files_new_file)) { if (listing != null) nameDialog = "file" },
                                    MenuEntry(stringResource(R.string.files_upload)) { if (listing != null && !vm.refuseIfTransferring()) picker.launch(arrayOf("*/*")) },
                                ),
                                onDismiss = { showNewMenu = false },
                            )
                        }
                        ToolbarIcon(
                            AppIcons.Search,
                            stringResource(R.string.files_search_open),
                            { searchExpanded = true },
                            enabled = listing != null,
                        )
                    }
                    Box {
                        ToolbarIcon(AppIcons.More, stringResource(R.string.files_more), { showMoreMenu = true })
                        GlassMenu(
                            show = showMoreMenu,
                            entries = buildList {
                                if (!showRoots) {
                                    add(MenuEntry(stringResource(R.string.files_hidden), selected = state.showHidden) { vm.toggleHidden() })
                                }
                                add(MenuEntry(stringResource(R.string.files_downloads_manage)) { onOpenDownloads() })
                            },
                            onDismiss = { showMoreMenu = false },
                        )
                    }
                }
            }
        }
        // Breadcrumb as plain text: "全部文件 › … › current", the current folder in the accent colour.
        if (!searching || showRoots) {
            val rootCrumbs = roots.map { r -> Crumb(rootLabel(r), r.path) }
            val crumbs = remember(listing?.path, rootCrumbs, showRoots) {
                if (showRoots || listing == null) emptyList() else FileListing.breadcrumbs(listing.path, rootCrumbs)
            }
            val crumbScroll = androidx.compose.foundation.rememberScrollState()
            LaunchedEffect(crumbs) { crumbScroll.scrollTo(crumbScroll.maxValue) }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.weight(1f).horizontalScroll(crumbScroll).padding(vertical = 6.dp).testTag("files-path"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val items = listOf(Crumb(allLabel, "")) + crumbs
                    items.forEachIndexed { i, c ->
                        val last = i == items.lastIndex
                        if (i > 0) {
                            Icon(
                                AppIcons.ChevronRight, contentDescription = null,
                                tint = COUITheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.6f),
                                modifier = Modifier.padding(horizontal = 4.dp).size(14.dp),
                            )
                        }
                        Text(
                            c.label,
                            style = COUITheme.textStyles.body2,
                            color = if (last) COUITheme.colorScheme.primary else COUITheme.colorScheme.onSurface,
                            maxLines = 1,
                            modifier = Modifier
                                .clip(SquircleShape(6.dp))
                                .then(
                                    if (last) Modifier else Modifier.clickable {
                                        if (i == 0) {
                                            showRoots = true
                                        } else {
                                            showRoots = false
                                            vm.browse(c.path)
                                        }
                                    },
                                )
                                .padding(horizontal = 2.dp, vertical = 4.dp),
                        )
                    }
                }
                if (!showRoots && listing != null) FilesSortEntry(sort = state.sort, onSort = vm::sortBy)
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
        if (showRoots) {
            LazyColumn(Modifier.fillMaxSize().testTag("files-roots"), contentPadding = pageListPadding(top = 4.dp)) {
                itemsIndexed(roots, key = { _, r -> r.path }) { i, r ->
                    FlatRow(
                        icon = FileIcon.FOLDER,
                        title = rootLabel(r),
                        summary = r.path,
                        chevron = true,
                        divider = i < roots.lastIndex,
                        onClick = {
                            showRoots = false
                            vm.browse(r.path)
                        },
                    )
                }
            }
            return@Column
        }
        val results = state.results
        if (results != null) {
            LoadableContent(results, onRetry = vm::search) { hits ->
                LazyColumn(Modifier.fillMaxSize().testTag("search-results"), contentPadding = pageListPadding(top = 4.dp)) {
                    item(key = "h") { SmallTitle(stringResource(R.string.files_results, hits.size)) }
                    if (hits.isEmpty()) item(key = "none") { EmptyNote(stringResource(R.string.files_search_none)) }
                    itemsIndexed(hits, key = { i, h -> "$i:${h.path}:${h.line}" }) { i, h ->
                        // The name is the title already: the summary is where it is and the matched line.
                        val folder = FileListing.relativeTo(listing?.path, h.path)?.let { rel ->
                            rel.removeSuffix(h.name).trimEnd('/', '\\').ifBlank { null }
                        }
                        FlatRow(
                            icon = FileTypes.iconOf(h.name, isDir = false),
                            title = h.name,
                            summary = listOfNotNull(
                                folder,
                                h.line?.let { stringResource(R.string.files_line_n, it) },
                            ).joinToString(" | ").ifBlank { null },
                            snippet = h.snippet?.trim()?.take(160),
                            chevron = false,
                            divider = i < hits.lastIndex,
                            onClick = { vm.openPath(h.path) },
                        )
                    }
                }
            }
            return@Column
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
                        if (query.isNotEmpty() && visible.isNotEmpty()) {
                            // While filtering, how many of the folder's entries match (the header keeps the total).
                            item(key = "matches") {
                                Text(
                                    pluralStringResource(R.plurals.files_filter_matches, visible.size, visible.size, list.entries.size),
                                    style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.padding(horizontal = PageDefaults.TextGutter, vertical = 4.dp),
                                )
                            }
                        }
                        itemsIndexed(visible, key = { _, e -> e.path }) { i, e ->
                            FlatRow(
                                icon = FileTypes.iconOf(e.name, e.isDir, e.sensitive),
                                title = e.name,
                                summary = entrySummary(e),
                                chevron = e.isDir,
                                divider = i < visible.lastIndex,
                                warnPill = if (e.sensitive) stringResource(R.string.new_session_sensitive) else null,
                                onClick = { vm.open(e) },
                                onLongClick = { actionsFor = e },
                                longClickLabel = stringResource(R.string.files_actions, e.name),
                            )
                        }
                        if (query.isNotEmpty() && visible.isNotEmpty()) {
                            item(key = "deep") {
                                LinkButton(stringResource(R.string.files_search_subfolders, query), vm::search, modifier = Modifier.padding(horizontal = 14.dp))
                            }
                        }
                        if (list.truncated || list.blocked > 0) {
                            item(key = "note") {
                                Text(
                                    stringResource(R.string.new_session_listing_note, list.blocked),
                                    style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.padding(horizontal = PageDefaults.TextGutter, vertical = 8.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    actionsFor?.let { e ->
        val copied = stringResource(R.string.files_copied)
        PanelSheet(show = true, title = e.name, onDismissRequest = { actionsFor = null }, imePadding = false) {
            val opts = buildList {
                if (!e.isDir && !state.transferRunning) add(stringResource(R.string.files_download) to { actionsFor = null; vm.download(e) })
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
            Spacer(Modifier.height(16.dp))
        }
    }
    nameDialog?.let { kind ->
        var name by rememberSaveable(kind) { mutableStateOf(if (kind == "rename") target?.name.orEmpty() else "") }
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

/** "12 KiB | 5月30日" for files, the date for folders (the computer does not report item counts). */
@Composable
private fun entrySummary(e: FsEntry): String? {
    val date = e.modifiedMs?.let { shortDate(it) }
    return if (e.isDir) date else listOfNotNull(Formatters.bytes(e.size), date).joinToString(" | ")
}

/** "5月30日" this year, "2025年5月30日" before, "14:05" today, in the phone's locale. */
private fun shortDate(epochMs: Long): String {
    val locale = java.util.Locale.getDefault()
    val then = java.util.Calendar.getInstance().apply { timeInMillis = epochMs }
    val now = java.util.Calendar.getInstance()
    val skeleton = when {
        then.get(java.util.Calendar.YEAR) != now.get(java.util.Calendar.YEAR) -> "yMMMd"
        then.get(java.util.Calendar.DAY_OF_YEAR) == now.get(java.util.Calendar.DAY_OF_YEAR) -> "Hm"
        else -> "MMMd"
    }
    val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton)
    return java.text.SimpleDateFormat(pattern, locale).format(java.util.Date(epochMs))
}

/** The large entry icon of a flat row: a filled folder in the accent colour, or the file-type glyph on a soft tile. */
@Composable
private fun EntryIcon(icon: FileIcon) {
    val accent = COUITheme.colorScheme.primary
    if (icon == FileIcon.FOLDER) {
        FolderGlyph(accent, Modifier.size(width = 44.dp, height = 36.dp))
        return
    }
    val tint = when (icon) {
        FileIcon.LOCKED -> LocalDevColors.current.warn
        else -> COUITheme.colorScheme.onSurfaceVariantSummary
    }
    Box(
        Modifier.size(width = 44.dp, height = 44.dp).clip(SquircleShape(10.dp)).background(tint.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = fileIconVector(icon), contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
    }
}

/** A plain filled folder (tab + body), drawn in [color] — our own shape, no bundled artwork. */
@Composable
private fun FolderGlyph(color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val w = size.width
        val h = size.height
        val r = androidx.compose.ui.geometry.CornerRadius(w * 0.09f)
        // Tab, slightly darker, behind the body.
        drawRoundRect(color.copy(alpha = 0.85f), topLeft = androidx.compose.ui.geometry.Offset(0f, 0f), size = androidx.compose.ui.geometry.Size(w * 0.42f, h * 0.4f), cornerRadius = r)
        drawRoundRect(color.copy(alpha = 0.55f).compositeOver(androidx.compose.ui.graphics.Color.White), topLeft = androidx.compose.ui.geometry.Offset(0f, h * 0.14f), size = androidx.compose.ui.geometry.Size(w, h * 0.86f), cornerRadius = r)
        drawRoundRect(color.copy(alpha = 0.75f), topLeft = androidx.compose.ui.geometry.Offset(w * 0.1f, h * 0.3f), size = androidx.compose.ui.geometry.Size(w * 0.16f, h * 0.06f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(h * 0.03f))
    }
}

/**
 * One flat entry row on the page (no card): large icon, name, "size | date" summary (an optional
 * matched-line snippet under it), an optional chevron, and a divider that starts at the text.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun FlatRow(
    icon: FileIcon,
    title: String,
    summary: String?,
    chevron: Boolean,
    divider: Boolean,
    onClick: () -> Unit,
    snippet: String? = null,
    /** A warning pill before the chevron (sensitive files). */
    warnPill: String? = null,
    onLongClick: (() -> Unit)? = null,
    longClickLabel: String? = null,
) {
    Column(
        Modifier.fillMaxWidth()
            .combinedClickable(onClick = onClick, onClickLabel = title, onLongClick = onLongClick, onLongClickLabel = longClickLabel),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(start = PageDefaults.TextGutter, end = 16.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(width = 44.dp, height = 44.dp), contentAlignment = Alignment.Center) { EntryIcon(icon) }
            Spacer(Modifier.size(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = COUITheme.textStyles.body1, color = COUITheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (!summary.isNullOrBlank()) {
                    Text(summary, style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                }
                if (!snippet.isNullOrBlank()) {
                    Text(snippet, style = DevType.label, color = COUITheme.colorScheme.onSurfaceVariantSummary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                }
            }
            warnPill?.let {
                Spacer(Modifier.size(8.dp))
                StatusPill(it, LocalDevColors.current.warn)
            }
            if (chevron) {
                Spacer(Modifier.size(8.dp))
                Icon(AppIcons.ChevronRight, contentDescription = null, tint = COUITheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.6f), modifier = Modifier.size(18.dp))
            }
        }
        if (divider) {
            // From the text, not under the icon (ColorOS list divider).
            Box(
                Modifier.fillMaxWidth().padding(start = PageDefaults.TextGutter + 60.dp, end = 16.dp).height(0.5.dp)
                    .background(COUITheme.colorScheme.onSurface.copy(alpha = 0.08f)),
            )
        }
    }
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
                t.error?.let { Text(it.resolve(), style = COUITheme.textStyles.footnote1, color = LocalDevColors.current.danger, modifier = Modifier.weight(1f)) }
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
private fun FileScreen(state: FilesUiState, vm: FilesViewModel, onOpenDownloads: () -> Unit) {
    val back = rememberPredictiveBack(onBack = vm::requestCloseFile)
    Column(Modifier.fillMaxSize().then(back)) {
        val f = (state.file as? Loadable.Ready)?.value
        val name = f?.path?.let(FileTypes::fileName).orEmpty()
        val controls = io.github.tianli0.switchtether.ui.files.rememberCodeViewerState(f?.path.orEmpty())
        val markdown = f?.text != null && FileTypes.isMarkdown(f.path)
        val rendered = markdown && io.github.tianli0.switchtether.ui.files.MarkdownPreviewMode.rendered
        // Lines of code are searchable; rendered Markdown is not (search the source instead).
        val codeShown = f?.text != null && !rendered && !state.editing
        var menuOpen by remember { mutableStateOf(false) }
        val context = LocalContext.current
        val clipboard = androidx.compose.ui.platform.LocalClipboard.current
        val scope = rememberCoroutineScope()
        // One title bar for the open file, like a file viewer's app bar (Material Files, Squircle):
        // back, name, "folder · size · modified", a quiet "kind · lines · encoding" line, and at
        // most three keys — Markdown rich text / source, find, and ⋮ for the rest. It used to be
        // a second back bar under the Files page's own, plus two loose meta rows and keys in
        // three places.
        io.github.tianli0.switchtether.ui.files.FilePreviewHeader(
            title = name,
            subtitle = f?.let { file ->
                listOfNotNull(
                    io.github.tianli0.switchtether.ui.files.previewFolder(file.path),
                    Formatters.bytes(file.size),
                    file.modifiedMs?.let { io.github.tianli0.switchtether.ui.common.relativeTime(it) },
                ).joinToString(" · ")
            },
            meta = f?.text?.let { text ->
                listOfNotNull(
                    io.github.tianli0.switchtether.ui.files.CodeHighlighting.label(f.path, f.language)?.replaceFirstChar { it.uppercase() },
                    stringResource(R.string.files_lines, text.count { it == '\n' } + 1),
                    f.encoding.uppercase(),
                    f.lineEnding.uppercase(),
                ).joinToString(" · ")
            },
            onBack = vm::requestCloseFile,
        ) {
            if (state.editing) {
                SmallButton(
                    text = if (state.saving) "…" else stringResource(R.string.files_save),
                    onClick = vm::save,
                    enabled = !state.saving,
                    tone = ActionTone.Primary,
                    modifier = Modifier.testTag("save"),
                )
            } else if (f != null) {
                if (markdown) {
                    ToolbarIcon(
                        if (rendered) com.adamglin.PhosphorIcons.Regular.Code else com.adamglin.PhosphorIcons.Regular.Article,
                        stringResource(if (rendered) R.string.files_md_show_source else R.string.files_md_show_rendered),
                        { io.github.tianli0.switchtether.ui.files.MarkdownPreviewMode.rendered = !rendered },
                    )
                }
                if (codeShown) {
                    ToolbarIcon(AppIcons.Search, stringResource(R.string.files_find), { controls.findOpen = !controls.findOpen })
                }
                Box {
                    ToolbarIcon(AppIcons.More, stringResource(R.string.files_more), { menuOpen = true })
                    val copied = stringResource(R.string.files_copied)
                    val editLabel = stringResource(R.string.files_edit)
                    val downloadLabel = stringResource(R.string.files_download)
                    val wrapLabel = stringResource(if (controls.wrap) R.string.command_scroll else R.string.command_wrap)
                    val gotoLabel = stringResource(R.string.files_goto_line)
                    val largerLabel = stringResource(R.string.files_text_larger)
                    val smallerLabel = stringResource(R.string.files_text_smaller)
                    val copyAllLabel = stringResource(R.string.files_copy_all)
                    val entries = buildList {
                        add(io.github.tianli0.coui17.patterns.MenuEntry(editLabel, onClick = vm::startEdit))
                        if (!state.transferRunning) {
                            add(io.github.tianli0.coui17.patterns.MenuEntry(downloadLabel, onClick = { vm.download(FsEntry(name, f.path, "file", f.size)) }))
                        }
                        if (codeShown) {
                            add(io.github.tianli0.coui17.patterns.MenuEntry(wrapLabel, onClick = { controls.wrap = !controls.wrap }))
                            add(io.github.tianli0.coui17.patterns.MenuEntry(gotoLabel, onClick = { controls.gotoOpen = true }))
                            add(io.github.tianli0.coui17.patterns.MenuEntry(largerLabel, onClick = controls::larger))
                            add(io.github.tianli0.coui17.patterns.MenuEntry(smallerLabel, onClick = controls::smaller))
                        }
                        f.text?.let { text ->
                            add(
                                io.github.tianli0.coui17.patterns.MenuEntry(copyAllLabel, onClick = {
                                    scope.launch { clipboard.setClipEntry(androidx.compose.ui.platform.ClipEntry(android.content.ClipData.newPlainText(name, text))) }
                                    android.widget.Toast.makeText(context, copied, android.widget.Toast.LENGTH_SHORT).show()
                                }),
                            )
                        }
                    }
                    io.github.tianli0.coui17.patterns.GlassMenu(show = menuOpen, onDismiss = { menuOpen = false }, entries = entries)
                }
            }
        }
        state.transfer?.let { TransferRow(it, vm, onOpenDownloads) }
        // Text scrolling up fades out under the title bar instead of being sliced right below the
        // "kind · lines · encoding" line, where the cut half-line read as part of the header.
        Box(Modifier.fillMaxSize().topEdgeFade(14.dp)) {
        LoadableContent(state.file!!, onRetry = { (state.file as? Loadable.Ready)?.value?.path?.let(vm::openPath) }) { file ->
            val dev = LocalDevColors.current
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
                    controls = controls,
                )
            }
        }
        }
    }
}

/** Fades the top [height] of the content to transparent (offscreen layer, DstIn gradient). */
private fun Modifier.topEdgeFade(height: androidx.compose.ui.unit.Dp): Modifier =
    graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val h = height.toPx().coerceAtMost(size.height)
            drawRect(
                androidx.compose.ui.graphics.Brush.verticalGradient(0f to androidx.compose.ui.graphics.Color.Transparent, 1f to androidx.compose.ui.graphics.Color.Black, startY = 0f, endY = h),
                size = androidx.compose.ui.geometry.Size(size.width, h),
                blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
            )
        }

@Composable
private fun ConflictScreen(state: FilesUiState, vm: FilesViewModel) {
    val c = state.conflict ?: return
    var confirmOverwrite by remember { mutableStateOf(false) }
    val back = rememberPredictiveBack(onBack = vm::dismissConflict)
    LazyColumn(Modifier.fillMaxSize().then(back).testTag("conflict"), contentPadding = PaddingValues(bottom = 24.dp)) {
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

/** Width from which an open file shows beside its folder instead of replacing it (§27). */
internal val FILES_SPLIT_MIN_WIDTH = 720.dp

/** Keeps a dialog's file entry across rotation / process death (the entry is plain protocol data). */
private val FsEntrySaver = androidx.compose.runtime.saveable.Saver<FsEntry?, String>(
    save = { e -> e?.let { io.github.tianli0.switchtether.remote.protocol.ProtocolJson.encodeToString(FsEntry.serializer(), it) } },
    restore = { runCatching { io.github.tianli0.switchtether.remote.protocol.ProtocolJson.decodeFromString(FsEntry.serializer(), it) }.getOrNull() },
)

/**
 * How a shared root is named on the phone: the system folders in the phone's language ("Desktop" →
 * "桌面"), a project root named by its full path as its folder name ("SwitchTether", not
 * "d:\\develop\\SwitchTether"), anything else as the desktop named it.
 */
@Composable
internal fun rootLabel(r: io.github.tianli0.switchtether.remote.protocol.FsEntry): String {
    io.github.tianli0.switchtether.ui.sessions.wellKnownFolderLabel(r.name)?.let { return it }
    val looksLikePath = r.name.isBlank() || r.name.contains('\\') || r.name.contains('/')
    return if (looksLikePath) FileTypes.fileName(r.path).ifBlank { r.path } else r.name
}
