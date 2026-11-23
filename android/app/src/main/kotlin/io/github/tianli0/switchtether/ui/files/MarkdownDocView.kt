package io.github.tianli0.switchtether.ui.files

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.compose.LazyMarkdownSuccess
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCode
import com.mikepenz.markdown.model.DefaultMarkdownColors
import com.mikepenz.markdown.model.DefaultMarkdownTypography
import com.mikepenz.markdown.model.markdownAlertColors
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.SyntaxThemes
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.tianli0.switchtether.R
import io.github.tianli0.switchtether.files.FileTypes
import io.github.tianli0.switchtether.ui.common.LocalBottomBarSpace
import io.github.tianli0.switchtether.ui.theme.DevType
import io.github.tianli0.switchtether.ui.theme.LocalDevColors

/**
 * Rendered Markdown (GitHub flavour: tables, task lists, alerts, fenced code with syntax colours) via
 * multiplatform-markdown-renderer (Mike Penz, Apache-2.0), laid out lazily so long READMEs scroll smoothly.
 *
 * Links: web and mail links open in the browser / mail app; a relative or absolute file link is resolved against
 * [path] (the Markdown file on the computer) and handed to [onOpenPath] (null ignores it). Images are not fetched.
 */
@Composable
fun MarkdownDocView(text: String, path: String, onOpenPath: ((String) -> Unit)?, modifier: Modifier = Modifier) {
    val dev = LocalDevColors.current
    val scheme = COUITheme.colorScheme
    val context = LocalContext.current
    val noApp = stringResource(R.string.viewer_no_app)
    val uriHandler = remember(path, onOpenPath) {
        object : UriHandler {
            override fun openUri(uri: String) {
                val lower = uri.lowercase()
                when {
                    lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("mailto:") ->
                        startSafely(context, Intent(Intent.ACTION_VIEW, Uri.parse(uri)), noApp)
                    lower.startsWith("#") -> Unit
                    else -> resolveLink(path, uri)?.let { target -> onOpenPath?.invoke(target) }
                }
            }
        }
    }
    val body = COUITheme.textStyles.body2.copy(color = scheme.onSurface, lineHeight = 22.sp)
    val code = DevType.code.copy(color = dev.codeText)
    val colors = DefaultMarkdownColors(
        text = scheme.onSurface,
        codeBackground = androidx.compose.ui.graphics.Color.Transparent, // MarkdownCodeCard draws it
        inlineCodeBackground = dev.codeBackground,
        dividerColor = dev.gutter.copy(alpha = 0.35f),
        tableBackground = dev.codeBackground,
        alert = markdownAlertColors(darkTheme = dev.isDark),
        highlightText = scheme.onSurface,
        highlightBackground = scheme.primary.copy(alpha = 0.2f),
    )
    val typography = DefaultMarkdownTypography(
        h1 = body.copy(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold),
        h2 = body.copy(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold),
        h3 = body.copy(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
        h4 = body.copy(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
        h5 = body.copy(fontWeight = FontWeight.SemiBold),
        h6 = body.copy(fontWeight = FontWeight.SemiBold, color = scheme.onSurfaceVariantSummary),
        text = body,
        code = code,
        inlineCode = code.copy(fontSize = body.fontSize),
        quote = body.copy(color = scheme.onSurfaceVariantSummary),
        paragraph = body,
        ordered = body,
        bullet = body,
        list = body,
        textLink = TextLinkStyles(style = SpanStyle(color = scheme.primary, textDecoration = TextDecoration.Underline)),
        table = body,
    )
    // Fenced code uses the Highlights library with a theme that follows the app's light/dark mode.
    val highlights = remember(dev.isDark) { Highlights.Builder().theme(SyntaxThemes.themes(dev.isDark).getValue(if (dev.isDark) "darcula" else "notepad")) }
    val components = remember(highlights) {
        markdownComponents(
            // Each block is a card with its language and a Copy button; the code itself is highlighted
            // by the library and scrolls sideways rather than wrapping.
            codeFence = {
                MarkdownCodeFence(it.content, it.node, it.typography.code) { code, language, style ->
                    MarkdownCodeCard(code, language) { HighlightedCode(code, language, style, highlights) }
                }
            },
            codeBlock = {
                MarkdownCodeBlock(it.content, it.node, it.typography.code) { code, language, style ->
                    MarkdownCodeCard(code, language) { HighlightedCode(code, language, style, highlights) }
                }
            },
        )
    }
    val bottom = 24.dp + LocalBottomBarSpace.current
    CompositionLocalProvider(LocalUriHandler provides uriHandler) {
        SelectionContainer(modifier) {
            Markdown(
                content = text,
                colors = colors,
                typography = typography,
                components = components,
                modifier = Modifier.fillMaxSize(),
                loading = { m -> Box(m.padding(16.dp)) { Text(stringResource(R.string.state_loading), color = scheme.onSurfaceVariantSummary) } },
                success = { state, comps, m ->
                    LazyMarkdownSuccess(state, comps, m, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottom))
                },
            )
        }
    }
}

/**
 * Resolves a link inside a Markdown file at [docPath] to a path on the computer: `file://` and absolute paths
 * as they are, relative ones against the file's folder (`./`, `../` collapsed). Anchors and line suffixes are
 * dropped; anything with another URL scheme gives null.
 */
fun resolveLink(docPath: String, link: String): String? {
    val raw = link.trim().substringBefore('#').substringBefore('?')
    if (raw.isEmpty()) return null
    val decoded = runCatching { java.net.URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8") }.getOrDefault(raw)
    if (FileTypes.isLocalPath(decoded)) return FileTypes.stripFileScheme(decoded)
    if (Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(decoded)) return null
    val win = docPath.contains('\\')
    val sep = if (win) '\\' else '/'
    val dir = docPath.trimEnd('/', '\\').let { p -> p.substring(0, maxOf(p.lastIndexOf('/'), p.lastIndexOf('\\')).coerceAtLeast(0)) }
    val parts = ArrayList(dir.split('/', '\\'))
    for (seg in decoded.split('/', '\\')) {
        when (seg) {
            "", "." -> Unit
            ".." -> if (parts.size > 1) parts.removeAt(parts.lastIndex)
            else -> parts += seg
        }
    }
    val joined = parts.joinToString(sep.toString())
    return if (!win && docPath.startsWith("/") && !joined.startsWith("/")) "/$joined" else joined
}

/** The library's highlighted code without its own header or background (the card draws those). */
@Composable
private fun HighlightedCode(code: String, language: String?, style: androidx.compose.ui.text.TextStyle, highlights: Highlights.Builder) {
    androidx.compose.foundation.layout.Box(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState())) {
        MarkdownHighlightedCode(code = code, language = language, style = style, highlightsBuilder = highlights, showHeader = false)
    }
}
