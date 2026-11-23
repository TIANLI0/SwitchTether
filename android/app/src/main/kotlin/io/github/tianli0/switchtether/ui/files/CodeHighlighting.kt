package io.github.tianli0.switchtether.ui.files

import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.BoldHighlight
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.SyntaxLanguage
import dev.snipme.highlights.model.SyntaxTheme
import io.github.tianli0.switchtether.files.FileTypes
import io.github.tianli0.switchtether.ui.code.SyntaxHighlighter
import io.github.tianli0.switchtether.ui.theme.DevColors

/**
 * Syntax highlighting for the file viewer. Languages the Highlights library (SnipMeDev, Apache-2.0) knows are
 * tokenized by it; other text formats (JSON, YAML, TOML, XML, SQL…) fall back to the app's small built-in
 * tokenizer. Both are coloured with the app's code palette, so light/dark follow the app theme.
 */
object CodeHighlighting {
    /** Above this size highlighting is skipped: the file still shows instantly, just in plain colours. */
    const val MAX_CHARS = 384 * 1024

    private val byExtension: Map<String, SyntaxLanguage> = mapOf(
        "kt" to SyntaxLanguage.KOTLIN, "kts" to SyntaxLanguage.KOTLIN,
        "java" to SyntaxLanguage.JAVA, "gradle" to SyntaxLanguage.JAVA, "groovy" to SyntaxLanguage.JAVA, "scala" to SyntaxLanguage.JAVA,
        "rs" to SyntaxLanguage.RUST, "py" to SyntaxLanguage.PYTHON, "pyw" to SyntaxLanguage.PYTHON,
        "js" to SyntaxLanguage.JAVASCRIPT, "mjs" to SyntaxLanguage.JAVASCRIPT, "cjs" to SyntaxLanguage.JAVASCRIPT, "jsx" to SyntaxLanguage.JAVASCRIPT,
        "ts" to SyntaxLanguage.TYPESCRIPT, "tsx" to SyntaxLanguage.TYPESCRIPT, "mts" to SyntaxLanguage.TYPESCRIPT, "cts" to SyntaxLanguage.TYPESCRIPT,
        "vue" to SyntaxLanguage.TYPESCRIPT, "svelte" to SyntaxLanguage.TYPESCRIPT,
        "c" to SyntaxLanguage.C, "h" to SyntaxLanguage.C, "m" to SyntaxLanguage.C,
        "cpp" to SyntaxLanguage.CPP, "cc" to SyntaxLanguage.CPP, "cxx" to SyntaxLanguage.CPP, "hpp" to SyntaxLanguage.CPP, "hh" to SyntaxLanguage.CPP, "hxx" to SyntaxLanguage.CPP, "mm" to SyntaxLanguage.CPP,
        "cs" to SyntaxLanguage.CSHARP, "dart" to SyntaxLanguage.DART, "go" to SyntaxLanguage.GO, "php" to SyntaxLanguage.PHP,
        "rb" to SyntaxLanguage.RUBY, "pl" to SyntaxLanguage.PERL, "pm" to SyntaxLanguage.PERL, "swift" to SyntaxLanguage.SWIFT,
        "sh" to SyntaxLanguage.SHELL, "bash" to SyntaxLanguage.SHELL, "zsh" to SyntaxLanguage.SHELL, "fish" to SyntaxLanguage.SHELL,
        "coffee" to SyntaxLanguage.COFFEESCRIPT,
    )

