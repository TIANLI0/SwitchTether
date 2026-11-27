package io.github.tianli0.switchtether.diag

import kotlinx.serialization.Serializable

/**
 * Process-exit diagnostics (docs/ANDROID_UI.md §37): why earlier app processes ended, from
 * `ActivityManager.getHistoricalProcessExitReasons`, to see how vendor ROMs (ColorOS) end background
 * delivery. Pure mapping, bounds and redaction; the Android side is [ExitDiagnostics].
 *
 * Nothing here holds conversation content or secrets: the record is the system's reason / status /
 * importance / memory numbers, the system's one-line description (redacted), the app's own process
 * state summary and, for an ANR, at most a filtered excerpt of the main thread's stack frames.
 */

/** Whether the foreground connection service (`MonitorService`) ran when the process ended. */
@Serializable
enum class MonitorState { RUNNING, NOT_RUNNING, UNKNOWN }

@Serializable
data class ExitRecord(
    val timestampMs: Long,
    val pid: Int,
    val reason: Int,
    val reasonName: String,
    val status: Int,
    val statusName: String? = null,
    val importance: Int,
    val importanceName: String,
    val pssKb: Long,
    val rssKb: Long,
    val description: String? = null,
    val monitor: MonitorState = MonitorState.UNKNOWN,
    /** Where [monitor] comes from: `summary` (the app's own process state) or `importance` (inferred). */
    val monitorSource: String? = null,
    val foreground: Boolean? = null,
    val online: Boolean? = null,
    val transfers: Int? = null,
    /** ANR only: the main thread's frames, filtered and redacted, ≤ [TRACE_EXCERPT_MAX] bytes. */
    val trace: String? = null,
    /** Size of the system's trace / tombstone, when there is one (its content is never stored otherwise). */
    val traceBytes: Long? = null,
)

/** What `ApplicationExitInfo` says, read on the Android side (kept plain for tests). */
data class RawExit(
    val timestampMs: Long,
    val pid: Int,
    val reason: Int,
    val status: Int,
    val importance: Int,
    val pssKb: Long,
    val rssKb: Long,
    val description: String?,
    val processStateSummary: ByteArray?,
    /** Bytes in the system's trace stream (ANR trace / native tombstone), null when there is none. */
    val traceBytes: Long? = null,
    /** The trace as text, read only for an ANR and only up to [TRACE_READ_MAX] bytes. */
    val traceText: String? = null,
)

const val MAX_EXIT_RECORDS = 50
const val TRACE_EXCERPT_MAX = 8 * 1024
const val TRACE_READ_MAX = 512 * 1024
private const val DESCRIPTION_MAX = 300

// ApplicationExitInfo.REASON_* (API 30+; 14-16 newer).
object ExitReasons {
    const val UNKNOWN = 0
    const val EXIT_SELF = 1
    const val SIGNALED = 2
    const val LOW_MEMORY = 3
    const val CRASH = 4
    const val CRASH_NATIVE = 5
    const val ANR = 6
    const val INITIALIZATION_FAILURE = 7
    const val PERMISSION_CHANGE = 8
    const val EXCESSIVE_RESOURCE_USAGE = 9
    const val USER_REQUESTED = 10
    const val USER_STOPPED = 11
    const val DEPENDENCY_DIED = 12
    const val OTHER = 13
    const val FREEZER = 14
    const val PACKAGE_STATE_CHANGE = 15
    const val PACKAGE_UPDATED = 16
}

fun reasonName(reason: Int): String = when (reason) {
    ExitReasons.UNKNOWN -> "UNKNOWN"
    ExitReasons.EXIT_SELF -> "EXIT_SELF"
    ExitReasons.SIGNALED -> "SIGNALED"
    ExitReasons.LOW_MEMORY -> "LOW_MEMORY"
    ExitReasons.CRASH -> "CRASH"
    ExitReasons.CRASH_NATIVE -> "CRASH_NATIVE"
    ExitReasons.ANR -> "ANR"
    ExitReasons.INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
    ExitReasons.PERMISSION_CHANGE -> "PERMISSION_CHANGE"
    ExitReasons.EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
    ExitReasons.USER_REQUESTED -> "USER_REQUESTED"
    ExitReasons.USER_STOPPED -> "USER_STOPPED"
    ExitReasons.DEPENDENCY_DIED -> "DEPENDENCY_DIED"
    ExitReasons.OTHER -> "OTHER"
    ExitReasons.FREEZER -> "FREEZER"
    ExitReasons.PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE"
    ExitReasons.PACKAGE_UPDATED -> "PACKAGE_UPDATED"
    else -> "REASON_$reason"
}

