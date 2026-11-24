package io.github.tianli0.switchtether.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.RotatingProgressIndicator
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.tianli0.coui17.patterns.StatusPill
import io.github.tianli0.switchtether.ui.icons.AppIcons
import io.github.tianli0.switchtether.ui.theme.LocalDevColors

// The agent-specific pieces of the chat timeline. The generic building blocks (composer, glass
// surfaces, collapsible rows, capsules, cards, bubbles) live in the COUI17 library's coui-patterns
// module (third_party/coui17), where they were extracted from this file.

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
