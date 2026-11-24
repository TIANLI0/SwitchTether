package io.github.tianli0.switchtether.ui.settings

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import io.github.suqi8.coui.kmp.layout.DialogButtonBarAction
import io.github.tianli0.coui17.patterns.PageDefaults
import io.github.tianli0.coui17.patterns.SheetButtons
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.LinearProgressIndicator
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.tianli0.coui17.patterns.PanelSheet
import io.github.tianli0.switchtether.R
import io.github.tianli0.switchtether.remote.update.ReleaseInfo
import io.github.tianli0.switchtether.ui.common.Formatters
import io.github.tianli0.switchtether.ui.markdown.MarkdownView
import io.github.tianli0.switchtether.ui.theme.LocalDevColors
import io.github.tianli0.switchtether.update.UpdateState

/** One-line summary for the Settings row. */
@Composable
fun updateSummary(state: UpdateState, current: String): String = when (state) {
    UpdateState.Idle -> stringResource(R.string.update_current, current)
    UpdateState.Checking -> stringResource(R.string.update_checking)
    is UpdateState.UpToDate -> stringResource(R.string.update_up_to_date, current)
    UpdateState.NoReleases -> stringResource(R.string.update_no_releases)
    is UpdateState.Available -> stringResource(R.string.update_available_body, state.release.version.toString())
    is UpdateState.Downloading -> stringResource(R.string.update_downloading)
    is UpdateState.Verifying -> stringResource(R.string.update_verifying)
    is UpdateState.NeedsInstallPermission, is UpdateState.ReadyToInstall -> stringResource(R.string.update_ready)
    is UpdateState.Installing -> stringResource(R.string.update_installing)
    is UpdateState.Failed -> failureText(state)
}

@Composable
private fun failureText(f: UpdateState.Failed): String = stringResource(
    when (f.reason) {
        UpdateState.Reason.NETWORK -> R.string.update_err_network
        UpdateState.Reason.NO_APK_ASSET -> R.string.update_err_no_apk
        UpdateState.Reason.NO_CHECKSUM -> R.string.update_err_no_checksum
        UpdateState.Reason.CHECKSUM_MISMATCH -> R.string.update_err_checksum
        UpdateState.Reason.SIGNATURE_MISMATCH -> R.string.update_err_signature
        UpdateState.Reason.NOT_AN_APK -> R.string.update_err_not_apk
        UpdateState.Reason.INSTALL_FAILED -> R.string.update_err_install
    },
)

private fun UpdateState.release(): ReleaseInfo? = when (this) {
    is UpdateState.Available -> release
    is UpdateState.Downloading -> release
    is UpdateState.Verifying -> release
    is UpdateState.NeedsInstallPermission -> release
    is UpdateState.ReadyToInstall -> release
    is UpdateState.Installing -> release
    is UpdateState.Failed -> release
    else -> null
}

/**
 * The "new version" sheet: release notes (Markdown), download progress, the two checks, and the
 * install / grant-permission actions. Never offers installing an APK that failed a check.
 */
@Composable
fun UpdateSheet(
    show: Boolean,
    state: UpdateState,
    onDownload: (ReleaseInfo) -> Unit,
    onInstall: () -> Unit,
    onGrant: () -> Unit,
    onDismiss: () -> Unit,
) {
    val release = state.release()
    PanelSheet(
        show = show && release != null,
        title = release?.let { stringResource(R.string.update_sheet_title, it.version.toString()) },
        onDismissRequest = onDismiss,
    ) {
        val r = release ?: return@PanelSheet
        val dev = LocalDevColors.current
        val (label, action) = when (state) {
            is UpdateState.Available -> stringResource(R.string.update_download) to { onDownload(r) }
            is UpdateState.ReadyToInstall -> stringResource(R.string.update_install) to onInstall
            is UpdateState.NeedsInstallPermission -> stringResource(R.string.update_grant) to onGrant
            is UpdateState.Failed -> if (state.reason == UpdateState.Reason.NETWORK) stringResource(R.string.update_retry) to { onDownload(r) } else null to null
            else -> null to null
        }
        Column(Modifier.fillMaxWidth()) {
          Column(Modifier.fillMaxWidth().padding(horizontal = PageDefaults.TextGutter).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                listOfNotNull(r.name.takeIf { it != r.tag }, r.publishedAt?.take(10), r.apk?.size?.takeIf { it > 0 }?.let(Formatters::bytes)).joinToString(" · "),
                style = COUITheme.textStyles.footnote1,
                color = COUITheme.colorScheme.onSurfaceVariantSummary,
            )
            Column(Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                MarkdownView(r.notes.ifBlank { stringResource(R.string.update_no_notes) })
            }
            Text(stringResource(R.string.update_checks_note), style = COUITheme.textStyles.footnote2, color = COUITheme.colorScheme.onSurfaceVariantSummary)
            when (state) {
                is UpdateState.Downloading -> {
                    LinearProgressIndicator(
                        progress = androidx.compose.animation.core.animateFloatAsState(if (state.total > 0) (state.done.toFloat() / state.total).coerceIn(0f, 1f) else 0f, label = "download").value,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(stringResource(R.string.update_progress, Formatters.bytes(state.done), Formatters.bytes(state.total)), style = COUITheme.textStyles.footnote2, color = COUITheme.colorScheme.onSurfaceVariantSummary)
                }
                is UpdateState.Verifying -> Text(stringResource(R.string.update_verifying), style = COUITheme.textStyles.body2, color = COUITheme.colorScheme.onSurface)
                is UpdateState.Failed -> {
                    Text(failureText(state), style = COUITheme.textStyles.body2, fontWeight = FontWeight.Medium, color = dev.danger)
                    state.detail?.let { Text(it, style = COUITheme.textStyles.footnote2, color = COUITheme.colorScheme.onSurfaceVariantSummary) }
                }
                else -> Unit
            }
            if (state is UpdateState.NeedsInstallPermission) {
                Text(stringResource(R.string.update_grant_hint), style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurfaceVariantSummary)
            }
          }
            if (label != null && action != null) {
                SheetButtons(primary = DialogButtonBarAction(text = label, onClick = action), modifier = Modifier.testTag("update-action"))
            } else {
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

/**
 * Hosts the update sheet for the whole app: opened from Settings → 检查更新 or from the
 * "new version" notification; re-checks the install permission when the user comes back.
 */
@Composable
fun UpdateHost() {
    val container = io.github.tianli0.switchtether.ui.appContainer()
    val updates = container.updates
    val state by updates.state.collectAsStateWithLifecycle()
    val show by container.showUpdateRequest.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LaunchedEffect(show) {
        if (show && state.release() == null) {
            val r = updates.check()
            if (r !is UpdateState.Available) container.showUpdateRequest.value = false
        }
    }
    LifecycleResumeEffect(Unit) {
        updates.refreshPermission()
        onPauseOrDispose { }
    }
    val context = LocalContext.current
    UpdateSheet(
        show = show,
        state = state,
        onDownload = { r -> scope.launch { updates.download(r) } },
        onInstall = {
            val s = updates.state.value
            if (s is UpdateState.ReadyToInstall) updates.install(s.release, s.apk)
        },
        onGrant = { runCatching { context.startActivity(updates.installPermissionIntent()) } },
        onDismiss = { container.showUpdateRequest.value = false },
    )
}
