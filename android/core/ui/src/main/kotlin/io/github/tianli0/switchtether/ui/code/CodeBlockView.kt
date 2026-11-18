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
import io.github.tianli0.switchtether.ui.icons.AppIcons
import io.github.suqi8.coui.kmp.basic.Icon
import android.content.ClipData
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Label of the copy button on code blocks (localized by the app). Null hides the button
 * (core:ui has no string resources of its own).
 */
val LocalCodeCopyLabel = compositionLocalOf<String?> { null }

/** Labels of the wrap toggle (wrap lines, scroll sideways), localized by the app; null hides it. */
val LocalCodeWrapLabels = compositionLocalOf<Pair<String, String>?> { null }

/** Monospace code block with lightweight syntax highlighting, horizontal scrolling and a copy button. */
@Composable
fun CodeBlockView(
    code: String,
    language: String?,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    highlight: Boolean = true,
    softWrap: Boolean = false,
    copyable: Boolean = true,
    /** Shows a small icon in the header that switches between wrapped lines and sideways scrolling. */
    wrapToggle: Boolean = false,
) {
    var wrapped by androidx.compose.runtime.saveable.rememberSaveable(code.hashCode()) { mutableStateOf(softWrap) }
    val wrapLabels = LocalCodeWrapLabels.current?.takeIf { wrapToggle }
    @Suppress("NAME_SHADOWING")
    val softWrap = if (wrapLabels != null) wrapped else softWrap
    val dev = LocalDevColors.current
    val text = remember(code, language, dev, highlight) {
        if (highlight && code.length <= 64 * 1024) SyntaxHighlighter.highlight(code, language, dev) else null
    }
    val copyLabel = LocalCodeCopyLabel.current?.takeIf { copyable && code.isNotBlank() }
    Column(
        modifier
            .fillMaxWidth()
            .clip(SquircleShape(8.dp))
            .background(dev.codeBackground),
    ) {
        // A header only when there is a label or a wrap toggle; a bare block (e.g. a path) keeps its
        // copy icon on the text's own line instead of an empty header row.
        val header = !language.isNullOrBlank() || wrapLabels != null
        if (header) {
            Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(language.orEmpty(), style = DevType.label, color = dev.gutter, modifier = Modifier.weight(1f))
                if (wrapLabels != null) {
                    val label = if (wrapped) wrapLabels.second else wrapLabels.first
                    Box(
                        Modifier.size(36.dp).clip(CircleShape)
                            .clickable(role = Role.Button, onClickLabel = label) { wrapped = !wrapped }
                            .semantics { contentDescription = label },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(if (wrapped) AppIcons.ScrollSideways else AppIcons.WrapLines, contentDescription = null, tint = dev.gutter, modifier = Modifier.size(16.dp))
                    }
                }
                if (copyLabel != null) CopyButton(code, copyLabel)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).then(if (softWrap) Modifier else Modifier.horizontalScroll(rememberScrollState()))
                .padding(start = 10.dp, end = 10.dp, bottom = 8.dp, top = if (header) 0.dp else 8.dp),
        ) {
            if (text != null) {
                Text(text, style = DevType.code, color = dev.codeText, softWrap = softWrap, maxLines = maxLines)
            } else {
                Text(code, style = DevType.code, color = dev.codeText, softWrap = softWrap, maxLines = maxLines)
            }
        }
        if (!header && copyLabel != null) CopyButton(code, copyLabel, Modifier.padding(end = 2.dp))
        }
    }
}

/** Small copy icon; shows a check for a moment after copying. */
@Composable
fun CopyButton(text: String, label: String, modifier: Modifier = Modifier) {
    val dev = LocalDevColors.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    Box(
        modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = label) {
                scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(label, text))) }
                copied = true
            }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(if (copied) AppIcons.Done else AppIcons.Copy, contentDescription = null, tint = if (copied) dev.ok else dev.gutter, modifier = Modifier.size(16.dp))
    }
}
