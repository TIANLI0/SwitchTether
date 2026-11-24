package io.github.tianli0.switchtether

import io.github.tianli0.switchtether.domain.SessionRepository
import io.github.tianli0.switchtether.remote.api.ApiException
import io.github.tianli0.switchtether.remote.api.CommandResponse
import io.github.tianli0.switchtether.remote.command.CommandOutcome
import io.github.tianli0.switchtether.remote.protocol.ApiErrorBody
import io.github.tianli0.switchtether.remote.protocol.ProtocolJson
import io.github.tianli0.switchtether.remote.protocol.SessionDetail
import io.github.tianli0.switchtether.remote.protocol.str
import io.github.tianli0.switchtether.remote.session.ClaudeTools
import io.github.tianli0.switchtether.ui.common.CommandPrompt
import io.github.tianli0.switchtether.ui.sessions.SessionDetailViewModel
import io.github.tianli0.switchtether.ui.sessions.render.isPlanExitApproval
import io.github.tianli0.switchtether.ui.sessions.sessionCost
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

/** Records approvals and session-tool calls in the order the desktop would receive them. */
private class PlanRepo(val base: FakeSessionRepository) : SessionRepository by base {
    val calls = mutableListOf<String>()
    var approval: (String) -> CommandOutcome = { ok(it, "{}") }
    var tool: (String) -> CommandOutcome = { ok(it, "{}") }

    override suspend fun resolveApproval(id: String, requestId: String, decision: String, commandId: String): CommandOutcome {
        calls += "approve:$requestId:$decision"
        return approval(commandId)
    }

    override suspend fun claudeTool(id: String, action: String, params: JsonObject, commandId: String): CommandOutcome {
        calls += "$action:${params.str("mode")}"
        return tool(commandId)
    }

