package io.github.tianli0.switchtether.ui.markdown

import androidx.compose.foundation.background
import io.github.suqi8.coui.kmp.squircle.SquircleShape
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.tianli0.switchtether.ui.code.CodeBlockView
import io.github.tianli0.switchtether.ui.theme.LocalDevColors

/**
 * Native Compose markdown renderer (no WebView). Parsing is memoized per [markdown] string, so a
 * streaming message that grows re-parses only when its text changes.
 */
@Composable
fun MarkdownView(
    markdown: String,
    modifier: Modifier = Modifier,
    onLinkClick: ((String) -> Unit)? = null,
    onImageClick: ((alt: String, url: String) -> Unit)? = null,
    imageContent: (@Composable (alt: String, url: String) -> Unit)? = null,
) {
    val blocks = remember(markdown) { MarkdownParser.parse(markdown) }
    SelectionContainer(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            blocks.forEach { MdBlockView(it, onLinkClick, onImageClick, imageContent) }
        }
    }
}

@Composable
private fun inlineColors(): InlineColors {
    val dev = LocalDevColors.current
    // An inline span is a light tint of the text colour, not the inset of a code block: in the dark
    // theme the block colour is darker than the page and a word in it read as a hole in the line.
    return InlineColors(link = COUITheme.colorScheme.primary, codeText = dev.codeText, codeBackground = COUITheme.colorScheme.onSurface.copy(alpha = 0.08f))
}

@Composable
private fun MdBlockView(
    block: MdBlock,
    onLinkClick: ((String) -> Unit)?,
    onImageClick: ((String, String) -> Unit)?,
    imageContent: (@Composable (String, String) -> Unit)?,
) {
    val colors = inlineColors()
    val textColor = COUITheme.colorScheme.onSurface
    // Explicit SansSerif + FontSynthesis.All ensures **bold** renders reliably for both Latin and CJK.
    val baseBody = COUITheme.textStyles.body1.copy(fontFamily = FontFamily.SansSerif, fontSynthesis = FontSynthesis.All)
    when (block) {
        is MdBlock.Heading -> {
            val style = when (block.level) {
                1 -> baseBody.copy(fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold)
                2 -> baseBody.copy(fontSize = 18.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold)
                3 -> baseBody.copy(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold)
                4 -> baseBody.copy(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold)
                else -> baseBody.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold)
            }
            val rendered = remember(block.text, colors, onLinkClick, onImageClick) {
                InlineMarkdown.render(block.text, colors, onLinkClick, onImageClick)
            }
            Text(
                rendered,
                style = style,
                color = textColor,
                modifier = Modifier.padding(top = 4.dp).semantics { heading() },
            )
        }
        is MdBlock.Paragraph -> {
            val rendered = remember(block.text, colors, onLinkClick, onImageClick) {
                InlineMarkdown.render(block.text, colors, onLinkClick, onImageClick)
            }
            Text(
                rendered,
                style = baseBody,
                color = textColor,
            )
        }
        is MdBlock.Code -> CodeBlockView(code = block.code, language = block.language)
        is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(COUITheme.colorScheme.dividerLine))
            Column(Modifier.padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                block.blocks.forEach { MdBlockView(it, onLinkClick, onImageClick, imageContent) }
            }
        }
        is MdBlock.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            block.items.forEachIndexed { index, item ->
                Row {
                    val marker = when {
                        item.checked == true -> "☑"
                        item.checked == false -> "☐"
                        block.ordered -> "${block.start + index}."
                        else -> "•"
                    }
                    Text(marker, style = baseBody, color = COUITheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.widthIn(min = 20.dp))
                    Column(Modifier.padding(start = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        item.blocks.forEach { MdBlockView(it, onLinkClick, onImageClick, imageContent) }
                    }
                }
            }
        }
        is MdBlock.Table -> MdTable(block, colors, onLinkClick, onImageClick)
        is MdBlock.Image -> {
            if (imageContent != null) {
                imageContent(block.alt, block.url)
            } else {
                val fallback = remember(block.alt, block.url, colors, onLinkClick, onImageClick) {
                    InlineMarkdown.render("![${block.alt}](${block.url})", colors, onLinkClick, onImageClick)
                }
                Text(
                    fallback,
                    style = baseBody,
                    color = textColor,
                )
            }
        }
        MdBlock.Rule -> Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(COUITheme.colorScheme.dividerLine))
    }
}

