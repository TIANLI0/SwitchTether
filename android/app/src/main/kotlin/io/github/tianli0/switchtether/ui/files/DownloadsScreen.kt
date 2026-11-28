package io.github.tianli0.switchtether.ui.files

import io.github.tianli0.coui17.patterns.PageDefaults
import io.github.tianli0.coui17.patterns.ActionTone
import io.github.tianli0.coui17.patterns.LinkButton
import io.github.tianli0.coui17.patterns.SmallButton
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.basic.BasicComponentDefaults
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.SmallTitle
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.cardListPositionOf
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.tianli0.switchtether.R
import io.github.tianli0.switchtether.files.DownloadRecord
import io.github.tianli0.switchtether.files.FileCache
import io.github.tianli0.switchtether.files.FileStore
import io.github.tianli0.switchtether.files.FileTypes
import io.github.tianli0.switchtether.ui.appContainer
import io.github.tianli0.coui17.patterns.PanelSheet
import io.github.tianli0.coui17.patterns.ConfirmDialog
import io.github.tianli0.switchtether.ui.common.Formatters
import io.github.tianli0.coui17.patterns.GroupCard
import io.github.tianli0.coui17.patterns.Page
import io.github.tianli0.coui17.patterns.ToolbarIcon
import io.github.tianli0.coui17.patterns.pageListPadding
import io.github.tianli0.coui17.patterns.pageScroll
import io.github.tianli0.coui17.patterns.rememberPageListState
import io.github.tianli0.switchtether.ui.icons.AppIcons
import io.github.tianli0.switchtether.ui.theme.DevType
import io.github.tianli0.switchtether.ui.theme.LocalDevColors
import kotlinx.coroutines.launch

private sealed interface PendingDelete {
    data class Some(val ids: Set<String>) : PendingDelete
    data object All : PendingDelete
}

/**
 * "Downloads & storage": the one place that manages what the app keeps on this phone — the files the user
 * downloaded (Downloads/SwitchTether) and the temporary preview cache — with clear wording for the difference.
 */
