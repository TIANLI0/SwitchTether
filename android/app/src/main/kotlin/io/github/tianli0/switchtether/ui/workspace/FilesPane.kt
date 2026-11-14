package io.github.tianli0.switchtether.ui.workspace

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.basic.Button
import io.github.suqi8.coui.kmp.basic.ButtonDefaults
import io.github.suqi8.coui.kmp.basic.LinearProgressIndicator
import io.github.suqi8.coui.kmp.basic.SmallTitle
import io.github.suqi8.coui.kmp.basic.Switch
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.basic.cardListPositionOf
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.AddFolder
import io.github.suqi8.coui.kmp.icon.extended.Back
import io.github.suqi8.coui.kmp.icon.extended.Create
import io.github.suqi8.coui.kmp.icon.extended.Download
import io.github.suqi8.coui.kmp.icon.extended.Edit
import io.github.suqi8.coui.kmp.icon.extended.Hide
import io.github.suqi8.coui.kmp.icon.extended.More
import io.github.suqi8.coui.kmp.icon.extended.Show
import io.github.suqi8.coui.kmp.icon.extended.UploadCloud
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.tianli0.switchtether.R
import io.github.tianli0.switchtether.remote.protocol.FsEntry
import io.github.tianli0.switchtether.ui.code.CodeBlockView
import io.github.tianli0.switchtether.ui.code.SyntaxHighlighter
import io.github.tianli0.switchtether.ui.common.ConfirmDialog
import io.github.tianli0.switchtether.ui.common.Formatters
import io.github.tianli0.switchtether.ui.common.GroupCard
import io.github.tianli0.switchtether.ui.common.Loadable
import io.github.tianli0.switchtether.ui.common.LoadableContent
import io.github.tianli0.switchtether.ui.common.TextInput
import io.github.tianli0.switchtether.ui.common.ToolbarIcon
import io.github.tianli0.switchtether.ui.common.pageListPadding
import io.github.tianli0.switchtether.ui.components.FilterChip
import io.github.tianli0.switchtether.ui.components.StatusPill
import io.github.tianli0.switchtether.ui.diff.DiffView
import io.github.tianli0.switchtether.ui.theme.DevType
import io.github.tianli0.switchtether.ui.theme.LocalDevColors

