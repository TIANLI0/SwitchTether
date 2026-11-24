package io.github.tianli0.switchtether.ui.manage

import io.github.tianli0.switchtether.remote.protocol.DailyUsage
import io.github.tianli0.switchtether.ui.chart.HeatCell
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** One square of the usage heatmap: its grid cell, the bucket's start and its usage (zeros if none). */
data class UsageHeatBucket(val cell: HeatCell, val start: LocalDateTime, val usage: DailyUsage)

data class UsageHeatGrid(
    val columns: Int,
    val rows: Int,
    val buckets: List<UsageHeatBucket>,
    val rowLabels: List<String?>,
    val columnLabels: Map<Int, String>,
)

/** Longest daily grid drawn in full; a longer range ("全部" on an old desktop) starts at the first data day. */
private const val MAX_DAILY_SPAN_DAYS = 371L

private fun bucketStart(date: String, hourly: Boolean): LocalDateTime? = runCatching {
    val t = OffsetDateTime.parse(date).toLocalDateTime()
    if (hourly) t.truncatedTo(ChronoUnit.HOURS) else t.toLocalDate().atStartOfDay()
}.recoverCatching { LocalDate.parse(date.take(10)).atStartOfDay() }.getOrNull()

private fun emptyUsage(start: LocalDateTime) = DailyUsage(date = start.toString(), requests = 0, costUsd = null, tokens = 0)

/**
 * Lays the trend buckets out like GitHub's contribution graph, in the desktop's zone:
 * daily ranges are weeks (columns, Monday first) × weekdays (rows); hourly ranges are
 * days (rows) × 24 hours (columns). Buckets the server did not return are zero.
 */
fun buildUsageHeatGrid(
    days: List<DailyUsage>,
    value: (DailyUsage) -> Double,
    fromSec: Long,
    toSec: Long,
    zone: ZoneId,
    hourly: Boolean,
    locale: Locale = Locale.getDefault(),
): UsageHeatGrid {
    val byStart = days.mapNotNull { d -> bucketStart(d.date, hourly)?.let { it to d } }.toMap()
    val from = Instant.ofEpochSecond(fromSec).atZone(zone).toLocalDateTime()
    val to = Instant.ofEpochSecond(toSec).atZone(zone).toLocalDateTime()
    fun bucket(column: Int, row: Int, start: LocalDateTime): UsageHeatBucket {
        val u = byStart[start] ?: emptyUsage(start)
        return UsageHeatBucket(HeatCell(column, row, value(u)), start, u)
    }
    if (hourly) {
        val firstHour = from.truncatedTo(ChronoUnit.HOURS)
        val lastHour = to.truncatedTo(ChronoUnit.HOURS)
        val firstDay = firstHour.toLocalDate()
        val rows = (ChronoUnit.DAYS.between(firstDay, lastHour.toLocalDate()) + 1).toInt().coerceAtLeast(1)
        val buckets = generateSequence(firstHour) { it.plusHours(1) }.takeWhile { !it.isAfter(lastHour) }
            .map { h -> bucket(h.hour, ChronoUnit.DAYS.between(firstDay, h.toLocalDate()).toInt(), h) }.toList()
        val rowLabels = (0 until rows).map { r -> firstDay.plusDays(r.toLong()).let { "%02d-%02d".format(it.monthValue, it.dayOfMonth) } }
        return UsageHeatGrid(24, rows, buckets, rowLabels, listOf(0, 6, 12, 18).associateWith { it.toString() })
    }
    val lastDay = to.toLocalDate()
    var firstDay = from.toLocalDate()
    if (ChronoUnit.DAYS.between(firstDay, lastDay) > MAX_DAILY_SPAN_DAYS) {
        val firstData = byStart.keys.minOrNull()?.toLocalDate()
        firstDay = maxOf(firstDay, firstData ?: lastDay.minusDays(MAX_DAILY_SPAN_DAYS))
    }
    val gridStart = firstDay.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val buckets = generateSequence(firstDay) { it.plusDays(1) }.takeWhile { !it.isAfter(lastDay) }
        .map { d -> bucket((ChronoUnit.DAYS.between(gridStart, d) / 7).toInt(), d.dayOfWeek.value - 1, d.atStartOfDay()) }.toList()
    val columns = (buckets.maxOfOrNull { it.cell.column } ?: 0) + 1
    // A month name over the first week it appears in, spaced so names never collide.
    val columnLabels = linkedMapOf<Int, String>()
    var lastLabeled = -10
    var lastMonth = -1
    for (b in buckets) {
        val d = b.start.toLocalDate()
        if (d.monthValue != lastMonth && b.cell.column - lastLabeled >= 3) {
            columnLabels[b.cell.column] = d.month.getDisplayName(TextStyle.SHORT, locale)
            lastLabeled = b.cell.column
        }
        lastMonth = d.monthValue
    }
    val rowLabels = (0 until 7).map { r -> if (r % 2 == 0 && r < 6) DayOfWeek.of(r + 1).getDisplayName(TextStyle.SHORT, locale) else null }
    return UsageHeatGrid(columns, 7, buckets, rowLabels, columnLabels)
}

/** The selected square's heading: "2026-11-24 周二" for days, "11-24 14:00–15:00" for hours. */
fun usageBucketTitle(start: LocalDateTime, hourly: Boolean, locale: Locale = Locale.getDefault()): String =
    if (hourly) {
        "%02d-%02d %02d:00–%02d:00".format(start.monthValue, start.dayOfMonth, start.hour, start.plusHours(1).hour)
    } else {
        "%04d-%02d-%02d %s".format(start.year, start.monthValue, start.dayOfMonth, start.dayOfWeek.getDisplayName(TextStyle.SHORT, locale))
    }
