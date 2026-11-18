package io.github.tianli0.switchtether

import io.github.tianli0.switchtether.remote.session.AgentEventLog
import io.github.tianli0.switchtether.ui.components.SegmentPosition
import io.github.tianli0.switchtether.ui.sessions.TimelineRow
import io.github.tianli0.switchtether.ui.sessions.buildTimeline
import io.github.tianli0.switchtether.ui.sessions.pendingRequestKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineTest {
    private fun call(id: String, seq: Long, status: String = "completed", title: String = id) =
        event(id, seq, "tool_call", """{"callId":"c$id","name":"Bash","title":"$title","category":"shell","status":"$status"}""")

    private fun result(id: String, seq: Long, error: Boolean = false) =
        event("r$id", seq, "tool_result", """{"callId":"c$id","isError":$error,"text":"out"}""")

    private val log = AgentEventLog.of(
        listOf(
            event("u1", 1, "user_message", """{"text":"go"}"""),
            call("t1", 2), result("t1", 3),
            event("th", 4, "reasoning", """{"text":"hmm"}"""),
            call("t2", 5), result("t2", 6, error = true),
            call("t3", 7, status = "running", title = "npm test"),
            event("a1", 8, "assistant_message", """{"text":"done"}"""),
            call("t4", 9),
            event("ap", 10, "approval_request", """{"requestId":"q1","title":"rm","options":[],"resolved":false}"""),
        ),
    )

    @Test
    fun consecutiveStepsFoldIntoOneCollapsedBlock() {
        val rows = buildTimeline(log.renderItems(), emptySet())
        assertEquals(listOf("u1", "steps:t1", "a1", "t4", "ap"), rows.map { it.key })
        val steps = rows[1] as TimelineRow.Steps
        assertEquals(3, steps.steps)
        assertTrue(steps.running)
        assertEquals(1, steps.failed)
        assertEquals("npm test", steps.latestTitle)
        // A lone tool call stays a standalone card.
        assertEquals(SegmentPosition.Single, (rows[3] as TimelineRow.Event).segment)
    }

    @Test
    fun expandedBlockListsMembersAsCardSegments() {
        val rows = buildTimeline(log.renderItems(), setOf("t1"))
        assertEquals(listOf("u1", "steps:t1", "t1", "th", "t2", "t3", "a1", "t4", "ap"), rows.map { it.key })
        assertEquals(SegmentPosition.Middle, (rows[2] as TimelineRow.Event).segment)
        assertEquals(SegmentPosition.Last, (rows[5] as TimelineRow.Event).segment)
    }

    @Test
    fun unresolvedApprovalsArePending() {
        assertEquals(listOf("ap"), pendingRequestKeys(log.renderItems()))
    }

    @Test
    fun backgroundShellStaysRunningAfterItsStartedResult() {
        val bg = AgentEventLog.of(
            listOf(
                event("u1", 1, "user_message", """{"text":"build"}"""),
                event("b1", 2, "tool_call", """{"callId":"cb1","name":"Bash","title":"cargo build","category":"shell","status":"running","background":true,"taskId":"x"}"""),
                event("rb1", 3, "tool_result", """{"callId":"cb1","isError":false,"text":"Command running in background with ID: x."}"""),
                call("t9", 4), result("t9", 5),
            ),
        )
        val items = bg.renderItems()
        val bgItem = items.first { it.event.id == "b1" }
        assertEquals(io.github.tianli0.switchtether.ui.components.StepStatus.Running, io.github.tianli0.switchtether.ui.sessions.render.toolStatus(bgItem))
        val steps = buildTimeline(items, emptySet()).filterIsInstance<TimelineRow.Steps>().single()
        assertTrue("a running background shell keeps the step block running", steps.running)
    }
}
