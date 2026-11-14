package io.github.tianli0.switchtether.remote

import io.github.tianli0.switchtether.remote.protocol.DailyUsage
import io.github.tianli0.switchtether.remote.protocol.ProtocolJson
import io.github.tianli0.switchtether.remote.protocol.UsageBreakdownRow
import io.github.tianli0.switchtether.remote.protocol.UsageSummary
import io.github.tianli0.switchtether.remote.usage.MoneyDisplay
import io.github.tianli0.switchtether.remote.usage.UsageFormat
import io.github.tianli0.switchtether.remote.usage.UsageRangePreset
import io.github.tianli0.switchtether.remote.usage.isHourlyRange
import io.github.tianli0.switchtether.remote.usage.resolveUsageRange
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The phone must print the same strings and use the same ranges as the desktop Usage page. */
class UsageParityTest {
    @Test
    fun tokensLikeFormatTokensCompact() {
        assertEquals("0", UsageFormat.tokens(0))
        assertEquals("1,234", UsageFormat.tokens(1234))
        assertEquals("9,999", UsageFormat.tokens(9999))
        assertEquals("48.2K", UsageFormat.tokens(48_213))
        assertEquals("189K", UsageFormat.tokens(189_400))
        assertEquals("18.2M", UsageFormat.tokens(18_240_000))
        assertEquals("1M", UsageFormat.tokens(999_950)) // 1000K rolls over to the next unit
        assertEquals("2.5B", UsageFormat.tokens(2_500_000_000))
    }

    @Test
    fun moneyLikeFormatMoney() {
        assertEquals("$0.41", UsageFormat.money(0.4137))
        assertEquals("$1,234.50", UsageFormat.money(1234.5))
        assertEquals("$0.00", UsageFormat.money(0.0))
        assertEquals("$0.00", UsageFormat.money(0.00123)) // USD keeps the caller's digits (desktop: same)
        assertEquals("--", UsageFormat.money(null))
        assertEquals("$0.0081", UsageFormat.moneyCompact(0.0081))
        assertEquals("$0.012", UsageFormat.moneyCompact(0.0123))
        assertEquals("$1.25", UsageFormat.moneyCompact(1.25))
    }

    @Test
    fun ratesUseTheDesktopScales() {
        assertEquals("50.0%", UsageFormat.hitRate(0.5)) // cacheHitRate is 0–1
        assertEquals("100%", UsageFormat.hitRate(0.99951))
        assertEquals("0.5%", UsageFormat.successRate(0.5)) // successRate is 0–100: 0.5 means 0.5 %
        assertEquals("98.7%", UsageFormat.successRate(98.66))
        assertEquals("100%", UsageFormat.successRate(100.0))
        assertEquals("42", UsageFormat.tokensPerSecond(41.6))
        assertEquals("0.4", UsageFormat.tokensPerSecond(0.42))
        assertEquals("1.2s", UsageFormat.firstTokenSeconds(1234.0))
    }

    @Test
    fun rangesLikeResolveUsageRange() {
        val zone = ZoneId.of("Asia/Shanghai")
        val now = ZonedDateTime.of(2026, 11, 14, 15, 30, 0, 0, zone)
        val nowMs = now.toInstant().toEpochMilli()
        val (todayFrom, todayTo) = resolveUsageRange(UsageRangePreset.TODAY, nowMs, zone)
        assertEquals(ZonedDateTime.of(2026, 11, 14, 0, 0, 0, 0, zone).toEpochSecond(), todayFrom)
        assertEquals(nowMs / 1000, todayTo)
        assertEquals(ZonedDateTime.of(2026, 11, 8, 0, 0, 0, 0, zone).toEpochSecond(), resolveUsageRange(UsageRangePreset.DAYS_7, nowMs, zone).first)
        assertEquals(ZonedDateTime.of(2026, 10, 16, 0, 0, 0, 0, zone).toEpochSecond(), resolveUsageRange(UsageRangePreset.DAYS_30, nowMs, zone).first)
        assertEquals(nowMs / 1000 - 86_400, resolveUsageRange(UsageRangePreset.LAST_24H, nowMs, zone).first)
        assertEquals(0L, resolveUsageRange(UsageRangePreset.ALL, nowMs, zone).first)
        assertTrue(isHourlyRange(todayFrom, todayTo))
        assertFalse(isHourlyRange(resolveUsageRange(UsageRangePreset.DAYS_7, nowMs, zone).first, todayTo))
    }

    @Test
    fun trendTokensIncludeCacheLikeTheDesktopChart() {
        val days = DailyUsage.parseList(
            ProtocolJson.parseToJsonElement(
                """{"days":[{"date":"2026-11-14T00:00:00+08:00","requestCount":3,"totalCost":"0.123456","totalTokens":150,
                   "totalInputTokens":100,"totalOutputTokens":50,"totalCacheCreationTokens":1000,"totalCacheReadTokens":20000}]}""",
            ),
        )
        assertEquals(21_150L, days.single().tokens)
        assertEquals(0.123456, days.single().costUsd!!, 1e-9)
    }

