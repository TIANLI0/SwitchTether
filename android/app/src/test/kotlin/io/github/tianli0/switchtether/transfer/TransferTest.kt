package io.github.tianli0.switchtether.transfer

import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The transfer state machine, the manager's bookkeeping, cancellation and process-death recovery. */
@OptIn(ExperimentalCoroutinesApi::class)
class TransferTest {
    private fun entry(phase: TransferPhase = TransferPhase.QUEUED, total: Long? = 100) =
        TransferEntry("t1", TransferKind.FILE_UPLOAD, "a.bin", total = total, phase = phase, startedAtMs = 1)

    private class RecordingEnvelope(var accept: Boolean = true) : TransferEnvelope {
        val begun = mutableListOf<TransferEntry>()
        val updates = mutableListOf<TransferEntry>()
        val ended = mutableListOf<TransferEntry>()
        override fun begin(entry: TransferEntry): Boolean { begun += entry; return accept }
        override fun update(entry: TransferEntry) { updates += entry }
        override fun end(entry: TransferEntry) { ended += entry }
    }

    // ---- state machine -----------------------------------------------------------------------------

    @Test
    fun progressIsMonotonicAndClampedToTheTotal() {
        var e = reduce(entry(), TransferEvent.Progress(40, 100))
        assertEquals(TransferPhase.RUNNING, e.phase)
        assertEquals(40, e.done)
        e = reduce(e, TransferEvent.Progress(10, 100))
        assertEquals("never backwards", 40, e.done)
        e = reduce(e, TransferEvent.Progress(500, null))
        assertEquals("never past the total", 100, e.done)
        assertEquals(100, e.percent)
        val unknown = reduce(entry(total = null), TransferEvent.Progress(5_000, null))
        assertNull(unknown.percent)
        assertEquals(5_000, unknown.done)
    }

    @Test
    fun terminalPhasesAbsorbEveryLaterEvent() {
        for (end in listOf(TransferEvent.Succeeded, TransferEvent.Failed("x"), TransferEvent.Cancelled, TransferEvent.StoppedBySystem, TransferEvent.ProcessDied)) {
            val done = reduce(reduce(entry(), TransferEvent.Progress(10, 100)), end)
            assertTrue(done.phase.terminal)
            for (late in listOf(TransferEvent.Progress(90, 100), TransferEvent.Succeeded, TransferEvent.Cancelled, TransferEvent.Failed("y"), TransferEvent.Ref(RemoteRef(uploadId = "u")))) {
                assertEquals("$end then $late", done, reduce(done, late))
            }
        }
        assertEquals(100, reduce(entry(), TransferEvent.Succeeded).done)
        assertEquals(200, reduce(entry(), TransferEvent.Failed("r".repeat(500))).error!!.length)
    }

    @Test
    fun refsMergeWithoutLosingEarlierIds() {
        var e = reduce(entry(), TransferEvent.Ref(RemoteRef(sessionId = "s1")))
        e = reduce(e, TransferEvent.Ref(RemoteRef(attachmentId = "att-1")))
        assertEquals(RemoteRef(sessionId = "s1", attachmentId = "att-1"), e.ref)
    }

    @Test
    fun onlyUnfinishedJournalEntriesBecomeInterrupted() {
        val running = entry(TransferPhase.RUNNING)
        val queued = entry(TransferPhase.QUEUED).copy(id = "t2")
        val old = entry(TransferPhase.INTERRUPTED).copy(id = "t3")
        val r = interruptedOnRestart(listOf(running, queued, old))
        assertEquals(listOf("t1", "t2"), r.map { it.id })
        assertTrue(r.all { it.phase == TransferPhase.INTERRUPTED })
    }

    @Test
    fun notificationUpdatesAreThrottled() {
        assertFalse("too soon", shouldRepost(1_000, 1_500, 10, 20))
        assertTrue(shouldRepost(1_000, 2_000, 10, 20))
        assertFalse("same percent", shouldRepost(1_000, 5_000, 20, 20))
        assertTrue("unknown total: on the interval", shouldRepost(1_000, 2_000, null, null))
    }

