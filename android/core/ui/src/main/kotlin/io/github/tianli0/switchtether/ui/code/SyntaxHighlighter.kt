package io.github.tianli0.switchtether.ui.code

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import io.github.tianli0.switchtether.ui.theme.DevColors

/**
 * Small, fast, regex-free tokenizer for "good enough" highlighting of common languages in code
 * blocks and diffs: keywords, strings, comments, numbers. Unknown languages get the generic
 * C-like rules. Output length always equals input length (spans only).
 */
object SyntaxHighlighter {
    private val keywords: Map<String, Set<String>> = run {
        val cLike = setOf(
            "if", "else", "for", "while", "do", "switch", "case", "default", "break", "continue", "return",
            "try", "catch", "finally", "throw", "new", "class", "interface", "enum", "struct", "public",
            "private", "protected", "static", "final", "const", "void", "import", "package", "true", "false",
            "null", "this", "super", "extends", "implements", "abstract", "override", "async", "await",
        )
        val kotlin = cLike + setOf("fun", "val", "var", "when", "object", "data", "sealed", "is", "in", "as", "suspend", "companion", "internal", "inline", "typealias", "by", "lazy")
        val js = cLike + setOf("function", "let", "var", "typeof", "instanceof", "export", "from", "undefined", "type", "yield", "of", "readonly", "namespace")
        val py = setOf("def", "class", "return", "if", "elif", "else", "for", "while", "in", "not", "and", "or", "is", "import", "from", "as", "with", "try", "except", "finally", "raise", "pass", "lambda", "yield", "None", "True", "False", "async", "await", "global", "nonlocal", "del", "assert", "break", "continue", "self")
        val rust = setOf("fn", "let", "mut", "pub", "struct", "enum", "impl", "trait", "use", "mod", "crate", "self", "Self", "match", "if", "else", "for", "while", "loop", "return", "as", "where", "async", "await", "move", "ref", "dyn", "const", "static", "true", "false", "Some", "None", "Ok", "Err", "unsafe", "type", "in", "break", "continue")
        val go = setOf("func", "package", "import", "var", "const", "type", "struct", "interface", "map", "chan", "go", "defer", "select", "if", "else", "for", "range", "switch", "case", "default", "return", "break", "continue", "nil", "true", "false")
        val sh = setOf("if", "then", "else", "elif", "fi", "for", "in", "do", "done", "while", "case", "esac", "function", "return", "export", "local", "echo", "cd", "set", "unset", "exit", "sudo")
        mapOf(
            "kotlin" to kotlin, "kt" to kotlin, "kts" to kotlin, "java" to cLike, "scala" to kotlin,
            "js" to js, "javascript" to js, "ts" to js, "typescript" to js, "tsx" to js, "jsx" to js,
            "py" to py, "python" to py, "rs" to rust, "rust" to rust, "go" to go,
            "sh" to sh, "bash" to sh, "zsh" to sh, "shell" to sh, "console" to sh, "ps1" to sh, "powershell" to sh,
            "c" to cLike, "cpp" to cLike, "c++" to cLike, "h" to cLike, "cs" to cLike, "csharp" to cLike, "swift" to kotlin,
            "json" to setOf("true", "false", "null"), "yaml" to setOf("true", "false", "null"), "toml" to setOf("true", "false"),
        )
    }

    fun languageForPath(path: String?): String? = path?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.isNotEmpty() }

    fun highlight(code: String, language: String?, colors: DevColors): AnnotatedString {
        val lang = language?.lowercase()?.trim()
        val kw = keywords[lang] ?: keywords.getValue("java")
        val hashComments = lang in setOf("py", "python", "sh", "bash", "zsh", "shell", "console", "yaml", "toml", "ps1", "powershell", "rb", "ruby")
        val slashComments = !hashComments || lang == null
        val kwStyle = SpanStyle(color = colors.keyword)
        val strStyle = SpanStyle(color = colors.string)
        val comStyle = SpanStyle(color = colors.comment, fontStyle = FontStyle.Italic)
        val numStyle = SpanStyle(color = colors.number)
        return buildAnnotatedString {
            append(code)
            var i = 0
            val n = code.length
            while (i < n) {
                val c = code[i]
                when {
                    slashComments && c == '/' && i + 1 < n && code[i + 1] == '/' -> {
                        val end = code.indexOf('\n', i).let { if (it < 0) n else it }
                        addStyle(comStyle, i, end); i = end
                    }
                    slashComments && c == '/' && i + 1 < n && code[i + 1] == '*' -> {
                        val end = code.indexOf("*/", i + 2).let { if (it < 0) n else it + 2 }
                        addStyle(comStyle, i, end); i = end
                    }
                    hashComments && c == '#' -> {
                        val end = code.indexOf('\n', i).let { if (it < 0) n else it }
                        addStyle(comStyle, i, end); i = end
                    }
                    c == '"' || c == '\'' || c == '`' -> {
                        var j = i + 1
                        while (j < n && code[j] != c && code[j] != '\n') {
                            if (code[j] == '\\') j++
                            j++
                        }
                        val end = (j + 1).coerceAtMost(n)
                        addStyle(strStyle, i, end); i = end
                    }
                    c.isDigit() && (i == 0 || !code[i - 1].isLetterOrDigit() && code[i - 1] != '_') -> {
                        var j = i
                        while (j < n && (code[j].isLetterOrDigit() || code[j] == '.' || code[j] == '_')) j++
                        addStyle(numStyle, i, j); i = j
                    }
                    c.isLetter() || c == '_' -> {
                        var j = i
                        while (j < n && (code[j].isLetterOrDigit() || code[j] == '_')) j++
                        if (code.substring(i, j) in kw) addStyle(kwStyle, i, j)
                        i = j
                    }
                    else -> i++
                }
            }
        }
    }
}
