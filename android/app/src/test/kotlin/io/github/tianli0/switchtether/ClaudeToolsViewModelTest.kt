package io.github.tianli0.switchtether

import io.github.tianli0.switchtether.domain.SessionRepository
import io.github.tianli0.switchtether.remote.api.ApiException
import io.github.tianli0.switchtether.remote.api.CommandResponse
import io.github.tianli0.switchtether.remote.command.CommandOutcome
import io.github.tianli0.switchtether.remote.protocol.ApiErrorBody
import io.github.tianli0.switchtether.remote.protocol.ProtocolJson
import io.github.tianli0.switchtether.remote.protocol.SessionDetail
import io.github.tianli0.switchtether.ui.common.Loadable
import io.github.tianli0.switchtether.ui.sessions.OutgoingStatus
import io.github.tianli0.switchtether.ui.sessions.SessionDetailViewModel
import io.github.tianli0.switchtether.ui.sessions.SessionToolsViewModel
import io.github.tianli0.switchtether.ui.sessions.ToolNotice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A fake desktop for the Claude Code session tools on top of [FakeSessionRepository]. */
private class ClaudeToolsRepo(val base: FakeSessionRepository) : SessionRepository by base {
    val calls = mutableListOf<Pair<String, JsonObject>>()
    var respond: (String, JsonObject) -> CommandOutcome = { action, _ -> ok("{}") }

    override suspend fun claudeTool(id: String, action: String, params: JsonObject, commandId: String): CommandOutcome {
        calls += action to params
        return respond(action, params)
    }

