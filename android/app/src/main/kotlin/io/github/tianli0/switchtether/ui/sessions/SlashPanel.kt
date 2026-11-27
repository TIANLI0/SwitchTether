package io.github.tianli0.switchtether.ui.sessions

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.RotatingProgressIndicator
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.cardListPositionOf
import io.github.suqi8.coui.kmp.squircle.SquircleShape
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.tianli0.coui17.patterns.ComposerFieldShape
import io.github.tianli0.coui17.patterns.ComposerKey
import io.github.tianli0.coui17.patterns.ComposerKeyGap
import io.github.tianli0.coui17.patterns.ComposerKeySize
import io.github.tianli0.coui17.patterns.FormDialog
import io.github.tianli0.coui17.patterns.GroupCard
import io.github.tianli0.coui17.patterns.InputPillState
import io.github.tianli0.coui17.patterns.PageDefaults
import io.github.tianli0.coui17.patterns.PanelSheet
import io.github.tianli0.coui17.patterns.TextInput
import io.github.tianli0.coui17.patterns.glassSurface
import io.github.tianli0.switchtether.R

/** Corner radius of the "/" panel; its first and last rows follow it. */
private val SlashPanelRadius = 20.dp

/** The description to show: the desktop's own, else the phone's text for a built-in command. */
@Composable
fun SlashEntry.descriptionText(): String = description.ifBlank { descriptionRes?.let { stringResource(it) }.orEmpty() }

private fun slashRowShape(index: Int, count: Int): Shape {
    val r = SlashPanelRadius
    return when {
        count == 1 -> SquircleShape(r)
        index == 0 -> SquircleShape(topStart = r, topEnd = r, bottomEnd = 0.dp, bottomStart = 0.dp)
        index == count - 1 -> SquircleShape(topStart = 0.dp, topEnd = 0.dp, bottomEnd = r, bottomStart = r)
        else -> androidx.compose.ui.graphics.RectangleShape
    }
}

/**
 * The commands matching the "/…" being typed, floating directly above the input row as the same
 * glass as the composer. It grows out of the input row and fades in; at most about 240dp, then it
 * scrolls. The gap to the input row is part of it, so nothing is left behind when it is gone.
 */
@Composable
fun SlashSuggestionPanel(suggestions: List<SlashEntry>, onPick: (SlashEntry) -> Unit, modifier: Modifier = Modifier) {
    // The rows stay while the panel shrinks away (the list is already empty by then).
    val last = remember { arrayOf(suggestions) }
    if (suggestions.isNotEmpty()) last[0] = suggestions
    val rows = last[0]
    AnimatedVisibility(
        visible = suggestions.isNotEmpty(),
        modifier = modifier,
        enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom),
        exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom),
    ) {
        val shape = SquircleShape(SlashPanelRadius)
        val scroll = rememberScrollState()
        // A narrower or different match list starts at its best match again.
        LaunchedEffect(rows.firstOrNull()?.name, rows.size) { scroll.scrollTo(0) }
        Column(
            Modifier
                .padding(bottom = 8.dp)
                .fillMaxWidth()
                .heightIn(max = 240.dp)
                .glassSurface(shape, halo = true)
                .clip(shape)
                .verticalScroll(scroll)
                .testTag("slash-panel"),
        ) {
            rows.forEachIndexed { i, entry -> SlashRow(entry, slashRowShape(i, rows.size), onPick) }
        }
    }
}

