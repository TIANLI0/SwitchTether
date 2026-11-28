package io.github.tianli0.switchtether.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.tianli0.switchtether.ui.theme.DevType
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * The top of a value axis: [max] rounded up to 1, 2, 2.5 or 5 × a power of ten, so the gridlines
 * at half and full read as round numbers (0 → 1).
 */
fun niceAxisMax(max: Double): Double {
    if (max <= 0 || max.isNaN() || max.isInfinite()) return 1.0
    val magnitude = 10.0.pow(floor(log10(max)))
    val f = max / magnitude
    val nice = when {
        f <= 1.0 -> 1.0
        f <= 2.0 -> 2.0
        f <= 2.5 -> 2.5
        f <= 5.0 -> 5.0
        else -> 10.0
    }
    return nice * magnitude
}

/** Indices of [count] bars that get a label so that at most [maxLabels] show, counted from the latest. */
fun barLabelIndices(count: Int, maxLabels: Int): Set<Int> {
    if (count <= 0) return emptySet()
    val step = ceil(count / maxLabels.coerceAtLeast(1).toDouble()).toInt().coerceAtLeast(1)
    return (count - 1 downTo 0 step step).toSet()
}

/**
 * Bars over time with a value axis (0, half, top as round numbers) and labels under the bars.
 * Long-press a bar to see its numbers in a floating bubble ([tooltip], drawn by
 * [ChartTooltipOverlay] at the top of the chart); drag sideways to move to other bars (a light
 * haptic tick on each), lift to dismiss. Taps and vertical drags still scroll the page. While a bar
 * is held the others are drawn lighter and a dashed guide joins it to the bubble. [tooltipSpace]
 * is the band above the bars the bubble floats in.
 */
@Composable
fun UsageBarChart(
    values: List<Double>,
    summary: String,
    axisLabel: (Double) -> String,
    tooltip: @Composable (Int) -> Unit,
    modifier: Modifier = Modifier,
    labels: Map<Int, String> = emptyMap(),
    height: Dp = 168.dp,
    tooltipSpace: Dp = 0.dp,
    accent: Color = COUITheme.colorScheme.primary,
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = DevType.label.copy(color = COUITheme.colorScheme.onSurfaceVariantSummary)
    val grid = COUITheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val guide = COUITheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.55f)
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val top = remember(values) { niceAxisMax(values.maxOrNull() ?: 0.0) }
    val ticks = remember(top, axisLabel) { listOf(top, top / 2, 0.0).map { it to axisLabel(it) } }
    val tickLayouts = remember(ticks, labelStyle) { ticks.map { (v, text) -> v to measurer.measure(text, labelStyle) } }
    val labelLayouts = remember(labels, labelStyle) { labels.mapValues { (_, text) -> measurer.measure(text, labelStyle) } }
    val axisWidth = remember(tickLayouts) { tickLayouts.maxOf { it.second.size.width } }
    val labelHeight = remember(labelStyle) { measurer.measure("0", labelStyle).size.height }
    val gridDash = remember { PathEffect.dashPathEffect(floatArrayOf(6f, 6f)) }
    val guideDash = remember { PathEffect.dashPathEffect(floatArrayOf(5f, 5f)) }
    val barPath = remember { Path() }
    var pressed by remember(values) { mutableStateOf<Int?>(null) }
    var width by remember { mutableFloatStateOf(0f) }
    val start = axisWidth + with(density) { 8.dp.toPx() }
    val spacePx = with(density) { tooltipSpace.toPx() }

    fun slot(w: Float) = if (values.isEmpty()) 0f else (w - start) / values.size
    fun centerOf(i: Int, w: Float) = start + slot(w) * i + slot(w) / 2f
    fun indexAt(x: Float, w: Float): Int? {
        val s = slot(w)
        if (values.isEmpty() || s <= 0f) return null
        return ((x - start) / s).toInt().coerceIn(0, values.lastIndex)
    }
    val press by rememberUpdatedState { i: Int? ->
        if (i != pressed) {
            if (i != null) {
                haptics.performHapticFeedback(if (pressed == null) HapticFeedbackType.LongPress else HapticFeedbackType.TextHandleMove)
            }
            pressed = i
        }
    }

    Box(modifier.fillMaxWidth().onSizeChanged { width = it.width.toFloat() }) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height + tooltipSpace)
                .semantics { contentDescription = summary }
                .pointerInput(values, start) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { o -> press(indexAt(o.x, size.width.toFloat())) },
                        onDragEnd = { press(null) },
                        onDragCancel = { press(null) },
                    ) { change, _ -> press(indexAt(change.position.x, size.width.toFloat())) }
                },
        ) {
            val plotTop = spacePx + labelHeight / 2f
            val plotBottom = size.height - labelHeight - 6.dp.toPx()
            val plotH = (plotBottom - plotTop).coerceAtLeast(1f)
            // Gridlines with their values on the start edge.
            tickLayouts.forEach { (v, layout) ->
                val y = plotBottom - (v / top).toFloat() * plotH
                drawText(layout, topLeft = Offset(axisWidth - layout.size.width.toFloat(), y - layout.size.height / 2f))
                drawLine(
                    grid,
                    Offset(start, y),
                    Offset(size.width, y),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = if (v > 0) gridDash else null,
                )
            }
            if (values.isEmpty()) return@Canvas
            val s = slot(size.width)
            val barW = (s * 0.62f).coerceIn(2f, 28.dp.toPx())
            val radius = minOf(4.dp.toPx(), barW / 2f)
            val held = pressed
            if (held != null) {
                // The dashed guide from the bubble down to the held bar.
                val cx = centerOf(held, size.width)
                drawLine(guide, Offset(cx, 0f), Offset(cx, plotBottom), strokeWidth = 1.dp.toPx(), pathEffect = guideDash)
            }
            values.forEachIndexed { i, v ->
                val cx = centerOf(i, size.width)
                val h = ((v / top).toFloat() * plotH).coerceIn(0f, plotH)
                if (h > 0f) {
                    val color = if (held == null || i == held) accent else accent.copy(alpha = 0.32f)
                    barPath.rewind()
                    barPath.addRoundRect(
                        RoundRect(
                            left = cx - barW / 2f,
                            top = plotBottom - h,
                            right = cx + barW / 2f,
                            bottom = plotBottom,
                            topLeftCornerRadius = CornerRadius(radius),
                            topRightCornerRadius = CornerRadius(radius),
                        ),
                    )
                    drawPath(barPath, color)
                }
                labelLayouts[i]?.let { layout ->
                    val x = (cx - layout.size.width / 2f).coerceIn(start, size.width - layout.size.width)
                    drawText(layout, topLeft = Offset(x, size.height - layout.size.height))
                }
            }
        }
        ChartTooltipOverlay(
            pressed = pressed,
            anchorX = { i -> centerOf(i, width) },
            modifier = Modifier.matchParentSize(),
            content = tooltip,
        )
    }
}
