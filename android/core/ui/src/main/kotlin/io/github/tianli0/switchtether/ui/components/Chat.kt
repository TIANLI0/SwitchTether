package io.github.tianli0.switchtether.ui.components

import io.github.suqi8.coui.kmp.blur.progressiveTextureBlur
import io.github.suqi8.coui.kmp.blur.isBlurEnabled
import io.github.suqi8.coui.kmp.blur.ProgressiveBlur
import io.github.suqi8.coui.kmp.blur.BlurDefaults
import io.github.suqi8.coui.kmp.blur.BlendColorEntry
import io.github.suqi8.coui.kmp.basic.BottomFloatingToolbarDefaults
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.drawBehind
import androidx.compose.runtime.remember
import io.github.suqi8.coui.kmp.blur.Backdrop
import io.github.suqi8.coui.kmp.basic.couiGlassSurface
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Shape
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.expandVertically
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.graphics.compositeOver
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
/**
 * Show / hide the child steps of a row (sub-agent, PTC dispatch): a small borderless text in the
 * accent colour next to the status mark, not a filled button, so it never outweighs the row it
 * belongs to. The touch target stays 40dp tall.
 */
@Composable
fun SubStepsToggle(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        text,
        style = COUITheme.textStyles.footnote2,
        fontWeight = FontWeight.Medium,
        color = COUITheme.colorScheme.primary,
        maxLines = 1,
        modifier = modifier
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 11.dp),
    )
}

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
 * The layer a floating chat control blurs: the conversation, recorded by its page as a coui
 * `LayerBackdrop`. Null (no recording, blur unsupported, tests) means opaque fills.
 */
val LocalGlassBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/**
 * The surface of a control floating over the conversation (composer, capsules): ColorOS 17's
 * floating-toolbar glass. The tint layers are coui's `BottomFloatingToolbarDefaults` recipe (the
 * Gallery toolbar material), not a translucent card colour: its `Luminosity` layer pulls whatever
 * is behind to the surface's own brightness, so the glass stays dark over white text in the dark
 * theme and light over dark text in the light one. A plain alpha tint let bright content through
 * and read as a light-grey slab in dark mode. [tint] adds a state's colour on top. A hairline rim,
 * clipped to [shape]. Without a backdrop it is the opaque card colour.
 */
@Composable
fun Modifier.glassSurface(shape: Shape, tint: Color? = null): Modifier {
    val scheme = COUITheme.colorScheme
    val dark = scheme.background.luminance() < 0.5f
    val fill = tint?.compositeOver(scheme.surfaceContainer) ?: scheme.surfaceContainer
    val rim = if (dark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.06f)
    val layers = remember(dark, tint) {
        val base = if (dark) BottomFloatingToolbarDefaults.BlurColorsDark else BottomFloatingToolbarDefaults.BlurColorsLight
        if (tint != null) base + BlendColorEntry(color = tint) else base
    }
    return this
        .couiGlassSurface(
            backdrop = LocalGlassBackdrop.current,
            shape = shape,
            containerColor = fill,
            fallback = Modifier.clip(shape).background(fill),
            blurRadius = 24f,
            blendColors = layers,
        )
        .border(0.5.dp, rim, shape)
        .clip(shape)
}

/**
 * The band behind a bar floating at the bottom of the conversation: the mirror image of the
 * ColorOS 17 glass header at the top. The content blurs progressively toward the bottom edge and
 * [containerColor] fades in with it, as ONE material, so the last lines dissolve under the bar
 * (no opaque scrim laid over the glass). Without blur it is a plain fade to [containerColor].
 */
@Composable
fun Modifier.glassBottomEdge(containerColor: Color): Modifier {
    val backdrop = LocalGlassBackdrop.current
    if (backdrop == null || !isBlurEnabled()) {
        return background(Brush.verticalGradient(0f to containerColor.copy(alpha = 0f), 0.4f to containerColor.copy(alpha = 0.86f), 1f to containerColor))
    }
    return progressiveTextureBlur(
        backdrop = backdrop,
        shape = RectangleShape,
        blurRadius = 25f,
        gradient = ProgressiveBlur.Bottom.copy(curve = 3f),
        colors = BlurDefaults.blurColors(),
    ).drawBehind {
        drawRect(Brush.verticalGradient(0f to containerColor.copy(alpha = 0f), 0.5f to containerColor.copy(alpha = 0.45f), 1f to containerColor.copy(alpha = 0.8f)))
    }
}

/**
 * The body under a collapsible row: it grows out of the row's bottom edge and fades in, and folds
 * back the same way (COUI expand motion; the row's arrow turns with it). The content is a column.
 */
@Composable
fun ExpandBody(expanded: Boolean, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    AnimatedVisibility(
        visible = expanded,
        modifier = modifier,
        enter = expandVertically(animationSpec = spring(dampingRatio = 0.9f, stiffness = 420f), expandFrom = Alignment.Top) +
            fadeIn(animationSpec = tween(durationMillis = 180, delayMillis = 40)),
        exit = shrinkVertically(animationSpec = spring(dampingRatio = 1f, stiffness = 520f), shrinkTowards = Alignment.Top) +
            fadeOut(animationSpec = tween(durationMillis = 110)),
    ) {
        Column(Modifier.fillMaxWidth(), content = content)
    }
}

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
            .glassSurface(RoundedCornerShape(24.dp))
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
        // The button wakes up when there is something to send: colour and a small spring.
        val sendFill by animateColorAsState(if (canSend) scheme.primary else scheme.onSurface.copy(alpha = 0.10f), label = "send-fill")
        val sendScale by animateFloatAsState(if (canSend) 1f else 0.9f, animationSpec = spring(dampingRatio = 0.6f, stiffness = 500f), label = "send-scale")
        Box(
            sendModifier
                .size(36.dp)
                .graphicsLayer { scaleX = sendScale; scaleY = sendScale }
                .clip(CircleShape)
                .background(sendFill)
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
            .glassSurface(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, tint = if (enabled) tint else tint.copy(alpha = 0.4f), modifier = Modifier.size(22.dp)) }
}

/**
 * Capsule of the session header: 30dp high, 13sp medium. A state capsule ([dot] given) carries its
 * state's colour as a soft tint with a coloured dot; an action capsule ([onClick]) has an accent
 * icon. Floating over the conversation ([backdrop] given) it is frosted glass, see [glassSurface];
 * otherwise it sits on the card colour.
 */
@Composable
fun InfoChip(
    text: String,
    modifier: Modifier = Modifier,
    dot: Color? = null,
    color: Color = COUITheme.colorScheme.onSurfaceSecondary,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    /** Non-null for a chip floating over content (the colour itself is no longer used: the chip is glass). */
    backdrop: Color? = null,
) {
    val scheme = COUITheme.colorScheme
    val tint = dot?.copy(alpha = 0.16f)
    val label = if (dot != null || onClick != null) scheme.onSurface else color
    val shape = RoundedCornerShape(50)
    val surface = if (backdrop != null) Modifier.glassSurface(shape, tint) else Modifier.clip(shape).background(tint ?: scheme.surfaceContainer)
    Row(
        modifier
            .heightIn(min = 30.dp)
            .then(surface)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot != null) {
            StatusDot(dot, size = 7.dp)
            Spacer(Modifier.size(6.dp))
        }
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = if (onClick != null) scheme.primary else label, modifier = Modifier.size(15.dp))
            Spacer(Modifier.size(5.dp))
        }
        Text(text, style = COUITheme.textStyles.footnote1, fontWeight = FontWeight.Medium, color = label, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
