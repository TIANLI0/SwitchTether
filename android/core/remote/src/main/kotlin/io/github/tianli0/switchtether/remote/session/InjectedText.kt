package io.github.tianli0.switchtether.remote.session

/**
 * Harness / IDE blocks that Claude Code adds to a user turn (`<ide_opened_file>`, `<system-reminder>`…).
 *
 * Current desktops strip them and send what they said as `data.context`; this is the same cleanup for
 * events from older desktop builds, so a chat bubble never shows raw tags.
 */
object InjectedText {
    data class Context(val kind: String, val path: String, val lines: String? = null) {
        val fileName: String get() = path.substringAfterLast('/').substringAfterLast('\\')
    }

    data class Cleaned(val text: String, val context: List<Context>)

    private val TAGS = listOf("system-reminder", "ide_opened_file", "ide_selection", "ide_diagnostics", "browser_instruction")
    private val OPENED = Regex("""opened the file (.+?) in the IDE""")
    private val SELECTED = Regex("""the lines (\d+) to (\d+) from ([^\n]+?):?\s*(\n|$)""")

    fun clean(raw: String): Cleaned {
        if ('<' !in raw) return Cleaned(raw.trim(), emptyList())
        var text = raw
        val context = ArrayList<Context>()
        for (tag in TAGS) {
            val open = "<$tag>"
            val close = "</$tag>"
            while (true) {
                val start = text.indexOf(open)
                if (start < 0) break
                val end = text.indexOf(close, start + open.length)
                val inner = if (end < 0) text.substring(start + open.length) else text.substring(start + open.length, end)
                when (tag) {
                    "ide_opened_file" -> OPENED.find(inner)?.let { context += Context("file", it.groupValues[1].trim()) }
                    "ide_selection" -> SELECTED.find(inner)?.let {
                        context += Context("selection", it.groupValues[3].trim(), "${it.groupValues[1]}-${it.groupValues[2]}")
                    }
                }
                text = text.substring(0, start) + if (end < 0) "" else text.substring(end + close.length)
            }
        }
        return Cleaned(text.trim(), context)
    }
}
