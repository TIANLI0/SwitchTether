package io.github.tianli0.switchtether

import io.github.tianli0.switchtether.connection.ConnectionState
import io.github.tianli0.switchtether.domain.ManagementRepository
import io.github.tianli0.switchtether.remote.protocol.ApiErrorBody
import io.github.tianli0.switchtether.remote.api.ApiException
import io.github.tianli0.switchtether.remote.command.CommandOutcome
import io.github.tianli0.switchtether.remote.command.CommandStatus
import io.github.tianli0.switchtether.remote.protocol.AppToggleItem
import io.github.tianli0.switchtether.remote.protocol.DailyUsage
import io.github.tianli0.switchtether.remote.protocol.PromptView
import io.github.tianli0.switchtether.remote.protocol.ProtocolJson
import io.github.tianli0.switchtether.remote.protocol.ProvidersView
import io.github.tianli0.switchtether.remote.protocol.QuotaView
import io.github.tianli0.switchtether.remote.protocol.RequestLog
import io.github.tianli0.switchtether.remote.protocol.RequestLogPage
import io.github.tianli0.switchtether.remote.protocol.StatusView
import io.github.tianli0.switchtether.remote.protocol.UsageBreakdownRow
import io.github.tianli0.switchtether.remote.protocol.UsageSummary
import io.github.tianli0.switchtether.remote.usage.UsageRangePreset
import io.github.tianli0.switchtether.ui.manage.UsageViewModel
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Usage page against the newer desktop API (§7.3): logs paging, 全部 start, zone, hidden apps. */
class UsageLogsTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun log(i: Int) = RequestLog.parse(
        ProtocolJson.parseToJsonElement(
            """{"requestId":"r$i","providerName":"P","appType":"claude","model":"m","inputTokens":10,"outputTokens":5,"cacheReadTokens":0,
               "cacheCreationTokens":0,"totalCostUsd":"0.0123","isStreaming":true,"latencyMs":1500,"statusCode":${if (i == 0) 500 else 200},
               "createdAt":1794600000,"dataSource":"proxy"}""",
        ).jsonObject,
    )

    private inner class Repo(val logsSupported: Boolean = true, val total: Int = 45) : ManagementRepository {
        val logCalls = mutableListOf<Triple<Long, Long, Int>>()
        val summaryRanges = mutableListOf<Pair<Long, Long>>()
        /** Held answers, to let an older request finish after a newer one. */
        var firstDateGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        val trendGates = mutableMapOf<Long, kotlinx.coroutines.CompletableDeferred<Unit>>()
        private fun no(): Nothing = throw UnsupportedOperationException()
        override val changes: Flow<String> = emptyFlow()
        override suspend fun apps(): List<String> = no()
        override suspend fun providers(app: String): ProvidersView = no()
        override suspend fun switchProvider(app: String, id: String, commandId: String): CommandOutcome = no()
        override suspend fun testProvider(app: String, id: String, commandId: String): CommandOutcome = no()
        override suspend fun setCredential(app: String, id: String, field: String, value: String, commandId: String): CommandOutcome = no()
        override suspend fun usageSummary(fromSec: Long, toSec: Long, app: String?): UsageSummary { summaryRanges += fromSec to toSec; no() }
        override suspend fun usageTrend(fromSec: Long, toSec: Long, app: String?): List<DailyUsage> {
            trendGates[fromSec]?.await()
            return listOf(DailyUsage(date = fromSec.toString(), requests = 1, costUsd = null, tokens = 1))
        }
        override suspend fun usageModels(fromSec: Long, toSec: Long, app: String?): List<UsageBreakdownRow> = emptyList()
        override suspend fun usageProviders(fromSec: Long, toSec: Long, app: String?): List<UsageBreakdownRow> = emptyList()
        override suspend fun quota(tool: String): QuotaView = no()
        override suspend fun mcpServers(): List<AppToggleItem> = no()
        override suspend fun toggleMcp(id: String, app: String, enabled: Boolean, commandId: String): CommandOutcome = no()
        override suspend fun skills(): List<AppToggleItem> = no()
        override suspend fun toggleSkill(id: String, app: String, enabled: Boolean, commandId: String): CommandOutcome = no()
        override suspend fun prompts(app: String): List<PromptView> = no()
        override suspend fun enablePrompt(app: String, id: String, commandId: String): CommandOutcome = no()
        override suspend fun commandStatus(commandId: String): CommandStatus = CommandStatus.NotFound
        override suspend fun usageLogs(fromSec: Long, toSec: Long, app: String?, page: Int, pageSize: Int): RequestLogPage {
            if (!logsSupported) throw ApiException(404, ApiErrorBody("not_found", "no such route"), null)
            logCalls += Triple(fromSec, toSec, page)
            val start = page * pageSize
            return RequestLogPage((start until minOf(total, start + pageSize)).map(::log), total.toLong(), page, pageSize)
        }
        override suspend fun usageLog(requestId: String) = log(requestId.removePrefix("r").toInt()).copy(errorMessage = "fresh")
        override suspend fun usageFirstDateMs(app: String?): Long { firstDateGate?.await(); return 1_790_000_000_000 }
        override suspend fun usageLastSyncMs(): Long = 1_794_600_000_000
        override suspend fun visibleApps(): Map<String, Boolean> = mapOf("gemini" to false, "codex" to true)
    }

    // 2026-11-14 02:00 UTC = 10:00 in Shanghai, still 13 Nov in Los Angeles.
    private val now = ZonedDateTime.parse("2026-11-14T02:00:00Z").toInstant().toEpochMilli()

    private fun online(tz: String?) = FakeConnection(ConnectionState.Online(TEST_DESKTOP, ONLINE.endpoint, StatusView(timeZone = tz, utcOffsetMinutes = 480)))

    @Test
    fun logsPageAndOpenDetail() = runTest(dispatcher) {
        val repo = Repo()
        val vm = UsageViewModel(online("Asia/Shanghai"), repo, ZoneId.of("America/Los_Angeles"), { now })
        advanceUntilIdle()
        val st = vm.state.value
        assertEquals(20, st.logs!!.size)
        assertTrue(st.logsHaveMore)
        assertFalse(st.logs!!.first().ok)
        assertEquals(0.0123, st.logs!!.first().totalCostUsd!!, 1e-9)
        assertEquals(1_794_600_000_000, st.logs!!.first().createdAtMs) // seconds → ms
        vm.loadMoreLogs(); advanceUntilIdle()
        vm.loadMoreLogs(); advanceUntilIdle()
        assertEquals(45, vm.state.value.logs!!.size)
        assertFalse(vm.state.value.logsHaveMore)
        vm.loadMoreLogs(); advanceUntilIdle()
        assertEquals(listOf(0, 1, 2), repo.logCalls.map { it.third })
        vm.openLog(vm.state.value.logs!![3]); advanceUntilIdle()
        assertEquals("fresh", vm.state.value.selectedLog!!.errorMessage)
        assertEquals(1_794_600_000_000, vm.state.value.lastSyncMs)
        assertEquals(setOf("gemini"), vm.state.value.hiddenApps)
    }

    @Test
    fun todayIsTheDesktopsDayAndAllStartsAtTheFirstDate() = runTest(dispatcher) {
        val repo = Repo()
        val vm = UsageViewModel(online("Asia/Shanghai"), repo, ZoneId.of("America/Los_Angeles"), { now })
        advanceUntilIdle()
        val shanghaiMidnight = ZonedDateTime.parse("2026-11-14T00:00:00+08:00[Asia/Shanghai]").toEpochSecond()
        assertEquals(shanghaiMidnight, vm.state.value.fromSec)
        assertEquals(ZoneId.of("Asia/Shanghai"), vm.state.value.zone)
        vm.setPreset(UsageRangePreset.ALL); advanceUntilIdle()
        assertEquals(1_790_000_000L, vm.state.value.fromSec)
    }

    @Test
    fun olderDesktopsHideTheLogAndUseThePhoneZone() = runTest(dispatcher) {
        val conn = FakeConnection(ConnectionState.Online(TEST_DESKTOP, ONLINE.endpoint, StatusView()))
        val vm = UsageViewModel(conn, Repo(logsSupported = false), ZoneId.of("America/Los_Angeles"), { now })
        advanceUntilIdle()
        assertNull(vm.state.value.logs)
        assertEquals(ZoneId.of("America/Los_Angeles"), vm.state.value.zone)
    }

    @Test
    fun aSlowAnswerForThePreviousRangeDoesNotReplaceTheCurrentOne() = runTest(dispatcher) {
        val repo = Repo()
        val shanghaiMidnight = ZonedDateTime.parse("2026-11-14T00:00:00+08:00[Asia/Shanghai]").toEpochSecond()
        val slowToday = kotlinx.coroutines.CompletableDeferred<Unit>()
        repo.trendGates[shanghaiMidnight] = slowToday
        val vm = UsageViewModel(online("Asia/Shanghai"), repo, ZoneId.of("America/Los_Angeles"), { now })
        advanceUntilIdle() // today's trend is still on its way
        vm.setPreset(UsageRangePreset.ALL); advanceUntilIdle()
        slowToday.complete(Unit); advanceUntilIdle()
        assertEquals("1790000000", vm.state.value.trend.valueOrNull!!.single().date)
    }

    @Test
    fun leavingAllWhileItsFirstDateLoadsKeepsTheNewRange() = runTest(dispatcher) {
        val repo = Repo()
        val vm = UsageViewModel(online("Asia/Shanghai"), repo, ZoneId.of("America/Los_Angeles"), { now })
        advanceUntilIdle()
        val today = vm.state.value.fromSec
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        repo.firstDateGate = gate
        vm.setPreset(UsageRangePreset.ALL)
        vm.setPreset(UsageRangePreset.TODAY); advanceUntilIdle()
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(UsageRangePreset.TODAY, vm.state.value.preset)
        assertEquals(today, vm.state.value.fromSec)
        assertEquals(today, repo.logCalls.last().first)
    }
}
