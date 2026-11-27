package io.github.tianli0.switchtether.transfer

import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Handed to the code doing a transfer: reports progress and what to clean up if the process dies. */
interface TransferTicket {
    val id: String
    fun progress(done: Long, total: Long?)
    fun ref(ref: RemoteRef)

    /** The transfer ended in failure without throwing (the caller already shows why). */
    fun fail(reason: String?)
}

/**
 * Runs a transfer inline in the caller's coroutine (its streaming and idempotency stay exactly as
 * they are); the runner only does the bookkeeping around it. Cancelling the caller's job cancels the
 * transfer; [TransferManager.cancel] (the notification's Cancel) cancels the caller's job.
 */
interface TransferRunner {
    suspend fun <T> run(kind: TransferKind, label: String, total: Long?, block: suspend (TransferTicket) -> T): T
}

/** No bookkeeping, no job: today's behaviour (tests, and callers built without a manager). */
object DirectTransfers : TransferRunner {
    override suspend fun <T> run(kind: TransferKind, label: String, total: Long?, block: suspend (TransferTicket) -> T): T =
        block(object : TransferTicket {
            override val id = "direct"
            override fun progress(done: Long, total: Long?) = Unit
            override fun ref(ref: RemoteRef) = Unit
            override fun fail(reason: String?) = Unit
        })
}

/** Unfinished transfers on disk (app-private), so process death is noticed at the next start. */
interface TransferJournal {
    fun load(): List<TransferEntry>
    fun save(entries: List<TransferEntry>)
}

class InMemoryTransferJournal(initial: List<TransferEntry> = emptyList()) : TransferJournal {
    @Volatile var saved: List<TransferEntry> = initial
    override fun load() = saved
    override fun save(entries: List<TransferEntry>) { saved = entries }
}

/**
 * `filesDir/transfers/journal.json`: id, kind, file name, size, start time and the ids to clean up
 * ([RemoteRef]). No content, no credentials (docs/SECURITY.md §5). At most [MAX_JOURNAL] entries.
 */
class FileTransferJournal(private val file: File) : TransferJournal {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val serializer = ListSerializer(TransferEntry.serializer())

    @Synchronized override fun load(): List<TransferEntry> =
        runCatching { if (file.isFile) json.decodeFromString(serializer, file.readText()) else emptyList() }.getOrDefault(emptyList())

    @Synchronized override fun save(entries: List<TransferEntry>) {
        runCatching {
            if (entries.isEmpty()) { file.delete(); return }
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(serializer, entries.takeLast(MAX_JOURNAL)))
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
    }

    companion object { const val MAX_JOURNAL = 64 }
}

/**
 * What keeps a running transfer alive outside the app: on Android 14+ a user-initiated data transfer
 * job with its progress notification (`JobTransferEnvelope`). [begin] returns false when no job could
 * be scheduled; the transfer then simply runs as before (in the app process, no notification).
 */
interface TransferEnvelope {
    fun begin(entry: TransferEntry): Boolean
    fun update(entry: TransferEntry)
    fun end(entry: TransferEntry)
}

object NoEnvelope : TransferEnvelope {
    override fun begin(entry: TransferEntry) = false
    override fun update(entry: TransferEntry) = Unit
    override fun end(entry: TransferEntry) = Unit
}

/** Result of [TransferManager.recover]. */
data class Recovery(
    /** Transfers the previous process was running when it ended (report them once). */
    val interrupted: List<TransferEntry>,
    /** Interrupted transfers whose leftovers (upload on the computer, pending download) are not cleaned up yet. */
    val cleanup: List<TransferEntry>,
)

/**
 * Bookkeeping for large transfers: the live list, the journal, cancellation from the notification and
 * the job envelope. The bytes still move in the caller's coroutine (see [TransferRunner]).
 */
