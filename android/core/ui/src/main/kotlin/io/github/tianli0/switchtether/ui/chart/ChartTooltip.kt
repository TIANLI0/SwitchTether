package io.github.tianli0.switchtether.ui.chart

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.squircle.SquircleShape
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlin.math.roundToInt

/**
 * The floating bubble of a long-pressed chart point, drawn like ColorOS 17's rich tooltip
 * (`COUIToolTips`): the surface-container bubble on the 17 dp smooth corner, the system popup
 * shadow and 16 dp inside margin (`TooltipDefaults` in COUI17). Content is the caller's.
 */
@Composable
fun ChartTooltipBubble(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = SquircleShape(17.dp)
    Column(
        modifier
            .widthIn(max = 260.dp)
            .dropShadow(shape, Shadow(radius = 27.85.dp, color = Color.Black.copy(alpha = 0.145f), offset = DpOffset(0.dp, 6.42.dp)))
            .background(COUITheme.colorScheme.surfaceContainer, shape)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("chart-tooltip"),
    ) { content() }
}

/**
 * Shows [content] for the [pressed] point in a [ChartTooltipBubble] at the top of the chart,
 * beside [anchorX] (px from the overlay's start): to its start when the point is in the end half,
 * else to its end, so the held bar or square stays visible; kept inside the overlay. It fades and
 * scales in and out after the finger lifts, still showing the last point while it leaves. Place it
 * in a Box over the chart (`matchParentSize`).
 */
@Composable
fun ChartTooltipOverlay(
    pressed: Int?,
    anchorX: (Int) -> Float,
    modifier: Modifier = Modifier,
    content: @Composable (Int) -> Unit,
) {
    // The point shown while the bubble leaves after the finger lifts (not state: only read here).
    val last = remember { IntArray(1) { -1 } }
    if (pressed != null) last[0] = pressed
    val shown = last[0].takeIf { it >= 0 } ?: return
    Layout(
        modifier = modifier,
        content = {
            AnimatedVisibility(
                visible = pressed != null,
                enter = fadeIn(tween(140)) + scaleIn(tween(180), initialScale = 0.92f, transformOrigin = TransformOrigin(0.5f, 0f)),
                exit = fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.96f, transformOrigin = TransformOrigin(0.5f, 0f)),
            ) {
                Box { ChartTooltipBubble { content(shown) } }
            }
        },
    ) { measurables, constraints ->
        val p = measurables.firstOrNull()?.measure(constraints.copy(minWidth = 0, minHeight = 0))
        layout(constraints.maxWidth, constraints.maxHeight) {
            if (p != null) {
                val a = anchorX(shown)
                val gap = 12.dp.toPx()
                val wanted = if (a > constraints.maxWidth / 2f) a - gap - p.width else a + gap
                p.place(wanted.roundToInt().coerceIn(0, (constraints.maxWidth - p.width).coerceAtLeast(0)), 0)
            }
        }
    }
}
