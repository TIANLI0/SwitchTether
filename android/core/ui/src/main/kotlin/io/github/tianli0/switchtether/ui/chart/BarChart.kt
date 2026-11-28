package io.github.tianli0.switchtether.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.tianli0.switchtether.ui.theme.DevType

data class BarPoint(val label: String, val value: Double)

/**
 * Daily trend bar chart drawn natively on a Canvas. One accent color (single series), a dashed
 * max-value guide line and first/last x labels; the full series is exposed to accessibility
 * services through [summary].
 */
@Composable
fun BarChart(
    points: List<BarPoint>,
    summary: String,
    formatValue: (Double) -> String,
    modifier: Modifier = Modifier,
    height: Dp = 140.dp,
    barColor: Color = COUITheme.colorScheme.primary,
) {
    val max = remember(points) { points.maxOfOrNull { it.value }?.takeIf { it > 0 } ?: 1.0 }
    val guide = COUITheme.colorScheme.dividerLine
    val guideDash = remember { PathEffect.dashPathEffect(floatArrayOf(6f, 6f)) }
    Column(modifier.fillMaxWidth().semantics { contentDescription = summary }) {
        Text(formatValue(max), style = DevType.label, color = COUITheme.colorScheme.onSurfaceVariantSummary)
        Canvas(Modifier.fillMaxWidth().height(height).padding(vertical = 4.dp)) {
            val n = points.size.coerceAtLeast(1)
            val slot = size.width / n
            val barW = (slot * 0.62f).coerceAtLeast(2f)
            drawLine(guide, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1f, pathEffect = guideDash)
            drawLine(guide, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1f)
            points.forEachIndexed { i, p ->
                val h = (p.value / max).toFloat() * size.height
                if (h > 0f) {
                    drawRoundRect(
                        color = barColor,
                        topLeft = Offset(i * slot + (slot - barW) / 2, size.height - h),
                        size = Size(barW, h),
                        cornerRadius = CornerRadius(minOf(barW / 2, 6f)),
                    )
                }
            }
        }
        if (points.isNotEmpty()) {
            Row(Modifier.fillMaxWidth()) {
                Text(points.first().label, style = DevType.label, color = COUITheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.weight(1f))
                if (points.size > 1) {
                    Text(points.last().label, style = DevType.label, color = COUITheme.colorScheme.onSurfaceVariantSummary, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
