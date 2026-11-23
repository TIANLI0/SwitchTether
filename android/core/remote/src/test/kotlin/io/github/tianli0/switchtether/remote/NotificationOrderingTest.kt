package io.github.tianli0.switchtether.remote

import io.github.tianli0.switchtether.remote.live.LivePhase
import io.github.tianli0.switchtether.remote.live.LiveReducer
import io.github.tianli0.switchtether.remote.live.LiveText
import io.github.tianli0.switchtether.remote.live.LiveUpdateRenderer
import io.github.tianli0.switchtether.remote.live.PinnedSessionState
import io.github.tianli0.switchtether.remote.notify.NotificationEngine
import io.github.tianli0.switchtether.remote.notify.NotifyPrefs
import io.github.tianli0.switchtether.remote.notify.NotifyType
import io.github.tianli0.switchtether.remote.protocol.AgentEvent
import io.github.tianli0.switchtether.remote.protocol.ProtocolJson
import io.github.tianli0.switchtether.remote.protocol.SessionSummary
import java.time.Instant
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "The order of live notifications is wrong": inputs of one session that arrive out of order
 * (status from the registry vs. steps tailed from the transcript, REST reads racing the stream)
 * must not move a notification backwards, and the shade order must follow when things happened.
 */
class NotificationOrderingTest {
    private val sid = "claude:obs"

    /** Computer time of the turn's last transcript write (summary `lastActiveAtMs`). */
    private val finishedOnComputer = 1_731_500_060_000L

    private fun iso(ms: Long) = Instant.ofEpochMilli(ms).toString()

    private fun ev(kind: String, data: String, id: String = kind, ts: Long? = null, seq: Long = 1) =
        AgentEvent(id, seq, sid, ts = ts?.let(::iso) ?: "", kind = kind, data = ProtocolJson.parseToJsonElement(data).jsonObject)

    private val summary = SessionSummary(id = sid, agent = "claude_code", title = "Fix bug", liveness = "live", status = "busy", control = "observe")

    private fun running(now: Long = 1_000): PinnedSessionState {
        val s = LiveReducer.onUpsert(PinnedSessionState(sid, "Fix bug", "claude_code", phase = LivePhase.DONE), summary, now)
        assertEquals(LivePhase.RUNNING, s.phase)
        return s
    }

    private fun finished(now: Long = 10_000): PinnedSessionState {
        var s = running()
        s = LiveReducer.onEvent(s, ev("tool_call", """{"status":"running","name":"Bash","title":"npm test"}""", id = "t1", ts = finishedOnComputer - 5_000), 5_000)
        s = LiveReducer.onUpsert(s, summary.copy(status = "idle", lastActiveAtMs = finishedOnComputer), now)
        assertEquals(LivePhase.DONE, s.phase)
        return s
    }

    // ---- Live Update card: one session over time ----

    @Test
    fun aStepTailedAfterTheRegistrySaidIdleDoesNotRestartTheTurn() {
        // Bug: the card went DONE → RUNNING ("thinking…") and stayed there, because the observed
        // session's registry stays idle and no further upsert ever closed the "new" turn.
        val done = finished(now = 10_000)
        val late = LiveReducer.onEvent(done, ev("tool_call", """{"status":"running","name":"Read"}""", id = "t2", ts = finishedOnComputer + 300), 10_500)
        assertEquals(LivePhase.DONE, late.phase)
        assertEquals(1, late.steps) // the finished turn's count is kept
        val reasoning = LiveReducer.onEvent(done, ev("reasoning", """{"text":"…"}""", ts = finishedOnComputer + 300), 10_600)
        assertEquals(LivePhase.DONE, reasoning.phase)
    }

    @Test
    fun aStepOlderThanTheOutcomeIsStaleEvenLongAfter() {
        val done = finished(now = 10_000)
        val now = 10_000 + LiveReducer.STRAGGLER_GRACE_MS + 60_000
        val old = LiveReducer.onEvent(done, ev("tool_call", """{"status":"running","name":"Bash"}""", id = "t-old", ts = finishedOnComputer - 1), now)
        assertEquals(LivePhase.DONE, old.phase)
    }