    companion object {
        fun ok(body: String): CommandOutcome = CommandOutcome.Succeeded("c", CommandResponse(ProtocolJson.parseToJsonElement(body), false))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ClaudeToolsViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val uuid = "0f8fad5b-d9cb-469f-a165-70867728950e"

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun repo(status: String = "busy", tools: Boolean = true) = ClaudeToolsRepo(
        FakeSessionRepository().apply {
            detail = SessionDetail(
                summary(control = "full", status = status).copy(managed = true, origin = "managed"),
                caps(send = "VERIFIED"),
                emptyList(),
                claudeTools = tools,
                permissionMode = "default",
            )
            nextOutcome = { CommandOutcome.Succeeded(it, CommandResponse(ProtocolJson.parseToJsonElement("""{"accepted":true,"messageUuid":"$uuid"}"""), false)) }
        },
    )

    @Test
    fun messageQueuedWhileBusyCanBeWithdrawn() = runTest(dispatcher) {
        val r = repo()
        val vm = SessionDetailViewModel("s1", FakeConnection(), r, FakeDrafts())
        advanceUntilIdle()
        assertTrue(vm.state.value.claudeTools)
        vm.setDraft("also check the tests")
        vm.send()
        advanceUntilIdle()
        val m = vm.state.value.outgoing.single()
        assertEquals(OutgoingStatus.DELIVERED, m.status)
        assertEquals(uuid, m.messageUuid)

        r.respond = { _, _ -> ClaudeToolsRepo.ok("""{"cancelled":true}""") }
        vm.withdrawOutgoing(m.commandId)
        advanceUntilIdle()
        assertEquals("cancel_message" to uuid, r.calls.last().first to r.calls.last().second["messageUuid"].toString().trim('"'))
        assertTrue(vm.state.value.outgoing.isEmpty())
        assertEquals(ToolNotice.Withdrawn, vm.state.value.notice)
    }

    @Test
    fun withdrawTooLateKeepsTheMessage() = runTest(dispatcher) {
        val r = repo()
        val vm = SessionDetailViewModel("s1", FakeConnection(), r, FakeDrafts())
        advanceUntilIdle()
        vm.setDraft("hi")
        vm.send()
        advanceUntilIdle()
        val id = vm.state.value.outgoing.single().commandId
        r.respond = { _, _ -> ClaudeToolsRepo.ok("""{"cancelled":false}""") }
        vm.withdrawOutgoing(id)
        advanceUntilIdle()
        val m = vm.state.value.outgoing.single()
        assertNull(m.messageUuid)
        assertFalse(m.withdrawing)
        assertEquals(ToolNotice.WithdrawTooLate, vm.state.value.notice)
    }

    @Test
    fun idleSessionMessagesAreNotOfferedForWithdraw() = runTest(dispatcher) {
        val vm = SessionDetailViewModel("s1", FakeConnection(), repo(status = "idle"), FakeDrafts())
        advanceUntilIdle()
        vm.setDraft("hi")
        vm.send()
        advanceUntilIdle()
        assertNull(vm.state.value.outgoing.single().messageUuid)
    }

    @Test
    fun permissionModeUpdatesTheDetailAndShowsRefusals() = runTest(dispatcher) {
        val r = repo()
        val vm = SessionDetailViewModel("s1", FakeConnection(), r, FakeDrafts())
        advanceUntilIdle()
        r.respond = { _, p -> ClaudeToolsRepo.ok("""{"mode":${p["mode"]}}""") }
        vm.setPermissionMode("plan")
        advanceUntilIdle()
        assertEquals("plan", vm.state.value.detail.valueOrNull?.permissionMode)
        assertNull(vm.state.value.permissionPending)

        r.respond = { _, _ ->
            CommandOutcome.Rejected("c", ApiException(422, ApiErrorBody("capability_unavailable", "bypassPermissions requires --dangerously-skip-permissions"), null))
        }
        vm.setPermissionMode("bypassPermissions")
        advanceUntilIdle()
        assertEquals("plan", vm.state.value.detail.valueOrNull?.permissionMode)
        assertEquals("bypassPermissions requires --dangerously-skip-permissions", vm.state.value.permissionError)
    }

    @Test
    fun rewindRunsDryFirstAndOnlyRestoresAfterConfirm() = runTest(dispatcher) {
        val r = repo()
        val vm = SessionDetailViewModel("s1", FakeConnection(), r, FakeDrafts())
        advanceUntilIdle()
        r.respond = { _, p ->
            val dry = p["dryRun"].toString() == "true"
            ClaudeToolsRepo.ok("""{"dryRun":$dry,"canRewind":true,"filesChanged":["a.txt"],"insertions":1,"deletions":0}""")
        }
        vm.previewRewind(uuid)
        advanceUntilIdle()
        val preview = vm.state.value.rewind
        assertEquals(listOf("a.txt"), preview?.preview?.filesChanged)
        assertEquals(1, r.calls.size)
        assertEquals("true", r.calls.single().second["dryRun"].toString())

        vm.confirmRewind()
        advanceUntilIdle()
        assertEquals("false", r.calls.last().second["dryRun"].toString())
        assertNull(vm.state.value.rewind)
        assertEquals(ToolNotice.Rewound(1), vm.state.value.notice)
    }

    @Test
    fun toolsPageLoadsAndAsksSideQuestions() = runTest(dispatcher) {
        val r = repo()
        r.respond = { action, _ ->
            when (action) {
                "context_usage" -> ClaudeToolsRepo.ok("""{"totalTokens":10,"maxTokens":100,"percentage":10,"categories":[]}""")
                "plan" -> ClaudeToolsRepo.ok("""{"exists":false}""")
                "mcp_status" -> ClaudeToolsRepo.ok("""{"servers":[{"name":"gh","status":"failed","error":"boom"}]}""")
                "side_question" -> ClaudeToolsRepo.ok("""{"answer":"Yes."}""")
                else -> ClaudeToolsRepo.ok("{}")
            }
        }
        val vm = SessionToolsViewModel("s1", r)
        advanceUntilIdle()
        assertEquals(10L, vm.state.value.context.valueOrNull?.totalTokens)
        assertFalse(vm.state.value.plan.valueOrNull!!.exists)
        assertEquals("boom", vm.state.value.mcp.valueOrNull?.single()?.error)

        vm.ask("is it safe?")
        advanceUntilIdle()
        assertEquals("Yes.", vm.state.value.answer)
        assertFalse(vm.state.value.asking)

        // A refusal is shown as the section's error, not as an empty result.
        r.respond = { _, _ -> CommandOutcome.Rejected("c", ApiException(422, ApiErrorBody("capability_unavailable", "not a managed Claude session"), null)) }
        vm.loadContext()
        advanceUntilIdle()
        val failed = vm.state.value.context as Loadable.Failed
        assertEquals("not a managed Claude session", failed.error.detail)
    }
}
