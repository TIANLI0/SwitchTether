package io.github.tianli0.switchtether.ui.workspace

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
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
import io.github.suqi8.coui.kmp.basic.Button
import io.github.suqi8.coui.kmp.basic.ButtonDefaults
import io.github.suqi8.coui.kmp.basic.ChipTabData
import io.github.suqi8.coui.kmp.basic.ChipTabGroup
import io.github.suqi8.coui.kmp.basic.DropdownImpl
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.LinearProgressIndicator
import io.github.suqi8.coui.kmp.basic.ListPopupColumn
import io.github.suqi8.coui.kmp.basic.SearchBar
import io.github.suqi8.coui.kmp.basic.SmallTitle
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.basic.cardListPositionOf
import io.github.suqi8.coui.kmp.layout.DialogButtonBar
import io.github.suqi8.coui.kmp.layout.DialogButtonBarAction
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.window.WindowDialog
import io.github.suqi8.coui.kmp.window.WindowListPopup
import io.github.tianli0.switchtether.R
import io.github.tianli0.switchtether.remote.protocol.FsEntry
import io.github.tianli0.switchtether.ui.code.CodeBlockView
import io.github.tianli0.switchtether.ui.code.SyntaxHighlighter
import io.github.tianli0.switchtether.ui.common.AppBottomSheet
import io.github.tianli0.switchtether.ui.common.ConfirmDialog
import io.github.tianli0.switchtether.ui.common.Formatters
import io.github.tianli0.switchtether.ui.common.GroupCard
import io.github.tianli0.switchtether.ui.common.Loadable
import io.github.tianli0.switchtether.ui.common.LoadableContent
import io.github.tianli0.switchtether.ui.common.LocalBottomBarSpace
import io.github.tianli0.switchtether.ui.common.TextInput
import io.github.tianli0.switchtether.ui.common.ToolbarIcon
import io.github.tianli0.switchtether.ui.common.pageListPadding
import io.github.tianli0.switchtether.ui.components.FilterChip
import io.github.tianli0.switchtether.ui.components.StatusPill
import io.github.tianli0.switchtether.ui.diff.DiffView
import io.github.tianli0.switchtether.ui.files.RemoteFileRef
import io.github.tianli0.switchtether.ui.files.RemoteFileViewer
import io.github.tianli0.switchtether.ui.theme.DevType
import io.github.tianli0.switchtether.ui.theme.LocalDevColors