@Composable
fun FilesPane(state: FilesUiState, vm: FilesViewModel) {
    val file = state.file
    when {
        state.conflict != null -> ConflictScreen(state, vm)
        file != null -> FileScreen(state, vm)
        else -> Browser(state, vm)
    }
    state.message?.let { msg ->
        ConfirmDialog(show = true, title = stringResource(R.string.files_title), message = msg, confirmText = stringResource(R.string.action_ok), onConfirm = vm::clearMessage, onDismiss = vm::clearMessage)
    }
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
private fun Browser(state: FilesUiState, vm: FilesViewModel) {
    var nameDialog by remember { mutableStateOf<String?>(null) } // "file" | "folder" | "rename"
    var target by remember { mutableStateOf<FsEntry?>(null) }
    var confirmDelete by remember { mutableStateOf<FsEntry?>(null) }
    var actionsFor by remember { mutableStateOf<FsEntry?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::upload) }
    val listing = (state.listing as? Loadable.Ready)?.value
    BackHandler(enabled = listing?.parent != null) { listing?.parent?.let(vm::browse) }
    Column(Modifier.fillMaxSize()) {
        (state.roots as? Loadable.Ready)?.value?.let { roots ->
            LazyRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(roots, key = { it.path }) { r -> FilterChip(r.name.ifBlank { r.path }, listing?.path == r.path, { vm.browse(r.path) }) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextInput(state.query, vm::setQuery, stringResource(R.string.files_search), modifier = Modifier.weight(1f), showClearButton = true,
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { vm.search() }),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search))
            ToolbarIcon(if (state.showHidden) COUIIcons.Show else COUIIcons.Hide, stringResource(R.string.files_hidden), vm::toggleHidden)
            ToolbarIcon(COUIIcons.Create, stringResource(R.string.files_new_file), { nameDialog = "file" }, enabled = listing != null)
            ToolbarIcon(COUIIcons.AddFolder, stringResource(R.string.files_new_folder), { nameDialog = "folder" }, enabled = listing != null)
            ToolbarIcon(COUIIcons.UploadCloud, stringResource(R.string.files_upload), { picker.launch(arrayOf("*/*")) }, enabled = listing != null)
        }
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.files_search_content), style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.weight(1f))
            Switch(checked = state.searchContent, onCheckedChange = vm::setSearchContent)
        }
        state.transfer?.let { TransferRow(it, vm) }
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
                LazyColumn(Modifier.fillMaxSize().testTag("file-list"), contentPadding = pageListPadding(top = 4.dp)) {
                    item { Text(list.path, style = DevType.codeSmall, color = COUITheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) }
                    item {
                        GroupCard {
                            val rows = listOfNotNull(list.parent?.let { FsEntry("..", it, "dir") }) + list.entries
                            rows.forEachIndexed { i, e ->
                                BasicComponent(
                                    title = (if (e.isDir) "\u25B8 " else "") + e.name,
                                    summary = if (e.isDir) null else listOfNotNull(Formatters.bytes(e.size), e.modifiedMs?.let(Formatters::relative)).joinToString(" · "),
                                    endActions = {
                                        if (e.sensitive) StatusPill(stringResource(R.string.new_session_sensitive), LocalDevColors.current.warn)
                                        if (e.name != "..") ToolbarIcon(COUIIcons.More, stringResource(R.string.files_actions, e.name), { actionsFor = e })
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
    actionsFor?.let { e ->
        io.github.suqi8.coui.kmp.window.WindowDialog(show = true, title = e.name, onDismissRequest = { actionsFor = null }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (!e.isDir) TextButton(text = stringResource(R.string.files_download), onClick = { actionsFor = null; vm.download(e) }, modifier = Modifier.fillMaxWidth())
                TextButton(text = stringResource(R.string.files_rename), onClick = { actionsFor = null; target = e; nameDialog = "rename" }, modifier = Modifier.fillMaxWidth())
                TextButton(text = stringResource(R.string.files_delete), onClick = { actionsFor = null; confirmDelete = e }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
    nameDialog?.let { kind ->
        var name by remember(kind) { mutableStateOf(if (kind == "rename") target?.name.orEmpty() else "") }
        io.github.suqi8.coui.kmp.window.WindowDialog(
            show = true,
            title = stringResource(if (kind == "folder") R.string.files_new_folder else if (kind == "file") R.string.files_new_file else R.string.files_rename),
            onDismissRequest = { nameDialog = null },
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextInput(name, { name = it }, stringResource(R.string.files_name))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(text = stringResource(R.string.action_cancel), onClick = { nameDialog = null }, modifier = Modifier.weight(1f))
                    Button(onClick = {
                        val n = name.trim()
                        if (n.isNotEmpty() && !n.contains('/') && !n.contains('\\')) {
                            when (kind) {
                                "folder" -> vm.mkdir(n)
                                "file" -> vm.newFile(n)
                                else -> target?.let { vm.rename(it, n) }
                            }
                        }
                        nameDialog = null
                    }, colors = ButtonDefaults.buttonColorsPrimary(), modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_ok), color = COUITheme.colorScheme.onPrimary) }
                }
            }
        }
    }
    confirmDelete?.let { e ->
        ConfirmDialog(true, stringResource(R.string.files_delete), stringResource(R.string.files_delete_confirm, e.name), stringResource(R.string.files_delete), { confirmDelete = null; vm.delete(e) }, { confirmDelete = null })
    }
}

@Composable
private fun TransferRow(t: TransferState, vm: FilesViewModel) {
    GroupCard {
        Column(Modifier.padding(16.dp)) {
            Text(t.label + " · " + Formatters.bytes(t.done) + (t.total?.let { " / " + Formatters.bytes(it) } ?: ""), style = COUITheme.textStyles.body2, color = COUITheme.colorScheme.onSurface)
            if (t.error == null && !t.finished) LinearProgressIndicator(progress = t.total?.let { if (it > 0) t.done.toFloat() / it else null }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            Row {
                t.error?.let { Text(it, style = COUITheme.textStyles.footnote1, color = LocalDevColors.current.danger, modifier = Modifier.weight(1f)) }
                if (t.finished) Text(stringResource(R.string.files_done), style = COUITheme.textStyles.footnote1, color = LocalDevColors.current.ok, modifier = Modifier.weight(1f))
                if (t.error == null && !t.finished) TextButton(text = stringResource(R.string.action_cancel), onClick = vm::cancelTransfer) else TextButton(text = stringResource(R.string.action_close), onClick = vm::dismissTransfer)
            }
        }
    }
}

@Composable
private fun FileScreen(state: FilesUiState, vm: FilesViewModel) {
    BackHandler { if (state.editing) vm.cancelEdit() else vm.closeFile() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            ToolbarIcon(COUIIcons.Back, stringResource(R.string.action_back), { if (state.editing) vm.cancelEdit() else vm.closeFile() })
            val f = (state.file as? Loadable.Ready)?.value
            Text(f?.path?.substringAfterLast('\\')?.substringAfterLast('/') ?: "", style = COUITheme.textStyles.body1, color = COUITheme.colorScheme.onSurface, modifier = Modifier.weight(1f), maxLines = 1)
            if (f != null && !state.editing) ToolbarIcon(COUIIcons.Edit, stringResource(R.string.files_edit), vm::startEdit, enabled = f.text != null && !f.truncated)
            if (f != null && !state.editing) ToolbarIcon(COUIIcons.Download, stringResource(R.string.files_download), { vm.download(FsEntry(f.path.substringAfterLast('\\').substringAfterLast('/'), f.path, "file", f.size)) })
            if (state.editing) {
                Button(onClick = vm::save, enabled = !state.saving, colors = ButtonDefaults.buttonColorsPrimary(), modifier = Modifier.padding(end = 8.dp).testTag("save")) {
                    Text(if (state.saving) "…" else stringResource(R.string.files_save), color = COUITheme.colorScheme.onPrimary)
                }
            }
        }
        state.transfer?.let { TransferRow(it, vm) }
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
                Text(stringResource(R.string.files_binary), modifier = Modifier.padding(16.dp), color = COUITheme.colorScheme.onSurfaceVariantSummary)
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp)) {
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
