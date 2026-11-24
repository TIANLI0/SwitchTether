package io.github.tianli0.switchtether.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.suqi8.coui.kmp.window.WindowAlertDialog
import io.github.tianli0.switchtether.R
import io.github.tianli0.switchtether.remote.command.CommandStatus

// Panels, the button bar, the full-screen dialog and the confirmation dialog come from the COUI17
// library (io.github.tianli0.coui17.patterns: PanelSheet, SheetButtons, FullScreenDialog, ConfirmDialog).

/**
 * Explains a command that did not simply succeed (§5). Retry is offered only when allowed and only
 * runs on an explicit tap; "Check status" queries `GET /v1/commands/{id}`.
 */
@Composable
fun CommandPromptDialog(prompt: CommandPrompt?, onCheckStatus: () -> Unit, onRetry: () -> Unit, onDismiss: () -> Unit) {
    if (prompt == null) {
        WindowAlertDialog(show = false, onConfirm = {}, onCancel = {})
        return
    }
    val title = when (prompt.kind) {
        CommandPrompt.Kind.UNKNOWN -> stringResource(R.string.cmd_unknown_title, prompt.label)
        CommandPrompt.Kind.NOT_SENT -> stringResource(R.string.cmd_not_sent_title, prompt.label)
        CommandPrompt.Kind.IN_PROGRESS -> stringResource(R.string.cmd_in_progress_title, prompt.label)
        CommandPrompt.Kind.REJECTED -> stringResource(R.string.cmd_rejected_title, prompt.label)
        CommandPrompt.Kind.BLOCKED -> stringResource(R.string.cmd_blocked_title, prompt.label)
        CommandPrompt.Kind.STEP_UP_REQUIRED -> stringResource(R.string.cmd_step_up_title, prompt.label)
    }
    val statusLine = when (val s = prompt.status) {
        null -> null
        is CommandStatus.Succeeded -> stringResource(R.string.cmd_status_succeeded)
        is CommandStatus.Failed -> stringResource(R.string.cmd_status_failed)
        is CommandStatus.Pending -> stringResource(R.string.cmd_status_pending)
        CommandStatus.NotFound -> stringResource(R.string.cmd_status_not_found)
        is CommandStatus.Unreachable -> stringResource(R.string.cmd_status_unreachable)
    }
    val body = buildString {
        append(
            when (prompt.kind) {
                CommandPrompt.Kind.UNKNOWN -> stringResource(R.string.cmd_unknown_body)
                CommandPrompt.Kind.NOT_SENT -> stringResource(R.string.cmd_not_sent_body)
                CommandPrompt.Kind.IN_PROGRESS -> stringResource(R.string.cmd_in_progress_body)
                CommandPrompt.Kind.REJECTED -> stringResource(R.string.cmd_rejected_body)
                CommandPrompt.Kind.BLOCKED -> stringResource(R.string.cmd_blocked_body)
                CommandPrompt.Kind.STEP_UP_REQUIRED -> stringResource(R.string.cmd_step_up_body)
            },
        )
        statusLine?.let { append("\n\n").append(it) }
        prompt.detail?.takeIf { it.isNotBlank() }?.let { append("\n\n").append(it) }
    }
    val canCheck = prompt.status == null && (prompt.kind == CommandPrompt.Kind.UNKNOWN || prompt.kind == CommandPrompt.Kind.IN_PROGRESS)
    val retryId = prompt.retryId
    val (confirmText, onConfirm) = when {
        prompt.busy -> stringResource(R.string.cmd_checking) to {}
        canCheck -> stringResource(R.string.cmd_check_status) to onCheckStatus
        retryId != null -> stringResource(R.string.action_retry) to onRetry
        else -> stringResource(R.string.action_ok) to onDismiss
    }
    WindowAlertDialog(
        show = true,
        onConfirm = onConfirm,
        onCancel = onDismiss,
        title = title,
        summary = body,
        confirmText = confirmText,
        cancelText = stringResource(R.string.action_close),
    )
}