@Composable
fun DownloadsScreen(onBack: () -> Unit) {
    val store = appContainer().fileStore
    val context = androidx.compose.ui.platform.LocalContext.current
    val noApp = stringResource(R.string.viewer_no_app)
    val scope = rememberCoroutineScope()
    val items by store.downloads.collectAsStateWithLifecycle()
    val cacheBytes by store.cacheBytes.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { store.refresh() }

    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var viewing by remember { mutableStateOf<DownloadRecord?>(null) }
    // A recently previewed copy opened from the cache list, and the one whose actions are shown.
    var viewingCached by remember { mutableStateOf<FileCache.Cached?>(null) }
    var cachedActions by remember { mutableStateOf<FileCache.Cached?>(null) }
    var cached by remember { mutableStateOf<List<FileCache.Cached>>(emptyList()) }
    LaunchedEffect(cacheBytes) { cached = store.cachedFiles() }
    val savedTo = stringResource(R.string.viewer_saved_to, FileStore.DOWNLOAD_FOLDER)
    val keepCached: (FileCache.Cached) -> Unit = { c ->
        scope.launch {
            val msg = runCatching { store.saveCached(c) }.fold({ savedTo }, { it.message ?: noApp })
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    var actionsFor by remember { mutableStateOf<DownloadRecord?>(null) }
    var pending by remember { mutableStateOf<PendingDelete?>(null) }
    var confirmClearCache by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    val back = io.github.tianli0.switchtether.ui.common.rememberPredictiveBack(enabled = viewing != null || viewingCached != null || selecting) {
        when {
            viewing != null -> viewing = null
            viewingCached != null -> viewingCached = null
            else -> { selecting = false; selected = emptySet() }
        }
    }
    // A record that vanished (deleted here or elsewhere) closes its viewer.
    LaunchedEffect(items) { if (viewing != null && items.none { it.id == viewing?.id }) viewing = null }

    viewing?.let { r ->
        val local = remember(r) { store.asLocal(r) }
        PreviewSurface {
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().then(back)) {
                LocalFileViewer(local, subtitle = listOf(Formatters.bytes(r.size), stringResource(R.string.viewer_saved_copy)).joinToString(" · "), onBack = { viewing = null })
            }
        }
        return
    }
    viewingCached?.let { c ->
        val local = remember(c) { store.asLocal(c) }
        PreviewSurface {
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().then(back)) {
            LocalFileViewer(
                local,
                subtitle = listOfNotNull(c.source?.let(::previewFolder), Formatters.bytes(c.size), stringResource(R.string.viewer_temp_copy)).joinToString(" · "),
                onBack = { viewingCached = null },
                onSave = { keepCached(c) },
            )
            }
        }
        return
    }

    Page(
        title = stringResource(R.string.downloads_title),
        onBack = onBack,
        large = false,
        immersive = true,
        actions = {
            if (items.isNotEmpty()) {
                // A text action of the title bar, as in ColorOS list pages ("选择" / "完成").
                LinkButton(
                    text = stringResource(if (selecting) R.string.downloads_done else R.string.downloads_select),
                    onClick = { selecting = !selecting; selected = emptySet() },
                    modifier = Modifier.padding(end = 6.dp),
                )
            }
        },
    ) {
        LazyColumn(state = rememberPageListState(), flingBehavior = io.github.tianli0.coui17.patterns.pageFlingBehavior(), modifier = Modifier.fillMaxSize().pageScroll(), contentPadding = pageListPadding(top = 4.dp)) {
            // Keyed sections: deleting downloads lets what is below the list glide up, and the
            // lines of the list fade when the last file goes.
            item(key = "saved-h") { SmallTitle(stringResource(R.string.downloads_section_saved)) }
            item(key = "saved-hint") {
                Text(
                    stringResource(R.string.downloads_saved_hint, FileStore.DOWNLOAD_FOLDER),
                    style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = PageDefaults.TextGutter).padding(bottom = 8.dp),
                )
            }
            if (items.isEmpty()) {
                item(key = "empty") { Text(stringResource(R.string.downloads_empty), style = COUITheme.textStyles.body2, color = COUITheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.animateItem().padding(horizontal = PageDefaults.TextGutter, vertical = 12.dp)) }
            } else {
                item(key = "total") {
                    Text(
                        pluralStringResource(R.plurals.downloads_total, items.size, items.size, Formatters.bytes(items.sumOf { it.size })),
                        style = DevType.label, color = LocalDevColors.current.gutter, modifier = Modifier.animateItem().padding(horizontal = 16.dp).padding(bottom = 6.dp),
                    )
                }
                item(key = "files") {
                    GroupCard(Modifier.animateItem()) {
                        items.forEachIndexed { i, r ->
                            BasicComponent(
                                title = r.name,
                                summary = listOf(Formatters.bytes(r.size), Formatters.dateTime(r.savedAtMs)).joinToString(" · "),
                                startAction = {
                                    if (selecting) SelectDot(r.id in selected) else Icon(AppIcons.File, contentDescription = null, tint = COUITheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.size(18.dp))
                                },
                                endActions = {
                                    if (!selecting) ToolbarIcon(AppIcons.More, stringResource(R.string.files_actions, r.name), { actionsFor = r })
                                },
                                onClick = {
                                    if (selecting) selected = if (r.id in selected) selected - r.id else selected + r.id
                                    else viewing = r
                                },
                                cardListPosition = cardListPositionOf(i, items.size),
                            )
                        }
                    }
                }
                item(key = "actions") {
                    Box(Modifier.animateItem()) {
                        if (selecting) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                SmallButton(
                                    text = stringResource(R.string.downloads_select_all),
                                    onClick = { selected = if (selected.size == items.size) emptySet() else items.map { it.id }.toSet() },
                                )
                                SmallButton(
                                    text = stringResource(R.string.downloads_delete_selected, selected.size),
                                    enabled = selected.isNotEmpty(),
                                    tone = ActionTone.Danger,
                                    onClick = { pending = PendingDelete.Some(selected) },
                                )
                            }
                        } else {
                            LinkButton(stringResource(R.string.downloads_delete_all), { pending = PendingDelete.All }, modifier = Modifier.padding(horizontal = 14.dp), color = LocalDevColors.current.danger)
                        }
                    }
                }
            }
            item(key = "cache-h") { SmallTitle(stringResource(R.string.downloads_section_cache), Modifier.animateItem()) }
            item(key = "cache") {
                GroupCard(Modifier.animateItem()) {
                    BasicComponent(
                        title = stringResource(R.string.downloads_cache_title),
                        summary = stringResource(R.string.downloads_cache_summary, Formatters.bytes(cacheBytes), Formatters.bytes(FileCache.DEFAULT_MAX_BYTES)),
                        endActions = { SmallButton(stringResource(R.string.downloads_cache_clear), { confirmClearCache = true }, enabled = cacheBytes > 0) },
                    )
                }
            }
            if (cached.isNotEmpty()) {
                item(key = "recent-h") { SmallTitle(stringResource(R.string.downloads_recent), Modifier.animateItem()) }
                item(key = "recent") {
                    val shown = cached.take(RECENT_LIMIT)
                    GroupCard(Modifier.animateItem()) {
                        shown.forEachIndexed { i, c ->
                            BasicComponent(
                                title = c.name,
                                summary = listOfNotNull(c.source?.let(::previewFolder), Formatters.bytes(c.size), io.github.tianli0.switchtether.ui.common.relativeTime(c.lastUsedMs)).joinToString(" · "),
                                startAction = { Icon(fileIconVector(FileTypes.iconOf(c.name, isDir = false)), contentDescription = null, tint = COUITheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.size(20.dp)) },
                                endActions = { ToolbarIcon(AppIcons.More, stringResource(R.string.files_actions, c.name), { cachedActions = c }) },
                                onClick = { viewingCached = c },
                                cardListPosition = cardListPositionOf(i, shown.size),
                            )
                        }
                    }
                }
            }
            item(key = "cache-hint") {
                Text(
                    stringResource(R.string.downloads_cache_hint),
                    style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.animateItem().padding(horizontal = PageDefaults.TextGutter),
                )
            }
            notice?.let { n -> item(key = "notice") { Text(n, style = COUITheme.textStyles.footnote1, color = LocalDevColors.current.warn, modifier = Modifier.animateItem().padding(horizontal = PageDefaults.TextGutter, vertical = 12.dp)) } }
        }
    }

    actionsFor?.let { r ->
        PanelSheet(show = true, title = r.name, onDismissRequest = { actionsFor = null }) {
            val opts = listOf(
                stringResource(R.string.downloads_open) to {
                    actionsFor = null
                    viewing = r
                },
                stringResource(R.string.viewer_open_with) to { actionsFor = null; startSafely(context, store.viewIntent(store.asLocal(r)), noApp) },
                stringResource(R.string.viewer_share) to { actionsFor = null; startSafely(context, store.shareIntent(store.asLocal(r)), noApp) },
                stringResource(R.string.downloads_delete) to { actionsFor = null; pending = PendingDelete.Some(setOf(r.id)) },
            )
            GroupCard {
                opts.forEachIndexed { i, (label, act) ->
                    BasicComponent(
                        title = label,
                        titleColor = BasicComponentDefaults.titleColor(color = if (i == opts.lastIndex) LocalDevColors.current.danger else COUITheme.colorScheme.onSurface),
                        onClick = act,
                        cardListPosition = cardListPositionOf(i, opts.size),
                    )
                }
            }
        }
    }

    cachedActions?.let { c ->
        PanelSheet(show = true, title = c.name, onDismissRequest = { cachedActions = null }) {
            val opts = listOf(
                stringResource(R.string.downloads_open) to { cachedActions = null; viewingCached = c },
                stringResource(R.string.viewer_open_with) to { cachedActions = null; startSafely(context, store.viewIntent(store.asLocal(c)), noApp) },
                stringResource(R.string.viewer_share) to { cachedActions = null; startSafely(context, store.shareIntent(store.asLocal(c)), noApp) },
                stringResource(R.string.downloads_keep) to { cachedActions = null; keepCached(c) },
                stringResource(R.string.downloads_remove_cached) to { cachedActions = null; scope.launch { store.removeCached(c.key) }; Unit },
            )
            GroupCard {
                opts.forEachIndexed { i, (label, act) ->
                    BasicComponent(
                        title = label,
                        titleColor = BasicComponentDefaults.titleColor(color = if (i == opts.lastIndex) LocalDevColors.current.danger else COUITheme.colorScheme.onSurface),
                        onClick = act,
                        cardListPosition = cardListPositionOf(i, opts.size),
                    )
                }
            }
        }
    }

    val p = pending
    ConfirmDialog(
        show = p != null,
        title = stringResource(R.string.downloads_delete_confirm_title),
        message = when (p) {
            is PendingDelete.Some -> pluralStringResource(R.plurals.downloads_delete_confirm, p.ids.size, p.ids.size)
            PendingDelete.All -> stringResource(R.string.downloads_delete_all_confirm, items.size)
            null -> ""
        },
        confirmText = stringResource(R.string.downloads_delete),
        onConfirm = {
            val req = p
            pending = null
            scope.launch {
                val failed = when (req) {
                    is PendingDelete.Some -> store.delete(req.ids)
                    PendingDelete.All -> store.deleteAllDownloads()
                    null -> 0
                }
                selecting = false
                selected = emptySet()
                notice = if (failed > 0) context.resources.getQuantityString(R.plurals.downloads_delete_failed, failed, failed) else null
            }
        },
        onDismiss = { pending = null },
        destructive = true,
    )
    ConfirmDialog(
        show = confirmClearCache,
        title = stringResource(R.string.downloads_cache_clear),
        message = stringResource(R.string.downloads_cache_clear_confirm),
        confirmText = stringResource(R.string.downloads_cache_clear),
        onConfirm = { confirmClearCache = false; scope.launch { store.clearCache() } },
        onDismiss = { confirmClearCache = false },
        destructive = true,
    )
}

@Composable
private fun SelectDot(on: Boolean) {
    Box(
        Modifier.size(20.dp).clip(CircleShape).background(if (on) COUITheme.colorScheme.primary else COUITheme.colorScheme.surfaceContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (on) Text("✓", style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onPrimary, maxLines = 1, overflow = TextOverflow.Clip)
    }
}

/** How many recently previewed copies the storage page lists (the cache keeps more, up to its size cap). */
private const val RECENT_LIMIT = 20

/** A full-page preview inside the storage page: the page colour under the system bars, the viewer between them. */
@Composable
private fun PreviewSurface(content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(io.github.tianli0.coui17.patterns.pageContainerColor())
            .statusBarsPadding().navigationBarsPadding(),
    ) { content() }
}
