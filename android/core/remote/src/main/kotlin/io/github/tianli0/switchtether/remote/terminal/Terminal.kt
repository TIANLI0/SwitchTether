package io.github.tianli0.switchtether.remote.terminal

import io.github.tianli0.switchtether.remote.protocol.ProtocolJson
import io.github.tianli0.switchtether.remote.protocol.TermCursor
import io.github.tianli0.switchtether.remote.protocol.TermLine
import io.github.tianli0.switchtether.remote.protocol.bool
import io.github.tianli0.switchtether.remote.protocol.long
import io.github.tianli0.switchtether.remote.protocol.obj
import io.github.tianli0.switchtether.remote.protocol.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Server → client frames of `GET /v1/terminals/{id}/stream` (§7.6). */
sealed interface TermFrame {
    data class Snapshot(
        val version: Long,
        val cols: Int,
        val rows: Int,
        val cursor: TermCursor,
        val lines: List<TermLine>,
        val title: String,
        val flags: TermFlags,
    ) : TermFrame

    data class Patch(val version: Long, val lines: List<Pair<Int, TermLine>>, val cursor: TermCursor?, val title: String?, val flags: TermFlags?) : TermFrame
    data class Exit(val code: Int?) : TermFrame
    data class Error(val code: String) : TermFrame
    data object Pong : TermFrame
    data class Unknown(val type: String?) : TermFrame

    companion object {
        fun parse(text: String): TermFrame {
            val o = runCatching { ProtocolJson.parseToJsonElement(text) }.getOrNull().obj() ?: return Unknown(null)
            return when (val type = o.str("type")) {
                "snapshot" -> Snapshot(
                    version = o.long("version") ?: 0,
                    cols = (o.long("cols") ?: 80).coerceIn(1, TermScreen.MAX_COLS.toLong()).toInt(),
                    rows = (o.long("rows") ?: 24).coerceIn(1, TermScreen.MAX_ROWS.toLong()).toInt(),
                    cursor = cursor(o["cursor"].obj()) ?: TermCursor(),
                    lines = (o["lines"] as? JsonArray).orEmpty().map { line(it.obj()) },
                    title = o.str("title") ?: "",
                    flags = flags(o) ?: TermFlags(),
                )
                "patch" -> Patch(
                    version = o.long("version") ?: 0,
                    lines = (o["lines"] as? JsonArray).orEmpty().mapNotNull { e ->
                        val p = e.obj() ?: return@mapNotNull null
                        // A row outside any screen (negative, or far beyond it) is dropped, not applied.
                        val y = p.long("y")?.takeIf { it in 0 until TermScreen.MAX_ROWS } ?: return@mapNotNull null
                        y.toInt() to line(p["line"].obj())
                    },
                    cursor = cursor(o["cursor"].obj()),
                    title = o.str("title"),
                    flags = flags(o),
                )
                "exit" -> Exit(o.long("code")?.toInt())
                "error" -> Error(o.str("code") ?: "error")
                "pong" -> Pong
                else -> Unknown(type)
            }
        }

        private fun line(o: JsonObject?): TermLine =
            if (o == null) TermLine() else runCatching { ProtocolJson.decodeFromJsonElement(TermLine.serializer(), o) }.getOrDefault(TermLine())

        private fun cursor(o: JsonObject?): TermCursor? =
            o?.let { runCatching { ProtocolJson.decodeFromJsonElement(TermCursor.serializer(), it) }.getOrNull() }

        private fun flags(o: JsonObject): TermFlags? {
            if (!o.containsKey("altScreen") && !o.containsKey("applicationCursor") && !o.containsKey("exited")) return null
            return TermFlags(
                altScreen = o.bool("altScreen") ?: false,
                applicationCursor = o.bool("applicationCursor") ?: false,
                bracketedPaste = o.bool("bracketedPaste") ?: false,
                exited = o.bool("exited") ?: false,
                exitCode = o.long("exitCode")?.toInt(),
            )
        }
    }
}

data class TermFlags(
    val altScreen: Boolean = false,
    val applicationCursor: Boolean = false,
    val bracketedPaste: Boolean = false,
    val exited: Boolean = false,
    val exitCode: Int? = null,
)

/** Client-side copy of the desktop's screen state (the desktop runs the vt100 emulator). */
data class TermScreen(
    val version: Long = -1,
    val cols: Int = 80,
    val rows: Int = 24,
    val lines: List<TermLine> = emptyList(),
    val cursor: TermCursor = TermCursor(),
    val title: String = "",
    val flags: TermFlags = TermFlags(),
) {
    val exited: Boolean get() = flags.exited

    /** Applies one frame. Snapshots replace everything; patches replace changed rows only. */
    fun apply(frame: TermFrame): TermScreen = when (frame) {
        is TermFrame.Snapshot -> TermScreen(frame.version, frame.cols, frame.rows, frame.lines, frame.cursor, frame.title, frame.flags)
        is TermFrame.Patch -> {
            if (frame.version in 0..<version) {
                this // stale patch
            } else {
                val newLines = lines.toMutableList()
                for ((y, l) in frame.lines) {
                    if (y !in 0 until MAX_ROWS) continue
                    while (newLines.size <= y) newLines += TermLine()
                    newLines[y] = l
                }
                copy(
                    version = frame.version,
                    lines = newLines,
                    cursor = frame.cursor ?: cursor,
                    title = frame.title ?: title,
                    flags = frame.flags ?: flags,
                    rows = maxOf(rows, newLines.size),
                )
            }
        }
        is TermFrame.Exit -> copy(flags = flags.copy(exited = true, exitCode = frame.code ?: flags.exitCode))
        else -> this
    }

    /** Plain text of the visible screen (for "copy screen"). */
    fun plainText(): String = lines.joinToString("\n") { it.text.trimEnd() }.trimEnd()

    companion object {
        /** Bounds for what a frame may claim; a real terminal is far smaller. */
        const val MAX_ROWS = 10_000
        const val MAX_COLS = 10_000
    }
}

