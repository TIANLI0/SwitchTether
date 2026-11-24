package io.github.tianli0.switchtether.ui.files

import io.github.tianli0.coui17.patterns.SmallButton
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.HighlightedIconButton
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.TabRowWithContour
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.couiTopAppBarActionItem
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Back
import io.github.suqi8.coui.kmp.squircle.SquircleShape
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.tianli0.switchtether.R
import io.github.tianli0.switchtether.ui.icons.AppIcons
import io.github.tianli0.switchtether.ui.theme.DevType
import io.github.tianli0.switchtether.ui.theme.LocalDevColors

/**
 * The file preview's title bar, drawn like the app's small top bar (`Page` with a back arrow): the
 * COUI back button, the file name with a one-line subtitle (folder, size, kind), and COUI action
 * buttons. It has no Scaffold or window insets of its own, so the same preview fits a full page,
 * the workspace's Files pane and the full-screen dialog opened from chat.
 */
@Composable
fun FilePreviewHeader(
    title: String,
    subtitle: String?,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        io.github.tianli0.coui17.patterns.BackArrow(onBack)
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = COUITheme.textStyles.body1.copy(fontWeight = FontWeight.SemiBold),
                color = COUITheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = COUITheme.textStyles.footnote2,
                    color = COUITheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/** Folder of [path] as shown under a file name ("…/src/main"), or null for a bare name. */
fun previewFolder(path: String): String? {
    val p = path.replace('\\', '/').trimEnd('/')
    val i = p.lastIndexOf('/')
    if (i <= 0) return null
    val dir = p.substring(0, i)
    val parts = dir.split('/').filter { it.isNotEmpty() }
    return if (parts.size <= 3) dir else "…/" + parts.takeLast(3).joinToString("/")
}

enum class NoticeTone { Info, Warn, Danger }

/** A rounded, tinted notice in the preview (large file cut, binary, failure), with optional actions. */
@Composable
fun PreviewNotice(text: String, tone: NoticeTone, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    val dev = LocalDevColors.current
    val accent: Color = when (tone) {
        NoticeTone.Info -> COUITheme.colorScheme.primary
        NoticeTone.Warn -> dev.warn
        NoticeTone.Danger -> dev.danger
    }
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(SquircleShape(14.dp)).background(accent.copy(alpha = 0.10f))
            .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        Row(content = actions)
    }
}

/** How Markdown files open: rendered (rich text) or as source code. Kept for the whole app session, so a
 *  reader who prefers source does not switch on every file. */
object MarkdownPreviewMode {
    var rendered by mutableStateOf(true)
}

/** The Markdown "Preview | Source" switch: the segmented control used across the app (e.g. New agent). */
@Composable
fun MarkdownModeSwitch(rendered: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    TabRowWithContour(
        tabs = listOf(stringResource(R.string.files_md_rendered), stringResource(R.string.files_md_source)),
        selectedTabIndex = if (rendered) 0 else 1,
        onTabSelected = { onChange(it == 0) },
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

/**
 * A fenced code block inside rendered Markdown: a rounded card on the code background with a header row
 * (language, Copy) above the highlighted code, which scrolls sideways instead of wrapping.
 */
@Composable
fun MarkdownCodeCard(code: String, language: String?, content: @Composable () -> Unit) {
    val dev = LocalDevColors.current
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val copied = stringResource(R.string.files_code_copied)
    Column(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(SquircleShape(14.dp)).background(dev.codeBackground),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                language?.takeIf { it.isNotBlank() } ?: stringResource(R.string.files_code_plain),
                style = DevType.label,
                color = dev.gutter,
                modifier = Modifier.weight(1f),
            )
            HighlightedIconButton(
                onClick = {
                    clipboard.setText(AnnotatedString(code))
                    android.widget.Toast.makeText(context, copied, android.widget.Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(AppIcons.Copy, contentDescription = stringResource(R.string.files_code_copy), tint = dev.gutter, modifier = Modifier.size(16.dp))
            }
        }
        Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp)) { content() }
    }
}

/** Action of a notice: a compact capsule, not a page-level button. */
@Composable
fun NoticeAction(text: String, onClick: () -> Unit) {
    SmallButton(text, onClick)
}
