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

    /**
     * The VS Code extension (2.1.331, plan approval card): `await onPermissionModeChange(mode)`
     * first, then `accept()`. The first edit after the plan must already run in the new mode.
     */
    @Test
    fun approvingWithAutoAcceptSwitchesToAcceptEditsThenApproves() = runTest(dispatcher) {
        val r = repo()
        r.tool = { PlanRepo.ok(it, """{"mode":"acceptEdits"}""") }
        val vm = vm(r)
        advanceUntilIdle()
        vm.approvePlan("Approve", "Permission mode", "r1", "acceptEdits")
        advanceUntilIdle()
        assertEquals(listOf("${ClaudeTools.PERMISSION_MODE}:acceptEdits", "approve:r1:allow"), r.calls)
        assertEquals("acceptEdits", vm.state.value.detail.valueOrNull?.permissionMode)
        assertNull(vm.commands.prompt.value)
        assertNull(vm.state.value.notice)
    }

    @Test
    fun approvingWithReviewSwitchesToDefaultThenApproves() = runTest(dispatcher) {
        val r = repo()
        val vm = vm(r)
        advanceUntilIdle()
        vm.approvePlan("Approve", "Permission mode", "r1", "default")
        advanceUntilIdle()
        assertEquals(listOf("${ClaudeTools.PERMISSION_MODE}:default", "approve:r1:allow"), r.calls)
        assertEquals("default", vm.state.value.detail.valueOrNull?.permissionMode)
    }

    @Test
    fun failedApprovalShowsTheApprovalPrompt() = runTest(dispatcher) {
        val r = repo()
        r.approval = { PlanRepo.rejected(it, "not_found", "request is gone") }
        val vm = vm(r)
        advanceUntilIdle()
        vm.approvePlan("Approve", "Permission mode", "r1", "acceptEdits")
        advanceUntilIdle()
        assertEquals(listOf("${ClaudeTools.PERMISSION_MODE}:acceptEdits", "approve:r1:allow"), r.calls)
        assertEquals("Approve", vm.commands.prompt.value?.label)
    }

    @Test
    fun aRefusedModeChangeStillApprovesThePlanAndSaysSo() = runTest(dispatcher) {
        val r = repo()
        r.tool = { PlanRepo.rejected(it, "validation_error", "unknown permission mode") }
        val vm = vm(r)
        advanceUntilIdle()
        vm.approvePlan("Approve", "Permission mode", "r1", "acceptEdits")
        advanceUntilIdle()
        // Mode asked once, approved once: no second attempt on its own.
        assertEquals(listOf("${ClaudeTools.PERMISSION_MODE}:acceptEdits", "approve:r1:allow"), r.calls)
        assertNull(vm.commands.prompt.value)
        val notice = vm.state.value.notice as io.github.tianli0.switchtether.ui.sessions.ToolNotice.Failed
        assertTrue(notice.message, notice.message.startsWith("Permission mode") && notice.message.contains("unknown permission mode"))
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

    /**
     * `costUsd` is Claude Code's `total_cost_usd`: the running total of the process, so the
     * session's cost is the latest value, not the sum (0.25 then 0.5 is 0.5 spent, not 0.75).
     * The tokens of a result are per turn and are summed.
     */
    @Test
    fun sessionCostIsTheLatestRunningTotalAndTokensAreSummed() {
        val events = listOf(
            event("m1:usage", 1, "usage", """{"inputTokens":900,"outputTokens":90}"""), // per message, no cost
            event("result:1", 2, "usage", """{"inputTokens":1000,"outputTokens":100,"costUsd":0.25}"""),
            event("u1", 3, "user_message", """{"text":"hi","costUsd":9}"""), // not a usage event
            event("result:2", 4, "usage", """{"inputTokens":2000,"outputTokens":300,"costUsd":0.5}"""),
            event("result:2", 4, "usage", """{"inputTokens":2000,"outputTokens":300,"costUsd":0.5}"""), // same id again
            event("result:3", 5, "usage", """{"inputTokens":5,"outputTokens":5,"costUsd":null}"""),
        )
        val c = sessionCost(events)!!
        assertEquals(0.5, c.costUsd, 1e-9)
        assertEquals(3000L, c.inputTokens)
        assertEquals(400L, c.outputTokens)
    }

    /** A `/clear` or a restart without a saved total starts again from zero: each run's last total counts. */
    @Test
    fun aTotalThatDropsStartsANewRunAndRunsAreAdded() {
        val events = listOf(
            event("result:1", 1, "usage", """{"costUsd":0.2}"""),
            event("result:2", 2, "usage", """{"costUsd":0.4}"""),
            // Crash / startup-error result: zeroed, neither a new run nor a cost.
            event("result:3", 3, "usage", """{"costUsd":0}"""),
            event("result:4", 4, "usage", """{"costUsd":0.6}"""),
            // /clear: the running total restarts.
            event("result:5", 5, "usage", """{"costUsd":0.1}"""),
            event("result:6", 6, "usage", """{"costUsd":0.3}"""),
        )
        assertEquals(0.9, sessionCost(events)!!.costUsd, 1e-9)
        // A resume that continues from the saved total is the same run.
        val resumed = listOf(
            event("result:1", 1, "usage", """{"costUsd":0.2}"""),
            event("r2:result:1", 2, "usage", """{"costUsd":0.2}"""),
            event("r2:result:2", 3, "usage", """{"costUsd":0.35}"""),
        )
        assertEquals(0.35, sessionCost(resumed)!!.costUsd, 1e-9)
    }

    @Test
    fun sessionCostIsNullWhenNothingCarriesACost() {
        assertNull(sessionCost(emptyList()))
        assertNull(sessionCost(listOf(event("m1:usage", 1, "usage", """{"inputTokens":900,"outputTokens":90}"""))))
    }

    @Test
    fun negativeOrNonFiniteCostsAreIgnored() {
        assertNull(sessionCost(listOf(event("result:1", 1, "usage", """{"costUsd":-1}"""))))
        assertEquals(0.3, sessionCost(listOf(event("result:1", 1, "usage", """{"costUsd":0.3}"""), event("result:2", 2, "usage", """{"costUsd":-5}""")))!!.costUsd, 1e-9)
    }

    @Test
    fun aZeroCostIsStillACost() {
        val c = sessionCost(listOf(event("result:1", 1, "usage", """{"inputTokens":10,"outputTokens":2,"costUsd":0}""")))
        assertEquals(0.0, c!!.costUsd, 0.0)
        assertEquals(10L, c.inputTokens)
    }
}
