package io.github.tianli0.switchtether.remote

import io.github.tianli0.switchtether.remote.live.LiveAction
import io.github.tianli0.switchtether.remote.live.LivePhase
import io.github.tianli0.switchtether.remote.live.LiveReducer
import io.github.tianli0.switchtether.remote.live.LiveText
import io.github.tianli0.switchtether.remote.live.LiveUpdateRenderer
import io.github.tianli0.switchtether.remote.live.LiveUpdateSpec
import io.github.tianli0.switchtether.remote.live.PinnedSessionState
import io.github.tianli0.switchtether.remote.live.StepKind
import io.github.tianli0.switchtether.remote.live.Step
import io.github.tianli0.switchtether.remote.protocol.AgentEvent
import io.github.tianli0.switchtether.remote.protocol.ProtocolJson
import io.github.tianli0.switchtether.remote.protocol.SessionSummary
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pinned task → Live Update (Fluid Cloud) state machine and notification content. */
class LiveUpdateTest {
    private val t = LiveText.English
    private fun ev(kind: String, data: String, id: String = kind) =
        AgentEvent(id, 1, "managed:1", kind = kind, data = ProtocolJson.parseToJsonElement(data).jsonObject)

    private val base = PinnedSessionState("managed:1", "Fix bug", "claude_code", phase = LivePhase.DONE)
    private val summary = SessionSummary(id = "managed:1", agent = "claude_code", title = "Fix bug", liveness = "live", status = "idle", control = "full", managed = true)

    private fun render(s: PinnedSessionState, now: Long, prev: LivePhase? = null) = LiveUpdateRenderer.render(s, now, t, prev)

    @Test
    fun runningShowsTheCurrentStepAndAChronometer() {
        var s = LiveReducer.onUpsert(base, summary.copy(status = "busy"), 1_000)
        assertEquals(LivePhase.RUNNING, s.phase)
        assertEquals("Thinking…", render(s, 1_000).text)
        s = LiveReducer.onEvent(s, ev("tool_call", """{"status":"running","name":"Bash","title":"npm test"}"""), 2_000)
        val spec = render(s, 2_000)
        assertEquals("Running npm test", spec.text) // human label, no "step N"
        assertEquals("Run", spec.chip) // capsule names what the agent is doing
        assertEquals(io.github.tianli0.switchtether.remote.live.StepKind.COMMAND, spec.stepKind)
        assertEquals(1_000L, spec.chronometerBaseMs)
        assertEquals(LiveUpdateSpec.Progress.NONE, spec.progress) // notification progress bar removed
        assertTrue(spec.segments.isEmpty())
        assertTrue(spec.promoted)
        assertEquals(listOf(LiveAction.OPEN, LiveAction.UNPIN), spec.actions)
        assertFalse(spec.alert)
    }

    @Test
    fun approvalAlertsOnceWithApproveDenyAndARedactedLockScreen() {
        var s = LiveReducer.onUpsert(base, summary.copy(status = "busy"), 1_000)
        s = LiveReducer.onEvent(s, ev("approval_request", """{"requestId":"r1","title":"Run: git push --force","resolved":false}"""), 2_000)
        assertEquals(LivePhase.WAITING_APPROVAL, s.phase)
        val first = render(s, 2_000, prev = LivePhase.RUNNING)
        assertTrue(first.alert)
        assertEquals("Waiting for your approval · Run: git push --force", first.text)
        assertEquals("r1", first.requestId)
        assertEquals(listOf(LiveAction.APPROVE, LiveAction.DENY, LiveAction.UNPIN), first.actions)
        assertFalse(first.publicText.contains("git push"))
        assertTrue(first.chip.length <= LiveUpdateRenderer.MAX_CHIP)
        // The same state posted again (e.g. the upsert that follows) does not alert again.
        val again = LiveReducer.onUpsert(s, summary.copy(status = "waiting", waitingFor = "permission prompt"), 2_100)
        assertSame(s, again)
        assertFalse(render(again, 2_100, prev = LivePhase.WAITING_APPROVAL).alert)
        // Resolved (by anyone) → back to running.
        val resolved = LiveReducer.onEvent(s, ev("approval_request", """{"requestId":"r1","resolved":true}"""), 3_000)
        assertEquals(LivePhase.RUNNING, resolved.phase)
        assertNull(resolved.pending)
    }