// ActivityManager.RunningAppProcessInfo.IMPORTANCE_*.
fun importanceName(importance: Int): String = when (importance) {
    100 -> "FOREGROUND"
    125 -> "FOREGROUND_SERVICE"
    130 -> "PERCEPTIBLE_PRE_26"
    150 -> "TOP_SLEEPING_PRE_28"
    200 -> "VISIBLE"
    230 -> "PERCEPTIBLE"
    300 -> "SERVICE"
    325 -> "TOP_SLEEPING"
    350 -> "CANT_SAVE_STATE"
    400 -> "CACHED"
    1000 -> "GONE"
    else -> "IMPORTANCE_$importance"
}

/** For [ExitReasons.SIGNALED] the status is the signal number. */
fun signalName(signal: Int): String? = when (signal) {
    1 -> "SIGHUP"
    2 -> "SIGINT"
    3 -> "SIGQUIT"
    6 -> "SIGABRT"
    9 -> "SIGKILL"
    11 -> "SIGSEGV"
    13 -> "SIGPIPE"
    15 -> "SIGTERM"
    else -> null
}

/**
 * The app's process state, handed to the system with `ActivityManager.setProcessStateSummary` and
 * returned with the exit record of that very process (≤ 128 bytes): `st1;fg=1;mon=0;on=1;tx=0`.
 */
data class ProcessSummary(val foreground: Boolean, val monitor: Boolean, val online: Boolean, val transfers: Int) {
    fun encode(): ByteArray =
        "st1;fg=${b(foreground)};mon=${b(monitor)};on=${b(online)};tx=${transfers.coerceIn(0, 99)}".toByteArray(Charsets.US_ASCII)

    companion object {
        private fun b(v: Boolean) = if (v) "1" else "0"

        fun decode(bytes: ByteArray?): ProcessSummary? {
            if (bytes == null || bytes.isEmpty() || bytes.size > 128) return null
            val text = runCatching { String(bytes, Charsets.US_ASCII) }.getOrNull() ?: return null
            val parts = text.split(';')
            if (parts.firstOrNull() != "st1") return null
            val kv = parts.drop(1).mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
            val fg = kv["fg"]?.let(::flag) ?: return null
            val mon = kv["mon"]?.let(::flag) ?: return null
            val on = kv["on"]?.let(::flag) ?: false
            val tx = kv["tx"]?.toIntOrNull()?.coerceIn(0, 99) ?: 0
            return ProcessSummary(fg, mon, on, tx)
        }

        private fun flag(s: String): Boolean? = when (s) { "1" -> true; "0" -> false; else -> null }
    }
}

private val URL = Regex("""\b[a-zA-Z][a-zA-Z0-9+.-]*://\S+""")
private val EMAIL = Regex("""[\w.+-]+@[\w-]+(\.[\w-]+)+""")
private val WIN_PATH = Regex("""\b[A-Za-z]:\\[^\s,;)'"]*""")
private val UNIX_PATH = Regex("""(?<![\w.])/(?:[\w.@+-]+/)+[\w.@+-]*""")
private val IPV4 = Regex("""\b\d{1,3}(\.\d{1,3}){3}(:\d+)?\b""")
private val TOKEN = Regex("""\b[A-Za-z0-9+_=-]{24,}\b""")
private val QUOTED = Regex(""""[^"]{40,}"|'[^']{40,}'""")

/**
 * Removes what could identify the user or their work from a system-supplied line: URLs, e-mail
 * addresses, file paths, IP addresses, long tokens and long quoted strings. Package / class names
 * and the ROM's kill reasons ("athena", "oplus …") stay, they are the point of the record.
 */
fun redactLine(s: String): String {
    var t = s
    t = URL.replace(t, "<url>")
    t = EMAIL.replace(t, "<email>")
    t = WIN_PATH.replace(t, "<path>")
    t = UNIX_PATH.replace(t) { m -> if (m.value.startsWith("/data/app/") || m.value.startsWith("/system/") || m.value.startsWith("/apex/")) m.value else "<path>" }
    t = IPV4.replace(t, "<ip>")
    t = QUOTED.replace(t, "<text>")
    t = TOKEN.replace(t) { m -> if (m.value.contains('.') || m.value.all { it.isLetter() || it == '_' }) m.value else "<token>" }
    return t
}

/** The system's description, redacted and cut to [DESCRIPTION_MAX] characters. */
fun redactDescription(s: String?): String? {
    val one = s?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val r = redactLine(one)
    return if (r.length > DESCRIPTION_MAX) r.take(DESCRIPTION_MAX - 1) + "…" else r
}

