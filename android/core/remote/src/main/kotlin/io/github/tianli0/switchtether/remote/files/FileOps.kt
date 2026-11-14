package io.github.tianli0.switchtether.remote.files

/** Builds a unified diff (desktop → mine) for the conflict screen. Line-based LCS, capped. */
object TextDiff {
    fun unified(oldText: String, newText: String, oldLabel: String = "desktop", newLabel: String = "mine", context: Int = 3): String {
        val a = oldText.replace("\r\n", "\n").split('\n')
        val b = newText.replace("\r\n", "\n").split('\n')
        // Trim common prefix/suffix to keep the LCS table small.
        var pre = 0
        while (pre < a.size && pre < b.size && a[pre] == b[pre]) pre++
        var suf = 0
        while (suf < a.size - pre && suf < b.size - pre && a[a.size - 1 - suf] == b[b.size - 1 - suf]) suf++
        val am = a.subList(pre, a.size - suf)
        val bm = b.subList(pre, b.size - suf)
        if (am.isEmpty() && bm.isEmpty()) return ""
        val ops = if (am.size.toLong() * bm.size <= 4_000_000L) lcsOps(am, bm) else am.map { '-' to it } + bm.map { '+' to it }
        // Whole sequence with line numbers.
        data class Op(val kind: Char, val text: String, val oldNo: Int, val newNo: Int)
        val all = ArrayList<Op>()
        var o = 1
        var n = 1
        for (i in 0 until pre) all += Op(' ', a[i], o++, n++)
        for ((k, t) in ops) when (k) {
            ' ' -> all += Op(' ', t, o++, n++)
            '-' -> all += Op('-', t, o++, n)
            else -> all += Op('+', t, o, n++)
        }
        for (i in a.size - suf until a.size) all += Op(' ', a[i], o++, n++)
        val sb = StringBuilder("--- a/$oldLabel\n+++ b/$newLabel\n")
        var i = 0
        while (i < all.size) {
            if (all[i].kind == ' ') { i++; continue }
            var start = maxOf(0, i - context)
            var end = i
            // extend hunk while changes are within 2*context
            while (end < all.size) {
                if (all[end].kind != ' ') { end++; continue }
                var j = end
                while (j < all.size && all[j].kind == ' ' && j - end < 2 * context) j++
                if (j < all.size && all[j].kind != ' ') end = j else break
            }
            val stop = minOf(all.size, end + context)
            val hunk = all.subList(start, stop)
            val oldStart = hunk.first().oldNo
            val newStart = hunk.first().newNo
            val oldCount = hunk.count { it.kind != '+' }
            val newCount = hunk.count { it.kind != '-' }
            sb.append("@@ -$oldStart,$oldCount +$newStart,$newCount @@\n")
            hunk.forEach { sb.append(it.kind).append(it.text).append('\n') }
            i = stop
            start = stop
        }
        return sb.toString()
    }

    private fun lcsOps(a: List<String>, b: List<String>): List<Pair<Char, String>> {
        val dp = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.size - 1 downTo 0) for (j in b.size - 1 downTo 0) {
            dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
        }
        val out = ArrayList<Pair<Char, String>>()
        var i = 0
        var j = 0
        while (i < a.size && j < b.size) {
            when {
                a[i] == b[j] -> { out += ' ' to a[i]; i++; j++ }
                dp[i + 1][j] >= dp[i][j + 1] -> { out += '-' to a[i]; i++ }
                else -> { out += '+' to b[j]; j++ }
            }
        }
        while (i < a.size) out += '-' to a[i++]
        while (j < b.size) out += '+' to b[j++]
        return out
    }
}

/** Chunk plan for `PUT /v1/fs/upload/{id}?offset=` (server max 8 MiB per chunk). */
object UploadPlan {
    const val DEFAULT_CHUNK = 2L * 1024 * 1024

    /** (offset, length) pairs covering [size] bytes starting at [resumeFrom]. */
    fun chunks(size: Long, chunk: Long = DEFAULT_CHUNK, resumeFrom: Long = 0): List<Pair<Long, Long>> {
        require(chunk > 0)
        if (size <= 0) return emptyList()
        val out = ArrayList<Pair<Long, Long>>()
        var off = resumeFrom.coerceIn(0, size)
        while (off < size) {
            val len = minOf(chunk, size - off)
            out += off to len
            off += len
        }
        return out
    }
}

/** "notes.txt" → "notes (phone copy).txt" when the desktop gives no suggestion. */
fun copyPathFor(path: String): String {
    val sepIdx = maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\'))
    val dir = path.substring(0, sepIdx + 1)
    val name = path.substring(sepIdx + 1)
    val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
    return dir + name.substring(0, dot) + " (phone copy)" + name.substring(dot)
}