    @Test
    fun theNextTurnStillStarts() {
        val done = finished(now = 10_000)
        // A new prompt starts the next turn at once…
        val prompt = LiveReducer.onEvent(done, ev("user_message", """{"text":"next"}""", ts = finishedOnComputer + 2_000), 11_000)
        assertEquals(LivePhase.RUNNING, prompt.phase)
        assertEquals(0, prompt.steps)
        // …so does the registry turning busy, and a newer step after the grace period.
        assertEquals(LivePhase.RUNNING, LiveReducer.onUpsert(done, summary.copy(status = "busy"), 11_000).phase)
        val laterStep = LiveReducer.onEvent(done, ev("tool_call", """{"status":"running","name":"Bash"}""", id = "t3", ts = finishedOnComputer + 30_000), 10_000 + LiveReducer.STRAGGLER_GRACE_MS + 1)
        assertEquals(LivePhase.RUNNING, laterStep.phase)
    }

    @Test
    fun anOldPromptDoesNotReopenAFinishedTurn() {
        val done = finished(now = 10_000)
        val stale = LiveReducer.onEvent(done, ev("user_message", """{"text":"old"}""", ts = finishedOnComputer - 10_000), 11_000)
        assertEquals(LivePhase.DONE, stale.phase)
    }

    @Test
    fun aRequestReadBeforeTheOutcomeDoesNotReopenTheCard() {
        // Bug: refresh() (REST) returned a pending request that was answered meanwhile; applied after
        // the stream's "finished", the card jumped back to "waiting for your approval".
        val done = finished(now = 10_000)
        val stale = LiveReducer.onEvent(done, ev("approval_request", """{"requestId":"r1","title":"Run: rm","resolved":false}""", ts = finishedOnComputer - 2_000), 10_200)
        assertEquals(LivePhase.DONE, stale.phase)
        assertNull(stale.pending)
    }

    @Test
    fun aTurnFinishedByAStatusEventFencesByItsTimestamp() {
        var s = running()
        s = LiveReducer.onEvent(s, ev("status", """{"state":"completed"}""", ts = 2_000_000), 3_000)
        assertEquals(LivePhase.DONE, s.phase)
        assertEquals(2_000_000L, s.finishedAtServerMs)
        val stale = LiveReducer.onEvent(s, ev("status", """{"state":"running"}""", ts = 1_999_000), 3_000 + LiveReducer.STRAGGLER_GRACE_MS + 1)
        assertEquals(LivePhase.DONE, stale.phase)
        val next = LiveReducer.onEvent(s, ev("status", """{"state":"running"}""", ts = 2_005_000), 3_500)
        assertEquals(LivePhase.RUNNING, next.phase)
    }

    // ---- Live Update card: order in the shade ----

    @Test
    fun theCardsWhenIsStableWithinAPhaseAndMovesOnANewPhase() {
        val t = LiveText.English
        var s = running(now = 1_000)
        val first = LiveUpdateRenderer.render(s, 1_000, t, null).whenMs
        s = LiveReducer.onEvent(s, ev("tool_call", """{"status":"running","name":"Bash"}""", id = "a"), 2_000)
        s = LiveReducer.onEvent(s, ev("tool_call", """{"status":"running","name":"Read"}""", id = "b"), 3_000)
        assertEquals("steps never re-rank the card", first, LiveUpdateRenderer.render(s, 3_000, t, null).whenMs)
        s = LiveReducer.onEvent(s, ev("approval_request", """{"requestId":"r","title":"x","resolved":false}"""), 4_000)
        assertEquals("waiting for you moves the card up", 4_000L, LiveUpdateRenderer.render(s, 4_000, t, null).whenMs)
        s = LiveReducer.onEvent(s, ev("status", """{"state":"completed"}"""), 5_000)
        val done = LiveUpdateRenderer.render(s, 5_000, t, null).whenMs
        assertEquals(5_000L, done)
        // The demotion re-post a minute later keeps the place.
        assertEquals(done, LiveUpdateRenderer.render(s, 65_000, t, null).whenMs)
    }

