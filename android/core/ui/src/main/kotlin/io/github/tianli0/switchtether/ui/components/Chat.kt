package io.github.tianli0.switchtether.ui.components

import io.github.tianli0.switchtether.ui.icons.AppIcons

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.ExpandArrow
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.RotatingProgressIndicator
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.squircle.SquircleShape
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.tianli0.switchtether.ui.theme.LocalDevColors

// Chat-timeline building blocks (ColorOS 17 language: soft cards, full-radius pills, quiet
// secondary text). Every glyph comes from coui-icons / coui-ui; strings are passed in by the app.

/** Accessibility state words (expanded, collapsed); the app provides localized ones at its root. */
val LocalExpandStateLabels = androidx.compose.runtime.staticCompositionLocalOf { "expanded" to "collapsed" }

/** Where a row sits inside a visually grouped card that spans several lazy-list items. */
enum class SegmentPosition { None, Single, First, Middle, Last }

/** Card background for one segment of a multi-item group (keeps each row its own lazy item). */
@Composable
fun Modifier.segment(position: SegmentPosition, radius: Dp = 16.dp, color: Color = COUITheme.colorScheme.surfaceContainer): Modifier {
    if (position == SegmentPosition.None) return this
    val r = radius
    val shape = when (position) {
        SegmentPosition.Single -> RoundedCornerShape(r)
        SegmentPosition.First -> RoundedCornerShape(topStart = r, topEnd = r)
        SegmentPosition.Middle -> SquircleShape(0.dp)
        SegmentPosition.Last -> RoundedCornerShape(bottomStart = r, bottomEnd = r)
        SegmentPosition.None -> SquircleShape(0.dp)
    }
    return this.clip(shape).background(color)
}

/**
 * Round agent badge: the agent's brand mark (or a generic glyph) on a softly tinted disc, with an
 * optional status dot. [mark] is drawn with [tint]; monochrome marks pass the content colour so
 * they follow light/dark mode.
 */
@Composable
fun AgentAvatar(mark: Painter, tint: Color, contentDescription: String?, modifier: Modifier = Modifier, size: Dp = 28.dp, statusColor: Color? = null) {
    Box(modifier.size(size)) {
        Box(
            Modifier.size(size).clip(CircleShape).background(tint.copy(alpha = if (LocalDevColors.current.isDark) 0.20f else 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(mark, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(size * 0.58f))
        }
        if (statusColor != null) {
            Box(
                Modifier.align(Alignment.BottomEnd).size(size * 0.34f).clip(CircleShape)
                    .background(COUITheme.colorScheme.surfaceContainer).padding(1.5.dp).clip(CircleShape).background(statusColor),
            )
        }
    }
}

@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier, size: Dp = 8.dp) {
    Box(modifier.size(size).clip(CircleShape).background(color))
}

/** Right-aligned message bubble (user turns), tinted with the accent. */
@Composable
fun ChatBubble(modifier: Modifier = Modifier, color: Color = COUITheme.colorScheme.primary, content: @Composable () -> Unit) {
    val dark = LocalDevColors.current.isDark
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Spacer(Modifier.size(40.dp))
        Box(
            Modifier
                .widthIn(max = 560.dp)
                .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 6.dp))
                .background(color.copy(alpha = if (dark) 0.30f else 0.12f))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) { content() }
    }
}

/** Execution state of one agent step (tool call / sub-agent). */
enum class StepStatus {
    Pending, Running, Completed, Failed, Denied, Interrupted, Unknown;

    companion object {
        fun parse(v: String?): StepStatus = when (v) {
            "pending" -> Pending
            "running" -> Running
            "completed" -> Completed
            "failed" -> Failed
            "denied" -> Denied
            "interrupted" -> Interrupted
            else -> Unknown
        }
    }
}

@Composable
fun stepStatusColor(status: StepStatus): Color {
    val dev = LocalDevColors.current
    return when (status) {
        StepStatus.Completed -> dev.ok
        StepStatus.Failed -> dev.danger
        StepStatus.Denied, StepStatus.Interrupted -> dev.warn
        StepStatus.Running, StepStatus.Pending -> COUITheme.colorScheme.primary
        StepStatus.Unknown -> dev.neutral
    }
}

/**
 * Compact trailing status: a spinner while running, a check when done, a labelled pill for
 * failed / denied / interrupted (never colour-only: [label] is always the accessible text).
 */
@Composable
fun StepStatusIndicator(status: StepStatus, label: String, modifier: Modifier = Modifier) {
    val color = stepStatusColor(status)
    when (status) {
        StepStatus.Running, StepStatus.Pending -> Box(modifier.size(20.dp).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
            RotatingProgressIndicator(color = color, size = 18.dp, ringDiameter = 14.4.dp, strokeWidth = 2.dp)
        }
        StepStatus.Completed -> Icon(AppIcons.Done, contentDescription = label, tint = color, modifier = modifier.size(18.dp))
        else -> StatusPill(label, color, modifier)
    }
}

/**
 * A tappable one-line row: leading library icon, title, optional secondary line, trailing slot
 * and the COUI expand arrow. Used for tool steps, step groups and the thinking row.
 */
