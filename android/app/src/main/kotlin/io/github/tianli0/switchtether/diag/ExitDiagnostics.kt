package io.github.tianli0.switchtether.diag

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Reads why earlier processes of the app ended (`getHistoricalProcessExitReasons`, API 30+) at every
 * start, keeps the last [MAX_EXIT_RECORDS] in `filesDir/diagnostics/exits.json` (app-private, no
 * content, no secrets: docs/SECURITY.md §5) and hands the system a small summary of the current
 * process state (`setProcessStateSummary`) so the next record knows whether the monitor service ran.
 */
class ExitDiagnostics(private val context: Context) {
    private val dir = File(context.filesDir, "diagnostics")
    private val file = File(dir, "exits.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val serializer = ListSerializer(ExitRecord.serializer())
    private val am: ActivityManager? get() = context.getSystemService(ActivityManager::class.java)

    private val _records = MutableStateFlow<List<ExitRecord>>(emptyList())
    val records: StateFlow<List<ExitRecord>> = _records.asStateFlow()

    @Volatile private var lastSummary: ProcessSummary? = null

    /** Merges the system's exit records into the local log. Runs on IO. */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        val stored = load()
        val incoming = runCatching { am?.getHistoricalProcessExitReasons(context.packageName, 0, MAX_EXIT_RECORDS).orEmpty() }
            .getOrDefault(emptyList())
            .filter { it.processName == context.packageName || it.processName.isNullOrEmpty() }
            .map { mapExit(raw(it)) }
        val merged = mergeExits(stored, incoming)
        if (merged != stored) save(merged)
        _records.value = merged
    }

    /** Called whenever foreground / monitor / connection / transfer state changes; cheap when unchanged. */
    fun setSummary(summary: ProcessSummary) {
        if (summary == lastSummary) return
        lastSummary = summary
        runCatching { am?.setProcessStateSummary(summary.encode()) }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        file.delete()
        exportDir().listFiles()?.forEach { it.delete() }
        _records.value = emptyList()
    }

    /** Writes the shareable text file into the cache (FileProvider `diagnostics/`) and returns its URI. */
    suspend fun export(): Uri = withContext(Dispatchers.IO) {
        val out = File(exportDir().apply { mkdirs() }, "switchtether-diagnostics.txt")
        val fmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxx").withZone(java.time.ZoneId.systemDefault())
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
        val header = listOf(
            "app" to version,
            "device" to listOf(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }.joinToString(" "),
            "android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "build" to Build.DISPLAY.orEmpty(),
            "exported" to fmt.format(java.time.Instant.now()),
        )
        out.writeText(formatReport(_records.value, header) { fmt.format(java.time.Instant.ofEpochMilli(it)) })
        FileProvider.getUriForFile(context, context.packageName + ".fileprovider", out)
    }

    private fun exportDir() = File(context.cacheDir, "diagnostics")

    private fun load(): List<ExitRecord> =
        runCatching { if (file.isFile) json.decodeFromString(serializer, file.readText()) else emptyList() }.getOrDefault(emptyList())

    private fun save(list: List<ExitRecord>) {
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, "exits.json.tmp")
            tmp.writeText(json.encodeToString(serializer, list.take(MAX_EXIT_RECORDS)))
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
    }

    private fun raw(info: ApplicationExitInfo): RawExit {
        var size: Long? = null
        var text: String? = null
        // Only an ANR trace is read as text (and only its beginning); a native tombstone is counted.
        if (info.reason == ApplicationExitInfo.REASON_ANR || info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) {
            runCatching {
                info.traceInputStream?.use { input ->
                    val keep = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(16 * 1024)
                    var n = 0L
                    while (true) {
                        val r = input.read(buf)
                        if (r < 0) break
                        if (info.reason == ApplicationExitInfo.REASON_ANR && keep.size() < TRACE_READ_MAX) {
                            keep.write(buf, 0, minOf(r, TRACE_READ_MAX - keep.size()))
                        }
                        n += r
                        if (n > 64L * 1024 * 1024) break
                    }
                    size = n
                    if (keep.size() > 0) text = keep.toString(Charsets.UTF_8.name())
                }
            }
        }
        return RawExit(
            timestampMs = info.timestamp,
            pid = info.pid,
            reason = info.reason,
            status = info.status,
            importance = info.importance,
            pssKb = info.pss,
            rssKb = info.rss,
            description = info.description,
            processStateSummary = info.processStateSummary,
            traceBytes = size,
            traceText = text,
        )
    }
}
