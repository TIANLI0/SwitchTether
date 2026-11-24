package io.github.tianli0.switchtether

import io.github.tianli0.switchtether.connection.ConnectionState
import io.github.tianli0.switchtether.domain.SessionChange
import io.github.tianli0.switchtether.remote.api.ApiException
import io.github.tianli0.switchtether.remote.api.CommandResponse
import io.github.tianli0.switchtether.remote.command.CommandOutcome
import io.github.tianli0.switchtether.remote.protocol.ApiErrorBody
import io.github.tianli0.switchtether.remote.protocol.ErrorCodes
import io.github.tianli0.switchtether.remote.protocol.SessionDetail
import io.github.tianli0.switchtether.ui.common.Loadable
import io.github.tianli0.switchtether.ui.sessions.OutgoingStatus
import io.github.tianli0.switchtether.ui.sessions.SessionDetailViewModel
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Session detail: requests in flight against live events, reloads, reconnects and repeated taps. */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionRacesTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun sendable() = FakeSessionRepository().apply { detail = SessionDetail(summary(control = "full"), caps(send = "VERIFIED"), emptyList()) }

    private fun detailVm(repo: FakeSessionRepository, conn: FakeConnection = FakeConnection(), id: String = "s1") =
        SessionDetailViewModel(id, conn, repo, FakeDrafts())

    private fun ok(id: String) = CommandOutcome.Succeeded(id, CommandResponse(JsonNull, false))

    // ---- log: load / older page / catch-up ---------------------------------------------------------

    @Test
    fun liveEventsThatArriveWhileTheNewestPageIsInFlightSurvive() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val repo = FakeSessionRepository().apply {
            detail = SessionDetail(summary(), caps(), emptyList())
            events = listOf(event("u1", 1, "user_message", """{"text":"hi"}"""), event("a1", 2, "assistant_message", """{"text":"Hel"}"""))
            eventsGate = gate
        }
        val vm = detailVm(repo)
        advanceUntilIdle()
        // The desktop read the page; before the answer lands, the stream delivers what happened next.
        repo.changeFlow.emit(SessionChange.Event(event("a1", 2, "assistant_message", """{"text":"Hello"}""")))
        repo.changeFlow.emit(SessionChange.Event(event("t1", 3, "tool_call", """{"callId":"c1","name":"Bash"}""")))
        gate.complete(Unit)
        advanceUntilIdle()
        val st = vm.state.value
        assertTrue(st.events is Loadable.Ready)
        assertEquals(listOf("u1", "a1", "t1"), st.items.map { it.key })
        assertTrue("the newer streamed text wins over the page", st.log["a1"]!!.data.toString().contains("Hello"))
    }

    @Test
    fun anOlderPageFetchedForAReplacedLogIsDiscarded() = runTest(dispatcher) {
        val repo = FakeSessionRepository().apply {
            detail = SessionDetail(summary(), caps(), emptyList())
            events = (1L..450L).map { event("e$it", it, "system", """{"text":"$it"}""") }
            hasMore = true
        }
        val vm = detailVm(repo)
        advanceUntilIdle()
        assertEquals(151L, vm.state.value.log.minSeq)
        val gate = CompletableDeferred<Unit>()
        repo.olderGate = gate
        vm.loadOlder() // events 1..150 of the old log, still in flight
        assertTrue(vm.state.value.loadingOlder)
        // The desktop restarted: a new sequence, the log is replaced by its newest page.
        repo.events = (1000L..1399L).map { event("n$it", it, "system", """{"text":"$it"}""") }
        repo.refetchFlow.tryEmit("epoch_changed")
        advanceUntilIdle()
        assertEquals(1100L, vm.state.value.log.minSeq)
        gate.complete(Unit)
        advanceUntilIdle()
        val st = vm.state.value
        assertEquals("no rows of the old log below a gap", 1100L, st.log.minSeq)
        assertEquals(300, st.log.size)
        assertTrue("older rows of the new log can still be loaded", st.hasMoreBefore)
        assertFalse(st.loadingOlder)
        // The next request is made for the new log.
        repo.olderGate = null
        vm.loadOlder()
        advanceUntilIdle()
        assertEquals(1000L, vm.state.value.log.minSeq)
    }

    @Test
    fun anOlderPageStillMergesWhenOnlyACatchUpRanMeanwhile() = runTest(dispatcher) {
        val repo = FakeSessionRepository().apply {
            detail = SessionDetail(summary(), caps(), emptyList())
            events = (1L..450L).map { event("e$it", it, "system", """{"text":"$it"}""") }
            hasMore = true
        }
        val vm = detailVm(repo)
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        repo.olderGate = gate
        vm.loadOlder()
        repo.refetchFlow.tryEmit("connected") // same log, only brought up to date
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1L, vm.state.value.log.minSeq)
        assertEquals(450, vm.state.value.log.size)
    }

    @Test
    fun catchUpFallsBackToAFullLoadWhenTheEventsNeverLoaded() = runTest(dispatcher) {
        val repo = FakeSessionRepository().apply {
            detail = SessionDetail(summary(), caps(), emptyList())
            eventsError = IOException("timeout")
        }
        val vm = detailVm(repo)
        advanceUntilIdle()
        assertTrue(vm.state.value.events is Loadable.Failed)
        // A live event gives the log a seq, but the page was never loaded.
        repo.changeFlow.emit(SessionChange.Event(event("e9", 9, "assistant_message", """{"text":"late"}""")))
        advanceUntilIdle()
        assertEquals(9L, vm.state.value.log.maxSeq)
        assertTrue(vm.state.value.events is Loadable.Failed)

        repo.eventsError = null
        repo.events = (1L..9L).map { event("e$it", it, "assistant_message", """{"text":"$it"}""") }
        repo.hasMore = true
        repo.refetchFlow.tryEmit("connected")
        advanceUntilIdle()
        val st = vm.state.value
        assertTrue("a catch-up alone would leave the error page", st.events is Loadable.Ready)
        assertEquals(9, st.log.size)
        assertTrue(st.hasMoreBefore)
    }

    // ---- optimistic send ------------------------------------------------------------------------------

    @Test
    fun aSecondTapOnABubbleThatIsBeingSentDoesNotPostAgain() = runTest(dispatcher) {
        val repo = sendable()
        val vm = detailVm(repo)
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        repo.sendGate = gate
        vm.setDraft("x")
        vm.send()
        val id = vm.state.value.outgoing.single().commandId
        assertEquals(OutgoingStatus.SENDING, vm.state.value.outgoing.single().status)
        vm.retrySend(id)
        vm.retrySend(id)
        advanceUntilIdle()
        assertEquals(1, repo.sent.size)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, repo.sent.size)
        assertEquals(OutgoingStatus.DELIVERED, vm.state.value.outgoing.single().status)
    }

    @Test
    fun twoReconnectSignalsSendTheQueueOnceAndInOrder() = runTest(dispatcher) {
        val conn = FakeConnection()
        val repo = sendable().apply { events = listOf(event("a1", 1, "assistant_message", """{"text":"hi"}""")) }
        val vm = detailVm(repo, conn)
        advanceUntilIdle()
        conn.state.value = ConnectionState.Offline(TEST_DESKTOP, "down", null)
        advanceUntilIdle()
        vm.setDraft("first")
        vm.send()
        vm.setDraft("second")
        vm.send()
        advanceUntilIdle()
        assertTrue(repo.sent.isEmpty())
        val gate = CompletableDeferred<Unit>()
        repo.sendGate = gate
        conn.state.value = ONLINE // load() flushes the queue
        advanceUntilIdle()
        repo.refetchFlow.tryEmit("connected") // the catch-up of the same reconnect flushes again
        advanceUntilIdle()
        repo.refetchFlow.tryEmit("resync")
        advanceUntilIdle()
        assertEquals("the second message waits for the first POST", listOf("first"), repo.sent.map { it.second })
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("first", "second"), repo.sent.map { it.second })
        assertEquals(2, repo.sent.map { it.first }.toSet().size)
        assertTrue(vm.state.value.outgoing.all { it.status == OutgoingStatus.DELIVERED })
    }

    @Test
    fun theSameTextSentTwiceIsNotClearedByOneEcho() = runTest(dispatcher) {
        val repo = sendable()
        val vm = detailVm(repo)
        advanceUntilIdle()
        repeat(2) {
            vm.setDraft("ok")
            vm.send()
            advanceUntilIdle()
        }
        assertEquals(2, vm.state.value.outgoing.size)
        // An older desktop (no clientId) echoes the first one.
        vm.applyEvents(listOf(event("u1", 1, "user_message", """{"text":"ok","source":"phone"}""")))
        assertEquals(1, vm.state.value.outgoing.size)
        // Anything else that rebuilds the rows must not hand the same echo to the second bubble.
        vm.applyEvents(listOf(event("a2", 2, "assistant_message", """{"text":"sure"}""")))
        vm.toggleSteps("x")
        assertEquals(1, vm.state.value.outgoing.size)
        assertEquals(1, vm.state.value.items.count { it.event.kind == "user_message" })
        vm.applyEvents(listOf(event("u3", 3, "user_message", """{"text":"ok","source":"phone"}""")))
        assertTrue(vm.state.value.outgoing.isEmpty())
        assertEquals(2, vm.state.value.items.count { it.event.kind == "user_message" })
    }

    @Test
    fun anEchoBelongsToTheAcceptedBubbleNotToTheFailedOrQueuedOneWithTheSameText() = runTest(dispatcher) {
        val repo = sendable().apply {
            nextOutcome = { id -> CommandOutcome.Rejected(id, ApiException(409, ApiErrorBody("conflict", "session busy"), null)) }
        }
        val vm = detailVm(repo)
        advanceUntilIdle()
        vm.setDraft("go on")
        vm.send()
        advanceUntilIdle()
        val failed = vm.state.value.outgoing.single()
        assertEquals(OutgoingStatus.FAILED, failed.status)
        repo.nextOutcome = ::ok
        vm.setDraft("go on")
        vm.send()
        advanceUntilIdle()
        vm.applyEvents(listOf(event("u1", 1, "user_message", """{"text":"go on","source":"phone"}""")))
        val left = vm.state.value.outgoing.single()
        assertEquals("the rejected message is still there to retry or delete", failed.commandId, left.commandId)
        assertEquals(OutgoingStatus.FAILED, left.status)

        // A message that waits for the connection was never sent: no event can be its echo.
        val conn = FakeConnection()
        val repo2 = sendable()
        val vm2 = detailVm(repo2, conn)
        advanceUntilIdle()
        conn.state.value = ConnectionState.Offline(TEST_DESKTOP, "down", null)
        advanceUntilIdle()
        vm2.setDraft("go on")
        vm2.send()
        vm2.applyEvents(listOf(event("u1", 1, "user_message", """{"text":"go on"}""")))
        assertEquals(OutgoingStatus.WAITING_CONNECTION, vm2.state.value.outgoing.single().status)
    }

    @Test
    fun theReplyingRowEndsWithAToolCallOrAnError() = runTest(dispatcher) {
        for ((kind, data) in listOf("tool_call" to """{"callId":"c1","name":"Bash"}""", "error" to """{"message":"overloaded"}""")) {
            val repo = sendable()
            val vm = detailVm(repo)
            advanceUntilIdle()
            vm.setDraft("hello")
            vm.send()
            advanceUntilIdle()
            val id = repo.sent.single().first
            assertTrue(vm.state.value.typing)
            vm.applyEvents(listOf(event("u1", 1, "user_message", """{"text":"hello","clientId":"$id"}""")))
            assertTrue(vm.state.value.typing)
            vm.applyEvents(listOf(event("x2", 2, kind, data)))
            assertFalse("$kind is the agent's answer", vm.state.value.typing)
            assertFalse(vm.state.value.awaitingReply)
        }
    }

    @Test
    fun theReplyingRowEndsWhenTheTurnEndedWithoutOutputWhileThePhoneWasAway() = runTest(dispatcher) {
        for (signal in listOf<String?>(null, "resync")) {
            val conn = FakeConnection()
            val repo = sendable()
            val vm = detailVm(repo, conn)
            advanceUntilIdle()
            vm.setDraft("hello")
            vm.send()
            advanceUntilIdle()
            val id = repo.sent.single().first
            repo.events = listOf(event("u1", 1, "user_message", """{"text":"hello","clientId":"$id"}"""))
            vm.applyEvents(repo.events)
            assertTrue(vm.state.value.typing)
            conn.state.value = ConnectionState.Offline(TEST_DESKTOP, "down", null)
            advanceUntilIdle()
            // Still working when the phone comes back: the row stays.
            repo.detail = SessionDetail(summary(control = "full", status = "busy"), caps(send = "VERIFIED"), emptyList())
            conn.state.value = ONLINE
            advanceUntilIdle()
            assertTrue(vm.state.value.typing)
            // Interrupted on the computer meanwhile: idle, nothing after our message. The phone learns
            // it from the full load of a reconnect, or from the catch-up after a resync.
            repo.detail = SessionDetail(summary(control = "full", status = "idle"), caps(send = "VERIFIED"), emptyList())
            if (signal == null) {
                conn.state.value = ConnectionState.Offline(TEST_DESKTOP, "down", null)
                advanceUntilIdle()
                conn.state.value = ONLINE
            } else {
                repo.refetchFlow.tryEmit(signal)
            }
            advanceUntilIdle()
            assertFalse("after ${signal ?: "load"}", vm.state.value.typing)
        }
    }

    /** The echo itself is first seen in the page of the reconnect: same answer. */
    @Test
    fun theReplyingRowDoesNotStartForATurnThatAlreadyEndedWhenTheEchoArrivesWithTheReload() = runTest(dispatcher) {
        val conn = FakeConnection()
        val repo = sendable()
        val vm = detailVm(repo, conn)
        advanceUntilIdle()
        vm.setDraft("hello")
        vm.send()
        advanceUntilIdle()
        val id = repo.sent.single().first
        conn.state.value = ConnectionState.Offline(TEST_DESKTOP, "down", null)
        advanceUntilIdle()
        repo.events = listOf(event("u1", 1, "user_message", """{"text":"hello","clientId":"$id"}"""))
        conn.state.value = ONLINE
        advanceUntilIdle()
        assertTrue(vm.state.value.outgoing.isEmpty())
        assertFalse(vm.state.value.typing)
        // A session that is still working keeps the row.
        vm.setDraft("again")
        vm.send()
        advanceUntilIdle()
        val id2 = repo.sent.last().first
        conn.state.value = ConnectionState.Offline(TEST_DESKTOP, "down", null)
        advanceUntilIdle()
        repo.events = repo.events + event("u2", 2, "user_message", """{"text":"again","clientId":"$id2"}""")
        repo.detail = SessionDetail(summary(control = "full", status = "busy"), caps(send = "VERIFIED"), emptyList())
        conn.state.value = ONLINE
        advanceUntilIdle()
        assertTrue(vm.state.value.outgoing.isEmpty())
        assertTrue(vm.state.value.typing)
    }

    @Test
    fun aQueuedMessageSaysTheSessionEndedWhenTheManagedProcessIsGone() = runTest(dispatcher) {
        val conn = FakeConnection()
        val repo = FakeSessionRepository().apply {
            detail = SessionDetail(summary("managed:1", control = "full"), caps(send = "VERIFIED"), emptyList())
        }
        val vm = detailVm(repo, conn, id = "managed:1")
        advanceUntilIdle()
        conn.state.value = ConnectionState.Offline(TEST_DESKTOP, "down", null)
        advanceUntilIdle()
        vm.setDraft("when you can")
        vm.send()
        advanceUntilIdle()
        assertEquals(OutgoingStatus.WAITING_CONNECTION, vm.state.value.outgoing.single().status)
        // The desktop restarted: the managed process no longer exists.
        repo.detailError = ApiException(404, ApiErrorBody(ErrorCodes.NOT_FOUND, "no such session"), null)
        conn.state.value = ONLINE
        advanceUntilIdle()
        val m = vm.state.value.outgoing.single()
        assertTrue(repo.sent.isEmpty())
        assertEquals(OutgoingStatus.FAILED, m.status)
        assertEquals(SessionDetailViewModel.SESSION_ENDED, m.error)
    }
}
