package io.github.tianli0.switchtether.transfer

import kotlinx.serialization.Serializable

/**
 * Large transfers (docs/ANDROID_UI.md §36): attachment uploads (≤ 1 GiB, streamed in chunks), uploads
 * into a folder on the computer and explicit downloads into `Downloads/SwitchTether`. Pure state
 * machine; the Android side is [TransferManager] (bookkeeping) and `TransferJobService` (the
 * user-initiated data transfer job that keeps a running transfer alive in the background).
 */
@Serializable
enum class TransferKind { ATTACHMENT_UPLOAD, FILE_UPLOAD, FILE_DOWNLOAD;

    val isUpload: Boolean get() = this != FILE_DOWNLOAD
}

@Serializable
enum class TransferPhase {
    /** Registered, the first byte has not moved yet. */
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,

    /** Stopped by the user (in the app or from the notification's Cancel). */
    CANCELLED,

    /** The system stopped the job (Task Manager "Stop", quota, lost network constraint). */
    STOPPED_BY_SYSTEM,

    /** The app process ended while it ran; found in the journal at the next start. Never resumed. */
    INTERRUPTED;

    val terminal: Boolean get() = this != QUEUED && this != RUNNING
}

/**
 * What the desktop / the phone holds for an unfinished transfer, so a transfer cut by process death
 * can be cleaned up at the next start (never continued): an attachment or upload id on the computer,
 * or the pending MediaStore entry of a download. Ids only, never content or credentials.
 */
@Serializable
data class RemoteRef(
    val sessionId: String? = null,
    val attachmentId: String? = null,
    val uploadId: String? = null,
    val pendingUri: String? = null,
)

@Serializable
data class TransferEntry(
    val id: String,
    val kind: TransferKind,
    /** The file's display name (shown in the notification). */
    val label: String,
    val total: Long? = null,
    val done: Long = 0,
    val phase: TransferPhase = TransferPhase.QUEUED,
    val startedAtMs: Long = 0,
    val ref: RemoteRef? = null,
    /** Short reason of a failure, already redacted by the caller (no content). */
    val error: String? = null,
) {
    val percent: Int? get() = percentOf(done, total)
}

sealed interface TransferEvent {
    data class Progress(val done: Long, val total: Long?) : TransferEvent
    data class Ref(val ref: RemoteRef) : TransferEvent
    data object Succeeded : TransferEvent
    data class Failed(val reason: String?) : TransferEvent
    data object Cancelled : TransferEvent
    data object StoppedBySystem : TransferEvent
    data object ProcessDied : TransferEvent
}

/**
 * One step of the state machine. Terminal phases absorb every later event (a late progress callback
 * or a second cancel never revives or rewrites a finished transfer). Progress never goes backwards
 * and never exceeds a known total.
 */
fun reduce(e: TransferEntry, ev: TransferEvent): TransferEntry {
    if (e.phase.terminal) return e
    return when (ev) {
        is TransferEvent.Progress -> {
            val total = ev.total?.takeIf { it >= 0 } ?: e.total
            val done = maxOf(e.done, ev.done.coerceAtLeast(0)).let { d -> if (total != null) d.coerceAtMost(total) else d }
            e.copy(phase = TransferPhase.RUNNING, done = done, total = total)
        }
        is TransferEvent.Ref -> e.copy(ref = merge(e.ref, ev.ref))
        TransferEvent.Succeeded -> e.copy(phase = TransferPhase.SUCCEEDED, done = e.total ?: e.done)
        is TransferEvent.Failed -> e.copy(phase = TransferPhase.FAILED, error = ev.reason?.take(200))
        TransferEvent.Cancelled -> e.copy(phase = TransferPhase.CANCELLED)
        TransferEvent.StoppedBySystem -> e.copy(phase = TransferPhase.STOPPED_BY_SYSTEM)
        TransferEvent.ProcessDied -> e.copy(phase = TransferPhase.INTERRUPTED)
    }
}

private fun merge(a: RemoteRef?, b: RemoteRef): RemoteRef = if (a == null) b else RemoteRef(
    sessionId = b.sessionId ?: a.sessionId,
    attachmentId = b.attachmentId ?: a.attachmentId,
    uploadId = b.uploadId ?: a.uploadId,
    pendingUri = b.pendingUri ?: a.pendingUri,
)

fun percentOf(done: Long, total: Long?): Int? =
    if (total == null || total <= 0) null else ((done.coerceIn(0, total) * 100) / total).toInt()

/**
 * Entries left in the journal by a process that died: each becomes [TransferPhase.INTERRUPTED]. The
 * caller reports them and cleans up their [RemoteRef]; it never re-runs one, so nothing is sent twice.
 */
fun interruptedOnRestart(journal: List<TransferEntry>): List<TransferEntry> =
    journal.filter { !it.phase.terminal }.map { reduce(it, TransferEvent.ProcessDied) }

/**
 * Whether a progress change is worth a notification update: at most every [minIntervalMs], and only
 * when the shown percentage changed (an unknown total updates on the interval alone).
 */
fun shouldRepost(lastAtMs: Long, nowMs: Long, lastPercent: Int?, percent: Int?, minIntervalMs: Long = 1_000): Boolean {
    if (nowMs - lastAtMs < minIntervalMs) return false
    return percent == null || percent != lastPercent
}
