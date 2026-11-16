package io.github.tianli0.switchtether.ui.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.em

/** Colors used by inline markdown. */
data class InlineColors(val link: Color, val codeText: Color, val codeBackground: Color)

/**
 * Inline markdown → [AnnotatedString]: emphasis, strong, strikethrough, code spans, links,
 * autolinks, escapes. Images are rendered as `[image: alt]` text — remote content is never
 * fetched. Links are [LinkAnnotation.Clickable] so the caller decides (e.g. confirm before opening).
 */
object InlineMarkdown {
    private val autolink = Regex("""https?://[^\s<>()\[\]]+[^\s<>()\[\].,;:!?'"]""")

    fun render(
        text: String,
        colors: InlineColors,
        onLink: ((String) -> Unit)? = null,
        onImage: ((alt: String, url: String) -> Unit)? = null,
    ): AnnotatedString = buildAnnotatedString { appendInline(text, colors, onLink, onImage) }

    private fun AnnotatedString.Builder.appendInline(
        s: String,
        colors: InlineColors,
        onLink: ((String) -> Unit)?,
        onImage: ((String, String) -> Unit)?,
    ) {
        var i = 0
        val plain = StringBuilder()
        fun flushPlain() {
            if (plain.isEmpty()) return
            appendAutolinked(plain.toString(), colors, onLink)
            plain.clear()
        }
        while (i < s.length) {
            val c = s[i]
            // escapes
            if (c == '\\' && i + 1 < s.length && s[i + 1] in "\\`*_{}[]()#+-.!|~<>") {
                plain.append(s[i + 1]); i += 2; continue
            }
            // inline code span
            if (c == '`') {
                val ticks = s.countRun(i, '`')
                val close = s.indexOf("`".repeat(ticks), i + ticks)
                if (close > 0) {
                    flushPlain()
                    val code = s.substring(i + ticks, close).let { if (it.startsWith(' ') && it.endsWith(' ') && it.isNotBlank()) it.substring(1, it.length - 1) else it }
                    pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = colors.codeText, background = colors.codeBackground, fontSize = 0.92.em))
                    append(code)
                    pop()
                    i = close + ticks
                    continue
                }
            }
            // ***bold italic*** or ___bold italic___
            if ((c == '*' || c == '_') && i + 2 < s.length && s[i + 1] == c && s[i + 2] == c) {
                val delim = "$c$c$c"
                val close = findClosingDelimiter(s, i + 3, delim)
                if (close > i + 3) {
                    flushPlain()
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic))
                    appendInline(s.substring(i + 3, close), colors, onLink, onImage)
                    pop()
                    i = close + 3
                    continue
                }
            }
            // **bold** or __bold__
            if ((c == '*' || c == '_') && i + 1 < s.length && s[i + 1] == c) {
                val delim = "$c$c"
                val close = findClosingDelimiter(s, i + 2, delim)
                if (close > i + 2) {
                    flushPlain()
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    appendInline(s.substring(i + 2, close), colors, onLink, onImage)
                    pop()
                    i = close + 2
                    continue
                }
            }
            if (c == '~' && i + 1 < s.length && s[i + 1] == '~') {
                val close = findClosingDelimiter(s, i + 2, "~~")
                if (close > i + 2) {
                    flushPlain()
                    pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                    appendInline(s.substring(i + 2, close), colors, onLink, onImage)
                    pop()
                    i = close + 2
                    continue
                }
            }
            if ((c == '*' || c == '_') && i + 1 < s.length && !s[i + 1].isWhitespace()) {
                val wordBoundaryOk = c == '*' || i == 0 || !s[i - 1].isLetterOrDigit()
                var close = findClosingDelimiter(s, i + 1, c.toString())
                while (close > 0 && close + 1 < s.length && s[close + 1] == c) close = findClosingDelimiter(s, close + 2, c.toString())
                if (wordBoundaryOk && close > i + 1 && !s[close - 1].isWhitespace() &&
                    (c == '*' || close + 1 >= s.length || !s[close + 1].isLetterOrDigit())
                ) {
                    flushPlain()
                    pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    appendInline(s.substring(i + 1, close), colors, onLink, onImage)
                    pop()
                    i = close + 1
                    continue
                }
            }
            if (c == '!' && i + 1 < s.length && s[i + 1] == '[') {
                val link = parseLink(s, i + 1)
                if (link != null) {
                    flushPlain()
                    val alt = link.first.ifBlank { link.second }
                    if (onImage != null) {
                        val style = TextLinkStyles(SpanStyle(color = colors.link, fontStyle = FontStyle.Italic, textDecoration = TextDecoration.Underline))
                        pushLink(LinkAnnotation.Clickable(tag = link.second, styles = style) { onImage(alt, link.second) })
                        append("[image: $alt]")
                        pop()
                    } else {
                        pushStyle(SpanStyle(color = colors.link, fontStyle = FontStyle.Italic))
                        append("[image: $alt]")
                        pop()
                    }
                    i = link.third
                    continue
                }
            }
            if (c == '[') {
                val link = parseLink(s, i)
                if (link != null) {
                    flushPlain()
                    appendLink(link.first, link.second, colors, onLink)
                    i = link.third
                    continue
                }
            }
            if (c == '<') {
                val close = s.indexOf('>', i + 1)
                if (close > 0) {
                    val inner = s.substring(i + 1, close)
                    if (inner.startsWith("http://") || inner.startsWith("https://")) {
                        flushPlain()
                        appendLink(inner, inner, colors, onLink)
                        i = close + 1
                        continue
                    }
                }
            }
            plain.append(c)
            i++
        }
        flushPlain()
    }

    /** Find closing delimiter skipping backtick code spans and backslash escapes. */
    private fun findClosingDelimiter(s: String, from: Int, delim: String): Int {
        var i = from
        while (i <= s.length - delim.length) {
            val ch = s[i]
            if (ch == '\\' && i + 1 < s.length) {
                i += 2
                continue
            }
            if (ch == '`') {
                val ticks = s.countRun(i, '`')
                val close = s.indexOf("`".repeat(ticks), i + ticks)
                if (close > 0) {
                    i = close + ticks
                    continue
                }
            }
            if (s.startsWith(delim, i)) return i
            i++
        }
        return -1
    }

    /** Returns (text, url, endIndexExclusive) for `[text](url "title")` starting at [start]. */
    private fun parseLink(s: String, start: Int): Triple<String, String, Int>? {
        var depth = 0
        var k = start
        while (k < s.length) {
            when (s[k]) {
                '\\' -> k++
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) break
                }
            }
            k++
        }
        if (k >= s.length || k + 1 >= s.length || s[k + 1] != '(') return null
        var pDepth = 1
        var j = k + 2
        while (j < s.length && pDepth > 0) {
            when (s[j]) {
                '\\' -> j++
                '(' -> pDepth++
                ')' -> pDepth--
            }
            if (pDepth > 0) j++
        }
        if (pDepth != 0) return null
        val text = s.substring(start + 1, k)
        val url = s.substring(k + 2, j).trim().substringBefore(' ').removePrefix("<").removeSuffix(">")
        return Triple(text, url, j + 1)
    }

    private fun AnnotatedString.Builder.appendLink(text: String, url: String, colors: InlineColors, onLink: ((String) -> Unit)?) {
        val style = TextLinkStyles(SpanStyle(color = colors.link, textDecoration = TextDecoration.Underline))
        if (onLink != null && (url.startsWith("http://") || url.startsWith("https://"))) {
            pushLink(LinkAnnotation.Clickable(tag = url, styles = style) { onLink(url) })
            append(text)
            pop()
        } else {
            pushStyle(style.style ?: SpanStyle(color = colors.link))
            append(text)
            pop()
        }
    }

    private fun AnnotatedString.Builder.appendAutolinked(text: String, colors: InlineColors, onLink: ((String) -> Unit)?) {
        var last = 0
        for (m in autolink.findAll(text)) {
            append(text.substring(last, m.range.first))
            appendLink(m.value, m.value, colors, onLink)
            last = m.range.last + 1
        }
        append(text.substring(last))
    }

    private fun String.countRun(from: Int, ch: Char): Int {
        var n = 0
        while (from + n < length && this[from + n] == ch) n++
        return n
    }
}
