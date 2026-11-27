package io.github.tianli0.switchtether

import io.github.tianli0.switchtether.connection.ConnectionState
import io.github.tianli0.switchtether.domain.ManagementRepository
import io.github.tianli0.switchtether.domain.PairingException
import io.github.tianli0.switchtether.domain.PairingRepository
import io.github.tianli0.switchtether.domain.PairingResult
import io.github.tianli0.switchtether.domain.SessionChange
import io.github.tianli0.switchtether.remote.api.ApiException
import io.github.tianli0.switchtether.remote.command.CommandOutcome
import io.github.tianli0.switchtether.remote.command.CommandStatus
import io.github.tianli0.switchtether.remote.pairing.PairingLink
import io.github.tianli0.switchtether.remote.protocol.AppToggleItem
import io.github.tianli0.switchtether.remote.protocol.ApiErrorBody
import io.github.tianli0.switchtether.remote.protocol.CommandRecord
import io.github.tianli0.switchtether.remote.protocol.DailyUsage
import io.github.tianli0.switchtether.remote.protocol.PromptView
import io.github.tianli0.switchtether.remote.protocol.ProvidersView
import io.github.tianli0.switchtether.remote.protocol.QuotaView
import io.github.tianli0.switchtether.remote.protocol.SessionDetail
import io.github.tianli0.switchtether.remote.protocol.UsageBreakdownRow
import io.github.tianli0.switchtether.remote.protocol.UsageSummary
import io.github.tianli0.switchtether.ui.common.CommandPrompt
import io.github.tianli0.switchtether.ui.common.Loadable
import io.github.tianli0.switchtether.ui.common.UiError
import io.github.tianli0.switchtether.ui.home.HomeViewModel
import io.github.tianli0.switchtether.ui.onboarding.CodeError
import io.github.tianli0.switchtether.ui.onboarding.PairingUiState
import io.github.tianli0.switchtether.ui.onboarding.PairingViewModel
import io.github.tianli0.switchtether.ui.sessions.ROLLING_STATUS_ID
import io.github.tianli0.switchtether.ui.sessions.OutgoingStatus
import io.github.tianli0.switchtether.ui.sessions.SessionDetailViewModel
import io.github.tianli0.switchtether.ui.sessions.SessionScope
import io.github.tianli0.switchtether.ui.sessions.SessionsViewModel
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun detailVm(repo: FakeSessionRepository, conn: FakeConnection = FakeConnection(), drafts: FakeDrafts = FakeDrafts()) =
        SessionDetailViewModel("s1", conn, repo, drafts)

    @Test
    fun onlyALiveManagedSessionCanBeEnded() = runTest(dispatcher) {
        // Taken over from the IDE (full control, not ours): its process is never ended from the phone.
        val attachedRepo = FakeSessionRepository().apply { detail = SessionDetail(summary(control = "full"), caps(send = "VERIFIED", interrupt = "VERIFIED"), emptyList()) }
        val attached = detailVm(attachedRepo)
        advanceUntilIdle()
        assertTrue(attached.state.value.detail.valueOrNull!!.summary.isAttached)
        assertFalse(attached.state.value.canStop)
        attached.stopSession("End session")
        advanceUntilIdle()
        assertTrue(attachedRepo.stopped.isEmpty())

        val managed = summary(control = "full").copy(origin = "managed", managed = true)
        val endedRepo = FakeSessionRepository().apply { detail = SessionDetail(managed.copy(liveness = "ended"), caps(send = "VERIFIED"), emptyList()) }
        val ended = detailVm(endedRepo)
        advanceUntilIdle()
        assertFalse(ended.state.value.canStop)

        val repo = FakeSessionRepository().apply { detail = SessionDetail(managed, caps(send = "VERIFIED", interrupt = "VERIFIED"), emptyList()) }
        val vm = detailVm(repo)
        advanceUntilIdle()
        assertTrue(vm.state.value.canStop)
        vm.stopSession("End session")
        advanceUntilIdle()
        assertEquals(listOf("s1"), repo.stopped)
    }

    @Test
    fun sessionDetailLoadsAndReplacesEventsById() = runTest(dispatcher) {
        val repo = FakeSessionRepository().apply {
            detail = SessionDetail(summary(), caps(), emptyList())
            events = listOf(
                event("u1", 1, "user_message", """{"text":"hi"}"""),
                event("a1", 2, "assistant_message", """{"text":"Hel"}""", ),
                event(ROLLING_STATUS_ID, 3, "status", """{"state":"running"}"""),
            )
        }
        val vm = detailVm(repo)
        advanceUntilIdle()
        val st = vm.state.value
        assertTrue(st.events is Loadable.Ready)
        // rolling status goes to the header, not the list
        assertEquals(listOf("u1", "a1"), st.items.map { it.key })
        assertEquals("running", st.rollingStatus?.data?.get("state")?.toString()?.trim('"'))

        // live streaming update: same id, new data → replaced in place, no duplicate row
        repo.changeFlow.emit(SessionChange.Event(event("a1", 2, "assistant_message", """{"text":"Hello world"}""")))
        advanceUntilIdle()
        assertEquals(listOf("u1", "a1"), vm.state.value.items.map { it.key })
        assertTrue(vm.state.value.log["a1"]!!.data.toString().contains("Hello world"))

        // events of other sessions are ignored
        repo.changeFlow.emit(SessionChange.Event(event("x", 9, "assistant_message", session = "other")))
        advanceUntilIdle()
        assertEquals(2, vm.state.value.items.size)
    }

    @Test
    fun continueOnPhoneForksIntoManagedSessionAndSurfacesConflicts() = runTest(dispatcher) {
        val created = """{"id":"managed:abc","agent":"claude_code","origin":"managed","managed":true,"title":"Fix login bug"}"""
        val repo = FakeSessionRepository().apply {
            detail = SessionDetail(summary(live = false, status = "completed").copy(cwd = "D:/w"), caps(continueSession = "PARTIAL"), emptyList())
            nextOutcome = { CommandOutcome.Succeeded(it, io.github.tianli0.switchtether.remote.api.CommandResponse(io.github.tianli0.switchtether.remote.protocol.ProtocolJson.parseToJsonElement(created), false)) }
        }
        val vm = detailVm(repo)
        advanceUntilIdle()
        assertTrue(vm.state.value.canOfferContinue)
        assertTrue(vm.state.value.canFork)
        vm.continueOnPhone("continue")
        advanceUntilIdle()
        assertEquals("s1", repo.lastCreate?.resumeFrom)
        assertEquals("continue", repo.lastCreate?.mode)
        assertEquals("claude_code", repo.lastCreate?.agent)
        assertEquals("managed:abc", vm.state.value.continuedAs?.id)

        vm.consumeContinued()
        vm.continueOnPhone("fork", fork = true)
        advanceUntilIdle()
        assertEquals("fork", repo.lastCreate?.mode)

        // 409 (original busy on the computer): shown with the desktop's message, never retried.
        vm.consumeContinued()
        repo.nextOutcome = {
            CommandOutcome.Rejected(it, io.github.tianli0.switchtether.remote.api.ApiException(409, io.github.tianli0.switchtether.remote.protocol.ApiErrorBody("conflict", "original session is busy"), null))
        }
        vm.continueOnPhone("continue")
        advanceUntilIdle()
        val prompt = vm.commands.prompt.value
        assertEquals(io.github.tianli0.switchtether.ui.common.CommandPrompt.Kind.REJECTED, prompt?.kind)
        assertTrue(prompt?.detail.orEmpty().contains("original session is busy"))
        assertEquals(null, vm.state.value.continuedAs)
    }

    @Test
    fun continueIsNotOfferedWithoutTheCapability() = runTest(dispatcher) {
        val repo = FakeSessionRepository().apply { detail = SessionDetail(summary(), caps(), emptyList()) }
        val vm = detailVm(repo)
        advanceUntilIdle()
        assertTrue(!vm.state.value.canOfferContinue)
    }

    @Test
    fun lowValueSystemRowsAreHidden() = runTest(dispatcher) {
        val repo = FakeSessionRepository().apply {
            detail = SessionDetail(summary(), caps(), emptyList())
            events = listOf(
                event("s1", 1, "system", """{"subtype":"thinking_tokens","text":"thinking_tokens"}"""),
                event("s2", 2, "system", """{"subtype":"commands_changed","text":""}"""),
                event("s3", 3, "system", """{"subtype":"init","text":"Session started"}"""),
                event("s4", 4, "system", """{"subtype":"compact_boundary","text":"compact_boundary"}"""),
            )
        }
        val vm = detailVm(repo)
        advanceUntilIdle()
        assertEquals(listOf("s3"), vm.state.value.items.map { it.key })
    }

    @Test
    fun composerDisabledUnlessSendMessageVerified() = runTest(dispatcher) {
        val repo = FakeSessionRepository().apply { detail = SessionDetail(summary(), caps(send = "BLOCKED"), emptyList()) }
        val vm = detailVm(repo)
        advanceUntilIdle()
        vm.setDraft("please fix it")
        vm.send()
        advanceUntilIdle()
        assertFalse(vm.state.value.canSend)
        assertTrue(repo.sent.isEmpty())
        assertTrue(vm.state.value.outgoing.isEmpty())

        val repo2 = FakeSessionRepository().apply { detail = SessionDetail(summary(control = "full"), caps(send = "VERIFIED"), emptyList()) }
        val drafts = FakeDrafts()
        val vm2 = detailVm(repo2, drafts = drafts)
        advanceUntilIdle()
        vm2.setDraft("please fix it")
        vm2.send()
        // Optimistic: bubble + cleared composer right away.
        assertEquals("please fix it", vm2.state.value.outgoing.single().text)
        assertEquals("", vm2.state.value.draft)
        assertEquals(1, vm2.state.value.sendTick)
        advanceUntilIdle()
        assertEquals("please fix it", repo2.sent.single().second)
        assertEquals(OutgoingStatus.DELIVERED, vm2.state.value.outgoing.single().status)
        assertTrue("typing row after a delivered message", vm2.state.value.typing)
    }

    @Test
    fun attachmentsUploadFirstThenTravelWithTheMessage() = runTest(dispatcher) {
        val repo = FakeSessionRepository().apply { detail = SessionDetail(summary(control = "full"), caps(send = "VERIFIED"), emptyList()) }
        val vm = SessionDetailViewModel("s1", FakeConnection(), repo, FakeDrafts(), ioDispatcher = dispatcher)
        advanceUntilIdle()
        // 5 MiB in 2 MiB chunks: three uploads, progress on the chip, ready at the end.
        val big = ByteArray(5 * 1024 * 1024) { (it % 251).toByte() }
        vm.addAttachment("build.log", big.size.toLong(), "text/plain") { big.inputStream() }
        vm.addAttachment("photo.jpg", 3, "image/jpeg") { byteArrayOf(1, 2, 3).inputStream() }
        advanceUntilIdle()
        val drafts = vm.state.value.draftAttachments
        assertTrue(drafts.all { it.ready })
        assertTrue(repo.uploads.getValue("att-1").contentEquals(big))
        // Too large is refused locally, never uploaded, and blocks sending until removed.
        vm.addAttachment("huge.iso", io.github.tianli0.switchtether.ui.sessions.MAX_ATTACHMENT_BYTES + 1, null) { null }
        val huge = vm.state.value.draftAttachments.last()
        assertEquals(io.github.tianli0.switchtether.ui.sessions.AttachmentFailure.TOO_LARGE, huge.failure)
        vm.send()
        assertTrue("not sent while a chip has failed", repo.sent.isEmpty())
        vm.removeAttachment(huge.localId)
        vm.send()
        advanceUntilIdle()
        val cmd = repo.sent.single().first
        assertEquals(listOf("att-1", "att-2"), repo.sentAttachments[cmd])
        assertTrue(vm.state.value.draftAttachments.isEmpty())
        // Removing an uploaded one tells the desktop to delete it.
        vm.addAttachment("x.txt", 1, null) { byteArrayOf(9).inputStream() }
        advanceUntilIdle()
        vm.removeAttachment(vm.state.value.draftAttachments.single().localId)
        advanceUntilIdle()
        assertEquals(listOf("att-3"), repo.cancelledAttachments)
        // An older desktop answers 404: the chip says to update the desktop app.
        repo.attachmentError = ApiException(404, ApiErrorBody("NOT_FOUND", "no route"), null)
        vm.addAttachment("y.txt", 1, null) { byteArrayOf(9).inputStream() }
        advanceUntilIdle()
        assertEquals(io.github.tianli0.switchtether.ui.sessions.AttachmentFailure.OLD_DESKTOP, vm.state.value.draftAttachments.single().failure)
    }

    @Test
    fun anAttachmentCancelledFromItsTransferNotificationStopsAndIsDiscarded() = runTest(dispatcher) {
        val repo = FakeSessionRepository().apply { detail = SessionDetail(summary(control = "full"), caps(send = "VERIFIED"), emptyList()) }
        val ended = mutableListOf<io.github.tianli0.switchtether.transfer.TransferEntry>()
        val envelope = object : io.github.tianli0.switchtether.transfer.TransferEnvelope {
            override fun begin(entry: io.github.tianli0.switchtether.transfer.TransferEntry) = true
            override fun update(entry: io.github.tianli0.switchtether.transfer.TransferEntry) = Unit
            override fun end(entry: io.github.tianli0.switchtether.transfer.TransferEntry) { ended += entry }
        }
        val transfers = io.github.tianli0.switchtether.transfer.TransferManager(io.github.tianli0.switchtether.transfer.InMemoryTransferJournal(), envelope)
        val vm = SessionDetailViewModel("s1", FakeConnection(), repo, FakeDrafts(), ioDispatcher = dispatcher, transfers = transfers)
        advanceUntilIdle()
        repo.chunkGate = kotlinx.coroutines.CompletableDeferred()
        val big = ByteArray(9 * 1024 * 1024) { 1 }
        vm.addAttachment("video.mp4", big.size.toLong(), "video/mp4") { big.inputStream() }
        advanceUntilIdle()
        val running = transfers.active.value.values.single()
        assertEquals("att-1", running.ref?.attachmentId)
        assertEquals(io.github.tianli0.switchtether.transfer.TransferKind.ATTACHMENT_UPLOAD, running.kind)
        // The notification's Cancel.
        assertTrue(transfers.cancel(running.id))
        advanceUntilIdle()
        assertTrue("the chip is gone", vm.state.value.draftAttachments.isEmpty())
        assertEquals("the partial upload is discarded once", listOf("att-1"), repo.cancelledAttachments)
        assertEquals("no chunk landed", 0, repo.uploads.getValue("att-1").size)
        assertEquals(io.github.tianli0.switchtether.transfer.TransferPhase.CANCELLED, ended.single().phase)
        assertTrue(transfers.active.value.isEmpty())
        // A normal upload through the manager still completes and is reported as succeeded.
        repo.chunkGate = null
        vm.addAttachment("a.txt", 3, "text/plain") { byteArrayOf(1, 2, 3).inputStream() }
        advanceUntilIdle()
        assertTrue(vm.state.value.draftAttachments.single().ready)
        assertEquals(io.github.tianli0.switchtether.transfer.TransferPhase.SUCCEEDED, ended.last().phase)
    }

    @Test
    fun echoWithClientIdReplacesTheBubbleWithoutDuplicates() = runTest(dispatcher) {
        val repo = FakeSessionRepository().apply { detail = SessionDetail(summary(control = "full"), caps(send = "VERIFIED"), emptyList()) }
        val vm = detailVm(repo)
        advanceUntilIdle()
        vm.setDraft("hello there")
        vm.send()
        advanceUntilIdle()
        val id = repo.sent.single().first
        // A different text with the right clientId still replaces it (exact match wins).
        vm.applyEvents(listOf(event("u9", 9, "user_message", """{"text":"hello  there\n","source":"phone","clientId":"$id"}""")))
        assertTrue(vm.state.value.outgoing.isEmpty())
        assertEquals(1, vm.state.value.items.count { it.event.kind == "user_message" })
        assertTrue("still waiting for the agent", vm.state.value.typing)
        vm.applyEvents(listOf(event("a10", 10, "assistant_message", """{"text":"Hi"}""")))
        assertFalse(vm.state.value.typing)
    }

    @Test
    fun olderDesktopsMatchByNormalizedTextFromThePhone() = runTest(dispatcher) {
        val repo = FakeSessionRepository().apply {
            detail = SessionDetail(summary(control = "full"), caps(send = "VERIFIED"), emptyList())
            events = listOf(event("u1", 1, "user_message", """{"text":"run tests","source":"phone"}"""))
        }
        val vm = detailVm(repo)
        advanceUntilIdle()
        vm.setDraft("  run   tests ")
        vm.send()
        advanceUntilIdle()
        // The identical older message (seq 1) is not the echo; a desktop-typed one is not either.
        vm.applyEvents(listOf(event("u2", 2, "user_message", """{"text":"run tests","source":"desktop"}""")))
        assertEquals(1, vm.state.value.outgoing.size)
        vm.applyEvents(listOf(event("u3", 3, "user_message", """{"text":"run tests","source":"phone"}""")))
        assertTrue(vm.state.value.outgoing.isEmpty())
    }

    @Test
    fun rejectedSendFailsAndRetriesUnderANewCommandId() = runTest(dispatcher) {
        val repo = FakeSessionRepository().apply {
            detail = SessionDetail(summary(control = "full"), caps(send = "VERIFIED"), emptyList())
            nextOutcome = { id -> CommandOutcome.Rejected(id, ApiException(409, ApiErrorBody("conflict", "session busy"), null)) }
        }
        val vm = detailVm(repo)
        advanceUntilIdle()
        vm.setDraft("x")
        vm.send()
        advanceUntilIdle()
        val failed = vm.state.value.outgoing.single()
        assertEquals(OutgoingStatus.FAILED, failed.status)
        assertEquals("session busy", failed.error)
        assertEquals("x", failed.text) // the text is kept
        repo.nextOutcome = { id -> CommandOutcome.Succeeded(id, io.github.tianli0.switchtether.remote.api.CommandResponse(kotlinx.serialization.json.JsonNull, false)) }
        vm.retrySend(failed.commandId)
        advanceUntilIdle()
        // The desktop stored that failure and replays it for the same id (§5): the retry is a new request.
        val (first, second) = repo.sent.map { it.first }
        assertEquals(failed.commandId, first)
        assertTrue(second != first)
        assertEquals(OutgoingStatus.DELIVERED, vm.state.value.outgoing.single().status)
        vm.deleteOutgoing(second)
        assertTrue(vm.state.value.outgoing.isEmpty())
    }

    @Test
    fun offlineWithoutALoadedSessionNeverSendsButKeepsDraft() = runTest(dispatcher) {
        val conn = FakeConnection(ConnectionState.Offline(TEST_DESKTOP, "down", null))
        val repo = FakeSessionRepository().apply { detail = SessionDetail(summary(control = "full"), caps(send = "VERIFIED"), emptyList()) }
        val drafts = FakeDrafts()
        val vm = detailVm(repo, conn, drafts)
        vm.setDraft("later")
        vm.send()
        advanceUntilIdle()
        assertTrue(repo.sent.isEmpty())
        assertEquals("later", vm.state.value.draft)
    }

    @Test
    fun queuedWhileOfflineGoesOutOnReconnectWithTheSameCommandId() = runTest(dispatcher) {
        val conn = FakeConnection()
        val repo = FakeSessionRepository().apply { detail = SessionDetail(summary(control = "full"), caps(send = "VERIFIED"), emptyList()) }
        val vm = detailVm(repo, conn)
        advanceUntilIdle()
        conn.state.value = ConnectionState.Offline(TEST_DESKTOP, "down", null)
        advanceUntilIdle()
        assertTrue(vm.state.value.canQueue)
        vm.setDraft("when you can")
        vm.send()
        advanceUntilIdle()
        val queued = vm.state.value.outgoing.single()
        assertEquals(OutgoingStatus.WAITING_CONNECTION, queued.status)
        assertTrue(repo.sent.isEmpty())
        conn.state.value = ONLINE
        advanceUntilIdle()
        assertEquals(listOf(queued.commandId to "when you can"), repo.sent)
        assertEquals(OutgoingStatus.DELIVERED, vm.state.value.outgoing.single().status)
    }

    /** A real reconnect sets Online and then emits a "connected" resync: the catch-up replaces the load. */
    @Test
    fun queuedWhileOfflineGoesOutWhenTheReconnectCatchUpReplacesTheLoad() = runTest(dispatcher) {
        val conn = FakeConnection()
        val repo = FakeSessionRepository().apply {
            detail = SessionDetail(summary(control = "full"), caps(send = "VERIFIED"), emptyList())
            events = listOf(event("a1", 1, "assistant_message", """{"text":"hi"}"""))
        }
        val vm = detailVm(repo, conn)
        advanceUntilIdle()
        conn.state.value = ConnectionState.Offline(TEST_DESKTOP, "down", null)
        advanceUntilIdle()
        vm.setDraft("when you can")
        vm.send()
        advanceUntilIdle()
        val queued = vm.state.value.outgoing.single()
        assertEquals(OutgoingStatus.WAITING_CONNECTION, queued.status)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        repo.detailGate = gate
        conn.state.value = ONLINE // load() starts and waits for the detail
        repo.refetchFlow.tryEmit("connected") // catchUp() cancels it
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(queued.commandId to "when you can"), repo.sent)
        assertEquals(OutgoingStatus.DELIVERED, vm.state.value.outgoing.single().status)
    }

    @Test
    fun unknownOutcomeChecksStatusBeforeOfferingRetry() = runTest(dispatcher) {
        val repo = FakeSessionRepository().apply {
            detail = SessionDetail(summary(control = "full"), caps(send = "VERIFIED"), emptyList())
            nextOutcome = { id -> CommandOutcome.Unknown(id, IOException("timeout")) }
            nextStatus = CommandStatus.NotFound
        }
        val vm = detailVm(repo)
        advanceUntilIdle()
        vm.setDraft("run tests")
        vm.send()
        advanceUntilIdle()
        val m = vm.state.value.outgoing.single()
        assertEquals(listOf(m.commandId), repo.statusQueries) // asked, not resent
        assertEquals(1, repo.sent.size)
        assertEquals(OutgoingStatus.FAILED, m.status)
        // Explicit tap: same command id.
        repo.nextOutcome = { id -> CommandOutcome.Succeeded(id, io.github.tianli0.switchtether.remote.api.CommandResponse(kotlinx.serialization.json.JsonNull, false)) }
        vm.retrySend(m.commandId)
        advanceUntilIdle()
        assertEquals(listOf(m.commandId, m.commandId), repo.sent.map { it.first })
    }

    @Test
    fun unknownOutcomeThatActuallySucceededIsTreatedAsDelivered() = runTest(dispatcher) {
        val repo = FakeSessionRepository().apply {
            detail = SessionDetail(summary(control = "full"), caps(send = "VERIFIED"), emptyList())
            nextOutcome = { id -> CommandOutcome.Unknown(id, IOException("timeout")) }
            nextStatus = CommandStatus.Succeeded(CommandRecord("x", status = "succeeded"))
        }
        val vm = detailVm(repo)
        advanceUntilIdle()
        vm.setDraft("x")
        vm.send()
        advanceUntilIdle()
        assertEquals(OutgoingStatus.DELIVERED, vm.state.value.outgoing.single().status)
        assertEquals("", vm.state.value.draft)
        assertEquals(1, repo.sent.size)
    }

    /** `unknown` = the computer restarted mid-send (§5). It used to read as pending → "delivered" forever. */
    @Test
    fun aMessageTheComputerLostInARestartFailsAndRetriesUnderANewIdOnlyOnTap() = runTest(dispatcher) {
        val repo = FakeSessionRepository().apply {
            detail = SessionDetail(summary(control = "full"), caps(send = "VERIFIED"), emptyList())
            nextOutcome = { id -> CommandOutcome.Unknown(id, IOException("timeout")) }
            nextStatus = CommandStatus.Interrupted(CommandRecord("x", status = "unknown"))
        }
        val vm = detailVm(repo)
        advanceUntilIdle()
        vm.setDraft("deploy")
        vm.send()
        advanceUntilIdle()
        val m = vm.state.value.outgoing.single()
        assertEquals(OutgoingStatus.FAILED, m.status)
        assertEquals(SessionDetailViewModel.OUTCOME_UNKNOWN, m.error)
        assertTrue(m.retryAsNew)
        assertEquals(1, repo.sent.size) // never resent on its own

        repo.nextOutcome = { id -> CommandOutcome.Succeeded(id, io.github.tianli0.switchtether.remote.api.CommandResponse(kotlinx.serialization.json.JsonNull, false)) }
        vm.retrySend(m.commandId)
        advanceUntilIdle()
        val (first, second) = repo.sent.map { it.first }
        assertEquals(m.commandId, first)
        assertTrue(second != first) // the old id is refused for good: a new request
        val now = vm.state.value.outgoing.single()
        assertEquals(second, now.commandId)
        assertEquals(OutgoingStatus.DELIVERED, now.status)
        assertFalse(now.retryAsNew)
    }

    /** A same-id retry after the computer restarted is answered 409 for good: not "delivered". */
    @Test
    fun aCommandInProgressAnswerIsCheckedAgainstTheCommandRecord() = runTest(dispatcher) {
        val repo = FakeSessionRepository().apply {
            detail = SessionDetail(summary(control = "full"), caps(send = "VERIFIED"), emptyList())
            nextOutcome = { id -> CommandOutcome.InProgress(id) }
            nextStatus = CommandStatus.Interrupted(CommandRecord("x", status = "unknown"))
        }
        val vm = detailVm(repo)
        advanceUntilIdle()
        vm.setDraft("deploy")
        vm.send()
        advanceUntilIdle()
        val m = vm.state.value.outgoing.single()
        assertEquals(listOf(m.commandId), repo.statusQueries)
        assertEquals(OutgoingStatus.FAILED, m.status)
        assertTrue(m.retryAsNew)

        // Still running on the computer: delivered, as before.
        repo.nextStatus = CommandStatus.Pending(CommandRecord("x", status = "pending"))
        vm.setDraft("again")
        vm.send()
        advanceUntilIdle()
        assertEquals(OutgoingStatus.DELIVERED, vm.state.value.outgoing.last().status)
    }

    @Test
    fun blockedApprovalSurfacesAsBlockedPrompt() = runTest(dispatcher) {
        val repo = FakeSessionRepository().apply {
            detail = SessionDetail(summary(), caps(), emptyList())
            nextOutcome = { id ->
                CommandOutcome.Rejected(id, ApiException(422, ApiErrorBody("capability_unavailable", "blocked", details = kotlinx.serialization.json.buildJsonObject { put("status", kotlinx.serialization.json.JsonPrimitive("BLOCKED")) }), null))
            }
        }
        val vm = detailVm(repo)
        advanceUntilIdle()
        vm.resolveApproval("Approve", "r1", "allow")
        advanceUntilIdle()
        assertEquals(CommandPrompt.Kind.BLOCKED, vm.commands.prompt.value?.kind)
    }

    @Test
    fun loadOlderPagesPrepend() = runTest(dispatcher) {
        val repo = FakeSessionRepository().apply {
            detail = SessionDetail(summary(), caps(), emptyList())
            events = (1L..450L).map { event("e$it", it, "system", """{"text":"$it"}""") }
            hasMore = true
        }
        val vm = detailVm(repo)
        advanceUntilIdle()
        assertEquals(300, vm.state.value.items.size)
        assertEquals(151L, vm.state.value.log.minSeq)
        vm.loadOlder()
        advanceUntilIdle()
        assertEquals(450, vm.state.value.items.size)
        assertEquals(1L, vm.state.value.log.minSeq)
    }

    @Test
    fun sessionsListSeparatesLiveAndHistoryAndAppliesUpserts() = runTest(dispatcher) {
        val repo = FakeSessionRepository().apply {
            sessions = listOf(summary("a", live = true), summary("b", live = false), summary("n", live = false).copy(lastActiveAtMs = null))
        }
        val vm = SessionsViewModel(FakeConnection(), repo)
        advanceUntilIdle()
        // Everything by default (history visible on a fresh start), running sessions first.
        assertEquals(listOf("a", "b", "n"), vm.state.value.visible.map { it.id }) // null lastActive must not crash sorting
        vm.setScope(SessionScope.LIVE)
        assertEquals(listOf("a"), vm.state.value.visible.map { it.id })
        vm.setScope(SessionScope.HISTORY)
        assertEquals(listOf("b", "n"), vm.state.value.visible.map { it.id })
        repo.changeFlow.emit(SessionChange.Upserted(summary("b", live = true)))
        advanceUntilIdle()
        assertEquals(listOf("n"), vm.state.value.visible.map { it.id })
        vm.setScope(SessionScope.LIVE)
        assertEquals(setOf("a", "b"), vm.state.value.visible.map { it.id }.toSet())
    }

    @Test
    fun homeShowsBackendUnavailableHonestly() = runTest(dispatcher) {
        val repo = FakeSessionRepository().apply { sessions = listOf(summary("a", status = "waiting"), summary("b", live = false)) }
        val mgmt = object : ManagementRepository by unavailableManagement() {}
        val vm = HomeViewModel(FakeConnection(), repo, mgmt)
        advanceUntilIdle()
        val st = vm.state.value
        assertEquals(1, st.live.size)
        assertEquals(1, st.waitingCount)
        val failed = st.usageToday as Loadable.Failed
        assertEquals(UiError.Kind.BACKEND_UNAVAILABLE, failed.error.kind)
    }

    @Test
    fun pairingViewModelValidatesLinksAndReportsFailures() = runTest(dispatcher) {
        var result: Result<PairingResult> = Result.failure(PairingException(PairingException.Reason.EXPIRED))
        val repo = object : PairingRepository {
            override suspend fun pair(target: io.github.tianli0.switchtether.remote.pairing.PairingTarget, deviceName: String, osVersion: String, appVersion: String) = result.getOrThrow()
        }
        var succeeded: PairingResult? = null
        val vm = PairingViewModel(repo, { true }, "Pixel", "15", "0.1.0") { succeeded = it }
        vm.submitLink("https://evil.example/pair")
        assertTrue(vm.state.value is PairingUiState.LinkInvalid)

        val link = "switchtether://pair?v=1&h=10.0.2.2&p=47615&fp=${"ab".repeat(32)}&id=p1&s=${"A".repeat(43)}&n=PC"
        vm.onLinkTextChange(link)
        vm.submitLink()
        assertTrue(vm.state.value is PairingUiState.Confirm)
        vm.pair()
        advanceUntilIdle()
        assertEquals(PairingException.Reason.EXPIRED, (vm.state.value as PairingUiState.Failed).reason)

        result = Result.success(PairingResult(TEST_DESKTOP, "123456", "123456", stepUpEnabled = false))
        vm.pair()
        advanceUntilIdle()
        assertTrue(vm.state.value is PairingUiState.Done)
        assertNotNull(succeeded)
        assertEquals("", vm.linkText.value) // single-use secret is dropped from memory
    }

    @Test
    fun pairingWithCodeFromNearbyOrTypedAddress() = runTest(dispatcher) {
        val seen = mutableListOf<io.github.tianli0.switchtether.remote.pairing.PairingTarget>()
        var result: Result<PairingResult> = Result.failure(PairingException(PairingException.Reason.WRONG_SECRET))
        val repo = object : PairingRepository {
            override suspend fun pair(target: io.github.tianli0.switchtether.remote.pairing.PairingTarget, deviceName: String, osVersion: String, appVersion: String): PairingResult {
                seen += target
                return result.getOrThrow()
            }
        }
        val vm = PairingViewModel(repo, { true }, "Pixel", "15", "0.1.0") {}
        val fp = "cd".repeat(32)
        vm.pickNearby(io.github.tianli0.switchtether.remote.discovery.DiscoveredDesktop("svc", "id", "PC", fp, "192.168.8.4", 47615))
        val entry = vm.state.value as PairingUiState.EnterCode
        assertEquals("192.168.8.4", entry.host)
        assertEquals(listOf("192.168.8.4"), vm.pendingHosts())

        vm.submitCode("", "12 34")
        assertEquals(CodeError.INVALID_CODE, (vm.state.value as PairingUiState.EnterCode).error)
        assertTrue(seen.isEmpty())

        vm.submitCode("", "123 456")
        advanceUntilIdle()
        val code = seen.single() as io.github.tianli0.switchtether.remote.pairing.PairingTarget.Code
        assertEquals("123456", code.code)
        assertEquals(fp, code.fingerprint)
        assertEquals(listOf("192.168.8.4"), code.hosts)
        assertEquals(PairingException.Reason.WRONG_SECRET, (vm.state.value as PairingUiState.Failed).reason)
        // Retrying a failed code goes back to the code screen (the code itself is not kept).
        vm.pair()
        assertEquals("192.168.8.4", (vm.state.value as PairingUiState.EnterCode).host)

        // Typed address: no fingerprint (trust on first use), port parsed from the address.
        vm.enterAddress()
        vm.submitCode("not an address!", "123456")
        assertEquals(CodeError.INVALID_ADDRESS, (vm.state.value as PairingUiState.EnterCode).error)
        result = Result.success(PairingResult(TEST_DESKTOP, "111111", "111111", stepUpEnabled = true))
        vm.submitCode("pc.tailnet.ts.net:48000", "654321")
        advanceUntilIdle()
        val typed = seen.last() as io.github.tianli0.switchtether.remote.pairing.PairingTarget.Code
        assertEquals(listOf("pc.tailnet.ts.net"), typed.hosts)
        assertEquals(48000, typed.port)
        assertEquals(null, typed.fingerprint)
        assertTrue(vm.state.value is PairingUiState.Done)
    }

    @Test
    fun connectionRefreshesStoredAddressesAndWakeTargets() {
        val status = io.github.tianli0.switchtether.remote.protocol.StatusView(
            hosts = listOf("192.168.8.4", "[fd7a::1]", "100.64.0.7"),
            wake = listOf(
                io.github.tianli0.switchtether.remote.wake.WakeTarget("00:11:22:33:44:55", "192.168.8.255", "192.168.8.4"),
                io.github.tianli0.switchtether.remote.wake.WakeTarget("bogus", "1.1.1.1", "x"),
            ),
        )
        val cur = TEST_DESKTOP.copy(hosts = listOf("192.168.1.20", "100.64.0.7"), lastGoodHost = "192.168.1.20")
        val viaTailscale = io.github.tianli0.switchtether.connection.ConnectionManager.refreshedAddresses(cur, status, "100.64.0.7")
        assertEquals(listOf("100.64.0.7", "192.168.8.4", "fd7a::1", "192.168.1.20"), viaTailscale.hosts)
        assertEquals("100.64.0.7", viaTailscale.lastGoodHost)
        assertEquals(listOf("00:11:22:33:44:55"), viaTailscale.wake.map { it.mac })
        // Via the relay: no working direct host; an older desktop without the fields changes nothing.
        val viaRelay = io.github.tianli0.switchtether.connection.ConnectionManager.refreshedAddresses(viaTailscale, io.github.tianli0.switchtether.remote.protocol.StatusView(), null)
        assertEquals(viaTailscale, viaRelay)
    }

    private fun unavailableManagement(): ManagementRepository = object : ManagementRepository {
        private fun no(): Nothing = throw ApiException(503, ApiErrorBody("backend_unavailable", "CC Switch backend is not ready"), null)
        override val changes: Flow<String> = emptyFlow()
        override suspend fun apps(): List<String> = no()
        override suspend fun providers(app: String): ProvidersView = no()
        override suspend fun switchProvider(app: String, id: String, commandId: String): CommandOutcome = no()
        override suspend fun testProvider(app: String, id: String, commandId: String): CommandOutcome = no()
        override suspend fun setCredential(app: String, id: String, field: String, value: String, commandId: String): CommandOutcome = no()
        override suspend fun usageSummary(fromSec: Long, toSec: Long, app: String?): UsageSummary = no()
        override suspend fun usageTrend(fromSec: Long, toSec: Long, app: String?): List<DailyUsage> = no()
        override suspend fun usageModels(fromSec: Long, toSec: Long, app: String?): List<UsageBreakdownRow> = no()
        override suspend fun usageProviders(fromSec: Long, toSec: Long, app: String?): List<UsageBreakdownRow> = no()
        override suspend fun quota(tool: String): QuotaView = no()
        override suspend fun mcpServers(): List<AppToggleItem> = no()
        override suspend fun toggleMcp(id: String, app: String, enabled: Boolean, commandId: String): CommandOutcome = no()
        override suspend fun skills(): List<AppToggleItem> = no()
        override suspend fun toggleSkill(id: String, app: String, enabled: Boolean, commandId: String): CommandOutcome = no()
        override suspend fun prompts(app: String): List<PromptView> = no()
        override suspend fun enablePrompt(app: String, id: String, commandId: String): CommandOutcome = no()
        override suspend fun commandStatus(commandId: String): CommandStatus = CommandStatus.NotFound
    }
}
