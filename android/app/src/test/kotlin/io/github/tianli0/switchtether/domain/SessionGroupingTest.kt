package io.github.tianli0.switchtether.domain

import io.github.tianli0.switchtether.remote.protocol.SessionSummary
import org.junit.Test
import org.junit.Assert.assertEquals

class SessionGroupingTest {
    private fun s(id: String, title: String?, at: Long, live: Boolean = false, native: String = id, cwd: String = "/p/app", agent: String = "claude_code") =
        SessionSummary(id = id, agent = agent, nativeId = native, title = title, cwd = cwd, project = "app", liveness = if (live) "live" else "ended", lastActiveAtMs = at)

    @Test fun resumedSessionsWithSameTitleCollapse() {
        val g = SessionGrouping.group(listOf(s("a", "Fix login bug", 1), s("b", "Fix login bug", 5), s("c", "Other work", 3)))
        assertEquals(2, g.size)
        assertEquals("b", g[0].primary.id)
        assertEquals(listOf("a"), g[0].others.map { it.id })
    }

    @Test fun liveBeatsNewer() {
        val g = SessionGrouping.group(listOf(s("a", "Fix login bug", 1, live = true), s("b", "Fix login bug", 9)))
        assertEquals("a", g.single().primary.id)
    }

    @Test fun sameNativeIdCollapsesEvenWithoutTitle() {
        val g = SessionGrouping.group(listOf(s("managed-1", null, 5, native = "n1"), s("claude:n1", null, 2, native = "n1")))
        assertEquals(1, g.size)
    }

    @Test fun differentFolderOrAgentStaySeparate() {
        val g = SessionGrouping.group(listOf(s("a", "Fix login bug", 1), s("b", "Fix login bug", 2, cwd = "/p/other"), s("c", "Fix login bug", 3, agent = "codex")))
        assertEquals(3, g.size)
    }

    @Test fun genericTitlesNeverMerge() {
        assertEquals(2, SessionGrouping.group(listOf(s("a", "Untitled", 1), s("b", "Untitled", 2))).size)
    }
}
