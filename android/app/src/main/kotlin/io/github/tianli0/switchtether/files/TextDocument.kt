package io.github.tianli0.switchtether.files

/** One find-in-file hit: [start]/[end] are offsets in [TextDocument.display]; [line] is 0-based. */
data class TextMatch(val line: Int, val start: Int, val end: Int)

/**
 * A text file prepared for the line-based viewer: tabs expanded (so columns line up in a monospace font),
 * line start offsets indexed once, so a 2 MiB file renders as a lazy list of lines instead of one huge text.
 * Pure Kotlin (JVM-testable); the original text stays untouched for copying and editing.
 */
class TextDocument(val original: String, tabWidth: Int = 4) {
    val display: String = if (original.indexOf('\t') < 0) original else original.replace("\t", " ".repeat(tabWidth))

    /** Start offset of each line in [display]; a trailing newline does not add an empty last line. */
    private val starts: IntArray = run {
        val out = ArrayList<Int>(display.length / 32 + 1)
        out += 0
        var i = display.indexOf('\n')
        while (i >= 0) {
            if (i + 1 < display.length) out += i + 1
            i = display.indexOf('\n', i + 1)
        }
        out.toIntArray()
    }

    val lineCount: Int get() = if (display.isEmpty()) 1 else starts.size

    fun lineStart(line: Int): Int = starts[line.coerceIn(0, starts.lastIndex)]

    /** End offset of [line], excluding its `\n` / `\r\n`. */
    fun lineEnd(line: Int): Int {
        val l = line.coerceIn(0, starts.lastIndex)
        val start = starts[l]
        var end = if (l + 1 < starts.size) starts[l + 1] - 1 else display.length
        if (end > start && display[end - 1] == '\n') end-- // last line of a file ending in a newline
        if (end > start && display[end - 1] == '\r') end--
        return end
    }

    fun line(line: Int): String = display.substring(lineStart(line), lineEnd(line))

    /** 0-based line containing [offset]. */
    fun lineOf(offset: Int): Int {
        val idx = starts.binarySearch(offset.coerceIn(0, display.length))
        return if (idx >= 0) idx else (-idx - 2).coerceAtLeast(0)
    }

    /**
     * Width of the widest line in monospace cells: CJK and other wide characters count as two, so the sideways
     * scroll range of the viewer covers them.
     */
    val widestLineCells: Int by lazy {
        var best = 0
        for (l in 0 until lineCount) {
            val s = lineStart(l)
            val e = lineEnd(l)
            var cells = 0
            for (i in s until e) cells += if (isWide(display[i])) 2 else 1
            if (cells > best) best = cells
        }
        best
    }

    /** Case-insensitive (unless [matchCase]) occurrences of [query]; at most [limit], in order. Never spans lines. */
    fun find(query: String, matchCase: Boolean = false, limit: Int = MAX_MATCHES): List<TextMatch> {
        if (query.isEmpty() || query.contains('\n')) return emptyList()
        val out = ArrayList<TextMatch>()
        var from = 0
        while (out.size < limit) {
            val i = display.indexOf(query, from, ignoreCase = !matchCase)
            if (i < 0) break
            out += TextMatch(lineOf(i), i, i + query.length)
            from = i + query.length.coerceAtLeast(1)
        }
        return out
    }

    companion object {
        const val MAX_MATCHES = 5000

        fun isWide(c: Char): Boolean {
            val cp = c.code
            return cp >= 0x1100 && (
                cp <= 0x115F || cp in 0x2E80..0xA4CF || cp in 0xAC00..0xD7A3 || cp in 0xF900..0xFAFF ||
                    cp in 0xFE30..0xFE4F || cp in 0xFF00..0xFF60 || cp in 0xFFE0..0xFFE6
                )
        }
    }
}
