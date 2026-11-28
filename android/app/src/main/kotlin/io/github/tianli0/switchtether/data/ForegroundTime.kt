package io.github.tianli0.switchtether.data

import io.github.tianli0.switchtether.remote.api.ApiException
import io.github.tianli0.switchtether.remote.api.RemoteApi
import io.github.tianli0.switchtether.remote.command.newCommandId
import io.github.tianli0.switchtether.remote.protocol.ForegroundInterval
import io.github.tianli0.switchtether.remote.protocol.ForegroundReport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Time the app spent in the foreground that the computer has not counted yet (PROTOCOL.md §7.15).
 * Pure state: [ForegroundTime] persists it and sends it.
 *
 * - [open] is the start of the stretch in front right now; [checkpoint] moves what has passed of it
 *   into [pending], so a process that dies in front loses at most one checkpoint interval.
 * - A report keeps its command id until the computer has it ([inFlight]), so a retry counts once.
 */
@Serializable
data class ForegroundLedger(
    val pending: List<ForegroundInterval> = emptyList(),
    val inFlight: ForegroundReport? = null,
    val open: Long? = null,
) {
    fun start(now: Long): ForegroundLedger = if (open != null) this else copy(open = now)

    fun stop(now: Long): ForegroundLedger = checkpoint(now).copy(open = null)

    /** Moves the passed part of the open stretch into [pending] (the stretch stays open). */
    fun checkpoint(now: Long): ForegroundLedger {
        val s = open ?: return this
        if (now <= s) return this
        return copy(pending = append(pending, ForegroundInterval(s, now)), open = now).pruned(now)
    }

    /** The report to send now: the one in flight, or a new one of the oldest pending intervals. */
    fun nextReport(now: Long, newId: () -> String = ::newCommandId): Pair<ForegroundLedger, ForegroundReport?> {
        inFlight?.let { return this to it }
        val p = pruned(now).pending
        if (p.isEmpty()) return this to null
        val batch = p.take(MAX_PER_REPORT)
        val r = ForegroundReport(newId(), batch)
        return copy(pending = p.drop(batch.size), inFlight = r) to r
    }

    /** The computer answered [commandId] (counted, or refused for good). */
    fun done(commandId: String): ForegroundLedger = if (inFlight?.commandId == commandId) copy(inFlight = null) else this

    private fun pruned(now: Long): ForegroundLedger {
        val keep = pending.filter { it.endMs > now - MAX_AGE_MS }.takeLast(MAX_PENDING)
        return if (keep.size == pending.size) this else copy(pending = keep)
    }

    companion object {
        const val MAX_PER_REPORT = 200
        const val MAX_PENDING = 2_000

        /** The computer drops older parts; the phone stops keeping them a little earlier. */
        const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000

        /** Adjacent stretches (one checkpoint after another) become one. */
        internal fun append(list: List<ForegroundInterval>, iv: ForegroundInterval): List<ForegroundInterval> {
            val last = list.lastOrNull()
            return if (last != null && last.endMs == iv.startMs) list.dropLast(1) + ForegroundInterval(last.startMs, iv.endMs) else list + iv
        }
    }
}

/**
 * Keeps the [ForegroundLedger] in a file of the app's private storage and reports it. Lifecycle
 * changes apply at once and in order (the main thread calls them); the file is written in the
 * background, latest state only.
 */
class ForegroundTime(
    private val file: File,
    scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private var ledger: ForegroundLedger? = null
    private val dirty = MutableStateFlow(0L)
    private val sending = Mutex()

    init {
        scope.launch(Dispatchers.IO) {
            dirty.collectLatest { gen -> if (gen > 0) write(synchronized(lock) { ledger }) }
        }
    }

    private fun load(): ForegroundLedger = ledger ?: (
        runCatching { json.decodeFromString(ForegroundLedger.serializer(), file.readText()) }.getOrNull()
            // The process died while in front: the open stretch ended at its last checkpoint.
            ?.copy(open = null)
            ?: ForegroundLedger()
        ).also { ledger = it }

    private fun write(l: ForegroundLedger?) {
        runCatching {
            if (l == null || (l.pending.isEmpty() && l.inFlight == null && l.open == null)) {
                file.delete()
                return@runCatching
            }
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(json.encodeToString(ForegroundLedger.serializer(), l))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    private fun <T> update(f: (ForegroundLedger) -> Pair<ForegroundLedger, T>): T {
        val r = synchronized(lock) {
            val (l, r) = f(load())
            ledger = l
            r
        }
        dirty.update { it + 1 }
        return r
    }

    fun onStart() = update { it.start(now()) to Unit }

    fun onStop() = update { it.stop(now()) to Unit }

    fun checkpoint() = update { it.checkpoint(now()) to Unit }

    /** Sends what is pending; keeps it on a transient failure. */
    suspend fun flush(api: RemoteApi) = sending.withLock {
        // One checkpoint per flush: taking one per report added a sliver of a few ms each time and
        // sent it as a report of its own.
        checkpoint()
        repeat(MAX_REPORTS_PER_FLUSH) {
            val report = withContext(Dispatchers.IO) { update { it.nextReport(now()) } } ?: return@withLock
            val finished = try {
                api.reportForeground(report)
                true
            } catch (e: ApiException) {
                // A refusal (bad request, or an outcome the computer cannot tell) is not retried;
                // rate limits and server errors are.
                e.httpStatus in 400..499 && e.httpStatus != 429
            } catch (e: java.io.IOException) {
                android.util.Log.d(TAG, "foreground time not sent yet: ${e.message}")
                false
            }
            if (!finished) return@withLock
            update { it.done(report.commandId) to Unit }
        }
    }

    /** Forgets everything not yet reported (the computer was unpaired). */
    fun clear() = update { l ->
        // Still in front: counting goes on for the next computer from now.
        ForegroundLedger(open = l.open?.let { now() }) to Unit
    }

    private companion object {
        const val MAX_REPORTS_PER_FLUSH = 10
        const val TAG = "ForegroundTime"
    }
}