    @Test
    fun summaryAndBreakdownFields() {
        val s = UsageSummary.parse(
            ProtocolJson.parseToJsonElement(
                """{"summary":{"totalRequests":12,"totalCost":"1.500000","totalInputTokens":10,"totalOutputTokens":20,
                   "totalCacheCreationTokens":30,"totalCacheReadTokens":40,"successRate":91.66,"realTotalTokens":100,"cacheHitRate":0.5},"byApp":[]}""",
            ),
        )
        assertEquals(100L, s.totalTokens)
        assertEquals(91.66, s.successRate!!, 1e-9)
        val p = UsageBreakdownRow.parseProviders(
            ProtocolJson.parseToJsonElement(
                """{"providers":[{"providerId":"p1","appType":"claude","providerName":"Anthropic","requestCount":5,"totalTokens":900,
                   "totalCost":"0.5","successRate":100,"speedOutputTokens":400,"speedGenerationMs":8000,"estFirstTokenMedianMs":1500}]}""",
            ),
        ).single()
        assertEquals("Anthropic", p.label)
        assertEquals("claude", p.app)
        assertEquals(50.0, p.tokensPerSecond!!, 1e-9)
        assertFalse(p.speedEstimated)
        assertEquals(1500.0, p.firstTokenMs!!, 1e-9)
        assertTrue(p.firstTokenEstimated)
    }

    // Cases copied from tests/components/usageFormat.test.ts ("usage money formatting", "display currency").
    @Test
    fun moneyMatchesTheDesktopTests() {
        assertEquals("$1.23", UsageFormat.money(1.23456, digits = 2))
        assertEquals("$0.000123", UsageFormat.money(0.000123, digits = 6))
        assertEquals("--", UsageFormat.money(null, digits = 4))
        assertEquals("$0.5", UsageFormat.moneyTick(0.5))
        assertEquals("$0", UsageFormat.moneyTick(null))
        val krw = MoneyDisplay.resolve(json("""{"currency":"KRW","exchangeRates":{"KRW":1400}}"""))
        assertEquals(MoneyDisplay("KRW", 1400.0), krw)
        assertEquals("₩2,800", UsageFormat.money(2.0, krw, 2))
        assertEquals("₩1.68", UsageFormat.money(0.0012, krw, 4))
        assertEquals("₩0.0014", UsageFormat.money(0.000001, krw, 4))
        assertEquals("₩700", UsageFormat.moneyShort(0.5, krw))
        assertEquals("₩1.40", UsageFormat.moneyCompact(0.001, krw))
        assertEquals("₩20,000", UsageFormat.moneyTick(20000.0, krw))
    }

    @Test
    fun displayCurrencyResolvesLikeTheDesktop() {
        assertEquals(MoneyDisplay("KRW", 1325.0), MoneyDisplay.resolve(json("""{"currency":"KRW"}""")))
        assertEquals(MoneyDisplay("KRW", 1325.0), MoneyDisplay.resolve(json("""{"currency":"KRW","exchangeRates":{"KRW":0}}""")))
        assertEquals(MoneyDisplay.USD, MoneyDisplay.resolve(null))
        assertEquals(MoneyDisplay.USD, MoneyDisplay.resolve(json("""{"currency":"GBP"}""")))
        assertEquals(MoneyDisplay.USD, MoneyDisplay.resolve(json("""{"currency":"USD","exchangeRates":{"CNY":7}}""")))
        // "auto": the fetched rate wins, the manual one is the fallback; "manual" ignores fetched rates.
        val both = """"exchangeRates":{"CNY":7.1},"autoExchangeRates":{"CNY":7.2}"""
        assertEquals(MoneyDisplay("CNY", 7.2), MoneyDisplay.resolve(json("""{"currency":"CNY","rateSource":"auto",$both}""")))
        assertEquals(MoneyDisplay("CNY", 7.1), MoneyDisplay.resolve(json("""{"currency":"CNY",$both}""")))
        assertEquals(MoneyDisplay("CNY", 7.1), MoneyDisplay.resolve(json("""{"currency":"CNY","rateSource":"auto","exchangeRates":{"CNY":7.1}}""")))
        val cny = MoneyDisplay("CNY", 7.0)
        assertEquals("¥2.87", UsageFormat.money(0.41, cny))
        assertEquals("¥0.0034", UsageFormat.money(0.00049, cny, 4)) // would round to ¥0.00
        assertEquals("¥8,750.00", UsageFormat.money(1250.0, cny))
        assertEquals("¥1.25", UsageFormat.moneyCompact(0.17857, cny))
        assertEquals("€1,087.50", UsageFormat.money(1250.0, MoneyDisplay("EUR", 0.87)))
        assertEquals("¥198", UsageFormat.money(1.25, MoneyDisplay("JPY", 158.0))) // yen: 2 decimals fewer
    }

    private fun json(s: String) = kotlinx.serialization.json.Json.parseToJsonElement(s)
}
