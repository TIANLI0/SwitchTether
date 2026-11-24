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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.text.style.TextAlign
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
    // The small title bar's measures: 56dp of bar, the back key on the 8dp edge, 12dp to the title,
    // keys 4dp apart and 12dp from the edge. The first version packed it into 6dp margins.
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        io.github.tianli0.coui17.patterns.BackArrow(onBack)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = COUITheme.textStyles.main.copy(fontWeight = FontWeight.SemiBold),
                color = COUITheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = COUITheme.textStyles.footnote1,
                    color = COUITheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/**
 * A file the app cannot show itself (an archive, an installer, an office document, a binary): what it
 * is, and the ways forward — keep it (Download), or hand it to another app. Never a dead end.
 */
@Composable
fun FileHandOffCard(
    name: String,
    detail: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    progress: Pair<Long, Long?>? = null,
    progressLabel: String? = null,
    primary: Pair<String, () -> Unit>? = null,
    secondary: Pair<String, () -> Unit>? = null,
    tertiary: Pair<String, () -> Unit>? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = io.github.tianli0.coui17.patterns.PageDefaults.CardGutter, vertical = 24.dp)
            .clip(SquircleShape(24.dp)).background(COUITheme.colorScheme.surfaceContainer)
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.size(72.dp).clip(SquircleShape(20.dp)).background(COUITheme.colorScheme.primary.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(fileIconVector(io.github.tianli0.switchtether.files.FileTypes.iconOf(name, isDir = false)), contentDescription = null, tint = COUITheme.colorScheme.primary, modifier = Modifier.size(36.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text(name, style = COUITheme.textStyles.main.copy(fontWeight = FontWeight.SemiBold), color = COUITheme.colorScheme.onSurface, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.MiddleEllipsis)
        Text(detail, style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurfaceVariantSummary, textAlign = TextAlign.Center)
        if (message != null) {
            Text(message, style = COUITheme.textStyles.body2, color = COUITheme.colorScheme.onSurfaceSecondary, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
        }
        if (progress != null) {
            val (done, total) = progress
            Column(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                io.github.suqi8.coui.kmp.basic.LinearProgressIndicator(progress = total?.let { if (it > 0) done.toFloat() / it else null }, modifier = Modifier.fillMaxWidth())
                if (progressLabel != null) {
                    Text(progressLabel, style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(top = 8.dp))
                }
            }
        } else if (primary != null || secondary != null || tertiary != null) {
            Column(Modifier.padding(top = 20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    primary?.let { (label, act) -> SmallButton(label, act, tone = io.github.tianli0.coui17.patterns.ActionTone.Primary) }
                    secondary?.let { (label, act) -> SmallButton(label, act) }
                }
                tertiary?.let { (label, act) -> io.github.tianli0.coui17.patterns.LinkButton(label, act) }
            }
        }
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
        modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 10.dp)
            .clip(SquircleShape(16.dp)).background(accent.copy(alpha = 0.10f))
            .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
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
        modifier = modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 10.dp),
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
