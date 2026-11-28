package io.github.tianli0.switchtether

import io.github.tianli0.switchtether.remote.session.AgentEventLog
import io.github.tianli0.coui17.patterns.SegmentPosition
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

    @Test fun aFileStepIsTitledByItsFileName() {
        val items = AgentEventLog.of(
            listOf(event("f1", 1, "tool_call", """{"callId":"cf1","name":"Read","title":"/repo/src/main.rs","category":"read","status":"completed","input":{"file_path":"/repo/src/main.rs"}}""")),
        ).renderItems(emptySet())
        assertEquals("main.rs", io.github.tianli0.switchtether.ui.sessions.stepTitle(items.single()))
    }

    @Test fun singleSubagentAndFileEditDoNotShowWrapperToolCalls() {
        val log = AgentEventLog.of(
            listOf(
                event("u1", 1, "user_message", """{"text":"fix it"}"""),
                event("tc1", 2, "tool_call", """{"callId":"k1","name":"invoke_subagent","category":"task","status":"completed"}"""),
                event("r1", 3, "tool_result", """{"callId":"k1","isError":false,"text":"started"}"""),
                event("sa:k1", 4, "subagent", """{"callId":"k1","agentId":"a1","description":"Research","status":"completed"}""", parent = "k1"),
                event("tc2", 5, "tool_call", """{"callId":"k2","name":"edit_file","category":"edit","status":"completed"}"""),
                event("r2", 6, "tool_result", """{"callId":"k2","isError":false,"text":"ok"}"""),
                event("fc:k2", 7, "file_change", """{"callId":"k2","path":"src/a.kt","change":"modify","diff":"+1"}"""),
            ),
        )
        val rows = buildTimeline(log.renderItems(), emptySet())
        // Single subagent stays a standalone card (not folded as "2 steps"), and edit_file is replaced by file_change.
        assertEquals(listOf("u1", "sa:k1", "fc:k2"), rows.map { it.key })
    }
}
