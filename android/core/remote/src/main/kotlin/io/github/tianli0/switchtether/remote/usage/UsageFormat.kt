package io.github.tianli0.switchtether.remote.usage

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs

/**
 * Usage numbers formatted exactly like the desktop Usage page (src/components/usage/format.ts),
 * so the phone and the computer show the same strings for the same data.
 */
object UsageFormat {
    private val grouped0 = DecimalFormat("#,##0", DecimalFormatSymbols(Locale.US))

    private fun precision(v: Double, digits: Int): String =
        BigDecimal(v).round(MathContext(digits, RoundingMode.HALF_UP)).stripTrailingZeros().toPlainString()

    /** `formatTokensCompact`: below 10 000 the grouped integer, above 3 significant digits + K/M/B. */
    fun tokens(n: Long): String {
        val a = abs(n.toDouble())
        if (a < 10_000) return grouped0.format(n)
        val units = listOf(1e9 to "B", 1e6 to "M", 1e3 to "K")
        for ((i, u) in units.withIndex()) {
            val (size, suffix) = u
            if (a >= size) {
                val scaled = precision(n / size, 3)
                if (abs(scaled.toDouble()) >= 1000 && i > 0) {
                    val (bigger, biggerSuffix) = units[i - 1]
                    return precision(n / bigger, 3) + biggerSuffix
                }
                return scaled + suffix
            }
        }
        return grouped0.format(n)
    }

    /** JS `x.toFixed(n)` (round half up on the exact binary value). */
    private fun toFixed(v: Double, n: Int): String = BigDecimal(v).setScale(n, RoundingMode.HALF_UP).toPlainString()

    /** JS `Number(s)` printed back: trailing zeros dropped (`"1.40"` → `1.4`, `"0.0000"` → `0`). */
    private fun jsNumber(s: String): String = BigDecimal(s).let { if (it.signum() == 0) "0" else it.stripTrailingZeros().toPlainString() }

    /** `groupThousands`: commas in the integer part only. */
    private fun groupThousands(fixed: String): String {
        val neg = fixed.startsWith("-")
        val body = fixed.removePrefix("-")
        val whole = body.substringBefore('.')
        val frac = body.substringAfter('.', missingDelimiterValue = "")
        val grouped = whole.reversed().chunked(3).joinToString(",").reversed()
        return (if (neg) "-" else "") + grouped + (if (body.contains('.')) ".$frac" else "")
    }

    /** `shownDigits`: converted amounts are estimates, so at most 2 decimals (yen/won 2 fewer). */
    private fun shownDigits(d: MoneyDisplay, digits: Int): Int =
        if (!d.converted) digits else maxOf(0, minOf(digits - (MoneyDisplay.FEWER_DIGITS[d.currency] ?: 0), 2))

    /**
     * `formatMoney(usd, digits, display)`: fixed decimals, grouped; a small non-zero amount that
     * would round to 0 after conversion keeps 2 significant digits (¥0.0034), so it never looks free.
     */
    fun money(usd: Double?, display: MoneyDisplay = MoneyDisplay.USD, digits: Int = 2, fallback: String = "--"): String {
        if (usd == null || !usd.isFinite()) return fallback
        val value = usd * display.rate
        val shown = shownDigits(display, digits)
        val fixed = toFixed(value, shown)
        if (shown < digits && value != 0.0 && BigDecimal(fixed).signum() == 0) return display.symbol + precision(value, 2)
        return display.symbol + groupThousands(fixed)
    }

    /** `formatMoneyCompact`: digits by magnitude, trailing zeros dropped: `$0.0081` / `$0.012` / `$1.25`. */
    fun moneyCompact(usd: Double?, display: MoneyDisplay = MoneyDisplay.USD): String {
        if (usd == null || !usd.isFinite()) return "—"
        val value = usd * display.rate
        return display.symbol + when {
            value < 0.01 -> jsNumber(toFixed(value, 4))
            value < 1 -> jsNumber(toFixed(value, 3))
            else -> groupThousands(toFixed(value, 2))
        }
    }

    /** `formatMoneyShort`: narrow places; from 100 (in the display currency) no decimals. */
    fun moneyShort(usd: Double?, display: MoneyDisplay = MoneyDisplay.USD): String {
        if (usd == null || !usd.isFinite()) return "--"
        return money(usd, display, if (kotlin.math.abs(usd * display.rate) >= 100) 0 else 2)
    }

    /** `formatMoneyTick`: [value] is already in the display currency (charts convert first). */
    fun moneyTick(value: Double?, display: MoneyDisplay = MoneyDisplay.USD): String {
        val v = value?.takeIf { it.isFinite() } ?: 0.0
        val plain = if (v == Math.rint(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString() else jsNumber(v.toString())
        return display.symbol + groupThousands(plain)
    }

    /** Cache hit rate, given as 0–1: one decimal, "100%" from 99.95 %. */
    fun hitRate(fraction: Double?): String {
        if (fraction == null) return "--"
        val pct = fraction * 100
        if (pct >= 99.95) return "100%"
        return String.format(Locale.US, "%.1f%%", pct)
    }

    /** Success rate, given as 0–100 by the desktop. */
    fun successRate(percent: Double?): String {
        if (percent == null) return "--"
        if (percent >= 99.95) return "100%"
        return String.format(Locale.US, "%.1f%%", percent)
    }

    /** tokens/s: integer from 1, one decimal below. */
    fun tokensPerSecond(tps: Double?): String? = tps?.let { if (it >= 1) Math.round(it).toString() else String.format(Locale.US, "%.1f", it) }

    /** First-token latency: milliseconds → seconds, one decimal. */
    fun firstTokenSeconds(ms: Double?): String? = ms?.takeIf { it >= 0 }?.let { String.format(Locale.US, "%.1fs", it / 1000) }
}

/** The desktop's range presets (src/lib/usageRange.ts) minus "custom". */
enum class UsageRangePreset { TODAY, LAST_24H, DAYS_7, DAYS_14, DAYS_30, ALL }

/**
 * `resolveUsageRange`: [from, to] in epoch seconds, both inclusive, computed in [zone] (the
 * desktop computes in its own local time zone; pass the same zone for identical numbers).
 */
/**
 * [zone] is the desktop's zone (GET /v1/status `timeZone`), so "今天" is the computer's day.
 * [firstDateMs]: the desktop's first usage day (`/v1/usage/first-date`) starts the "全部" range.
 */
fun resolveUsageRange(preset: UsageRangePreset, nowMs: Long, zone: ZoneId, firstDateMs: Long? = null): Pair<Long, Long> {
    val end = nowMs / 1000
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    fun daysBack(n: Long): Long = today.minusDays(n - 1).atStartOfDay(zone).toEpochSecond()
    val start = when (preset) {
        UsageRangePreset.TODAY -> today.atStartOfDay(zone).toEpochSecond()
        UsageRangePreset.LAST_24H -> end - 24 * 60 * 60
        UsageRangePreset.DAYS_7 -> daysBack(7)
        UsageRangePreset.DAYS_14 -> daysBack(14)
        UsageRangePreset.DAYS_30 -> daysBack(30)
        UsageRangePreset.ALL -> firstDateMs?.let { it / 1000 } ?: 0L
    }
    return start to end
}

/** Trend buckets are hourly when the range is at most 24 h, else days (desktop usage_stats.rs). */
fun isHourlyRange(fromSec: Long, toSec: Long): Boolean = toSec - fromSec <= 24 * 60 * 60
