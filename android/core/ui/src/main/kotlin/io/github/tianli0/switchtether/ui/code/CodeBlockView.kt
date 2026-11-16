package io.github.tianli0.switchtether.ui.code

import androidx.compose.foundation.background
import io.github.suqi8.coui.kmp.squircle.SquircleShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.Text
import io.github.tianli0.switchtether.ui.theme.DevType
import io.github.tianli0.switchtether.ui.theme.LocalDevColors

/** Monospace code block with lightweight syntax highlighting and horizontal scrolling. */
@Composable
fun CodeBlockView(
    code: String,
    language: String?,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    highlight: Boolean = true,
) {
    val dev = LocalDevColors.current
    val text = remember(code, language, dev, highlight) {
        if (highlight && code.length <= 64 * 1024) SyntaxHighlighter.highlight(code, language, dev) else null
    }
    Column(
        modifier
            .fillMaxWidth()
            .clip(SquircleShape(8.dp))
            .background(dev.codeBackground),
    ) {
        if (!language.isNullOrBlank()) {
            Row(Modifier.fillMaxWidth().padding(start = 10.dp, top = 6.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(language, style = DevType.label, color = dev.gutter)
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 8.dp)) {
            if (text != null) {
                Text(text, style = DevType.code, color = dev.codeText, softWrap = false, maxLines = maxLines)
            } else {
                Text(code, style = DevType.code, color = dev.codeText, softWrap = false, maxLines = maxLines)
            }
        }
    }
}