    @Test
    fun questionsOfferAnswerNotApprove() {
        val s = LiveReducer.onEvent(base, ev("question", """{"requestId":"q1","questions":[{"question":"Which branch?"}]}"""), 1_000)
        val spec = render(s, 1_000, prev = LivePhase.RUNNING)
        assertEquals(LivePhase.WAITING_INPUT, s.phase)
        assertEquals("Which branch?", spec.text)
        assertEquals(listOf(LiveAction.ANSWER, LiveAction.UNPIN), spec.actions)
    }

    @Test
    fun finishedTurnsStayPromotedForAMinuteThenDemote() {
        var s = LiveReducer.onUpsert(base, summary.copy(status = "busy"), 1_000)
        s = LiveReducer.onEvent(s, ev("status", """{"state":"idle"}"""), 5_000)
        assertEquals(LivePhase.DONE, s.phase)
        assertTrue(render(s, 5_000).promoted)
        assertEquals(LiveUpdateSpec.Progress.NONE, render(s, 5_000).progress)
        assertFalse(render(s, 5_000 + LiveUpdateRenderer.DONE_PROMOTED_MS).promoted)
        // The next turn re-promotes.
        val next = LiveReducer.onUpsert(s, summary.copy(status = "busy"), 100_000)
        assertTrue(render(next, 100_000).promoted)
        assertEquals(LivePhase.RUNNING, next.phase)
    }

    @Test
    fun failuresInterruptsOfflineAndEnd() {
        val running = LiveReducer.onUpsert(base, summary.copy(status = "busy"), 1_000)
        assertEquals("Error", render(LiveReducer.onEvent(running, ev("error", """{"message":"boom"}"""), 2_000), 2_000).chip)
        assertEquals("Stop", render(LiveReducer.onEvent(running, ev("system", """{"subtype":"turn_stopped"}"""), 2_000), 2_000).chip)
        val off = LiveReducer.onConnection(running, online = false, now = 3_000)
        assertEquals(LivePhase.OFFLINE, off.phase)
        assertEquals("Off", render(off, 3_000).chip)
        assertTrue(render(off, 3_000).promoted)
        // An idle pin losing the connection says so, but quietly (not promoted).
        val idle = base.copy(phase = LivePhase.DONE, phaseSinceMs = 0)
        val idleOff = LiveReducer.onConnection(idle, online = false, now = 3_000)
        assertEquals("Off", render(idleOff, 3_000).chip)
        assertFalse(render(idleOff, 3_000).promoted)
        assertEquals(LivePhase.RUNNING, LiveReducer.onConnection(off, online = true, now = 4_000).phase)
        val ended = LiveReducer.onUpsert(running, summary.copy(liveness = "ended"), 5_000)
        val spec = render(ended, 5_000)
        assertFalse(spec.promoted)
        assertFalse(spec.actions.contains(LiveAction.UNPIN))
    }

    @Test
    fun dismissalSticksUntilThePhaseChanges() {
        val running = LiveReducer.onUpsert(base, summary.copy(status = "busy"), 1_000)
        val dismissed = LiveReducer.onDismissed(running)
        assertTrue(dismissed.dismissed)
        // More steps in the same phase: still dismissed (no repost).
        assertTrue(LiveReducer.onEvent(dismissed, ev("tool_call", """{"status":"running","name":"Read"}"""), 2_000).dismissed)
        // A new phase (waiting for approval) clears it.
        assertFalse(LiveReducer.onEvent(dismissed, ev("approval_request", """{"requestId":"r","title":"x"}"""), 3_000).dismissed)
    }

    @Test
    fun phoneAnswerLosingToTheComputerSaysSo() {
        var s = LiveReducer.onEvent(base, ev("approval_request", """{"requestId":"r1","title":"rm -rf build"}"""), 1_000)
        s = LiveReducer.onResolvedElsewhere(s, 2_000)
        assertEquals(LivePhase.RUNNING, s.phase)
        assertEquals("Handled on the computer", render(s, 2_000).text)
    }

    @Test
    fun allChipsFitTheCapsule() {
        LivePhase.entries.forEach { p ->
            assertTrue(render(base.copy(phase = p), 0).chip.length <= LiveUpdateRenderer.MAX_CHIP)
        }
    }