@Composable
fun CollapsibleRow(
    icon: ImageVector,
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    toggleLabel: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    iconTint: Color = COUITheme.colorScheme.onSurfaceSecondary,
    titleColor: Color = COUITheme.colorScheme.onSurface,
    titleMaxLines: Int = 1,
    subtitleMaxLines: Int = 1,
    showArrow: Boolean = true,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val labels = LocalExpandStateLabels.current
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(role = Role.Button, onClickLabel = toggleLabel, onClick = onToggle)
            .semantics { stateDescription = if (expanded) labels.first else labels.second }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(28.dp).clip(SquircleShape(10.dp)).background(iconTint.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(18.dp)) }
        Spacer(Modifier.size(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = COUITheme.textStyles.body2, color = titleColor, maxLines = titleMaxLines, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = COUITheme.textStyles.footnote2, color = COUITheme.colorScheme.onSurfaceVariantSummary, maxLines = subtitleMaxLines, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.size(6.dp))
        trailing()
        if (showArrow) ExpandArrow(expanded = expanded)
    }
}

/** Accent-bordered COUI17 squircle card for things that need the user (approvals, questions). */
@Composable
fun AttentionCard(modifier: Modifier = Modifier, color: Color = COUITheme.colorScheme.primary, active: Boolean = true, content: @Composable () -> Unit) {
    val shape = SquircleShape(20.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(COUITheme.colorScheme.surfaceContainer)
            .then(if (active) Modifier.border(1.5.dp, color.copy(alpha = 0.85f), shape) else Modifier)
            .padding(14.dp),
    ) { content() }
}

/** Plain COUI17 squircle card used by timeline items (diffs, plans, errors). */
@Composable
fun TimelineCard(modifier: Modifier = Modifier, border: Color? = null, padding: Dp = 12.dp, content: @Composable () -> Unit) {
    val shape = SquircleShape(18.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(COUITheme.colorScheme.surfaceContainer)
            .then(if (border != null) Modifier.border(1.dp, border, shape) else Modifier)
            .padding(padding),
    ) { content() }
}

@Immutable
data class InputPillState(val enabled: Boolean, val sendEnabled: Boolean, val busy: Boolean)

/**
 * The composer: a full-radius input pill with a round accent send button inside it. When
 * [InputPillState.enabled] is false the field still accepts a local draft (callers decide), but the
 * send button is greyed out.
 */
@Composable
fun InputPill(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    sendLabel: String,
    onSend: () -> Unit,
    state: InputPillState,
    modifier: Modifier = Modifier,
    fieldModifier: Modifier = Modifier,
    sendModifier: Modifier = Modifier,
    maxLines: Int = 5,
) {
    val scheme = COUITheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(SquircleShape(24.dp))
            .background(scheme.surfaceContainer)
            .padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).padding(vertical = 6.dp)) {
            if (value.isEmpty()) {
                Text(placeholder, style = COUITheme.textStyles.body1, color = scheme.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = COUITheme.textStyles.body1.copy(color = scheme.onSurface),
                cursorBrush = SolidColor(scheme.primary),
                maxLines = maxLines,
                modifier = fieldModifier.fillMaxWidth().semantics { contentDescription = placeholder },
            )
        }
        Spacer(Modifier.size(6.dp))
        val canSend = state.sendEnabled && !state.busy
        Box(
            sendModifier
                .size(36.dp)
                .clip(CircleShape)
                .background(if (canSend) scheme.primary else scheme.onSurface.copy(alpha = 0.10f))
                .clickable(enabled = canSend, role = Role.Button, onClickLabel = sendLabel, onClick = onSend)
                .semantics { contentDescription = sendLabel },
            contentAlignment = Alignment.Center,
        ) {
            if (state.busy) {
                RotatingProgressIndicator(color = scheme.onSurfaceSecondary, size = 18.dp, ringDiameter = 14.4.dp, strokeWidth = 2.dp)
            } else {
                Icon(AppIcons.Send, contentDescription = null, tint = if (canSend) scheme.onPrimary else scheme.onSurfaceVariantSummary, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** Small round icon button in the same family as the send button (e.g. interrupt). */
@Composable
fun RoundIconButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = COUITheme.colorScheme.onSurface, enabled: Boolean = true) {
    Box(
        modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(COUITheme.colorScheme.surfaceContainer)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, tint = if (enabled) tint else tint.copy(alpha = 0.4f), modifier = Modifier.size(22.dp)) }
}

/**
 * Capsule for the session header. A state capsule ([dot] given) is tinted with that state's colour
 * (soft fill, coloured dot, readable text); a plain one sits on the neutral card colour. No outline.
 * With [onClick] it is a button.
 */
@Composable
fun InfoChip(
    text: String,
    modifier: Modifier = Modifier,
    dot: Color? = null,
    color: Color = COUITheme.colorScheme.onSurfaceSecondary,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
) {
    val fill = if (dot != null) dot.copy(alpha = 0.14f) else COUITheme.colorScheme.surfaceContainer
    val label = if (dot != null) COUITheme.colorScheme.onSurface else color
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .heightIn(min = 24.dp)
            .clip(shape)
            .background(fill)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot != null) {
            StatusDot(dot, size = 6.dp)
            Spacer(Modifier.size(5.dp))
        }
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = label, modifier = Modifier.size(13.dp))
            Spacer(Modifier.size(4.dp))
        }
        Text(text, style = COUITheme.textStyles.footnote2, fontWeight = FontWeight.Medium, color = label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Quiet "icon + number" figure for the stats line under the capsules (no outline, no fill). */
@Composable
fun StatFigure(icon: ImageVector, text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = COUITheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.size(14.dp))
        Spacer(Modifier.size(4.dp))
        Text(text, style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurfaceVariantSummary, maxLines = 1)
    }
}
