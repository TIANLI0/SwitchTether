package io.github.tianli0.switchtether

import io.github.tianli0.switchtether.data.ForegroundLedger
import io.github.tianli0.switchtether.remote.protocol.ForegroundInterval
import io.github.tianli0.switchtether.ui.settings.StatsPreset
import io.github.tianli0.switchtether.ui.settings.statsRange
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class RemoteStatsTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    private fun sec(y: Int, m: Int, d: Int, h: Int = 0) = ZonedDateTime.of(y, m, d, h, 0, 0, 0, zone).toEpochSecond()

    @Test
    fun rangesFollowTheDesktopCalendar() {
        // Saturday 2026-11-28 15:00 in Shanghai.
        val now = sec(2026, 11, 28, 15) * 1000
        assertEquals(sec(2026, 11, 23) to now / 1000, statsRange(StatsPreset.THIS_WEEK, now, zone))
        assertEquals(sec(2026, 11, 16) to sec(2026, 11, 23) - 1, statsRange(StatsPreset.LAST_WEEK, now, zone))
        assertEquals(sec(2026, 11, 1) to now / 1000, statsRange(StatsPreset.THIS_MONTH, now, zone))
        assertEquals(null to now / 1000, statsRange(StatsPreset.ALL, now, zone))
        // On a Monday the week starts that day.
        val monday = sec(2026, 11, 23, 9) * 1000
        assertEquals(sec(2026, 11, 23), statsRange(StatsPreset.THIS_WEEK, monday, zone).first)
    }

    @Test
    fun checkpointsMergeIntoOneInterval() {
        var l = ForegroundLedger().start(1_000)
        l = l.checkpoint(61_000).checkpoint(121_000).stop(130_000)
        assertEquals(listOf(ForegroundInterval(1_000, 130_000)), l.pending)
        assertNull(l.open)
        // A second start while in front keeps the first.
        assertEquals(5L, ForegroundLedger(open = 5).start(9).open)
    }

    @Test
    fun aReportKeepsItsIdUntilDone() {
        var l = ForegroundLedger().start(0).stop(10_000).start(20_000).stop(25_000)
        val (l1, r1) = l.nextReport(30_000) { "c1" }
        assertEquals("c1", r1!!.commandId)
        assertEquals(2, r1.intervals.size)
        assertEquals(0, l1.pending.size)
        // New time while the report is in flight waits; a retry sends the same report.
        l = l1.start(40_000).stop(41_000)
        val (l2, r2) = l.nextReport(50_000) { "c2" }
        assertSame(r1, r2)
        l = l2.done("other")
        assertEquals(r1, l.inFlight)
        l = l.done("c1")
        val (l3, r3) = l.nextReport(60_000) { "c3" }
        assertEquals("c3", r3!!.commandId)
        assertEquals(listOf(ForegroundInterval(40_000, 41_000)), r3.intervals)
        // Everything sent: the next round of the same flush has nothing to send.
        assertNull(l3.done("c3").nextReport(61_000) { "c4" }.second)
    }

    @Test
    fun oldTimeIsDropped() {
        val now = ForegroundLedger.MAX_AGE_MS + 100_000
        val l = ForegroundLedger(pending = listOf(ForegroundInterval(0, 50_000), ForegroundInterval(now - 10_000, now - 5_000)))
        val (_, r) = l.nextReport(now) { "c" }
        assertEquals(listOf(ForegroundInterval(now - 10_000, now - 5_000)), r!!.intervals)
    }
}