private val FRAME_LINES = listOf(
    Regex("""^"[^"]*"( daemon)? prio=.*"""),
    Regex("""^\s+\| .*"""),
    Regex("""^\s+at [\w$.<>/-]+\(.*\)\s*$"""),
    Regex("""^\s+native: #\d+ pc .*"""),
    Regex("""^\s+- (waiting|locked|sleeping|parking|waiting to lock|waiting on).*"""),
    Regex("""^\s+\(no managed stack frames\)\s*$"""),
)

/**
 * The main thread's block of an ANR trace ("main" … up to the next blank line), keeping only thread
 * header / state / frame lines, each redacted. Null when there is no main thread block. Lines of any
 * other kind are dropped and counted, never copied.
 */
fun anrMainThreadExcerpt(trace: String, maxBytes: Int = TRACE_EXCERPT_MAX): String? {
    val lines = trace.lines()
    val start = lines.indexOfFirst { it.startsWith("\"main\"") }
    if (start < 0) return null
    val out = StringBuilder()
    var dropped = 0
    var truncated = false
    for (i in start until lines.size) {
        val line = lines[i]
        if (i > start && line.isBlank()) break
        if (FRAME_LINES.none { it.matches(line) }) { dropped++; continue }
        val clean = redactLine(line)
        if (out.length + clean.length + 1 > maxBytes) { truncated = true; break }
        out.append(clean).append('\n')
    }
    if (dropped > 0) out.append("(").append(dropped).append(" other lines withheld)\n")
    if (truncated) out.append("(truncated)\n")
    return out.toString().takeIf { it.isNotBlank() }
}

/** One `ApplicationExitInfo` → the stored record. */
fun mapExit(raw: RawExit): ExitRecord {
    val summary = ProcessSummary.decode(raw.processStateSummary)
    val (monitor, source) = when {
        summary != null -> (if (summary.monitor) MonitorState.RUNNING else MonitorState.NOT_RUNNING) to "summary"
        // A foreground-service importance with no summary: the only foreground service is MonitorService.
        raw.importance == 125 -> MonitorState.RUNNING to "importance"
        else -> MonitorState.UNKNOWN to null
    }
    val trace = if (raw.reason == ExitReasons.ANR) raw.traceText?.let { anrMainThreadExcerpt(it) } else null
    return ExitRecord(
        timestampMs = raw.timestampMs,
        pid = raw.pid,
        reason = raw.reason,
        reasonName = reasonName(raw.reason),
        status = raw.status,
        statusName = if (raw.reason == ExitReasons.SIGNALED) signalName(raw.status) else null,
        importance = raw.importance,
        importanceName = importanceName(raw.importance),
        pssKb = raw.pssKb,
        rssKb = raw.rssKb,
        description = redactDescription(raw.description),
        monitor = monitor,
        monitorSource = source,
        foreground = summary?.foreground,
        online = summary?.online,
        transfers = summary?.transfers,
        trace = trace,
        traceBytes = raw.traceBytes,
    )
}

/** Newest first, one record per (pid, time), at most [limit]. */
fun mergeExits(existing: List<ExitRecord>, incoming: List<ExitRecord>, limit: Int = MAX_EXIT_RECORDS): List<ExitRecord> =
    (incoming + existing).distinctBy { it.timestampMs to it.pid }.sortedByDescending { it.timestampMs }.take(limit)

/** The text file the About page shares. */
fun formatReport(records: List<ExitRecord>, header: List<Pair<String, String>>, formatTime: (Long) -> String): String = buildString {
    appendLine("SwitchTether process exit diagnostics")
    header.forEach { (k, v) -> appendLine("$k: $v") }
    appendLine("records: ${records.size} (newest first, at most $MAX_EXIT_RECORDS)")
    appendLine()
    records.forEach { r ->
        appendLine("== ${formatTime(r.timestampMs)}  pid ${r.pid}")
        appendLine("reason: ${r.reasonName} (${r.reason})")
        appendLine("status: ${r.status}${r.statusName?.let { " $it" } ?: ""}")
        appendLine("importance: ${r.importanceName} (${r.importance})")
        appendLine("pss: ${r.pssKb} KB  rss: ${r.rssKb} KB")
        r.description?.let { appendLine("description: $it") }
        appendLine("monitor service: ${r.monitor}${r.monitorSource?.let { " ($it)" } ?: ""}")
        r.foreground?.let { appendLine("app in foreground: $it") }
        r.online?.let { appendLine("connected: $it") }
        r.transfers?.let { appendLine("transfers running: $it") }
        r.traceBytes?.let { appendLine("trace size: $it bytes") }
        r.trace?.let { appendLine("main thread (filtered):"); append(it) }
        appendLine()
    }
}
