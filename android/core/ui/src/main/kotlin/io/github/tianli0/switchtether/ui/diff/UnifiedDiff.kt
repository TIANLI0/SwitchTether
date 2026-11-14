package io.github.tianli0.switchtether.ui.diff

/** Parsed unified diff (git / `diff -u` / Claude Code structuredPatch rendered by the desktop). */
data class DiffFile(val oldPath: String?, val newPath: String?, val hunks: List<DiffHunk>) {
    val additions: Int get() = hunks.sumOf { h -> h.lines.count { it.type == DiffLine.Type.ADD } }
    val deletions: Int get() = hunks.sumOf { h -> h.lines.count { it.type == DiffLine.Type.DEL } }
    val displayPath: String get() = (newPath?.takeIf { it != "/dev/null" } ?: oldPath ?: "").removePrefix("b/").removePrefix("a/")
}

data class DiffHunk(val header: String, val lines: List<DiffLine>)

data class DiffLine(val type: Type, val text: String, val oldNumber: Int?, val newNumber: Int?) {
    enum class Type { CONTEXT, ADD, DEL, META }
}

object UnifiedDiff {
    private val hunkHeader = Regex("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@(.*)$")

    fun parse(diff: String): List<DiffFile> {
        val files = ArrayList<DiffFile>()
        var oldPath: String? = null
        var newPath: String? = null
        var hunks = ArrayList<DiffHunk>()
        var lines = ArrayList<DiffLine>()
        var header: String? = null
        var oldNo = 0
        var newNo = 0

        fun closeHunk() {
            header?.let { hunks += DiffHunk(it, lines) }
            header = null
            lines = ArrayList()
        }
        fun closeFile() {
            closeHunk()
            if (hunks.isNotEmpty() || oldPath != null || newPath != null) files += DiffFile(oldPath, newPath, hunks)
            hunks = ArrayList()
            oldPath = null
            newPath = null
        }

        // Remaining old/new line counts of the current hunk (from its header). When both reach 0
        // the hunk is complete, so a following "--- a/…" starts a new file instead of a deletion.
        var oldLeft = Int.MAX_VALUE
        var newLeft = Int.MAX_VALUE
        for (raw in diff.replace("\r\n", "\n").split('\n')) {
            if (header != null && oldLeft <= 0 && newLeft <= 0 && !raw.startsWith("\\")) closeHunk()
            when {
                raw.startsWith("diff --git ") -> closeFile()
                raw.startsWith("--- ") && header == null -> {
                    if (oldPath != null || hunks.isNotEmpty()) closeFile()
                    oldPath = raw.removePrefix("--- ").substringBefore('\t').trim()
                }
                raw.startsWith("+++ ") && header == null -> newPath = raw.removePrefix("+++ ").substringBefore('\t').trim()
                raw.startsWith("@@") -> {
                    closeHunk()
                    val m = hunkHeader.matchEntire(raw)
                    oldNo = m?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    newNo = m?.groupValues?.get(3)?.toIntOrNull() ?: 0
                    oldLeft = if (m == null) Int.MAX_VALUE else m.groupValues[2].ifEmpty { "1" }.toIntOrNull() ?: Int.MAX_VALUE
                    newLeft = if (m == null) Int.MAX_VALUE else m.groupValues[4].ifEmpty { "1" }.toIntOrNull() ?: Int.MAX_VALUE
                    header = raw
                }
                header != null -> when {
                    raw.startsWith("+") -> {
                        lines += DiffLine(DiffLine.Type.ADD, raw.substring(1), null, newNo++)
                        newLeft--
                    }
                    raw.startsWith("-") -> {
                        lines += DiffLine(DiffLine.Type.DEL, raw.substring(1), oldNo++, null)
                        oldLeft--
                    }
                    raw.startsWith("\\") -> lines += DiffLine(DiffLine.Type.META, raw, null, null)
                    else -> {
                        lines += DiffLine(DiffLine.Type.CONTEXT, raw.removePrefix(" "), oldNo++, newNo++)
                        oldLeft--
                        newLeft--
                    }
                }
                else -> Unit // git extended headers (index, mode, similarity…)
            }
        }
        closeFile()
        // Trailing empty context line produced by a final newline.
        return files.map { f ->
            f.copy(hunks = f.hunks.map { h ->
                val last = h.lines.lastOrNull()
                if (last != null && last.type == DiffLine.Type.CONTEXT && last.text.isEmpty()) h.copy(lines = h.lines.dropLast(1)) else h
            })
        }
    }
}