    @Test
    fun aReconnectDoesNotReorderTheCards() {
        // Bug: every pin's phase start became the reconnect time, so all cards were re-ranked (and
        // turns that finished long ago were promoted again).
        var s = finished(now = 10_000)
        s = LiveReducer.onConnection(s, online = false, now = 20_000)
        assertEquals(LivePhase.OFFLINE, s.phase)
        s = LiveReducer.onConnection(s, online = true, now = 30_000)
        assertEquals(LivePhase.DONE, s.phase)
        assertEquals(10_000L, s.phaseSinceMs)
        assertEquals(10_000L, LiveUpdateRenderer.render(s, 30_000, LiveText.English, null).whenMs)
    }

    // ---- Ordinary notifications ----

    private var now = 1_731_500_000_000L
    private fun engine(prefs: NotifyPrefs = NotifyPrefs()) = NotificationEngine({ prefs }, { 12 * 60 }, { now })

    @Test
    fun aReplyTailedAfterFinishedDoesNotReplaceIt() {
        // Bug: "finished" (status slot) was replaced by "replied" / a tool call of the same turn.
        val on = NotifyPrefs(enabled = NotifyPrefs().enabled + NotifyType.AGENT_REPLY + NotifyType.TOOL_CALL)
        val e = engine(on)
        val s = summary.copy(status = "idle")
        e.onSessionUpsert(s)
        e.onSessionUpsert(s.copy(status = "busy"))
        now += 60_000
        val done = e.onSessionUpsert(s.copy(lastActiveAtMs = finishedOnComputer))
        assertEquals(NotifyType.TASK_COMPLETED, done?.type)
        assertEquals(finishedOnComputer, done!!.atMs)

        assertNull(e.onSessionEvent(ev("tool_call", """{"status":"running","name":"Bash"}""", id = "late-tool", ts = now + 100), s))
        val reply = e.onSessionEvent(ev("assistant_message", """{"text":"All tests pass."}""", id = "late-reply", ts = now + 200), s)
        assertNotNull(reply)
        // Still "finished", quietly updated with the reply, in place and at its original time.
        assertEquals(NotifyType.TASK_COMPLETED, reply!!.type)
        assertEquals(done.title, reply.title)
        assertEquals("All tests pass.", reply.body)
        assertTrue(reply.alertOnce)
        assertEquals(finishedOnComputer, reply.atMs)

        // The next prompt starts a new turn: its steps and replies are notified again.
        e.onSessionEvent(ev("user_message", """{"text":"next"}""", id = "u2", ts = now + 5_000), s)
        assertEquals(NotifyType.TOOL_CALL, e.onSessionEvent(ev("tool_call", """{"status":"running","name":"Bash"}""", id = "t-next", ts = now + 6_000), s)?.type)
        assertEquals(NotifyType.AGENT_REPLY, e.onSessionEvent(ev("assistant_message", """{"text":"ok"}""", id = "r-next", ts = now + 7_000), s)?.type)
    }

    @Test
    fun withRepliesOffALateReplyPostsNothing() {
        val e = engine()
        val s = summary.copy(status = "idle")
        e.onSessionUpsert(s)
        e.onSessionUpsert(s.copy(status = "busy"))
        assertNotNull(e.onSessionUpsert(s))
        assertNull(e.onSessionEvent(ev("assistant_message", """{"text":"done"}""", id = "late"), s))
    }

    @Test
    fun notificationsCarryWhenTheyHappenedOnTheComputer() {
        val e = engine()
        now += 10 * 60_000
        val at = now - 90_000 // happened while the phone was offline, delivered after the reconnect
        e.onSessionEvent(ev("status", """{"state":"running"}""", id = "s1", ts = at - 1_000, seq = 1), summary)
        val done = e.onSessionEvent(ev("status", """{"state":"completed"}""", id = "s2", ts = at, seq = 2), summary)
        assertEquals(NotifyType.TASK_COMPLETED, done?.type)
        assertEquals(at, done!!.atMs)
    }
}
