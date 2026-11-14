package io.github.tianli0.switchtether.ui.markdown

/*
 * A pragmatic CommonMark-ish block parser for agent output, rendered natively with Compose
 * (no WebView). Supported: ATX headings, paragraphs, fenced code (``` / ~~~ with language),
 * indented code, block quotes, ordered/unordered/task lists (nested by indentation), pipe tables,
 * thematic breaks. Inline syntax is handled by [InlineParser].
 */

sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Code(val language: String?, val code: String) : MdBlock
    data class Quote(val blocks: List<MdBlock>) : MdBlock
    data class ListBlock(val ordered: Boolean, val start: Int, val items: List<ListItem>) : MdBlock
    data class Table(val header: List<String>, val align: List<Align>, val rows: List<List<String>>) : MdBlock
    data object Rule : MdBlock
}

data class ListItem(val checked: Boolean?, val blocks: List<MdBlock>)

enum class Align { START, CENTER, END }

object MarkdownParser {
    private val heading = Regex("^ {0,3}(#{1,6})(?:[ \\t]+(.*?))?(?:[ \\t]+#+)?[ \\t]*$")
    private val fence = Regex("^( {0,3})(`{3,}|~{3,})\\s*([^`\\s]*)?.*$")
    private val rule = Regex("^ {0,3}((\\*[ \\t]*){3,}|(-[ \\t]*){3,}|(_[ \\t]*){3,})$")
    private val bullet = Regex("^( *)([-*+])[ \\t]+(.*)$")
    private val ordered = Regex("^( *)(\\d{1,9})[.)][ \\t]+(.*)$")
    private val task = Regex("^\\[([ xX])][ \\t]+(.*)$")
    private val tableSep = Regex("^\\s*\\|?\\s*:?-{1,}:?\\s*(\\|\\s*:?-{1,}:?\\s*)*\\|?\\s*$")

    fun parse(markdown: String): List<MdBlock> = parseLines(markdown.replace("\r\n", "\n").replace('\r', '\n').split('\n'))

    private fun parseLines(lines: List<String>): List<MdBlock> {
        val out = ArrayList<MdBlock>()
        var i = 0
        val para = StringBuilder()
        fun flush() {
            if (para.isNotBlank()) out += MdBlock.Paragraph(para.toString().trim())
            para.clear()
        }
        while (i < lines.size) {
            val line = lines[i]
            if (line.isBlank()) {
                flush(); i++; continue
            }
            val fm = fence.matchEntire(line)
            if (fm != null) {
                flush()
                val marker = fm.groupValues[2]
                val indent = fm.groupValues[1].length
                val lang = fm.groupValues[3].takeIf { it.isNotBlank() }
                val code = StringBuilder()
                i++
                while (i < lines.size) {
                    val l = lines[i]
                    val t = l.trim()
                    if (t.length >= marker.length && t.all { it == marker[0] }) {
                        i++
                        break
                    }
                    if (code.isNotEmpty()) code.append('\n')
                    code.append(l.dropLeadingSpaces(indent))
                    i++
                }
                out += MdBlock.Code(lang, code.toString())
                continue
            }

            val hm = heading.matchEntire(line)
            if (hm != null) {
                flush()
                out += MdBlock.Heading(hm.groupValues[1].length, hm.groupValues[2].trim())
                i++
                continue
            }

            if (rule.matches(line)) {
                flush(); out += MdBlock.Rule; i++; continue
            }

            if (line.trimStart().startsWith(">")) {
                flush()
                val inner = ArrayList<String>()
                while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                    inner += lines[i].trimStart().removePrefix(">").removePrefix(" ")
                    i++
                }
                out += MdBlock.Quote(parseLines(inner))
                continue
            }

            if (line.contains('|') && i + 1 < lines.size && tableSep.matches(lines[i + 1]) && lines[i + 1].contains('-')) {
                flush()
                val header = splitRow(line)
                val align = splitRow(lines[i + 1]).map {
                    val t = it.trim()
                    when {
                        t.startsWith(":") && t.endsWith(":") -> Align.CENTER
                        t.endsWith(":") -> Align.END
                        else -> Align.START
                    }
                }
                i += 2
                val rows = ArrayList<List<String>>()
                while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) {
                    rows += splitRow(lines[i]); i++
                }
                out += MdBlock.Table(header, align, rows)
                continue
            }