@Composable
private fun SlashRow(entry: SlashEntry, shape: Shape, onPick: (SlashEntry) -> Unit) {
    val description = entry.descriptionText()
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(shape)
            .clickable(role = Role.Button, onClick = { onPick(entry) })
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("slash-${entry.name}"),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("/${entry.name}", style = COUITheme.textStyles.body1, fontWeight = FontWeight.Medium, color = COUITheme.colorScheme.onSurface, maxLines = 1)
            if (entry.argumentHint.isNotBlank()) {
                Spacer(Modifier.size(6.dp))
                Text(entry.argumentHint, style = COUITheme.textStyles.body2, color = COUITheme.colorScheme.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (description.isNotBlank()) {
            Text(description, style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurfaceVariantSummary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** "/help": every command this session offers, in a half-screen panel; a row does what the "/" panel does. */
@Composable
fun SlashHelpSheet(show: Boolean, commands: List<SlashEntry>, onPick: (SlashEntry) -> Unit, onDismiss: () -> Unit) {
    PanelSheet(show = show, title = stringResource(R.string.slash_help_title), onDismissRequest = onDismiss, imePadding = false) {
        // The list itself is what "/help" shows: it is not a row of its own list.
        val rows = commands.filter { slashActionFor(it) != SlashAction.HELP }
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(bottom = 16.dp).testTag("slash-help")) {
            GroupCard {
                rows.forEachIndexed { i, entry ->
                    BasicComponent(
                        title = listOf("/${entry.name}", entry.argumentHint).filter { it.isNotBlank() }.joinToString(" "),
                        summary = entry.descriptionText().ifBlank { null },
                        onClick = { onPick(entry) },
                        cardListPosition = cardListPositionOf(i, rows.size),
                    )
                }
            }
            Text(
                stringResource(R.string.slash_help_note),
                style = COUITheme.textStyles.footnote2,
                color = COUITheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = PageDefaults.TextGutter),
            )
        }
    }
}

/** "/rename": asks for the new title (Claude Code's own rename, the same call as on the tools page). */
@Composable
fun SlashRenameDialog(show: Boolean, current: String, onRename: (String, (String?) -> Unit) -> Unit, onDismiss: () -> Unit) {
    var title by rememberSaveable(show, current) { mutableStateOf(current) }
    var saving by remember(show) { mutableStateOf(false) }
    var error by remember(show) { mutableStateOf<String?>(null) }
    val valid = title.isNotBlank() && title.trim() != current && title.trim().length <= 200
    FormDialog(
        show = show,
        title = stringResource(R.string.tools_rename),
        confirmText = stringResource(R.string.tools_rename_save),
        confirmEnabled = valid && !saving,
        onConfirm = {
            saving = true
            error = null
            onRename(title) { failure ->
                saving = false
                if (failure == null) onDismiss() else error = failure
            }
        },
        onDismiss = onDismiss,
    ) {
        TextInput(
            value = title,
            onValueChange = { title = it },
            label = stringResource(R.string.tools_rename_field),
            enabled = !saving,
            modifier = Modifier.fillMaxWidth().testTag("slash-rename-field"),
        )
        error?.let { Text(it, style = COUITheme.textStyles.footnote2, color = io.github.tianli0.switchtether.ui.theme.LocalDevColors.current.danger) }
    }
}

/**
 * The composer's field and send key: COUI17's `InputPill` (same measures, glass, halo and send
 * key), except that a draft changed from outside the keyboard (a slash command picked from the
 * panel, a failed message put back for editing) leaves the caret at its end. `InputPill` takes a
 * plain string, so its caret would stay where it was, in the middle of the inserted "/name ".
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun DraftInputPill(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    sendLabel: String,
    onSend: () -> Unit,
    state: InputPillState,
    sendIcon: ImageVector,
    modifier: Modifier = Modifier,
    fieldModifier: Modifier = Modifier,
    sendModifier: Modifier = Modifier,
    maxLines: Int = 5,
    /** Rich content (keyboard images / GIFs, pasted pictures, drops onto the field); null = text only. */
    receiveContent: androidx.compose.foundation.content.ReceiveContentListener? = null,
) {
    val scheme = COUITheme.colorScheme
    // A state-based field: only it supports `Modifier.contentReceiver` (keyboard commits, paste).
    val fieldState = androidx.compose.foundation.text.input.rememberTextFieldState(value)
    val echo = remember { DraftEcho() }
    // The draft was replaced from outside when it changed and is not what was just typed here.
    // (A draft that merely has not caught up with the keyboard yet is left alone.)
    val seen = remember { arrayOf(value) }
    if (value != seen[0]) {
        seen[0] = value
        if (echo.isOutside(value, fieldState.text.toString())) {
            SideEffect { if (fieldState.text.toString() != value) fieldState.setTextAndPlaceCursorAtEnd(value) }
        }
    }
    val currentValue by androidx.compose.runtime.rememberUpdatedState(value)
    val emit by androidx.compose.runtime.rememberUpdatedState(onValueChange)
    LaunchedEffect(fieldState) {
        androidx.compose.runtime.snapshotFlow { fieldState.text.toString() }.collect { t ->
            if (t != currentValue) {
                echo.sent(t)
                emit(t)
            }
        }
    }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Box(
            Modifier
                .weight(1f)
                .heightIn(min = ComposerKeySize)
                .glassSurface(ComposerFieldShape)
                .padding(horizontal = 16.dp, vertical = 9.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (fieldState.text.isEmpty()) {
                Text(placeholder, style = COUITheme.textStyles.body1, color = scheme.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            BasicTextField(
                state = fieldState,
                textStyle = COUITheme.textStyles.body1.copy(color = scheme.onSurface),
                cursorBrush = SolidColor(scheme.primary),
                lineLimits = androidx.compose.foundation.text.input.TextFieldLineLimits.MultiLine(1, maxLines),
                modifier = fieldModifier
                    .then(if (receiveContent != null) Modifier.contentReceiver(receiveContent) else Modifier)
                    .fillMaxWidth()
                    .semantics { contentDescription = placeholder },
            )
        }
        Spacer(Modifier.size(ComposerKeyGap))
        val canSend = state.sendEnabled && !state.busy
        val ready by animateFloatAsState(if (canSend) 1f else 0f, animationSpec = spring(dampingRatio = 0.8f, stiffness = 500f), label = "send-ready")
        ComposerKey(label = sendLabel, onClick = onSend, enabled = canSend, modifier = sendModifier) {
            Box(Modifier.matchParentSize().graphicsLayer { alpha = ready }.clip(CircleShape).background(scheme.primary))
            if (state.busy) {
                RotatingProgressIndicator(color = scheme.onSurfaceSecondary, size = 18.dp, ringDiameter = 14.4.dp, strokeWidth = 2.dp)
            } else {
                Icon(sendIcon, contentDescription = null, tint = lerp(scheme.onSurfaceVariantSummary, scheme.onPrimary, ready), modifier = Modifier.size(20.dp))
            }
        }
    }
}

/**
 * Tells a draft replaced from outside (a picked slash command, a message put back for editing, the
 * draft cleared by send) from one that only has not caught up with what the field reported yet:
 * the view model's drafts arrive in the order the field sent them, possibly skipping some.
 */
class DraftEcho {
    private val pending = ArrayDeque<String>()

    /** The field reported [text] to the view model. */
    fun sent(text: String) {
        pending.addLast(text)
        while (pending.size > 64) pending.removeFirst()
    }

    /** The view model's draft became [value]: true when it is not an echo of the field (replace the field's text). */
    fun isOutside(value: String, fieldText: String): Boolean {
        val i = pending.indexOf(value)
        if (i >= 0) {
            repeat(i + 1) { pending.removeFirst() }
            return false
        }
        pending.clear()
        return value != fieldText
    }
}
