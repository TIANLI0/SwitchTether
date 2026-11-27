package io.github.tianli0.switchtether

import io.github.tianli0.switchtether.remote.protocol.SessionSummary
import io.github.tianli0.switchtether.remote.session.BackgroundTaskNode
import io.github.tianli0.switchtether.remote.session.SubagentNode
import io.github.tianli0.switchtether.ui.common.Loadable
import io.github.tianli0.switchtether.ui.home.HomeState
import io.github.tianli0.switchtether.ui.home.SessionActivity
import io.github.tianli0.switchtether.ui.sessions.SessionScope
import io.github.tianli0.switchtether.ui.sessions.sessionRowOrder
import org.junit.Assert.assertEquals
import org.junit.Test

/** A live session whose turn ended but whose sub-agents / background tasks still run is not idle (§10.1 `activeTasks`). */
class ActiveTasksGroupingTest {
    private fun live(id: String, status: String, activeTasks: Int? = null, at: Long = 0) = SessionSummary(
        id = id, agent = "claude_code", liveness = "live", status = status, activeTasks = activeTasks, lastActiveAtMs = at,
    )

    @Test
    fun homeCountsIdleSessionsWithActiveTasksAsRunning() {
        val st = HomeState(
            sessions = Loadable.Ready(
                listOf(
                    live("busy", "busy", at = 5),
                    live("bg", "idle", activeTasks = 2, at = 4),
                    live("done", "completed", activeTasks = 0, at = 3),
                    live("old", "idle", at = 2),
                    live("wait", "waiting", at = 1),
                ),
            ),
        )
        assertEquals(listOf("busy", "bg"), st.running.map { it.id })
        assertEquals(listOf("done", "old"), st.idle.map { it.id })
        assertEquals(listOf("wait"), st.attention.map { it.id })
    }

    @Test
    fun homeFallsBackToItsAgentMapPollForOlderDesktops() {
        val sessions = Loadable.Ready(listOf(live("a", "idle", at = 2), live("b", "idle", at = 1)))
        val polled = HomeState(
            sessions = sessions,
            activity = mapOf(
                "a" to SessionActivity(tasks = listOf(BackgroundTaskNode(taskId = "t1", status = "running"))),
                "b" to SessionActivity(),
            ),
        )
        assertEquals(listOf("a"), polled.running.map { it.id })
        assertEquals(listOf("b"), polled.idle.map { it.id })
        // Nothing polled and no field: idle, as before.
        assertEquals(listOf("a", "b"), HomeState(sessions = sessions).idle.map { it.id })
        // The desktop's count wins over the poll.
        val reported = HomeState(
            sessions = Loadable.Ready(listOf(live("a", "idle", activeTasks = 0))),
            activity = mapOf("a" to SessionActivity(agents = listOf(SubagentNode(agentId = "x", status = "running")))),
        )
        assertEquals(listOf("a"), reported.idle.map { it.id })
    }

    @Test
    fun sessionsTabPartitionPutsActiveTasksUnderRunning() {
        val all = listOf(
            live("idle", "idle", activeTasks = 0),
            live("bg", "idle", activeTasks = 1),
            live("busy", "busy"),
            live("legacy", "idle"),
        )
        // Running group first (busy, bg), then idle (idle, legacy).
        val order = sessionRowOrder(all, SessionScope.LIVE, emptySet()).map { it.first }
        assertEquals(setOf("bg", "busy"), order.take(2).toSet())
        assertEquals(setOf("idle", "legacy"), order.drop(2).toSet())
    }

    @Test
    fun displayStatusAndBackgroundWork() {
        val s = live("s", "idle", activeTasks = 3)
        assertEquals("busy", s.displayStatus())
        assertEquals(3, s.backgroundWork())
        // An ended session has nothing running, whatever the count says.
        assertEquals(0, s.copy(liveness = "ended").backgroundWork())
        assertEquals("idle", live("t", "idle").displayStatus())
        assertEquals("busy", live("t", "idle").displayStatus(fallback = 1))
    }
}