            val b = bullet.matchEntire(line)
            val o = ordered.matchEntire(line)
            if ((b != null || o != null) && (para.isEmpty() || line.startsWith(" ").not())) {
                flush()
                val (block, next) = parseList(lines, i)
                out += block
                i = next
                continue
            }

            if (line.startsWith("    ") && para.isEmpty()) {
                val code = StringBuilder()
                while (i < lines.size && (lines[i].startsWith("    ") || lines[i].isBlank())) {
                    if (code.isNotEmpty()) code.append('\n')
                    code.append(lines[i].drop(4))
                    i++
                }
                out += MdBlock.Code(null, code.toString().trimEnd())
                continue
            }

            if (para.isNotEmpty()) para.append('\n')
            para.append(line.trim())
            i++
        }
        flush()
        return out
    }

    private fun parseList(lines: List<String>, startIndex: Int): Pair<MdBlock.ListBlock, Int> {
        val first = lines[startIndex]
        val orderedMatch = ordered.matchEntire(first)
        val isOrdered = orderedMatch != null
        val baseIndent = (orderedMatch ?: bullet.matchEntire(first))!!.groupValues[1].length
        val start = orderedMatch?.groupValues?.get(2)?.toIntOrNull() ?: 1
        val items = ArrayList<ListItem>()
        var i = startIndex
        while (i < lines.size) {
            val line = lines[i]
            val m = (if (isOrdered) ordered else bullet).matchEntire(line)
            if (m == null || m.groupValues[1].length != baseIndent) break
            val content = ArrayList<String>()
            content += m.groupValues[3]
            val contentIndent = baseIndent + m.groupValues[2].length + 1
            i++
            while (i < lines.size) {
                val l = lines[i]
                if (l.isBlank()) {
                    // blank line: continues the item only if the next line is indented further
                    val next = lines.getOrNull(i + 1)
                    if (next != null && next.leadingSpaces() > baseIndent && !next.isBlank()) {
                        content += ""; i++; continue
                    }
                    break
                }
                val ind = l.leadingSpaces()
                val isSibling = ind == baseIndent && ((if (isOrdered) ordered else bullet).matches(l))
                if (isSibling || (ind <= baseIndent && (bullet.matches(l) || ordered.matches(l)))) break
                if (ind < contentIndent && ind <= baseIndent) {
                    // lazy continuation of a paragraph
                    content += l.trim()
                } else {
                    content += l.dropLeadingSpaces(minOf(ind, contentIndent))
                }
                i++
            }
            var checked: Boolean? = null
            val firstLine = content.first()
            task.matchEntire(firstLine)?.let {
                checked = it.groupValues[1] != " "
                content[0] = it.groupValues[2]
            }
            items += ListItem(checked, parseLines(content))
        }
        return MdBlock.ListBlock(isOrdered, start, items) to i
    }

    private fun splitRow(row: String): List<String> {
        var r = row.trim()
        if (r.startsWith("|")) r = r.drop(1)
        if (r.endsWith("|") && !r.endsWith("\\|")) r = r.dropLast(1)
        val cells = ArrayList<String>()
        val cur = StringBuilder()
        var k = 0
        while (k < r.length) {
            val c = r[k]
            if (c == '\\' && k + 1 < r.length && r[k + 1] == '|') {
                cur.append('|'); k += 2; continue
            }
            if (c == '|') {
                cells += cur.toString().trim(); cur.clear()
            } else {
                cur.append(c)
            }
            k++
        }
        cells += cur.toString().trim()
        return cells
    }

    private fun String.leadingSpaces(): Int {
        var n = 0
        for (c in this) if (c == ' ') n++ else if (c == '\t') n += 4 else break
        return n
    }

    private fun String.dropLeadingSpaces(max: Int): String {
        var k = 0
        while (k < length && k < max && this[k] == ' ') k++
        return substring(k)
    }
}