    // ---- manager -----------------------------------------------------------------------------------

    @Test
    fun aSuccessfulTransferIsJournaledWhileItRunsAndForgottenAfter() = runTest(UnconfinedTestDispatcher()) {
        val journal = InMemoryTransferJournal()
        val env = RecordingEnvelope()
        var now = 0L
        val m = TransferManager(journal, env, clock = { now }, newId = { "id1" })
        val gate = CompletableDeferred<Unit>()
        val result = async {
            m.run(TransferKind.ATTACHMENT_UPLOAD, "photo.jpg", 10) { t ->
                t.ref(RemoteRef(sessionId = "s1", attachmentId = "att-1"))
                now = 5_000
                t.progress(4, 10)
                gate.await()
                t.progress(10, 10)
                "ok"
            }
        }
        assertEquals(1, env.begun.size)
        assertEquals(listOf("id1"), journal.saved.map { it.id })
        assertEquals("att-1", journal.saved.single().ref?.attachmentId)
        assertEquals(4L, m.active.value.getValue("id1").done)
        assertTrue(env.updates.isNotEmpty())
        gate.complete(Unit)
        assertEquals("ok", result.await())
        assertTrue(m.active.value.isEmpty())
        assertTrue(journal.saved.isEmpty())
        assertEquals(TransferPhase.SUCCEEDED, env.ended.single().phase)
    }

    @Test
    fun failuresAreReportedWhetherThrownOrSignalled() = runTest(UnconfinedTestDispatcher()) {
        val env = RecordingEnvelope()
        val m = TransferManager(InMemoryTransferJournal(), env)
        m.run(TransferKind.FILE_UPLOAD, "a", 1) { it.fail("changed while read") }
        assertEquals(TransferPhase.FAILED, env.ended.last().phase)
        assertEquals("changed while read", env.ended.last().error)
        val thrown = runCatching { m.run(TransferKind.FILE_DOWNLOAD, "b", 1) { throw IOException("reset") } }
        assertTrue(thrown.exceptionOrNull() is IOException)
        assertEquals(TransferPhase.FAILED, env.ended.last().phase)
        assertTrue(m.active.value.isEmpty())
    }

    @Test
    fun cancelFromTheNotificationCancelsTheCallersJob() = runTest(UnconfinedTestDispatcher()) {
        val env = RecordingEnvelope()
        val m = TransferManager(InMemoryTransferJournal(), env, newId = { "n1" })
        var cleanedUp = false
        var chunksAfterCancel = 0
        val gate = CompletableDeferred<Unit>()
        val job = launch {
            m.run(TransferKind.FILE_UPLOAD, "big.iso", 100) { t ->
                try {
                    t.progress(10, 100)
                    gate.await()
                    chunksAfterCancel++
                } catch (e: kotlinx.coroutines.CancellationException) {
                    cleanedUp = true
                    throw e
                }
            }
        }
        assertTrue(m.cancel("n1"))
        advanceUntilIdle()
        assertTrue(job.isCancelled)
        assertTrue("the caller's own cancel path ran", cleanedUp)
        assertEquals("nothing more was sent", 0, chunksAfterCancel)
        assertEquals(TransferPhase.CANCELLED, env.ended.single().phase)
        assertFalse("a second cancel finds nothing", m.cancel("n1"))
    }

    @Test
    fun aSystemStopIsToldApartFromTheUsersCancel() = runTest(UnconfinedTestDispatcher()) {
        val env = RecordingEnvelope()
        val m = TransferManager(InMemoryTransferJournal(), env, newId = { "s1" })
        val job = launch { m.run(TransferKind.FILE_DOWNLOAD, "x", null) { CompletableDeferred<Unit>().await() } }
        assertTrue(m.onSystemStop("s1"))
        advanceUntilIdle()
        assertTrue(job.isCancelled)
        assertEquals(TransferPhase.STOPPED_BY_SYSTEM, env.ended.single().phase)
    }