/** Special keys of the extra-key row. */
enum class TermKey { ESC, TAB, UP, DOWN, LEFT, RIGHT, HOME, END, PAGE_UP, PAGE_DOWN, ENTER, BACKSPACE, DELETE, INSERT }

/**
 * Encodes keys/text to the bytes a VT100/xterm program expects. Ctrl/Alt are latching modifiers
 * applied to the next key or character.
 */
object KeyEncoder {
    private const val ESC = "\u001b"

    fun key(k: TermKey, applicationCursor: Boolean, ctrl: Boolean = false, alt: Boolean = false): String {
        val prefix = if (applicationCursor && !ctrl) "${ESC}O" else "$ESC["
        val modifier = when {
            ctrl && alt -> 7
            ctrl -> 5
            alt -> 3
            else -> 0
        }
        fun arrow(c: Char): String = if (modifier != 0) "$ESC[1;$modifier$c" else "$prefix$c"
        val base = when (k) {
            TermKey.ESC -> ESC
            TermKey.TAB -> if (ctrl) "$ESC[9;5u" else "\t"
            TermKey.UP -> arrow('A')
            TermKey.DOWN -> arrow('B')
            TermKey.RIGHT -> arrow('C')
            TermKey.LEFT -> arrow('D')
            TermKey.HOME -> arrow('H')
            TermKey.END -> arrow('F')
            TermKey.PAGE_UP -> "$ESC[5~"
            TermKey.PAGE_DOWN -> "$ESC[6~"
            TermKey.INSERT -> "$ESC[2~"
            TermKey.DELETE -> "$ESC[3~"
            TermKey.ENTER -> "\r"
            TermKey.BACKSPACE -> "\u007f"
        }
        val modifiedByCsi = k in setOf(TermKey.UP, TermKey.DOWN, TermKey.LEFT, TermKey.RIGHT, TermKey.HOME, TermKey.END)
        return if (alt && !modifiedByCsi) ESC + base else base
    }

    /** Typed text with latching Ctrl/Alt applied to its first character. */
    fun text(s: String, ctrl: Boolean = false, alt: Boolean = false): String {
        if (s.isEmpty()) return s
        var first = s.substring(0, 1)
        if (ctrl) first = ctrlChar(s[0])?.toString() ?: first
        if (alt) first = ESC + first
        return (first + s.substring(1)).replace("\n", "\r")
    }

    /** Ctrl+letter → C0 control (Ctrl+C = 0x03, Ctrl+[ = ESC, Ctrl+Space = NUL). */
    fun ctrlChar(c: Char): Char? = when (c) {
        in 'a'..'z' -> (c - 'a' + 1).toChar()
        in 'A'..'Z' -> (c - 'A' + 1).toChar()
        '@', ' ' -> 0.toChar()
        '[' -> 27.toChar()
        '\\' -> 28.toChar()
        ']' -> 29.toChar()
        '^' -> 30.toChar()
        '_', '/' -> 31.toChar()
        '?' -> 127.toChar()
        else -> null
    }

    /** Paste: wrapped in bracketed-paste markers when the program enabled them (ESC[?2004h). */
    fun paste(s: String, bracketed: Boolean): String {
        val normalized = s.replace("\r\n", "\r").replace('\n', '\r')
        return if (bracketed) "$ESC[200~${normalized.replace("$ESC[201~", "")}$ESC[201~" else normalized
    }
}

/** xterm 256-color palette lookup ("0".."255" or "#rrggbb"); null = default. Returns 0xAARRGGBB. */
object TermPalette {
    private val base16 = longArrayOf(
        0xFF000000, 0xFFCD3131, 0xFF0DBC79, 0xFFE5E510, 0xFF2472C8, 0xFFBC3FBC, 0xFF11A8CD, 0xFFE5E5E5,
        0xFF666666, 0xFFF14C4C, 0xFF23D18B, 0xFFF5F543, 0xFF3B8EEA, 0xFFD670D6, 0xFF29B8DB, 0xFFFFFFFF,
    )

    fun argb(spec: String?): Long? {
        if (spec.isNullOrEmpty()) return null
        if (spec.startsWith("#") && spec.length == 7) return spec.substring(1).toLongOrNull(16)?.let { 0xFF000000 or it }
        val idx = spec.toIntOrNull() ?: return null
        return when (idx) {
            in 0..15 -> base16[idx]
            in 16..231 -> {
                val n = idx - 16
                val steps = intArrayOf(0, 95, 135, 175, 215, 255)
                val r = steps[n / 36]; val g = steps[(n / 6) % 6]; val b = steps[n % 6]
                0xFF000000 or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong()
            }
            in 232..255 -> {
                val v = 8 + (idx - 232) * 10
                0xFF000000 or (v.toLong() shl 16) or (v.toLong() shl 8) or v.toLong()
            }
            else -> null
        }
    }
}
