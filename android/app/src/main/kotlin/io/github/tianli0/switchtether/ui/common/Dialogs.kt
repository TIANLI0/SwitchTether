package io.github.tianli0.switchtether.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.Button
import io.github.suqi8.coui.kmp.basic.ButtonDefaults
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.layout.BottomSheetDefaults
import io.github.suqi8.coui.kmp.layout.DialogButtonBar
import io.github.suqi8.coui.kmp.layout.DialogButtonBarAction
import io.github.suqi8.coui.kmp.layout.SheetCloseCapsule
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.theme.LocalDismissState
import io.github.suqi8.coui.kmp.window.WindowAlertDialog
import io.github.suqi8.coui.kmp.window.WindowBottomSheet
import io.github.tianli0.switchtether.R
import io.github.tianli0.switchtether.remote.command.CommandStatus
import io.github.tianli0.switchtether.ui.components.compactButtonTextStyle

/** The confirming action of a form panel ("new …", "save"): the accent capsule at the end of its toolbar. */
data class SheetConfirm(val text: String, val enabled: Boolean = true, val testTag: String? = null, val onClick: () -> Unit)

/** Side gutter of text inside a panel: the page's [TextGutter] (cards and rows keep the page's 16dp). */
val SheetTextGutter = TextGutter

/**
 * The app's half-screen panel (docs/ANDROID_UI.md §22.2): a COUI17 [WindowBottomSheet] on the page
 * colour, so group cards read as cards exactly as they do on a page, with the page's 16dp gutters
 * (no extra inside margin). Its toolbar is one 50dp row: [SheetCloseCapsule] (wired through
 * [LocalDismissState] so it animates out), the centred [title], and — for a form — [confirm] as
 * an accent capsule, which stays in view while the keyboard is open.
 */
@Composable
fun AppBottomSheet(
    show: Boolean,
    title: String?,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    confirm: SheetConfirm? = null,
    endAction: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    WindowBottomSheet(
        show = show,
        modifier = modifier,
        title = null,
        startAction = null,
        endAction = null,
        backgroundColor = pageContainerColor(),
        insideMargin = DpSize(0.dp, 0.dp),
        onDismissRequest = onDismissRequest,
    ) {
        val dismissState = LocalDismissState.current
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .heightIn(min = BottomSheetDefaults.HeaderHeight),
            ) {
                SheetCloseCapsule(
                    onClick = { dismissState?.invoke() ?: onDismissRequest() },
                    modifier = Modifier.align(Alignment.CenterStart),
                )
                if (title != null) {
                    Text(
                        text = title,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(horizontal = if (confirm != null) 84.dp else 68.dp),
                        style = COUITheme.textStyles.headline1,
                        fontWeight = FontWeight.SemiBold,
                        color = COUITheme.colorScheme.onBackground,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val end: (@Composable () -> Unit)? = endAction ?: confirm?.let { c -> { SheetConfirmCapsule(c) } }
                if (end != null) {
                    Box(modifier = Modifier.align(Alignment.CenterEnd)) { end() }
                }
            }
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

/** The toolbar's confirming capsule: the close capsule's 40dp height, filled with the accent. */
@Composable
private fun SheetConfirmCapsule(confirm: SheetConfirm) {
    val colors = ButtonDefaults.buttonColorsPrimary()
    Button(
        onClick = confirm.onClick,
        enabled = confirm.enabled,
        colors = colors,
        minHeight = BottomSheetDefaults.ActionButtonSize,
        minWidth = 60.dp,
        insideMargin = PaddingValues(horizontal = 16.dp),
        modifier = if (confirm.testTag != null) Modifier.testTag(confirm.testTag) else Modifier,
    ) {
        Text(
            confirm.text,
            style = compactButtonTextStyle(),
            color = if (confirm.enabled) colors.contentColor else colors.disabledContentColor,
            maxLines = 1,
        )
    }
}

/**
 * The actions under a panel that asks for a decision (not a form): the COUI17 button bar — two 44dp
 * capsules side by side, or stacked when there are three or the labels are long — 16dp in from the
 * panel's edges. [primary] is the filled one.
 */
@Composable
fun SheetButtons(
    primary: DialogButtonBarAction?,
    modifier: Modifier = Modifier,
    secondary: DialogButtonBarAction? = null,
    tertiary: DialogButtonBarAction? = null,
) {
    // Stacked, the bar reads neutral / positive / negative: the primary action goes first.
    val recommended = primary?.copy(recommended = true)
    DialogButtonBar(
        negative = if (tertiary != null) tertiary else secondary,
        positive = if (tertiary != null) secondary else recommended,
        neutral = if (tertiary != null) recommended else null,
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * A page shown in its own window over the current one (a file or picture opened from the chat).
 * The window runs edge to edge like the activity's: [content] draws under the status and navigation
 * bars and pads itself with the insets, the bars keep the page's icon colours, and there is no dim
 * or window animation. A plain `Dialog` stopped at the bars and left a strip of its own colour there.
 */
@Composable
fun FullScreenDialog(onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismissRequest,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        io.github.suqi8.coui.kmp.utils.RemovePlatformDialogDefaultEffects()
        content()
    }
}

/** Confirmation for every write (provider switch, toggles, approvals…). */
@Composable
fun ConfirmDialog(
    show: Boolean,
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    WindowAlertDialog(
        show = show,
        onConfirm = onConfirm,
        onCancel = onDismiss,
        title = title,
        summary = message,
        confirmText = confirmText,
        cancelText = stringResource(R.string.action_cancel),
    )
}

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