@Composable
private fun MdTable(
    table: MdBlock.Table,
    colors: InlineColors,
    onLinkClick: ((String) -> Unit)?,
    onImageClick: ((String, String) -> Unit)?,
) {
    val border = COUITheme.colorScheme.dividerLine
    val headerFill = LocalDevColors.current.codeBackground
    val columns = maxOf(table.header.size, table.rows.maxOfOrNull { it.size } ?: 0)
    val rows = listOf(table.header).plus(table.rows)
    val cellStyle = COUITheme.textStyles.body2.copy(
        fontSize = 13.sp,
        fontFamily = FontFamily.SansSerif,
        fontSynthesis = FontSynthesis.All,
    )
    // A real grid: every column as wide as its widest cell (64–260dp), every row as tall as its
    // tallest cell. Rows used to size themselves, so columns started at a different x per row,
    // and the body rows' `surface` fill (black in the dark theme) only reached their own text.
    // Body cells now sit on the page colour; only the header row is filled, like the file preview.
    val grid = remember { TableGrid() }
    Box(
        Modifier
            .clip(SquircleShape(8.dp))
            .border(1.dp, border, SquircleShape(8.dp))
            .horizontalScroll(rememberScrollState()),
    ) {
        androidx.compose.ui.layout.Layout(
            content = {
                rows.forEachIndexed { r, row ->
                    for (c in 0 until columns) {
                        val align = when (table.align.getOrNull(c)) {
                            Align.CENTER -> TextAlign.Center
                            Align.END -> TextAlign.End
                            else -> TextAlign.Start
                        }
                        val cellText = row.getOrElse(c) { "" }
                        val cellRendered = remember(cellText, colors, onLinkClick, onImageClick) {
                            InlineMarkdown.render(cellText, colors, onLinkClick, onImageClick)
                        }
                        Text(
                            cellRendered,
                            style = cellStyle,
                            fontWeight = if (r == 0) FontWeight.Bold else null,
                            textAlign = align,
                            color = COUITheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        )
                    }
                }
            },
            modifier = Modifier.drawBehind {
                if (grid.rowBottoms.isEmpty()) return@drawBehind
                val line = 1.dp.toPx()
                drawRect(headerFill, size = androidx.compose.ui.geometry.Size(size.width, grid.rowBottoms[0].toFloat()))
                grid.rowBottoms.dropLast(1).forEach { y ->
                    drawRect(border, topLeft = androidx.compose.ui.geometry.Offset(0f, y.toFloat()), size = androidx.compose.ui.geometry.Size(size.width, line))
                }
                grid.columnRights.dropLast(1).forEach { x ->
                    drawRect(border, topLeft = androidx.compose.ui.geometry.Offset(x.toFloat(), 0f), size = androidx.compose.ui.geometry.Size(line, size.height))
                }
            },
        ) { measurables, _ ->
            val minW = 64.dp.roundToPx()
            val maxW = 260.dp.roundToPx()
            val line = 1.dp.roundToPx()
            val widths = IntArray(columns) { c ->
                (0 until rows.size).maxOf { r -> measurables[r * columns + c].maxIntrinsicWidth(Int.MAX_VALUE) }.coerceIn(minW, maxW)
            }
            val placeables = measurables.mapIndexed { i, m -> m.measure(androidx.compose.ui.unit.Constraints.fixedWidth(widths[i % columns])) }
            val heights = IntArray(rows.size) { r -> (0 until columns).maxOfOrNull { c -> placeables[r * columns + c].height } ?: 0 }
            val width = widths.sum() + line * (columns - 1).coerceAtLeast(0)
            val height = heights.sum() + line * (rows.size - 1).coerceAtLeast(0)
            grid.rowBottoms = heights.runningFold(0) { acc, h -> acc + h + line }.drop(1).map { it - line }
            grid.columnRights = widths.toList().runningFold(0) { acc, w -> acc + w + line }.drop(1).map { it - line }
            layout(width, height) {
                var y = 0
                for (r in rows.indices) {
                    var x = 0
                    for (c in 0 until columns) {
                        placeables[r * columns + c].place(x, y)
                        x += widths[c] + line
                    }
                    y += heights[r] + line
                }
            }
        }
    }
}

/** Where the table's rows end and columns end (px), from its last layout, for the dividers. */
private class TableGrid {
    var rowBottoms: List<Int> = emptyList()
    var columnRights: List<Int> = emptyList()
}