@Composable
fun FilesPane(state: FilesUiState, vm: FilesViewModel, onOpenDownloads: () -> Unit = {}) {
    val file = state.file
    val viewer = state.viewer
    when {
        viewer != null -> {
            BackHandler(onBack = vm::closeViewer)
            RemoteFileViewer(
                ref = RemoteFileRef(viewer.path, viewer.name, viewer.kind, viewer.size, viewer.modifiedMs),
                onBack = vm::closeViewer,
                onManageDownloads = onOpenDownloads,
            )
        }
        state.conflict != null -> ConflictScreen(state, vm)
        file != null -> FileScreen(state, vm)
        else -> Browser(state, vm, onOpenDownloads)
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
            io.github.suqi8.coui.kmp.window.WindowAlertDialog(
                show = true,
                title = stringResource(R.string.files_title),
                summary = msg.resolve(),
                confirmText = stringResource(R.string.action_ok),
                onConfirm = vm::clearMessage,
                onCancel = vm::clearMessage,
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
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::upload) }
    val listing = (state.listing as? Loadable.Ready)?.value
    // Back closes search results first, then goes up a folder.
    BackHandler(enabled = state.results != null || listing?.parent != null) {
        if (state.results != null) {
            vm.clearSearch()
            searchExpanded = false
        } else {
            listing?.parent?.let(vm::browse)
        }
    }
    Column(Modifier.fillMaxSize()) {
        (state.roots as? Loadable.Ready)?.value?.takeIf { it.size > 1 }?.let { roots ->
            val currentPath = listing?.path.orEmpty()
            val selectedRoot = roots.indexOfFirst { currentPath == it.path || currentPath.startsWith(it.path.trimEnd('/', '\\') + "/") || currentPath.startsWith(it.path.trimEnd('/', '\\') + "\\") }.coerceAtLeast(0)
            ChipTabGroup(
                tabs = roots.map { ChipTabData(it.name.ifBlank { it.path }) },
                selectedIndex = selectedRoot,
                onSelectedChange = { vm.browse(roots[it].path) },
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
                label = stringResource(R.string.files_search),
                cancelText = stringResource(R.string.action_cancel),
                onCancel = {
                    vm.clearSearch()
                    searchExpanded = false
                },
                insideMargin = DpSize(0.dp, 4.dp),
                modifier = Modifier.weight(1f),
            )
            if (searchExpanded || state.query.isNotEmpty()) {
                Spacer(Modifier.size(6.dp))
                FilterChip(
                    label = stringResource(R.string.files_search_content),
                    selected = state.searchContent,
                    onClick = { vm.setSearchContent(!state.searchContent) },
                )
            } else {
                Box {
                    ToolbarIcon(AppIcons.More, stringResource(R.string.files_more), { showMoreMenu = true })
                    WindowListPopup(show = showMoreMenu, onDismissRequest = { showMoreMenu = false }) {
                        val menuItems = listOf(
                            stringResource(R.string.files_new_file) to { if (listing != null) nameDialog = "file" },
                            stringResource(R.string.files_new_folder) to { if (listing != null) nameDialog = "folder" },
                            stringResource(R.string.files_upload) to { if (listing != null) picker.launch(arrayOf("*/*")) },
                            stringResource(R.string.files_hidden) to { vm.toggleHidden() },
                            stringResource(R.string.files_downloads_manage) to onOpenDownloads,
                        )
                        ListPopupColumn {
                            menuItems.forEachIndexed { idx, (label, act) ->
                                DropdownImpl(
                                    text = label,
                                    optionSize = menuItems.size,
                                    isSelected = idx == 3 && state.showHidden,
                                    index = idx,
                                    onSelectedIndexChange = {
                                        showMoreMenu = false
                                        act()
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
        state.transfer?.let { TransferRow(it, vm, onOpenDownloads) }
        val results = state.results
        if (results != null) {
            LoadableContent(results, onRetry = vm::search) { hits ->
                LazyColumn(Modifier.fillMaxSize(), contentPadding = pageListPadding(top = 4.dp)) {
                    item { SmallTitle(stringResource(R.string.files_results, hits.size)) }
                    item {
                        GroupCard {
                            hits.forEachIndexed { i, h ->
                                BasicComponent(
                                    title = h.name, summary = listOfNotNull(h.path, h.line?.let { "L$it" }, h.snippet).joinToString(" · "),
                                    onClick = { vm.openPath(h.path) }, cardListPosition = cardListPositionOf(i, hits.size),
                                )
                            }
                        }
                    }
                }
            }
            return
        }
        when (val l = state.listing) {
            null -> Unit
            else -> LoadableContent(l, onRetry = { vm.currentDir()?.let(vm::browse) ?: vm.loadRoots() }) { list ->
                val crumbs = remember(list.path) { buildBreadcrumbs(list.path) }
                io.github.suqi8.coui.kmp.basic.PullToRefresh(isRefreshing = state.refreshing, onRefresh = vm::refreshListing, topAppBarScrollBehavior = null) {
                LazyColumn(Modifier.fillMaxSize().testTag("file-list"), contentPadding = pageListPadding(top = 4.dp)) {
                    if (crumbs.isNotEmpty()) {
                        item(key = "crumbs") {
                            BreadcrumbBar(
                                items = crumbs.map { BreadcrumbItem(path = it.second, text = it.first) },
                                onItemClick = { idx -> vm.browse(crumbs[idx].second) },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            )
                        }
                    }
                    item {
                        GroupCard {
                            val rows = listOfNotNull(list.parent?.let { FsEntry("..", it, "dir") }) + list.entries
                            rows.forEachIndexed { i, e ->
                                BasicComponent(
                                    title = e.name,
                                    summary = if (e.isDir) null else listOfNotNull(Formatters.bytes(e.size), e.modifiedMs?.let { io.github.tianli0.switchtether.ui.common.relativeTime(it) }).joinToString(" · "),
                                    startAction = {
                                        Icon(
                                            imageVector = if (e.isDir) AppIcons.Workspace else AppIcons.File,
                                            contentDescription = null,
                                            tint = if (e.isDir) COUITheme.colorScheme.primary else COUITheme.colorScheme.onSurfaceVariantSummary,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    },
                                    endActions = {
                                        if (e.sensitive) StatusPill(stringResource(R.string.new_session_sensitive), LocalDevColors.current.warn)
                                        if (e.name != "..") ToolbarIcon(AppIcons.More, stringResource(R.string.files_actions, e.name), { actionsFor = e })
                                    },
                                    onClick = { if (e.name == "..") vm.browse(e.path) else vm.open(e) },
                                    onClickLabel = e.name,
                                    cardListPosition = cardListPositionOf(i, rows.size),
                                )
                            }
                        }
                    }
                    if (list.truncated || list.blocked > 0) item { Text(stringResource(R.string.new_session_listing_note, list.blocked), style = DevType.label, color = COUITheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(16.dp)) }
                }
                }
            }
        }
    }
    actionsFor?.let { e ->
        AppBottomSheet(show = true, title = e.name, onDismissRequest = { actionsFor = null }) {
            val opts = buildList {
                if (!e.isDir) add(stringResource(R.string.files_download) to { actionsFor = null; vm.download(e) })
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
        WindowDialog(
            show = true,
            title = stringResource(if (kind == "folder") R.string.files_new_folder else if (kind == "file") R.string.files_new_file else R.string.files_rename),
            onDismissRequest = { nameDialog = null },
        ) {
            Column(Modifier.fillMaxWidth()) {
                Box(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                    Column {
                        TextInput(name, { name = it }, stringResource(R.string.files_name))
                        if (invalidName) {
                            Text(stringResource(R.string.files_name_invalid), style = COUITheme.textStyles.footnote1, color = LocalDevColors.current.danger, modifier = Modifier.padding(top = 4.dp, start = 4.dp))
                        }
                    }
                }
                DialogButtonBar(
                    negative = DialogButtonBarAction(text = stringResource(R.string.action_cancel), onClick = { nameDialog = null }),
                    positive = DialogButtonBarAction(
                        text = stringResource(R.string.action_ok),
                        enabled = name.trim().isNotEmpty() && !invalidName,
                        onClick = {
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
                    ),
                )
            }
        }
    }
    confirmDelete?.let { e ->
        ConfirmDialog(true, stringResource(R.string.files_delete), stringResource(R.string.files_delete_confirm, e.name), stringResource(R.string.files_delete), { confirmDelete = null; vm.delete(e) }, { confirmDelete = null })
    }
}

@Composable
private fun TransferRow(t: TransferState, vm: FilesViewModel, onOpenDownloads: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = io.github.tianli0.switchtether.ui.appContainer().fileStore
    val noApp = stringResource(R.string.viewer_no_app)
    GroupCard {
        Column(Modifier.padding(16.dp)) {
            Text(t.label + " · " + Formatters.bytes(t.done) + (t.total?.let { " / " + Formatters.bytes(it) } ?: ""), style = COUITheme.textStyles.body2, color = COUITheme.colorScheme.onSurface)
            if (t.error == null && !t.finished) LinearProgressIndicator(progress = t.total?.let { if (it > 0) t.done.toFloat() / it else null }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                t.error?.let { Text(it, style = COUITheme.textStyles.footnote1, color = LocalDevColors.current.danger, modifier = Modifier.weight(1f)) }
                if (t.finished) Text(stringResource(R.string.files_done), style = COUITheme.textStyles.footnote1, color = LocalDevColors.current.ok, modifier = Modifier.weight(1f))
                t.downloaded?.let { r ->
                    TextButton(text = stringResource(R.string.files_transfer_open), onClick = { io.github.tianli0.switchtether.ui.files.startSafely(context, store.viewIntent(store.asLocal(r)), noApp) })
                    TextButton(text = stringResource(R.string.viewer_manage), onClick = onOpenDownloads)
                }
                if (t.error == null && !t.finished) TextButton(text = stringResource(R.string.action_cancel), onClick = vm::cancelTransfer) else TextButton(text = stringResource(R.string.action_close), onClick = vm::dismissTransfer)
            }
        }
    }
}

@Composable
private fun FileScreen(state: FilesUiState, vm: FilesViewModel) {
    BackHandler(onBack = vm::requestCloseFile)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            ToolbarIcon(AppIcons.Back, stringResource(R.string.action_back), vm::requestCloseFile)
            val f = (state.file as? Loadable.Ready)?.value
            Text(
                f?.path?.substringAfterLast('\\')?.substringAfterLast('/') ?: "",
                style = COUITheme.textStyles.body1,
                color = COUITheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (f != null && !state.editing) ToolbarIcon(AppIcons.Edit, stringResource(R.string.files_edit), vm::startEdit, enabled = f.text != null && !f.truncated)
            if (f != null && !state.editing) ToolbarIcon(AppIcons.Download, stringResource(R.string.files_download), { vm.download(FsEntry(f.path.substringAfterLast('\\').substringAfterLast('/'), f.path, "file", f.size)) })
            if (state.editing) {
                Button(onClick = vm::save, enabled = !state.saving, colors = ButtonDefaults.buttonColorsPrimary(), modifier = Modifier.padding(end = 8.dp).testTag("save")) {
                    Text(if (state.saving) "…" else stringResource(R.string.files_save), color = COUITheme.colorScheme.onPrimary)
                }
            }
        }
        state.transfer?.let { TransferRow(it, vm, {}) }
        LoadableContent(state.file!!, onRetry = { (state.file as? Loadable.Ready)?.value?.path?.let(vm::openPath) }) { f ->
            val dev = LocalDevColors.current
            Text(
                listOf(f.encoding, f.lineEnding.uppercase(), Formatters.bytes(f.size), f.sha256.take(12)).joinToString(" · ") + if (f.truncated) " · truncated" else "",
                style = DevType.label, color = dev.gutter, modifier = Modifier.padding(horizontal = 16.dp),
            )
            if (state.editing) {
                BasicTextField(
                    value = state.editText,
                    onValueChange = vm::setEditText,
                    textStyle = DevType.code.copy(color = dev.codeText),
                    cursorBrush = SolidColor(COUITheme.colorScheme.primary),
                    modifier = Modifier.fillMaxSize().padding(8.dp).background(dev.codeBackground).padding(8.dp).testTag("editor"),
                )
            } else if (f.text == null) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.files_binary), color = COUITheme.colorScheme.onSurfaceVariantSummary)
                    TextButton(text = stringResource(R.string.files_view_binary), onClick = { vm.openViewer(f.path, f.path.substringAfterLast('\\').substringAfterLast('/'), f.size.takeIf { it > 0 }, f.modifiedMs) })
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 24.dp + LocalBottomBarSpace.current)) {
                    CodeBlockView(f.text.orEmpty(), f.language ?: SyntaxHighlighter.languageForPath(f.path))
                }
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

/** Splits a Windows or POSIX path into `(label, fullPath)` pairs for COUI17 [BreadcrumbBar]. */
private fun buildBreadcrumbs(path: String): List<Pair<String, String>> {
    if (path.isBlank()) return emptyList()
    val isWin = path.contains('\\') || (path.length >= 2 && path[1] == ':')
    val sep = if (isWin) "\\" else "/"
    val parts = path.split('/', '\\').filter { it.isNotEmpty() }
    if (parts.isEmpty()) return listOf("/" to "/")
    val out = mutableListOf<Pair<String, String>>()
    var acc = if (path.startsWith("/")) "/" else ""
    parts.forEachIndexed { idx, part ->
        acc = when {
            idx == 0 && isWin && part.endsWith(":") -> "$part\\"
            acc.isEmpty() || acc == "/" || acc.endsWith("\\") -> acc + part
            else -> acc + sep + part
        }
        out += part to acc
    }
    return out
}

/** Outcome messages that only report success: shown as a toast instead of a dialog. */
private val QUIET_MESSAGES = setOf(R.string.files_msg_saved, R.string.files_msg_saved_as, R.string.files_msg_ok, R.string.files_msg_cancelled)
