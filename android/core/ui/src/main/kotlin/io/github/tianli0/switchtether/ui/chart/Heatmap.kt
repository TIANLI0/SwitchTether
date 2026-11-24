package io.github.tianli0.switchtether.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.tianli0.switchtether.ui.theme.DevType
import kotlinx.coroutines.flow.first

/** One square of the heatmap: grid position and the value that picks its shade. */
data class HeatCell(val column: Int, val row: Int, val value: Double)

/**
 * Intensity level 0..4 like GitHub's contribution graph: 0 is empty, 1..4 split the non-zero
 * values at their quartiles (so one huge day does not wash every other day out).
 */
fun heatLevels(values: List<Double>): List<Int> {
    val nonZero = values.filter { it > 0 }.sorted()
    if (nonZero.isEmpty()) return values.map { 0 }
    fun q(p: Double) = nonZero[((nonZero.size - 1) * p).toInt()]
    val (q1, q2, q3) = Triple(q(0.25), q(0.5), q(0.75))
    return values.map { v ->
        when {
            v <= 0 -> 0
            v <= q1 -> 1
            v <= q2 -> 2
            v <= q3 -> 3
            else -> 4
        }
    }
}

/**
 * GitHub-style contribution heatmap drawn on a Canvas: [rows] × [columns] squares, row labels on
 * the start edge (kept in place), column labels on top, a "less … more" legend. Wide grids scroll
 * horizontally and open at the latest column. Tapping a square selects it (long-press and drag to
 * scrub); the caller shows the selected cell's details. Cells missing from [cells] are not drawn
 * (outside the range, like GitHub's future days).
 */
@Composable
fun ContributionHeatmap(
    columns: Int,
    rows: Int,
    cells: List<HeatCell>,
    selected: Int?,
    onSelect: (Int) -> Unit,
    summary: String,
    lessLabel: String,
    moreLabel: String,
    modifier: Modifier = Modifier,
    rowLabels: List<String?> = emptyList(),
    columnLabels: Map<Int, String> = emptyMap(),
    rowLabelWidth: Dp = 28.dp,
    minPitch: Dp = 13.dp,
    maxPitch: Dp = 30.dp,
    accent: Color = COUITheme.colorScheme.primary,
) {
    val levels = remember(cells) { heatLevels(cells.map { it.value }) }
    val index = remember(cells) { cells.withIndex().associate { (i, c) -> (c.column to c.row) to i } }
    val empty = COUITheme.colorScheme.onSurface.copy(alpha = 0.07f)
    val shades = listOf(empty, accent.copy(alpha = 0.28f), accent.copy(alpha = 0.5f), accent.copy(alpha = 0.75f), accent)
    val outline = COUITheme.colorScheme.onSurface
    val labelColor = COUITheme.colorScheme.onSurfaceVariantSummary
    val labelStyle = DevType.label.copy(color = labelColor)
    val measurer = rememberTextMeasurer()
    val select by rememberUpdatedState(onSelect)
    val density = LocalDensity.current
    val labelW = if (rowLabels.any { !it.isNullOrEmpty() }) rowLabelWidth else 0.dp
    val headerH = if (columnLabels.isNotEmpty()) 16.dp else 0.dp

    Column(modifier.fillMaxWidth().semantics { contentDescription = summary }) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val avail = maxWidth - labelW
            val pitch = (avail / columns.coerceAtLeast(1)).coerceIn(minPitch, maxPitch)
            val gridW = pitch * columns
            val gridH = pitch * rows
            Row {
                // Row labels stay put while the grid scrolls.
                Column(Modifier.width(labelW).padding(top = headerH)) {
                    for (r in 0 until rows) {
                        Box(Modifier.height(pitch).width(labelW), contentAlignment = Alignment.CenterStart) {
                            rowLabels.getOrNull(r)?.takeIf { it.isNotEmpty() }?.let { Text(it, style = labelStyle, maxLines = 1) }
                        }
                    }
                }
                val scroll = rememberScrollState()
                LaunchedEffect(columns, rows) {
                    snapshotFlow { scroll.maxValue }.first { it > 0 }
                    scroll.scrollTo(scroll.maxValue)
                }
                val pitchPx = with(density) { pitch.toPx() }
                val topPx = with(density) { headerH.toPx() }
                fun hit(o: Offset) {
                    val c = (o.x / pitchPx).toInt()
                    val r = ((o.y - topPx) / pitchPx).toInt()
                    if (o.y < topPx || c !in 0 until columns || r !in 0 until rows) return
                    index[c to r]?.let(select)
                }
                Box(Modifier.horizontalScroll(scroll)) {
                    Canvas(
                        Modifier
                            .size(gridW, headerH + gridH)
                            .pointerInput(cells, columns, rows, pitch) { detectTapGestures(onTap = { hit(it) }) }
                            .pointerInput(cells, columns, rows, pitch) {
                                detectDragGesturesAfterLongPress(onDragStart = { hit(it) }) { change, _ -> hit(change.position) }
                            },
                    ) {
                        val p = pitch.toPx()
                        val top = headerH.toPx()
                        val gap = (p * 0.16f).coerceAtLeast(2f)
                        val side = p - gap
                        val radius = CornerRadius(side * 0.22f)
                        columnLabels.forEach { (c, text) ->
                            val layout = measurer.measure(text, labelStyle)
                            // Keep the last label inside the grid.
                            val x = (c * p).coerceAtMost(size.width - layout.size.width).coerceAtLeast(0f)
                            drawText(layout, topLeft = Offset(x, 0f))
                        }
                        cells.forEachIndexed { i, cell ->
                            val origin = Offset(cell.column * p + gap / 2, top + cell.row * p + gap / 2)
                            drawRoundRect(shades[levels[i]], topLeft = origin, size = Size(side, side), cornerRadius = radius)
                        }
                        selected?.let(cells::getOrNull)?.let { cell ->
                            val stroke = 1.5.dp.toPx()
                            drawRoundRect(
                                outline,
                                topLeft = Offset(cell.column * p + gap / 2 - stroke / 2, top + cell.row * p + gap / 2 - stroke / 2),
                                size = Size(side + stroke, side + stroke),
                                cornerRadius = CornerRadius(radius.x + stroke / 2),
                                style = Stroke(stroke),
                            )
                        }
                    }
                }
            }
        }
        // Legend: less □□□□□ more, end-aligned like GitHub.
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(lessLabel, style = labelStyle)
            Spacer(Modifier.width(2.dp))
            shades.forEach { shade ->
                Canvas(Modifier.size(10.dp)) { drawRoundRect(shade, cornerRadius = CornerRadius(size.width * 0.22f)) }
            }
            Spacer(Modifier.width(2.dp))
            Text(moreLabel, style = labelStyle)
        }
    }
}
