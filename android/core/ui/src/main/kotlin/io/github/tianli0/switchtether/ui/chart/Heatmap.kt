package io.github.tianli0.switchtether.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
 * the start edge, column labels on top, a "less … more" legend. It never scrolls sideways: the
 * squares shrink to fit down to [minPitch]; a grid still too wide shows its latest columns (the
 * most recent weeks), and a narrow one is centred instead of hugging the start edge. Tapping a
 * square selects it (long-press and drag to scrub); the caller shows the selected cell's
 * details. Cells missing from [cells] are not drawn (outside the range, like GitHub's future days).
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
    minPitch: Dp = 10.dp,
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
            // Columns that fit at the smallest pitch; older ones are left out, newest kept.
            val fit = (avail / minPitch).toInt().coerceAtLeast(1)
            val firstColumn = (columns - fit).coerceAtLeast(0)
            val shown = (columns - firstColumn).coerceAtLeast(1)
            val pitch = (avail / shown).coerceAtMost(maxPitch)
            val gridW = pitch * shown
            val gridH = pitch * rows
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                Column(Modifier.width(labelW).padding(top = headerH)) {
                    for (r in 0 until rows) {
                        Box(Modifier.height(pitch).width(labelW), contentAlignment = Alignment.CenterStart) {
                            rowLabels.getOrNull(r)?.takeIf { it.isNotEmpty() }?.let { Text(it, style = labelStyle, maxLines = 1) }
                        }
                    }
                }
                val pitchPx = with(density) { pitch.toPx() }
                val topPx = with(density) { headerH.toPx() }
                fun hit(o: Offset) {
                    val c = (o.x / pitchPx).toInt() + firstColumn
                    val r = ((o.y - topPx) / pitchPx).toInt()
                    if (o.y < topPx || o.x < 0 || c !in firstColumn until columns || r !in 0 until rows) return
                    index[c to r]?.let(select)
                }
                Canvas(
                    Modifier
                        .size(gridW, headerH + gridH)
                        .pointerInput(cells, columns, rows, pitch, firstColumn) { detectTapGestures(onTap = { hit(it) }) }
                        .pointerInput(cells, columns, rows, pitch, firstColumn) {
                            detectDragGesturesAfterLongPress(onDragStart = { hit(it) }) { change, _ -> hit(change.position) }
                        },
                ) {
                    val p = pitch.toPx()
                    val top = headerH.toPx()
                    val gap = (p * 0.18f).coerceAtLeast(2f)
                    val side = p - gap
                    val radius = CornerRadius(side * 0.26f)
                    columnLabels.forEach { (c, text) ->
                        if (c < firstColumn) return@forEach
                        val layout = measurer.measure(text, labelStyle)
                        // Keep the last label inside the grid.
                        val x = ((c - firstColumn) * p).coerceAtMost(size.width - layout.size.width).coerceAtLeast(0f)
                        drawText(layout, topLeft = Offset(x, 0f))
                    }
                    cells.forEachIndexed { i, cell ->
                        if (cell.column < firstColumn) return@forEachIndexed
                        val origin = Offset((cell.column - firstColumn) * p + gap / 2, top + cell.row * p + gap / 2)
                        drawRoundRect(shades[levels[i]], topLeft = origin, size = Size(side, side), cornerRadius = radius)
                    }
                    selected?.let(cells::getOrNull)?.takeIf { it.column >= firstColumn }?.let { cell ->
                        val stroke = 1.5.dp.toPx()
                        drawRoundRect(
                            outline,
                            topLeft = Offset((cell.column - firstColumn) * p + gap / 2 - stroke / 2, top + cell.row * p + gap / 2 - stroke / 2),
                            size = Size(side + stroke, side + stroke),
                            cornerRadius = CornerRadius(radius.x + stroke / 2),
                            style = Stroke(stroke),
                        )
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