    private val byName: Map<String, SyntaxLanguage> = mapOf(
        "kotlin" to SyntaxLanguage.KOTLIN, "java" to SyntaxLanguage.JAVA, "rust" to SyntaxLanguage.RUST, "python" to SyntaxLanguage.PYTHON,
        "javascript" to SyntaxLanguage.JAVASCRIPT, "typescript" to SyntaxLanguage.TYPESCRIPT, "c" to SyntaxLanguage.C, "cpp" to SyntaxLanguage.CPP,
        "c++" to SyntaxLanguage.CPP, "csharp" to SyntaxLanguage.CSHARP, "c#" to SyntaxLanguage.CSHARP, "dart" to SyntaxLanguage.DART,
        "go" to SyntaxLanguage.GO, "php" to SyntaxLanguage.PHP, "ruby" to SyntaxLanguage.RUBY, "perl" to SyntaxLanguage.PERL,
        "swift" to SyntaxLanguage.SWIFT, "shell" to SyntaxLanguage.SHELL, "bash" to SyntaxLanguage.SHELL, "sh" to SyntaxLanguage.SHELL,
        "zsh" to SyntaxLanguage.SHELL, "coffeescript" to SyntaxLanguage.COFFEESCRIPT,
    )

    /** Files the built-in fallback tokenizer handles reasonably (keywords, strings, comments, numbers). */
    private val fallbackExtensions = setOf(
        "json", "jsonc", "json5", "yaml", "yml", "toml", "ini", "cfg", "conf", "properties", "env", "sql", "xml", "html", "htm",
        "css", "scss", "less", "lua", "r", "ps1", "psm1", "bat", "cmd", "proto", "graphql", "tf", "nix", "zig", "ex", "exs", "hs", "ml",
    )

    /** The Highlights language for a file (by the desktop's `language` hint, then by extension), or null. */
    fun libraryLanguage(path: String, serverLanguage: String?): SyntaxLanguage? =
        serverLanguage?.lowercase()?.trim()?.let { byName[it] ?: byExtension[it] } ?: byExtension[FileTypes.extension(path)]

    /** A short label for the viewer's info line ("kotlin", "json"…); null for plain text. */
    fun label(path: String, serverLanguage: String?): String? =
        serverLanguage?.takeIf { it.isNotBlank() } ?: libraryLanguage(path, null)?.name?.lowercase()
            ?: FileTypes.extension(path).takeIf { it in fallbackExtensions }

    /** Highlighted copy of [text] (same length, spans only), or null when it is plain text or too large. */
    fun highlight(text: String, path: String, serverLanguage: String?, colors: DevColors): AnnotatedString? {
        if (text.isEmpty() || text.length > MAX_CHARS) return null
        val lang = libraryLanguage(path, serverLanguage)
        if (lang != null) return runCatching { withLibrary(text, lang, colors) }.getOrNull()
        val ext = FileTypes.extension(path)
        val fallback = serverLanguage?.lowercase() ?: ext.takeIf { it in fallbackExtensions } ?: return null
        return SyntaxHighlighter.highlight(text, fallback, colors)
    }

    private fun withLibrary(text: String, lang: SyntaxLanguage, colors: DevColors): AnnotatedString {
        val plain = colors.codeText.rgb()
        val theme = SyntaxTheme(
            key = if (colors.isDark) "switchtether-dark" else "switchtether-light",
            code = plain,
            keyword = colors.keyword.rgb(),
            string = colors.string.rgb(),
            literal = colors.number.rgb(),
            comment = colors.comment.rgb(),
            metadata = colors.info.rgb(),
            multilineComment = colors.comment.rgb(),
            punctuation = plain,
            mark = plain,
        )
        val highlights = Highlights.Builder().code(text).language(lang).theme(theme).build().getHighlights()
        return buildAnnotatedString {
            append(text)
            for (h in highlights) {
                val s = h.location.start.coerceIn(0, text.length)
                val e = h.location.end.coerceIn(s, text.length)
                if (s == e) continue
                when (h) {
                    is ColorHighlight -> if (h.rgb and 0xFFFFFF != plain) addStyle(SpanStyle(color = androidx.compose.ui.graphics.Color(0xFF000000.toInt() or h.rgb)), s, e)
                    is BoldHighlight -> addStyle(SpanStyle(fontWeight = FontWeight.Bold), s, e)
                }
            }
        }
    }

    private fun androidx.compose.ui.graphics.Color.rgb(): Int = toArgb() and 0xFFFFFF
}
