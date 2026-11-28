package io.github.tianli0.switchtether.ui.files

import io.github.tianli0.coui17.patterns.ActionTone
import io.github.tianli0.coui17.patterns.SmallButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.tianli0.switchtether.R
import io.github.tianli0.switchtether.files.FileTypes
import io.github.tianli0.switchtether.remote.protocol.FileContent
import io.github.tianli0.switchtether.ui.common.Formatters
import io.github.tianli0.coui17.patterns.LocalPageTopInset

/** How much of a text file `fs/read` returns (RemoteApi.fsRead's default `maxBytes`). */
const val TEXT_READ_LIMIT: Long = 2L * 1024 * 1024

/**
 * Body of an opened text file, shared by the Files page and links opened from chat: the line viewer, Markdown
 * rendered by default (with a Rendered / Source switch), a clear notice when only the beginning of a large file
 * was read, and a binary notice with the ways to still look at the file.
 */
@Composable
fun TextFileBody(
    file: FileContent,
    modifier: Modifier = Modifier,
    onOpenPath: ((String) -> Unit)? = null,
    onDownload: (() -> Unit)? = null,
    onOpenBinary: (() -> Unit)? = null,
    /**
     * The caller's title bar offers the viewer's tools and the Markdown rich text / source choice
     * (Files page); null = this body draws them itself (previews opened from chat).
     */
    controls: CodeViewerState? = null,
) {
    val text = file.text
    val markdown = text != null && FileTypes.isMarkdown(file.path)
    val fixedControls = file.truncated || text == null || (markdown && controls == null)
    val top = LocalPageTopInset.current
    Column(modifier.fillMaxSize().padding(top = if (fixedControls) top else 0.dp)) {
        if (file.truncated && file.text != null) {
            PreviewNotice(
                stringResource(R.string.files_truncated_banner, Formatters.bytes(TEXT_READ_LIMIT), Formatters.bytes(file.size)),
                NoticeTone.Warn,
            ) {
                if (onDownload != null) NoticeAction(stringResource(R.string.files_download), onDownload)
            }
        }
        if (text == null) {
            BinaryNotice(file, onDownload, onOpenBinary)
            return@Column
        }
        CompositionLocalProvider(LocalPageTopInset provides if (fixedControls) 0.dp else top) {
            if (markdown) {
                // Rich text or source; the choice carries over to the next Markdown file.
                val rendered = MarkdownPreviewMode.rendered
                if (controls == null) MarkdownModeSwitch(rendered, onChange = { MarkdownPreviewMode.rendered = it })
                if (rendered) {
                    MarkdownDocView(text, file.path, onOpenPath, Modifier.weight(1f).fillMaxWidth())
                } else {
                    CodeViewer(text, file.path, file.language ?: "markdown", Modifier.weight(1f), controls = controls)
                }
            } else {
                CodeViewer(text, file.path, file.language, Modifier.weight(1f), controls = controls)
            }
        }
    }
}

/** A file that is not text: what it is, and the ways to still look at it. */
@Composable
private fun BinaryNotice(file: FileContent, onDownload: (() -> Unit)?, onOpenBinary: (() -> Unit)?) {
    val name = FileTypes.fileName(file.path)
    io.github.tianli0.coui17.patterns.GroupCard(Modifier.padding(top = 12.dp)) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 28.dp, horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(fileIconVector(FileTypes.iconOf(name, isDir = false)), contentDescription = null, tint = COUITheme.colorScheme.primary, modifier = Modifier.size(44.dp))
            Text(name, style = COUITheme.textStyles.body1, color = COUITheme.colorScheme.onSurface, textAlign = TextAlign.Center)
            Text(
                listOf(Formatters.bytes(file.size), stringResource(R.string.files_binary)).joinToString(" · "),
                style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurfaceVariantSummary, textAlign = TextAlign.Center,
            )
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onOpenBinary != null) SmallButton(stringResource(R.string.files_view_binary), onOpenBinary, tone = ActionTone.Primary)
                if (onDownload != null) SmallButton(stringResource(R.string.files_download), onDownload)
            }
        }
    }
}
