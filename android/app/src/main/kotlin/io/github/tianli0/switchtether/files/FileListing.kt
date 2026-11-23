package io.github.tianli0.switchtether.files

import io.github.tianli0.switchtether.remote.protocol.FsEntry

/** What the folder list is sorted by. Folders always come first, whatever the key. */
enum class SortKey { NAME, MODIFIED, SIZE, TYPE }

data class SortOrder(val key: SortKey = SortKey.NAME, val descending: Boolean = false) {
    /** Picking the active key again flips the direction; a new key starts in its natural direction. */
    fun toggled(next: SortKey): SortOrder =
        if (next == key) copy(descending = !descending) else SortOrder(next, descending = next == SortKey.MODIFIED || next == SortKey.SIZE)
}

/** One segment of the path bar: the label shown and the full path it opens. */
data class Crumb(val label: String, val path: String)

/** Pure helpers for the Files page list: sorting, quick filter, path breadcrumbs. */
object FileListing {
    /** Folders first, then by [order]; ties fall back to the natural name order (so the list is stable). */
    fun sort(entries: List<FsEntry>, order: SortOrder): List<FsEntry> {
        val byName = Comparator<FsEntry> { a, b -> naturalCompare(a.name, b.name) }
        val byKey: Comparator<FsEntry> = when (order.key) {
            SortKey.NAME -> byName
            SortKey.MODIFIED -> compareBy<FsEntry> { it.modifiedMs ?: Long.MIN_VALUE }.then(byName)
            SortKey.SIZE -> compareBy<FsEntry> { if (it.isDir) 0L else it.size }.then(byName)
            SortKey.TYPE -> Comparator<FsEntry> { a, b -> FileTypes.extension(a.name).compareTo(FileTypes.extension(b.name)) }.then(byName)
        }
        val directed = if (order.descending) byKey.reversed() else byKey
        return entries.sortedWith(compareBy<FsEntry> { !it.isDir }.then(directed))
    }

    /**
     * Quick filter of the current folder: every whitespace-separated term must appear in the name
     * (case-insensitive). A term starting with `*.` or `.` matches the extension exactly.
     */
    fun filter(entries: List<FsEntry>, query: String): List<FsEntry> {
        val terms = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (terms.isEmpty()) return entries
        return entries.filter { e -> terms.all { t -> matches(e, t) } }
    }

    private fun matches(e: FsEntry, term: String): Boolean {
        val ext = when {
            term.startsWith("*.") && term.length > 2 -> term.substring(2)
            term.startsWith(".") && term.length > 1 && !term.substring(1).contains('.') && !e.name.startsWith(term) -> term.substring(1)
            else -> null
        }
        if (ext != null) return !e.isDir && FileTypes.extension(e.name) == ext.lowercase()
        return e.name.contains(term, ignoreCase = true)
    }

    /**
     * "Natural" order: case-insensitive, and digit runs compare by value, so `file2` sorts before `file10`.
     */
    fun naturalCompare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                val si = i
                val sj = j
                while (i < a.length && a[i].isDigit()) i++
                while (j < b.length && b[j].isDigit()) j++
                val na = a.substring(si, i).trimStart('0')
                val nb = b.substring(sj, j).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
            } else {
                val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (c != 0) return c
                i++
                j++
            }
        }
        val rest = (a.length - i) - (b.length - j)
        return if (rest != 0) rest else a.compareTo(b)
    }

    /** Splits a Windows or POSIX path into tappable segments, each opening the folder up to it. */
    fun breadcrumbs(path: String): List<Crumb> {
        if (path.isBlank()) return emptyList()
        if (path.startsWith("\\\\")) {
            // UNC: \\server\share\dir → the share is the root segment.
            val parts = path.trimStart('\\').split('\\', '/').filter { it.isNotEmpty() }
            if (parts.size < 2) return listOf(Crumb(path, path))
            val root = "\\\\" + parts[0] + "\\" + parts[1]
            val out = mutableListOf(Crumb(parts[1], root))
            var acc = root
            parts.drop(2).forEach { p -> acc = "$acc\\$p"; out += Crumb(p, acc) }
            return out
        }
        val isWin = path.contains('\\') || (path.length >= 2 && path[1] == ':')
        val sep = if (isWin) "\\" else "/"
        val parts = path.split('/', '\\').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return listOf(Crumb("/", "/"))
        val out = mutableListOf<Crumb>()
        var acc = if (path.startsWith("/")) "/" else ""
        parts.forEachIndexed { idx, part ->
            acc = when {
                idx == 0 && isWin && part.endsWith(":") -> "$part\\"
                acc.isEmpty() || acc == "/" || acc.endsWith("\\") -> acc + part
                else -> acc + sep + part
            }
            out += Crumb(part, acc)
        }
        return out
    }

    /**
     * Like [breadcrumbs], but when [path] lies inside one of the desktop's shared [roots] (label to path) the bar
     * starts at that root, shown by its label: the levels above it are not browsable anyway.
     */
    fun breadcrumbs(path: String, roots: List<Crumb>): List<Crumb> {
        val all = breadcrumbs(path)
        val root = roots.filter { isInside(it.path, path) }.maxByOrNull { trimSep(it.path).length } ?: return all
        val rootNorm = trimSep(root.path)
        val idx = all.indexOfFirst { trimSep(it.path).equals(rootNorm, ignoreCase = isWindows(rootNorm)) }
        if (idx < 0) return all
        return listOf(Crumb(root.label.ifBlank { all[idx].label }, all[idx].path)) + all.drop(idx + 1)
    }

    private const val BACKSLASH = '\\'

    private fun trimSep(p: String) = p.trimEnd('/', BACKSLASH)

    private fun isSep(c: Char) = c == '/' || c == BACKSLASH

    private fun isWindows(p: String) = p.contains(BACKSLASH) || p.getOrNull(1) == ':'

    /** Whether [path] is [root] itself or below it (separator-aware; case-insensitive for Windows paths). */
    fun isInside(root: String, path: String): Boolean {
        val r = trimSep(root)
        if (r.isEmpty()) return path.startsWith("/")
        val p = trimSep(path)
        val ic = isWindows(r)
        if (p.equals(r, ignoreCase = ic)) return true
        return p.length > r.length && p.startsWith(r, ignoreCase = ic) && isSep(p[r.length])
    }

    /** [path] shown relative to [root] when it is below it (search results), else unchanged. */
    fun relativeTo(root: String?, path: String): String {
        if (root.isNullOrBlank()) return path
        val r = trimSep(root)
        if (r.isEmpty() || trimSep(path).length <= r.length || !isInside(r, path)) return path
        return path.substring(r.length + 1)
    }
}