    companion object {
        fun ok(id: String, body: String): CommandOutcome = CommandOutcome.Succeeded(id, CommandResponse(ProtocolJson.parseToJsonElement(body), false))
        fun rejected(id: String, code: String, message: String): CommandOutcome =
            CommandOutcome.Rejected(id, ApiException(422, ApiErrorBody(code, message), null))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PlanApprovalAndCostTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun repo(tools: Boolean = true) = PlanRepo(
        FakeSessionRepository().apply {
            detail = SessionDetail(
                summary(control = "full", status = "busy").copy(managed = true, origin = "managed"),
                caps(send = "VERIFIED", approve = "VERIFIED"),
                emptyList(),
                claudeTools = tools,
                permissionMode = "plan",
            )
        },
    )

    private fun vm(r: PlanRepo) = SessionDetailViewModel("s1", FakeConnection(), r, FakeDrafts())

    @Test
    fun approvingWithAutoAcceptApprovesThenSwitchesToAcceptEdits() = runTest(dispatcher) {
        val r = repo()
        r.tool = { PlanRepo.ok(it, """{"mode":"acceptEdits"}""") }
        val vm = vm(r)
        advanceUntilIdle()
        vm.approvePlan("Approve", "Permission mode", "r1", "acceptEdits")
        advanceUntilIdle()
        assertEquals(listOf("approve:r1:allow", "${ClaudeTools.PERMISSION_MODE}:acceptEdits"), r.calls)
        assertEquals("acceptEdits", vm.state.value.detail.valueOrNull?.permissionMode)
        assertNull(vm.commands.prompt.value)
    }

    @Test
    fun approvingWithReviewLeavesPlanModeForDefault() = runTest(dispatcher) {
        val r = repo()
        val vm = vm(r)
        advanceUntilIdle()
        vm.approvePlan("Approve", "Permission mode", "r1", "default")
        advanceUntilIdle()
        assertEquals(listOf("approve:r1:allow", "${ClaudeTools.PERMISSION_MODE}:default"), r.calls)
        assertEquals("default", vm.state.value.detail.valueOrNull?.permissionMode)
    }

    @Test
    fun failedApprovalDoesNotChangeTheMode() = runTest(dispatcher) {
        val r = repo()
        r.approval = { PlanRepo.rejected(it, "not_found", "request is gone") }
        val vm = vm(r)
        advanceUntilIdle()
        vm.approvePlan("Approve", "Permission mode", "r1", "acceptEdits")
        advanceUntilIdle()
        assertEquals(listOf("approve:r1:allow"), r.calls)
        assertEquals("plan", vm.state.value.detail.valueOrNull?.permissionMode)
        assertEquals("Approve", vm.commands.prompt.value?.label)
    }

    @Test
    fun failedModeChangeAfterApprovalShowsTheCommandErrorOnce() = runTest(dispatcher) {
        val r = repo()
        r.tool = { PlanRepo.rejected(it, "validation_error", "unknown permission mode") }
        val vm = vm(r)
        advanceUntilIdle()
        vm.approvePlan("Approve", "Permission mode", "r1", "acceptEdits")
        advanceUntilIdle()
        // Approved once, mode asked once: no second attempt on its own.
        assertEquals(listOf("approve:r1:allow", "${ClaudeTools.PERMISSION_MODE}:acceptEdits"), r.calls)
        val prompt = vm.commands.prompt.value
        assertEquals("Permission mode", prompt?.label)
        assertEquals(CommandPrompt.Kind.REJECTED, prompt?.kind)
        assertTrue(prompt?.detail.orEmpty().contains("unknown permission mode"))
        assertEquals("plan", vm.state.value.detail.valueOrNull?.permissionMode)
    }

    @Test
    fun withoutClaudeToolsItIsAPlainApproval() = runTest(dispatcher) {
        val r = repo(tools = false)
        val vm = vm(r)
        advanceUntilIdle()
        vm.approvePlan("Approve", "Permission mode", "r1", "acceptEdits")
        advanceUntilIdle()
        assertEquals(listOf("approve:r1:allow"), r.calls)
    }

    @Test
    fun planChoicesOnlyForExitPlanModeThatCanBeAllowed() {
        assertTrue(isPlanExitApproval("ExitPlanMode", listOf("allow", "deny")))
        assertFalse(isPlanExitApproval("Bash", listOf("allow", "deny")))
        assertFalse(isPlanExitApproval(null, listOf("allow", "deny")))
        assertFalse(isPlanExitApproval("ExitPlanMode", listOf("deny")))
    }

    @Test
    fun inSessionModesIncludeAutoAndDontAsk() {
        assertEquals(listOf("default", "acceptEdits", "plan", "auto", "dontAsk", "bypassPermissions"), ClaudeTools.PERMISSION_MODES)
    }

    // ---- session cost ----

    @Test
    fun sessionCostSumsCostedUsageEventsOnce() {
        val events = listOf(
            event("m1:usage", 1, "usage", """{"inputTokens":900,"outputTokens":90}"""), // per message, no cost
            event("result:1", 2, "usage", """{"inputTokens":1000,"outputTokens":100,"costUsd":0.25}"""),
            event("u1", 3, "user_message", """{"text":"hi","costUsd":9}"""), // not a usage event
            event("result:2", 4, "usage", """{"inputTokens":2000,"outputTokens":300,"costUsd":0.5}"""),
            event("result:2", 4, "usage", """{"inputTokens":2000,"outputTokens":300,"costUsd":0.5}"""), // same id again
            event("result:3", 5, "usage", """{"inputTokens":5,"outputTokens":5,"costUsd":null}"""),
        )
        val c = sessionCost(events)!!
        assertEquals(0.75, c.costUsd, 1e-9)
        assertEquals(3000L, c.inputTokens)
        assertEquals(400L, c.outputTokens)
    }

    @Test
    fun sessionCostIsNullWhenNothingCarriesACost() {
        assertNull(sessionCost(emptyList()))
        assertNull(sessionCost(listOf(event("m1:usage", 1, "usage", """{"inputTokens":900,"outputTokens":90}"""))))
    }

    @Test
    fun aZeroCostIsStillACost() {
        val c = sessionCost(listOf(event("result:1", 1, "usage", """{"inputTokens":10,"outputTokens":2,"costUsd":0}""")))
        assertEquals(0.0, c!!.costUsd, 0.0)
        assertEquals(10L, c.inputTokens)
    }
}
