package io.github.tianli0.switchtether

import io.github.tianli0.switchtether.ui.sessions.sessionUsageBreakdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Per-turn usage for the cost sheet (docs/ANDROID_UI.md §26.1). */
class SessionUsageBreakdownTest {
    @Test
    fun costedTurnsTakeTheGrowthOfTheRunningTotal() {
        val events = listOf(
            event("u1", 1, "user_message", """{"text":"\n  修复登录测试\n第二行"}"""),
            event("m1:usage", 2, "usage", """{"inputTokens":900,"outputTokens":90}"""), // per message, ignored
            event("result:1", 3, "usage", """{"inputTokens":10,"outputTokens":100,"cacheReadTokens":1000,"cacheWriteTokens":50,"costUsd":0.25,"durationMs":4000}"""),
            event("u2", 4, "user_message", """{"text":"再跑一遍"}"""),
            event("result:2", 5, "usage", """{"inputTokens":20,"outputTokens":300,"cacheReadTokens":2000,"costUsd":0.75}"""),
            event("result:2", 5, "usage", """{"inputTokens":20,"outputTokens":300,"cacheReadTokens":2000,"costUsd":0.75}"""), // again
            event("u3", 6, "user_message", """{"text":"/clear 之后"}"""),
            // A lower total: a new run (e.g. after /clear) whose turn cost is its own total.
            event("result:3", 7, "usage", """{"inputTokens":5,"outputTokens":5,"costUsd":0.1}"""),
            event("u4", 8, "user_message", """{"text":"没有用量的一轮"}"""),
        )
        val b = sessionUsageBreakdown(events, "claude_code")!!
        assertEquals(3, b.turns.size)
        val (t1, t2, t3) = b.turns
        assertEquals("修复登录测试", t1.prompt)
        assertEquals(0.25, t1.costUsd!!, 1e-9)
        assertEquals(4000L, t1.durationMs)
        assertEquals(1160L, t1.totalTokens)
        assertEquals(0.5, t2.costUsd!!, 1e-9)
        assertEquals("再跑一遍", t2.prompt)
        assertEquals(0.1, t3.costUsd!!, 1e-9)
        assertEquals(listOf(1, 2, 3), b.turns.map { it.index })
        // Total = finished run (0.75) + current run (0.1), as sessionCost computes it.
        assertEquals(0.85, b.costUsd!!, 1e-9)
        assertEquals(35L, b.inputTokens)
        assertEquals(405L, b.outputTokens)
        assertEquals(3000L, b.cacheReadTokens)
        // Context of the last request: its prompt tokens.
        assertEquals(5L, b.contextTokens)
        assertEquals(3000.0 / (35 + 3000 + 50), b.cacheHitRate!!, 1e-9)
    }

    @Test
    fun transcriptsWithoutCostCountEveryUsageEventPerTurn() {
        val events = listOf(
            event("u1", 1, "user_message", """{"text":"a"}"""),
            event("m1:usage", 2, "usage", """{"inputTokens":100,"outputTokens":10,"cacheReadTokens":5000}"""),
            event("m2:usage", 3, "usage", """{"inputTokens":50,"outputTokens":20}"""),
            event("u2", 4, "user_message", """{"text":"b"}"""),
            event("m3:usage", 5, "usage", """{"inputTokens":7,"outputTokens":3,"cacheReadTokens":6000,"cacheWriteTokens":100}"""),
        )
        val b = sessionUsageBreakdown(events, "claude_code")!!
        assertNull(b.costUsd)
        assertEquals(2, b.turns.size)
        assertNull(b.turns[0].costUsd)
        assertEquals(5180L, b.turns[0].totalTokens)
        assertEquals(6107L, b.contextTokens)
    }

    @Test
    fun codexCachedTokensAreInsideTheInput() {
        val events = listOf(
            event("u1", 1, "user_message", """{"text":"go"}"""),
            event("cx-usage-t1", 2, "usage", """{"inputTokens":12000,"outputTokens":300,"cacheReadTokens":10000}"""),
        )
        val t = sessionUsageBreakdown(events, "codex")!!.turns.single()
        assertEquals(2000L, t.inputTokens)
        assertEquals(10000L, t.cacheReadTokens)
        assertEquals(12000L, sessionUsageBreakdown(events, "codex")!!.contextTokens)
    }

    @Test
    fun nothingWithoutUsage() {
        assertNull(sessionUsageBreakdown(emptyList()))
        assertNull(sessionUsageBreakdown(listOf(event("u1", 1, "user_message", """{"text":"x"}"""))))
        // Usage before the first message still makes a turn (history loaded from the middle).
        val b = sessionUsageBreakdown(listOf(event("m1:usage", 1, "usage", """{"inputTokens":1,"outputTokens":1}""")))!!
        assertNull(b.turns.single().prompt)
    }
}