class TransferManager(
    private val journal: TransferJournal,
    @Volatile var envelope: TransferEnvelope = NoEnvelope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : TransferRunner {
    private val _active = MutableStateFlow<Map<String, TransferEntry>>(emptyMap())
    /** Transfers running right now. */
    val active: StateFlow<Map<String, TransferEntry>> = _active.asStateFlow()

    private val jobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    /** Why a job was cancelled from outside the caller: the notification (user) or the system. */
    private val stopCause = java.util.concurrent.ConcurrentHashMap<String, TransferEvent>()
    private val lastPost = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Int?>>()
    /** Journal entries of earlier processes still waiting for cleanup. */
    private val leftovers = java.util.concurrent.ConcurrentHashMap<String, TransferEntry>()
    private val lock = Any()

    /**
     * At process start: every journal entry that was still running becomes [TransferPhase.INTERRUPTED]
     * (never resumed); those with leftovers stay in the journal until [cleanedUp].
     */
    fun recover(): Recovery = synchronized(lock) {
        val all = journal.load()
        val interrupted = interruptedOnRestart(all)
        val waiting = all.filter { it.phase == TransferPhase.INTERRUPTED && it.ref != null } + interrupted.filter { it.ref != null }
        waiting.forEach { leftovers[it.id] = it }
        persist()
        Recovery(interrupted, waiting)
    }

    /** Leftovers of an interrupted transfer were removed (or are gone): forget it. */
    fun cleanedUp(id: String) = synchronized(lock) {
        if (leftovers.remove(id) != null) persist()
    }

    fun entry(id: String): TransferEntry? = _active.value[id]

    /** Cancel from the notification: the caller's job is cancelled; its own cancel path cleans up. */
    fun cancel(id: String): Boolean = stop(id, TransferEvent.Cancelled)

    /** The system stopped the transfer's job (Task Manager, quota, constraint lost). */
    fun onSystemStop(id: String): Boolean = stop(id, TransferEvent.StoppedBySystem)

    private fun stop(id: String, cause: TransferEvent): Boolean {
        val job = jobs[id] ?: return false
        stopCause.putIfAbsent(id, cause)
        job.cancel(CancellationException(if (cause == TransferEvent.Cancelled) "cancelled by the user" else "stopped by the system"))
        return true
    }

    override suspend fun <T> run(kind: TransferKind, label: String, total: Long?, block: suspend (TransferTicket) -> T): T {
        val job = currentCoroutineContext()[Job]
        val id = newId()
        val first = TransferEntry(id, kind, label.take(120), total?.takeIf { it >= 0 }, startedAtMs = clock())
        synchronized(lock) {
            _active.update { it + (id to first) }
            if (job != null) jobs[id] = job
            persist()
        }
        runCatching { envelope.begin(first) }
        var failure: String? = null
        var failed = false
        val ticket = object : TransferTicket {
            override val id = id
            override fun progress(done: Long, total: Long?) = step(id, TransferEvent.Progress(done, total), journal = false)
            override fun ref(ref: RemoteRef) = step(id, TransferEvent.Ref(ref), journal = true)
            override fun fail(reason: String?) { failed = true; failure = reason }
        }
        try {
            val r = block(ticket)
            finish(id, if (failed) TransferEvent.Failed(failure) else TransferEvent.Succeeded)
            return r
        } catch (e: CancellationException) {
            finish(id, stopCause[id] ?: TransferEvent.Cancelled)
            throw e
        } catch (e: Throwable) {
            finish(id, TransferEvent.Failed(failure ?: e.javaClass.simpleName))
            throw e
        }
    }

    private fun step(id: String, ev: TransferEvent, journal: Boolean) {
        var updated: TransferEntry? = null
        synchronized(lock) {
            val cur = _active.value[id] ?: return
            val next = reduce(cur, ev)
            if (next == cur) return
            _active.update { it + (id to next) }
            updated = next
            if (journal) persist()
        }
        val e = updated ?: return
        if (ev is TransferEvent.Progress) {
            val now = clock()
            val (lastAt, lastPct) = lastPost[id] ?: (0L to null)
            if (!shouldRepost(lastAt, now, lastPct, e.percent)) return
            lastPost[id] = now to e.percent
        }
        runCatching { envelope.update(e) }
    }

    private fun finish(id: String, ev: TransferEvent) {
        val done: TransferEntry
        synchronized(lock) {
            val cur = _active.value[id] ?: return
            done = reduce(cur, ev)
            _active.update { it - id }
            jobs.remove(id)
            stopCause.remove(id)
            lastPost.remove(id)
            persist()
        }
        runCatching { envelope.end(done) }
    }

    /** Running transfers (to resume nothing, but to notice their death) plus leftovers to clean up. */
    private fun persist() = journal.save(_active.value.values.sortedBy { it.startedAtMs } + leftovers.values)
}
