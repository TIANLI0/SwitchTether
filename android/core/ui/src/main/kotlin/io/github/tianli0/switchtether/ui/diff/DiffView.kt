package io.github.tianli0.switchtether.ui.diff

import androidx.compose.foundation.background
import io.github.suqi8.coui.kmp.squircle.SquircleShape
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.tianli0.switchtether.ui.icons.AppIcons
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.Text
import io.github.tianli0.switchtether.ui.theme.DevType
import io.github.tianli0.switchtether.ui.theme.LocalDevColors

/**
 * Native unified-diff view: line-number gutter, +/- coloring, hunk headers, horizontal scrolling.
 * Long diffs are capped at [initialLines] with an explicit "show more" button.
 *
 * Scrolling sideways moves only the code: the two line-number columns stay put (they scrolled
 * away with it, and a scrolled line no longer said where it was or which side it came from). As
 * on code blocks, a small toggle (shown when the app provides [LocalCodeWrapLabels]) wraps long
 * lines instead.
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
    var wrapped by androidx.compose.runtime.saveable.rememberSaveable(diff.hashCode()) { mutableStateOf(false) }
    val wrapLabels = io.github.tianli0.switchtether.ui.code.LocalCodeWrapLabels.current
    val dev = LocalDevColors.current
    val total = files.sumOf { f -> f.hunks.sumOf { it.lines.size + 1 } }
    // What is shown, in order: a hunk header (null line) or a line.
    val rows = remember(files, limit) {
        buildList<Pair<String, DiffLine?>> {
            for (file in files) for (hunk in file.hunks) {
                if (size >= limit) break
                add(hunk.header to null)
                for (line in hunk.lines) {
                    if (size >= limit) break
                    add(hunk.header to line)
                }
            }
        }
    }
    Column(modifier.fillMaxWidth().clip(SquircleShape(8.dp)).background(dev.codeBackground)) {
        Box(Modifier.fillMaxWidth()) {
            if (wrapped) {
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    rows.forEach { (header, line) ->
                        if (line == null) HunkHeader(header, wrap = true)
                        else Row(Modifier.fillMaxWidth().background(lineColors(line).first)) {
                            Gutter(line)
                            LineText(line, wrap = true, modifier = Modifier.weight(1f))
                        }
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    // Fixed gutter: one cell per row, the same height as the row beside it.
                    Column(Modifier.width(IntrinsicSize.Max)) {
                        rows.forEach { (_, line) ->
                            if (line == null) HunkHeader(" ", wrap = false)
                            else Row(Modifier.background(lineColors(line).first)) { Gutter(line) }
                        }
                    }
                    BoxWithConstraints(Modifier.weight(1f)) {
                        val viewport = maxWidth
                        Column(Modifier.horizontalScroll(rememberScrollState()).widthIn(min = viewport).width(IntrinsicSize.Max)) {
                            rows.forEach { (header, line) ->
                                if (line == null) HunkHeader(header, wrap = false)
                                else Box(Modifier.fillMaxWidth().background(lineColors(line).first)) { LineText(line, wrap = false) }
                            }
                        }
                    }
                }
            }
            if (wrapLabels != null) {
                val label = if (wrapped) wrapLabels.second else wrapLabels.first
                Box(
                    Modifier.align(Alignment.TopEnd).size(32.dp).clip(CircleShape)
                        .clickable(role = Role.Button, onClickLabel = label) { wrapped = !wrapped }
                        .semantics { contentDescription = label },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(if (wrapped) AppIcons.ScrollSideways else AppIcons.WrapLines, contentDescription = null, tint = dev.gutter, modifier = Modifier.size(16.dp))
                }
            }
        }
        if (total > limit) {
            io.github.tianli0.coui17.patterns.LinkButton(showMoreLabel(total - limit), { limit += 400 }, modifier = Modifier.padding(horizontal = 2.dp))
        }
    }
}

@Composable
private fun HunkHeader(text: String, wrap: Boolean) {
    val dev = LocalDevColors.current
    // The end padding keeps the header clear of the wrap toggle in the corner.
    Text(text, style = DevType.codeSmall, color = dev.hunkHeader, softWrap = wrap, maxLines = if (wrap) Int.MAX_VALUE else 1, modifier = Modifier.padding(start = 8.dp, end = 36.dp, top = 2.dp, bottom = 2.dp))
}

@Composable
private fun lineColors(line: DiffLine): Triple<androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.Color, String> {
    val dev = LocalDevColors.current
    return when (line.type) {
        DiffLine.Type.ADD -> Triple(dev.addedBackground, dev.addedText, "+")
        DiffLine.Type.DEL -> Triple(dev.removedBackground, dev.removedText, "-")
        DiffLine.Type.META -> Triple(dev.codeBackground, dev.gutter, " ")
        DiffLine.Type.CONTEXT -> Triple(dev.codeBackground, dev.codeText, " ")
    }
}

@Composable
private fun Gutter(line: DiffLine) {
    val dev = LocalDevColors.current
    // Numbers right-aligned in their column, the first one 8dp in from the card's edge (they sat
    // flush against it, left-aligned).
    Text(line.oldNumber?.toString() ?: "", style = DevType.codeSmall, color = dev.gutter, textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.padding(start = 8.dp).width(32.dp).padding(end = 4.dp), maxLines = 1, overflow = TextOverflow.Clip)
    Text(line.newNumber?.toString() ?: "", style = DevType.codeSmall, color = dev.gutter, textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.width(36.dp).padding(end = 6.dp), maxLines = 1, overflow = TextOverflow.Clip)
}

@Composable
private fun LineText(line: DiffLine, wrap: Boolean, modifier: Modifier = Modifier) {
    val (_, fg, sign) = lineColors(line)
    Text("$sign${line.text}", style = DevType.codeSmall, color = fg, softWrap = wrap, maxLines = if (wrap) Int.MAX_VALUE else 1, modifier = modifier)
}