    @Test
    fun noProgressBarInAnyPhase() {
        val running = LiveReducer.onUpsert(base, summary.copy(status = "busy"), 1_000)
        val waiting = LiveReducer.onEvent(running, ev("approval_request", """{"requestId":"r1","title":"Run: ls","resolved":false}"""), 2_000)
        val resumed = LiveReducer.onEvent(waiting, ev("approval_request", """{"requestId":"r1","title":"Run: ls","resolved":true}"""), 3_000)
        val done = LiveReducer.onUpsert(resumed, summary.copy(status = "idle"), 4_000)
        listOf(running, waiting, resumed, done).forEach { s ->
            val spec = render(s, 4_000)
            assertEquals(LiveUpdateSpec.Progress.NONE, spec.progress)
            assertEquals(0, spec.progressValue)
            assertTrue(spec.segments.isEmpty())
        }
    }

    @Test
    fun toolNamesBecomeHumanSteps() {
        fun step(name: String, input: String = "{}") = LiveReducer.classifyStep(name, null, null, kotlinx.serialization.json.Json.parseToJsonElement(input) as kotlinx.serialization.json.JsonObject)
        assertEquals(Step(StepKind.MESSAGE), step("SendMessage"))
        assertEquals(Step(StepKind.COMMAND, "npm test"), step("Bash", """{"command":"npm test
echo done"}"""))
        assertEquals(Step(StepKind.EDIT, "Login.kt"), step("Edit", """{"file_path":"src/app/Login.kt"}"""))
        assertEquals(Step(StepKind.READ, "README.md"), step("Read", """{"file_path":"D:/repo/README.md"}"""))
        assertEquals(Step(StepKind.WEB_FETCH, "example.com"), step("WebFetch", """{"url":"https://example.com/docs"}"""))
        assertEquals(Step(StepKind.OTHER), step("mcp__weird__tool"))
        assertEquals("Working…", t.step(Step(StepKind.OTHER)))
    }

    @Test
    fun approvalSubjectsAreShort() {
        assertEquals("tracker.txt", LiveUpdateRenderer.shortSubject("D:/develop/app/build/tracker.txt"))
        assertEquals("Run: ls -la", LiveUpdateRenderer.shortSubject("Run: ls -la"))
        assertEquals(60, LiveUpdateRenderer.shortSubject("x".repeat(80)).length)
    }

    @Test
    fun expandedCardCarriesPlanFilesAndTokens() {
        var s = LiveReducer.onUpsert(base, summary.copy(status = "busy"), 1_000)
        s = LiveReducer.onEvent(s, ev("plan", """{"items":[{"text":"Write parser","status":"completed"},{"text":"Add tests","status":"in_progress"},{"text":"Docs","status":"pending"}]}"""), 1_500)
        s = LiveReducer.onEvent(s, ev("file_change", """{"path":"src/a.kt"}"""), 1_600)
        s = LiveReducer.onEvent(s, ev("file_change", """{"path":"src/a.kt"}"""), 1_700)
        s = LiveReducer.onEvent(s, ev("usage", """{"inputTokens":1200,"outputTokens":300}"""), 1_800)
        s = LiveReducer.onEvent(s, ev("tool_call", """{"status":"running","name":"Bash","title":"npm test"}"""), 2_000)
        val spec = render(s, 2_000)
        assertTrue(spec.detail, spec.detail.contains("Plan 1/3 · now: Add tests"))
        assertTrue(spec.detail, spec.detail.contains("1 file changed"))
        assertTrue(spec.detail, spec.detail.contains("1,500 tokens"))
        assertTrue(spec.progressNote, spec.progressNote.startsWith("1/3"))
    }
    @Test
    fun childCompletionDoesNotCompleteThePinnedParent() {
        val parent = LiveReducer.onUpsert(base, summary.copy(status = "busy"), 1_000)
        val childDone = ev("status", """{"state":"completed"}""").copy(parentId = "spawn")
        assertSame(parent, LiveReducer.onEvent(parent, childDone, 2_000))
        val childQuestion = ev("question", """{"requestId":"child-q","questions":[]}""").copy(parentId = "spawn")
        assertEquals(LivePhase.WAITING_INPUT, LiveReducer.onEvent(parent, childQuestion, 2_000).phase)
    }

}
