package io.github.tianli0.switchtether

import io.github.tianli0.switchtether.connection.ConnectionController
import io.github.tianli0.switchtether.domain.QUOTA_FAILURE_TTL_MS
import io.github.tianli0.switchtether.domain.QUOTA_REFRESH_MIN_MS
import io.github.tianli0.switchtether.domain.QUOTA_TTL_MS
import io.github.tianli0.switchtether.domain.RemoteManagementRepository
import io.github.tianli0.switchtether.domain.quotaFresh
import io.github.tianli0.switchtether.domain.quotaRefreshAt
import io.github.tianli0.switchtether.remote.api.RemoteApi
import io.github.tianli0.switchtether.remote.protocol.QuotaTier
import io.github.tianli0.switchtether.remote.protocol.QuotaView
import io.github.tianli0.switchtether.ui.chart.barLabelIndices
import io.github.tianli0.switchtether.ui.chart.niceAxisMax
import io.github.tianli0.switchtether.ui.manage.usageBarLabels
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuotaCacheAndBarsTest {
    private val ok = QuotaView("claude", true, null, "Max", listOf(QuotaTier("five_hour", 40.0, null)))
    private val failed = QuotaView("codex", false, "not signed in", null, emptyList())

    @Test
    fun quotaAnswersAreReusedForFiveMinutesFailuresForThirtySeconds() {
        assertTrue(quotaFresh(ok, 0, QUOTA_TTL_MS))
        assertTrue(quotaFresh(ok, QUOTA_TTL_MS - 1, QUOTA_TTL_MS))
        assertFalse(quotaFresh(ok, QUOTA_TTL_MS, QUOTA_TTL_MS))
        // A refresh by hand still reuses a very young answer.
        assertTrue(quotaFresh(ok, QUOTA_REFRESH_MIN_MS - 1, QUOTA_REFRESH_MIN_MS))
        assertFalse(quotaFresh(ok, QUOTA_REFRESH_MIN_MS, QUOTA_REFRESH_MIN_MS))
        // Unavailable answers are asked again sooner.
        assertTrue(quotaFresh(failed, QUOTA_FAILURE_TTL_MS - 1, QUOTA_TTL_MS))
        assertFalse(quotaFresh(failed, QUOTA_FAILURE_TTL_MS, QUOTA_TTL_MS))
        // A clock that went backwards does not keep an answer forever.
        assertFalse(quotaFresh(ok, -1, QUOTA_TTL_MS))
    }

    @Test
    fun quotaCarriesTheDesktopsReadTime() {
        val withTime = Json.parseToJsonElement("""{"tool":"claude","success":true,"tiers":[{"name":"five_hour","utilization":12}],"cachedAtMs":1795863270260}""") as JsonObject
        assertEquals(1795863270260L, QuotaView.parse(withTime, "claude").cachedAtMs)
        val older = Json.parseToJsonElement("""{"tool":"claude","success":true,"tiers":[{"name":"five_hour","utilization":12}]}""") as JsonObject
        assertNull(QuotaView.parse(older, "claude").cachedAtMs)
    }

    @Test
    fun quotaRefreshUsesTheEarliestResetAndBacksOffOnAnOldAnswer() {
        val fetched = 1795863270000L
        val due = fetched + 60_000
        val q = ok.copy(
            cachedAtMs = fetched,
            tiers = listOf(
                QuotaTier("past", 12.0, (fetched - 1000).toString()),
                QuotaTier("week", 50.0, (fetched + 10 * QUOTA_TTL_MS).toString()),
                QuotaTier("hour", 100.0, java.time.Instant.ofEpochMilli(due).toString()),
            ),
        )
        assertEquals(due, quotaRefreshAt(q, fetched, QUOTA_TTL_MS))
        assertEquals(due, quotaRefreshAt(q.copy(cachedAtMs = null), fetched, QUOTA_TTL_MS))
        assertEquals(due + QUOTA_REFRESH_MIN_MS, quotaRefreshAt(q, due, QUOTA_TTL_MS))
        assertEquals(due + QUOTA_TTL_MS, quotaRefreshAt(q.copy(cachedAtMs = due), due, QUOTA_TTL_MS))
    }

    @Test
    fun foregroundQuotaRefreshReadsAgainAtResetWithoutManualAction() = runBlocking {
        val resetsAt = System.currentTimeMillis() + 2_000
        val claudeReads = ConcurrentLinkedQueue<Pair<Long, String?>>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val tool = request.url.pathSegments.last()
            val now = System.currentTimeMillis()
            if (tool == "claude") claudeReads.add(now to request.url.queryParameter("maxAge"))
            val tiers = if (tool == "claude") {
                val used = if (now < resetsAt) 100 else 0
                """[{"name":"five_hour","utilization":$used,"resetsAt":"$resetsAt"}]"""
            } else {
                """[{"name":"five_hour","utilization":50}]"""
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"available":true,"tiers":$tiers,"cachedAtMs":$now}""".toResponseBody())
                .build()
        }.build()
        val api = RemoteApi(client, ONLINE.endpoint)
        val conn = object : ConnectionController by FakeConnection() {
            override fun apiOrNull() = api
        }
        val repo = RemoteManagementRepository(conn)
        val refresh = launch { repo.followQuotaRefresh() }
        try {
            withTimeout(10_000) {
                repo.quotas.first { it["claude"]?.tiers?.single()?.utilizationPercent == 100.0 }
                repo.quotas.first { it["claude"]?.tiers?.single()?.utilizationPercent == 0.0 }
            }
            val reads = claudeReads.toList()
            assertEquals(listOf("300", "30"), reads.map { it.second })
            assertTrue(reads[0].first < resetsAt)
            assertTrue(reads[1].first >= resetsAt)
        } finally {
            refresh.cancelAndJoin()
        }
    }

    @Test
    fun axisTopIsARoundNumber() {
        assertEquals(1.0, niceAxisMax(0.0), 0.0)
        assertEquals(1.0, niceAxisMax(0.7), 1e-9)
        assertEquals(2.0, niceAxisMax(1.3), 1e-9)
        assertEquals(2.5, niceAxisMax(2.2), 1e-9)
        assertEquals(5_000_000.0, niceAxisMax(3_400_000.0), 1e-3)
        assertEquals(10.0, niceAxisMax(7.5), 1e-9)
        assertEquals(0.05, niceAxisMax(0.031), 1e-12)
    }

    @Test
    fun barLabelsKeepTheLatestBucket() {
        assertEquals(setOf(29, 24, 19, 14, 9, 4), barLabelIndices(30, 6))
        assertEquals(setOf(6, 5, 4, 3, 2, 1, 0), barLabelIndices(7, 7))
        assertTrue(barLabelIndices(0, 6).isEmpty())
        val days = (0 until 30).map { LocalDate.of(2026, 10, 30).plusDays(it.toLong()).atStartOfDay() }
        val month = usageBarLabels(days, hourly = false, locale = Locale.US)
        assertEquals("11/28", month[29])
        assertEquals(6, month.size)
        val week = usageBarLabels(days.takeLast(7), hourly = false, locale = Locale.US)
        assertEquals(listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"), (0 until 7).map { week[it] })
        val hours = (0 until 24).map { LocalDate.of(2026, 11, 28).atStartOfDay().plusHours(it.toLong()) }
        assertEquals(mapOf(0 to "0", 6 to "6", 12 to "12", 18 to "18"), usageBarLabels(hours, hourly = true))
    }
}
