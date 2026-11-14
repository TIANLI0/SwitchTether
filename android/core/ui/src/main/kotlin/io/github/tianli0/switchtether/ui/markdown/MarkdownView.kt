package io.github.tianli0.switchtether.ui.markdown

import androidx.compose.foundation.background
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
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
fun MarkdownView(markdown: String, modifier: Modifier = Modifier, onLinkClick: ((String) -> Unit)? = null) {
    val blocks = remember(markdown) { MarkdownParser.parse(markdown) }
    SelectionContainer(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            blocks.forEach { MdBlockView(it, onLinkClick) }
        }
    }
}

@Composable
private fun inlineColors(): InlineColors {
    val dev = LocalDevColors.current
    return InlineColors(link = COUITheme.colorScheme.primary, codeText = dev.codeText, codeBackground = dev.codeBackground)
}

@Composable
private fun MdBlockView(block: MdBlock, onLinkClick: ((String) -> Unit)?) {
    val colors = inlineColors()
    val textColor = COUITheme.colorScheme.onSurface
    when (block) {
        is MdBlock.Heading -> {
            val style = when (block.level) {
                1 -> COUITheme.textStyles.title3
                2 -> COUITheme.textStyles.title4
                else -> COUITheme.textStyles.body1.copy(fontWeight = FontWeight.SemiBold)
            }
            Text(
                InlineMarkdown.render(block.text, colors, onLinkClick),
                style = style,
                color = textColor,
                modifier = Modifier.padding(top = 4.dp).semantics { heading() },
            )
        }
        is MdBlock.Paragraph -> Text(InlineMarkdown.render(block.text, colors, onLinkClick), style = COUITheme.textStyles.body1, color = textColor)
        is MdBlock.Code -> CodeBlockView(code = block.code, language = block.language)
        is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(COUITheme.colorScheme.dividerLine))
            Column(Modifier.padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                block.blocks.forEach { MdBlockView(it, onLinkClick) }
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
                    Text(marker, style = COUITheme.textStyles.body1, color = COUITheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.widthIn(min = 20.dp))
                    Column(Modifier.padding(start = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        item.blocks.forEach { MdBlockView(it, onLinkClick) }
                    }
                }
            }
        }
        is MdBlock.Table -> MdTable(block, colors, onLinkClick)
        MdBlock.Rule -> Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(COUITheme.colorScheme.dividerLine))
    }
}

@Composable
private fun MdTable(table: MdBlock.Table, colors: InlineColors, onLinkClick: ((String) -> Unit)?) {
    val border = COUITheme.colorScheme.dividerLine
    val columns = maxOf(table.header.size, table.rows.maxOfOrNull { it.size } ?: 0)
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, border, RoundedCornerShape(8.dp))
            .horizontalScroll(rememberScrollState()),
    ) {
        Column {
            listOf(table.header).plus(table.rows).forEachIndexed { r, row ->
                Row(Modifier.background(if (r == 0) LocalDevColors.current.codeBackground else COUITheme.colorScheme.surface)) {
                    for (c in 0 until columns) {
                        val align = when (table.align.getOrNull(c)) {
                            Align.CENTER -> TextAlign.Center
                            Align.END -> TextAlign.End
                            else -> TextAlign.Start
                        }
                        Text(
                            InlineMarkdown.render(row.getOrElse(c) { "" }, colors, onLinkClick),
                            style = COUITheme.textStyles.body2.copy(fontSize = 13.sp),
                            fontWeight = if (r == 0) FontWeight.SemiBold else null,
                            textAlign = align,
                            color = COUITheme.colorScheme.onSurface,
                            modifier = Modifier.widthIn(min = 64.dp, max = 260.dp).padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
}
