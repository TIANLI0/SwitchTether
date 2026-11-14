package io.github.tianli0.switchtether.ui.diff

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.tianli0.switchtether.ui.theme.DevType
import io.github.tianli0.switchtether.ui.theme.LocalDevColors

/**
 * Native unified-diff view: line-number gutter, +/- coloring, hunk headers, horizontal scrolling.
 * Long diffs are capped at [initialLines] with an explicit "show more" button.
 */
@Composable
fun DiffView(
    diff: String,
    modifier: Modifier = Modifier,
    initialLines: Int = 120,
    showMoreLabel: (Int) -> String = { "Show $it more lines" },
) {
    val files = remember(diff) { UnifiedDiff.parse(diff) }
    var limit by remember(diff) { mutableStateOf(initialLines) }
    val dev = LocalDevColors.current
    val total = files.sumOf { f -> f.hunks.sumOf { it.lines.size + 1 } }
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(dev.codeBackground)) {
        BoxWithConstraints {
        val viewport = maxWidth
        Column(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp).widthIn(min = viewport).width(IntrinsicSize.Max)) {
            var shown = 0
            for (file in files) {
                for (hunk in file.hunks) {
                    if (shown >= limit) break
                    Text(hunk.header, style = DevType.codeSmall, color = dev.hunkHeader, softWrap = false, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                    shown++
                    for (line in hunk.lines) {
                        if (shown >= limit) break
                        DiffLineRow(line)
                        shown++
                    }
                }
            }
        }
        }
        if (total > limit) {
            TextButton(text = showMoreLabel(total - limit), onClick = { limit += 400 }, modifier = Modifier.padding(4.dp))
        }
    }
}

@Composable
private fun DiffLineRow(line: DiffLine) {
    val dev = LocalDevColors.current
    val (bg, fg, sign) = when (line.type) {
        DiffLine.Type.ADD -> Triple(dev.addedBackground, dev.addedText, "+")
        DiffLine.Type.DEL -> Triple(dev.removedBackground, dev.removedText, "-")
        DiffLine.Type.META -> Triple(dev.codeBackground, dev.gutter, " ")
        DiffLine.Type.CONTEXT -> Triple(dev.codeBackground, dev.codeText, " ")
    }
    Row(Modifier.fillMaxWidth().background(bg)) {
        Text(line.oldNumber?.toString() ?: "", style = DevType.codeSmall, color = dev.gutter, modifier = Modifier.width(36.dp).padding(end = 4.dp), maxLines = 1, overflow = TextOverflow.Clip)
        Text(line.newNumber?.toString() ?: "", style = DevType.codeSmall, color = dev.gutter, modifier = Modifier.width(36.dp).padding(end = 4.dp), maxLines = 1, overflow = TextOverflow.Clip)
        Text("$sign${line.text}", style = DevType.codeSmall, color = fg, softWrap = false, maxLines = 1)
    }
}
