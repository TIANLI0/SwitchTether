package io.github.tianli0.switchtether.ui.settings

import android.content.ClipData
import android.content.Intent
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.suqi8.coui.kmp.basic.CardListPosition
import io.github.suqi8.coui.kmp.basic.SmallTitle
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.preference.ArrowPreference
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.tianli0.coui17.patterns.GroupCard
import io.github.tianli0.coui17.patterns.Page
import io.github.tianli0.coui17.patterns.PageDefaults
import io.github.tianli0.coui17.patterns.pageListPadding
import io.github.tianli0.coui17.patterns.pageScroll
import io.github.tianli0.coui17.patterns.rememberPageListState
import io.github.tianli0.switchtether.R
import io.github.tianli0.switchtether.diag.ExitReasons
import io.github.tianli0.switchtether.diag.ExitRecord
import io.github.tianli0.switchtether.diag.MonitorState
import io.github.tianli0.switchtether.ui.appContainer
import kotlinx.coroutines.launch

/**
 * About → 诊断记录: why earlier app processes ended (docs/ANDROID_UI.md §37), newest first, with an
 * export that shares a text file through the app's FileProvider.
 */
@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val container = appContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val records by container.diagnostics.records.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { container.diagnostics.refresh() }
    val shareTitle = stringResource(R.string.diag_share_title)
    val failed = stringResource(R.string.diag_export_failed)
    Page(title = stringResource(R.string.about_diagnostics), onBack = onBack, immersive = true) {
        LazyColumn(state = rememberPageListState(), flingBehavior = io.github.tianli0.coui17.patterns.pageFlingBehavior(), modifier = Modifier.fillMaxSize().pageScroll(), contentPadding = pageListPadding()) {
            item {
                Text(
                    stringResource(R.string.diag_intro),
                    style = COUITheme.textStyles.footnote1,
                    color = COUITheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = PageDefaults.TextGutter, vertical = 12.dp),
                )
            }
            item {
                GroupCard {
                    ArrowPreference(
                        title = stringResource(R.string.diag_export),
                        summary = stringResource(R.string.diag_export_summary),
                        cardListPosition = CardListPosition.Head,
                        modifier = Modifier.testTag("diag-export"),
                        onClick = {
                            scope.launch {
                                runCatching { container.diagnostics.export() }
                                    .onSuccess { uri ->
                                        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                                            .putExtra(Intent.EXTRA_STREAM, uri)
                                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            .apply { clipData = ClipData.newRawUri("", uri) }
                                        runCatching { context.startActivity(Intent.createChooser(send, shareTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                                    }
                                    .onFailure { android.widget.Toast.makeText(context, failed, android.widget.Toast.LENGTH_SHORT).show() }
                            }
                        },
                    )
                    ArrowPreference(
                        title = stringResource(R.string.diag_clear),
                        cardListPosition = CardListPosition.Tail,
                        onClick = { scope.launch { container.diagnostics.clear() } },
                    )
                }
            }
            item { SmallTitle(stringResource(R.string.diag_records)) }
            if (records.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.diag_empty),
                        style = COUITheme.textStyles.body2,
                        color = COUITheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = PageDefaults.TextGutter, vertical = 12.dp),
                    )
                }
            }
            items(records, key = { "${it.timestampMs}:${it.pid}" }) { r -> RecordCard(r) }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun RecordCard(r: ExitRecord) {
    val context = LocalContext.current
    GroupCard {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(reasonLabel(r.reason), style = COUITheme.textStyles.body2, color = COUITheme.colorScheme.onSurface)
            val time = DateUtils.formatDateTime(context, r.timestampMs, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH)
            val status = buildString {
                append(r.reasonName)
                append(" · status ").append(r.status)
                r.statusName?.let { append(" ").append(it) }
            }
            Line("$time · $status")
            Line(stringResource(R.string.diag_state, r.importanceName))
            Line(
                when (r.monitor) {
                    MonitorState.RUNNING -> stringResource(R.string.diag_monitor_running)
                    MonitorState.NOT_RUNNING -> stringResource(R.string.diag_monitor_off)
                    MonitorState.UNKNOWN -> stringResource(R.string.diag_monitor_unknown)
                },
            )
            Line(stringResource(R.string.diag_memory, Formatter.formatShortFileSize(context, r.pssKb * 1024), Formatter.formatShortFileSize(context, r.rssKb * 1024)))
            r.transfers?.takeIf { it > 0 }?.let { Line(stringResource(R.string.diag_transfers, it)) }
            r.description?.let { Line(it) }
            r.traceBytes?.let { Line(stringResource(R.string.diag_trace, Formatter.formatShortFileSize(context, it))) }
            if (r.trace != null) Line(stringResource(R.string.diag_trace_excerpt))
        }
    }
}

@Composable
private fun Line(text: String) {
    Text(text, style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurfaceVariantSummary)
}

@Composable
private fun reasonLabel(reason: Int): String = stringResource(
    when (reason) {
        ExitReasons.EXIT_SELF -> R.string.diag_reason_exit_self
        ExitReasons.SIGNALED -> R.string.diag_reason_signaled
        ExitReasons.LOW_MEMORY -> R.string.diag_reason_low_memory
        ExitReasons.CRASH -> R.string.diag_reason_crash
        ExitReasons.CRASH_NATIVE -> R.string.diag_reason_crash_native
        ExitReasons.ANR -> R.string.diag_reason_anr
        ExitReasons.USER_REQUESTED -> R.string.diag_reason_user_requested
        ExitReasons.USER_STOPPED -> R.string.diag_reason_user_stopped
        ExitReasons.EXCESSIVE_RESOURCE_USAGE -> R.string.diag_reason_resource
        ExitReasons.FREEZER -> R.string.diag_reason_freezer
        ExitReasons.OTHER -> R.string.diag_reason_other
        else -> R.string.diag_reason_unknown
    },
)
