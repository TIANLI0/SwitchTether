package io.github.tianli0.switchtether

import io.github.tianli0.switchtether.remote.protocol.DailyUsage
import io.github.tianli0.switchtether.ui.chart.heatLevels
import io.github.tianli0.switchtether.ui.manage.buildUsageHeatGrid
import io.github.tianli0.switchtether.ui.manage.usageBucketTitle
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class UsageHeatmapTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun sec(y: Int, m: Int, d: Int, h: Int = 0, min: Int = 0) = ZonedDateTime.of(y, m, d, h, min, 0, 0, zone).toEpochSecond()
    private fun day(date: String, tokens: Long) = DailyUsage(date = date, requests = 1, costUsd = 0.5, tokens = tokens)

    @Test
    fun dailyRangeIsWeeksByWeekdaysWithMissingDaysAsZero() {
        // Wed 2026-11-11 .. Tue 2026-11-24 (14 days).
        val days = listOf(day("2026-11-11T00:00:00+08:00", 100), day("2026-11-24T00:00:00+08:00", 300))
        val g = buildUsageHeatGrid(days, { it.tokens.toDouble() }, sec(2026, 11, 11), sec(2026, 11, 24, 15), zone, hourly = false, locale = Locale.ENGLISH)
        assertEquals(7, g.rows)
        assertEquals(3, g.columns) // weeks starting Mon 11-09, 11-16, 11-23
        assertEquals(14, g.buckets.size)
        val first = g.buckets.first()
        assertEquals(0, first.cell.column)
        assertEquals(2, first.cell.row) // Wednesday
        assertEquals(100.0, first.cell.value, 0.0)
        val last = g.buckets.last()
        assertEquals(2, last.cell.column)
        assertEquals(1, last.cell.row) // Tuesday
        assertEquals(300.0, last.cell.value, 0.0)
        assertEquals(0.0, g.buckets[1].cell.value, 0.0)
        assertEquals("Nov", g.columnLabels[0])
        assertEquals(listOf("Mon", null, "Wed", null, "Fri", null, null), g.rowLabels)
    }

    @Test
    fun hourlyRangeIsDaysByHours() {
        val days = listOf(day("2026-11-23T15:00:00+08:00", 7), day("2026-11-24T09:00:00+08:00", 9))
        val g = buildUsageHeatGrid(days, { it.tokens.toDouble() }, sec(2026, 11, 23, 14, 30), sec(2026, 11, 24, 14, 30), zone, hourly = true)
        assertEquals(24, g.columns)
        assertEquals(2, g.rows)
        assertEquals(25, g.buckets.size) // 14:00 yesterday .. 14:00 today
        val b15 = g.buckets.single { it.start == LocalDateTime.of(2026, 11, 23, 15, 0) }
        assertEquals(15, b15.cell.column)
        assertEquals(0, b15.cell.row)
        assertEquals(7.0, b15.cell.value, 0.0)
        val b9 = g.buckets.single { it.start == LocalDateTime.of(2026, 11, 24, 9, 0) }
        assertEquals(1, b9.cell.row)
        assertEquals(9.0, b9.cell.value, 0.0)
        assertEquals(listOf("11-23", "11-24"), g.rowLabels)
    }

    @Test
    fun allTimeOnAnOldDesktopStartsAtTheFirstDataDay() {
        val days = listOf(day("2026-11-20T00:00:00+08:00", 5))
        val g = buildUsageHeatGrid(days, { it.tokens.toDouble() }, 0, sec(2026, 11, 24, 12), zone, hourly = false)
        assertEquals(5, g.buckets.size)
    }

    @Test
    fun levelsSplitNonZeroValuesAtQuartiles() {
        assertEquals(listOf(0, 1, 2, 3, 4, 0), heatLevels(listOf(0.0, 1.0, 2.0, 3.0, 1000.0, 0.0)))
        assertEquals(listOf(0, 0), heatLevels(listOf(0.0, 0.0)))
    }

    @Test
    fun bucketTitles() {
        assertEquals("2026-11-24 Tue", usageBucketTitle(LocalDateTime.of(2026, 11, 24, 0, 0), hourly = false, locale = Locale.ENGLISH))
        assertEquals("11-24 23:00–00:00", usageBucketTitle(LocalDateTime.of(2026, 11, 24, 23, 0), hourly = true))
    }
}