    @Test
    fun withoutAJobTheTransferStillRuns() = runTest(UnconfinedTestDispatcher()) {
        val env = RecordingEnvelope(accept = false)
        val m = TransferManager(InMemoryTransferJournal(), env)
        assertEquals(3, m.run(TransferKind.FILE_UPLOAD, "a", 3) { 3 })
        assertEquals(TransferPhase.SUCCEEDED, env.ended.single().phase)
    }

    @Test
    fun processDeathIsReportedNeverResumedAndLeftoversAreCleanedOnce() = runTest(UnconfinedTestDispatcher()) {
        val journal = InMemoryTransferJournal()
        var n = 0
        val first = TransferManager(journal, RecordingEnvelope(), newId = { "dead${++n}" })
        var runs = 0
        // The process "dies" while the upload runs: its coroutine never finishes, the journal keeps it.
        launch {
            first.run(TransferKind.ATTACHMENT_UPLOAD, "video.mp4", 1_000) { t ->
                runs++
                t.ref(RemoteRef(sessionId = "s1", attachmentId = "att-9"))
                t.progress(300, 1_000)
                CompletableDeferred<Unit>().await()
            }
        }
        val plain = launch { first.run(TransferKind.FILE_UPLOAD, "noref.txt", 5) { CompletableDeferred<Unit>().await() } }
        assertEquals(2, journal.saved.size)

        // Next process: same journal, a new manager.
        val second = TransferManager(InMemoryTransferJournal(journal.saved), RecordingEnvelope(), newId = { "new" })
        val rec = second.recover()
        assertEquals(2, rec.interrupted.size)
        assertTrue(rec.interrupted.all { it.phase == TransferPhase.INTERRUPTED })
        assertEquals("only the one with something to clean up", listOf("att-9"), rec.cleanup.map { it.ref?.attachmentId })
        assertEquals(1, runs)
        assertTrue("nothing is running in the new process", second.active.value.isEmpty())

        // A new transfer in the new process must not overwrite the leftovers waiting for cleanup.
        val newJournal = InMemoryTransferJournal(journal.saved)
        val third = TransferManager(newJournal, RecordingEnvelope(), newId = { "fresh" })
        val again = third.recover()
        third.run(TransferKind.FILE_UPLOAD, "other", 1) { }
        assertEquals(listOf(again.cleanup.single().id), newJournal.saved.map { it.id })
        third.cleanedUp(again.cleanup.single().id)
        assertTrue(newJournal.saved.isEmpty())
        // Reported once: a later start finds nothing.
        val fourth = TransferManager(newJournal, RecordingEnvelope())
        assertTrue(fourth.recover().interrupted.isEmpty())
        plain.cancel()
        coroutineContext.cancelChildren()
    }

    @Test
    fun fileJournalRoundTripsAndToleratesGarbage() {
        val dir = Files.createTempDirectory("journal").toFile()
        val f = java.io.File(dir, "transfers/journal.json")
        val j = FileTransferJournal(f)
        assertTrue(j.load().isEmpty())
        val e = entry(TransferPhase.RUNNING).copy(ref = RemoteRef(uploadId = "u1"))
        j.save(listOf(e))
        assertEquals(listOf(e), FileTransferJournal(f).load())
        j.save(emptyList())
        assertFalse(f.exists())
        f.parentFile!!.mkdirs()
        f.writeText("{not json")
        assertTrue(FileTransferJournal(f).load().isEmpty())
        dir.deleteRecursively()
    }

    private fun kotlin.coroutines.CoroutineContext.cancelChildren() = this[kotlinx.coroutines.Job]?.children?.forEach { it.cancel() }
}
