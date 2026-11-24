package io.github.tianli0.switchtether.remote

import io.github.tianli0.switchtether.remote.api.CommandResponse
import io.github.tianli0.switchtether.remote.command.CommandOutcome
import io.github.tianli0.switchtether.remote.command.CommandStatus
import io.github.tianli0.switchtether.remote.command.Commands
import io.github.tianli0.switchtether.remote.events.EventStreamReducer
import io.github.tianli0.switchtether.remote.events.StreamCursor
import io.github.tianli0.switchtether.remote.events.StreamDecision
import io.github.tianli0.switchtether.remote.events.StreamFrame
import io.github.tianli0.switchtether.remote.forward.ForwardFrames
import io.github.tianli0.switchtether.remote.forward.LocalForwarder
import io.github.tianli0.switchtether.remote.forward.TunnelConnector
import io.github.tianli0.switchtether.remote.forward.TunnelEvents
import io.github.tianli0.switchtether.remote.forward.TunnelLink
import io.github.tianli0.switchtether.remote.forward.TunnelStatus
import io.github.tianli0.switchtether.remote.live.LivePhase
import io.github.tianli0.switchtether.remote.live.LiveReducer
import io.github.tianli0.switchtether.remote.live.PinnedSessionState
import io.github.tianli0.switchtether.remote.notify.NotificationEngine
import io.github.tianli0.switchtether.remote.notify.NotifyPrefs
import io.github.tianli0.switchtether.remote.notify.NotifyType
import io.github.tianli0.switchtether.remote.protocol.AgentEvent
import io.github.tianli0.switchtether.remote.protocol.CommandRecord
import io.github.tianli0.switchtether.remote.protocol.ProtocolJson
import io.github.tianli0.switchtether.remote.protocol.SessionSummary
import io.github.tianli0.switchtether.remote.session.AgentEventLog
import io.github.tianli0.switchtether.remote.session.SubagentNode
import io.github.tianli0.switchtether.remote.session.SubagentTree
import io.github.tianli0.switchtether.remote.terminal.TermFrame
import io.github.tianli0.switchtether.remote.terminal.TermScreen
import io.github.tianli0.switchtether.remote.update.ReleaseInfo
import io.github.tianli0.switchtether.remote.update.SemVer
import io.github.tianli0.switchtether.remote.update.Sha256Sums
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Live Update card: an outcome of the turn in flight applies once, whichever source reports it. */
class LiveUpdateEdgeTest {
    private fun ev(kind: String, data: String, id: String = kind) =
        AgentEvent(id, 1, "managed:1", kind = kind, data = ProtocolJson.parseToJsonElement(data).jsonObject)

    private val base = PinnedSessionState("managed:1", "Fix bug", "claude_code", phase = LivePhase.DONE)
    private val summary = SessionSummary(id = "managed:1", agent = "claude_code", title = "Fix bug", liveness = "live", status = "idle", control = "full", managed = true)
    private val running = LiveReducer.onUpsert(base, summary.copy(status = "busy"), 1_000)

    @Test
    fun aLateErrorSummaryDoesNotReplaceAnOutcomeAlreadyShown() {
        val done = LiveReducer.onEvent(running, ev("status", """{"state":"idle"}"""), 2_000)
        assertEquals(LivePhase.DONE, done.phase)
        assertEquals(LivePhase.DONE, LiveReducer.onUpsert(done, summary.copy(status = "error"), 3_000).phase)
        assertEquals(LivePhase.DONE, LiveReducer.onUpsert(done, summary.copy(status = "error"), 3_000, fromRest = true).phase)
        val stopped = LiveReducer.onEvent(running, ev("status", """{"state":"interrupted"}"""), 2_000)
        assertEquals(LivePhase.INTERRUPTED, LiveReducer.onUpsert(stopped, summary.copy(status = "error"), 3_000).phase)
        val ended = LiveReducer.onEnded(running, 2_000)
        assertEquals(LivePhase.ENDED, LiveReducer.onUpsert(ended, summary.copy(status = "error"), 3_000).phase)
        // A turn in flight still takes it, also while the connection is down.
        assertEquals(LivePhase.FAILED, LiveReducer.onUpsert(running, summary.copy(status = "error"), 3_000).phase)
        val off = LiveReducer.onConnection(running, online = false, now = 2_000)
        val failed = LiveReducer.onUpsert(off, summary.copy(status = "error"), 3_000, fromRest = true)
        assertEquals(LivePhase.FAILED, failed.phase)
        assertEquals(LivePhase.FAILED, LiveReducer.onConnection(failed, online = true, now = 4_000).phase)
    }

    @Test
    fun aTurnThatFailedOrWasStoppedWhileOfflineIsNotRestoredAsRunning() {
        val off = LiveReducer.onConnection(running, online = false, now = 2_000)
        assertEquals(LivePhase.OFFLINE, off.phase)
        val failed = LiveReducer.onEvent(off, ev("error", """{"message":"boom"}"""), 3_000)
        assertEquals(LivePhase.FAILED, failed.phase)
        assertEquals(LivePhase.FAILED, LiveReducer.onConnection(failed, online = true, now = 4_000).phase)
        val stopped = LiveReducer.onEvent(off, ev("system", """{"subtype":"turn_stopped"}"""), 3_000)
        assertEquals(LivePhase.INTERRUPTED, stopped.phase)
        assertEquals(LivePhase.INTERRUPTED, LiveReducer.onConnection(stopped, online = true, now = 4_000).phase)
        // Other system rows do not end the turn, and a turn still running comes back as running.
        val other = LiveReducer.onEvent(off, ev("system", """{"subtype":"compact"}"""), 3_000)
        assertEquals(LivePhase.RUNNING, LiveReducer.onConnection(other, online = true, now = 4_000).phase)
        // An outcome already shown is not replaced by a late error row / stop marker.
        val done = LiveReducer.onEvent(running, ev("status", """{"state":"idle"}"""), 2_000)
        assertEquals(LivePhase.DONE, LiveReducer.onEvent(done, ev("error", """{"message":"boom"}"""), 3_000).phase)
        assertEquals(LivePhase.DONE, LiveReducer.onEvent(done, ev("system", """{"subtype":"turn_stopped"}"""), 3_000).phase)
    }
}

/** Notification rules: outcomes across mixed sources, and mutes that end. */
class NotificationEdgeTest {
    private var now = 1_000_000L
    private var prefs = NotifyPrefs()
    private fun engine() = NotificationEngine({ prefs }, { 12 * 60 }, { now })

    private fun ev(id: String, seq: Long, kind: String, data: String, session: String = S.id) =
        AgentEvent(id, seq, session, kind = kind, data = ProtocolJson.parseToJsonElement(data).jsonObject)

    private val S = SessionSummary(id = "managed:1", agent = "claude_code", project = "app", title = "Fix bug", liveness = "live", status = "idle", control = "full", managed = true)

    /** A session seen only through its event log: every turn is opened by `running`, so no outcome is lost. */
    @Test
    fun everyTurnOfAnEventOnlySessionIsReported() {
        val e = engine()
        for (turn in 1..3) {
            assertNull(e.onSessionEvent(ev("r$turn", turn * 10L, "status", """{"state":"running"}"""), S))
            assertEquals("turn $turn", NotifyType.TASK_COMPLETED, e.onSessionEvent(ev("i$turn", turn * 10L + 1, "status", """{"state":"idle"}"""), S)?.type)
            // The same state again is not a second outcome.
            assertNull(e.onSessionEvent(ev("i$turn-again", turn * 10L + 2, "status", """{"state":"idle"}"""), S))
        }
    }

    /** Turn 2 is opened by the summary alone; its `idle` event equals the last event state of turn 1. */
    @Test
    fun aTurnOpenedByTheSummaryIsClosedByTheNextStatusEvent() {
        val e = engine()
        assertNull(e.onSessionUpsert(S))
        assertNull(e.onSessionEvent(ev("r1", 1, "status", """{"state":"running"}"""), S))
        assertNull(e.onSessionUpsert(S.copy(status = "busy")))
        assertEquals("done:${S.id}:1", e.onSessionEvent(ev("i1", 2, "status", """{"state":"idle"}"""), S)?.key)
        assertNull(e.onSessionUpsert(S.copy(status = "idle")))
        // Turn 2: only the summary says busy.
        assertNull(e.onSessionUpsert(S.copy(status = "busy")))
        assertEquals("done:${S.id}:2", e.onSessionEvent(ev("i2", 3, "status", """{"state":"idle"}"""), S)?.key)
        // Turn 3 starts before the summary was ever seen idle (busy → busy): nothing is announced twice.
        assertNull(e.onSessionUpsert(S.copy(status = "busy", lastActiveAtMs = 5)))
        assertNull(e.onSessionEvent(ev("i2-again", 4, "status", """{"state":"idle"}"""), S))
    }

    @Test
    fun aSecondFailedTurnOpenedByTheSummaryIsReported() {
        val e = engine()
        assertNull(e.onSessionUpsert(S))
        assertNull(e.onSessionEvent(ev("r1", 1, "status", """{"state":"running"}"""), S))
        assertEquals(NotifyType.TASK_FAILED, e.onSessionEvent(ev("e1", 2, "status", """{"state":"error"}"""), S)?.type)
        assertNull(e.onSessionEvent(ev("e1-again", 3, "status", """{"state":"error"}"""), S))
        assertNull(e.onSessionUpsert(S.copy(status = "busy")))
        assertEquals("fail:${S.id}:2", e.onSessionEvent(ev("e2", 4, "status", """{"state":"error"}"""), S)?.key)
        assertNull(e.onSessionUpsert(S.copy(status = "error")))
    }

    /** A mute filters what is shown; it does not use up the notification of a state that is still current. */
    @Test
    fun aRequestStillOpenAfterUnmutingIsAnnounced() {
        val e = engine()
        prefs = NotifyPrefs(mutedProjects = setOf("app"))
        val request = ev("a", 1, "approval_request", """{"requestId":"r","title":"Run npm test"}""")
        assertNull(e.onSessionEvent(request, S))
        assertNull(e.onSessionUpsert(S.copy(status = "waiting", waitingFor = "permission prompt")))
        prefs = NotifyPrefs()
        // The request is seen again (updated in place, re-read after a reconnect) and is still unanswered.
        val n = e.onSessionEvent(request, S)
        assertEquals(NotifyType.WAITING_APPROVAL, n?.type)
        assertEquals("r", n?.requestId)
        assertNull(e.onSessionEvent(request, S)) // once
        assertNull(e.onSessionUpsert(S.copy(status = "waiting", waitingFor = "permission prompt")))
        // Answered meanwhile: nothing.
        val e2 = engine()
        prefs = NotifyPrefs(mutedAgents = setOf("claude_code"))
        assertNull(e2.onSessionEvent(request, S))
        prefs = NotifyPrefs()
        assertNull(e2.onSessionEvent(ev("a", 1, "approval_request", """{"requestId":"r","resolved":true}"""), S))
    }

    @Test
    fun aWaitStillCurrentAfterUnmutingOrEnablingIsAnnouncedOnce() {
        val waiting = S.copy(status = "waiting", waitingFor = "input")
        val e = engine()
        prefs = NotifyPrefs(mutedAgents = setOf("claude_code"))
        assertNull(e.onSessionUpsert(waiting))
        assertNull(e.onSessionUpsert(waiting.copy(lastActiveAtMs = 1)))
        prefs = NotifyPrefs()
        assertEquals(NotifyType.WAITING_INPUT, e.onSessionUpsert(waiting.copy(lastActiveAtMs = 2))?.type)
        assertNull(e.onSessionUpsert(waiting.copy(lastActiveAtMs = 3)))

        val e2 = engine()
        prefs = NotifyPrefs(enabled = NotifyPrefs().enabled - NotifyType.WAITING_INPUT)
        assertNull(e2.onSessionUpsert(waiting))
        prefs = NotifyPrefs()
        assertEquals(NotifyType.WAITING_INPUT, e2.onSessionUpsert(waiting)?.type)
        assertNull(e2.onSessionUpsert(waiting))
        // A wait that ended while muted is not announced afterwards.
        val e3 = engine()
        prefs = NotifyPrefs(mutedAgents = setOf("claude_code"))
        assertNull(e3.onSessionUpsert(waiting))
        assertNull(e3.onSessionUpsert(S.copy(status = "busy")))
        prefs = NotifyPrefs()
        assertNull(e3.onSessionUpsert(S.copy(status = "busy", lastActiveAtMs = 9)))
    }

    @Test
    fun theLongRunningReminderSurvivesAMute() {
        val e = engine()
        val busy = S.copy(status = "busy")
        e.onSessionUpsert(busy)
        now += 31 * 60_000L
        prefs = NotifyPrefs(mutedProjects = setOf("app"))
        assertEquals(0, e.longRunning(listOf(busy)).size)
        prefs = NotifyPrefs()
        assertEquals(1, e.longRunning(listOf(busy)).size)
        assertEquals(0, e.longRunning(listOf(busy)).size)
    }
}

class EventStreamEdgeTest {
    private fun ev(seq: Long, epoch: String = "E1") = StreamFrame.Event(seq, epoch, "", "session.event", JsonNull)

    @Test
    fun epochChangeDuringAResyncStartsTheNewSequence() {
        var c = StreamCursor("E1", 1_000)
        // The resync names the new epoch, whose head is far below the old cursor.
        val (c1, d1) = EventStreamReducer.reduce(c, StreamFrame.Resync("E2", 5, "epoch_changed"))
        assertEquals(StreamCursor("E2", 5), c1)
        assertTrue(d1 is StreamDecision.Resync)
        // Covered by the refetch: dropped. New ones apply although their seq is below the old cursor.
        assertEquals(StreamDecision.Duplicate, EventStreamReducer.reduce(c1, ev(5, "E2")).second)
        val (c2, d2) = EventStreamReducer.reduce(c1, ev(6, "E2"))
        assertTrue(d2 is StreamDecision.Apply)
        assertEquals(StreamCursor("E2", 6), c2)
        // A second restart while the refetch of the first is still running: resync again, never "duplicate".
        val (c3, d3) = EventStreamReducer.reduce(c2, ev(1, "E3"))
        assertEquals(StreamDecision.Resync("epoch_changed"), d3)
        assertEquals(StreamCursor("E3", 1), c3)
        assertTrue(EventStreamReducer.reduce(c3, ev(2, "E3")).second is StreamDecision.Apply)
        // A resync without an epoch keeps the known one.
        c = EventStreamReducer.reduce(StreamCursor("E1", 10), StreamFrame.Resync("", 40, "gap")).first
        assertEquals(StreamCursor("E1", 40), c)
    }

    /** A `seq` that is not an integer must not move the cursor to the end of the number range. */
    @Test
    fun aMalformedSeqIsNotAnEvent() {
        for (seq in listOf("1e30", "18446744073709551615", "-3", "1.5", "\"x\"", "null", "{}")) {
            val f = StreamFrame.parse("""{"type":"event","seq":$seq,"epoch":"E1","topic":"session.event","data":{}}""")
            assertTrue("seq=$seq → $f", f is StreamFrame.Unknown)
            assertEquals(StreamCursor("E1", 7), EventStreamReducer.reduce(StreamCursor("E1", 7), f).first)
        }
        assertEquals(42L, (StreamFrame.parse("""{"type":"event","seq":42,"epoch":"E1","topic":"t"}""") as StreamFrame.Event).seq)
        val resync = StreamFrame.parse("""{"type":"resync","epoch":"E1","headSeq":1e30,"reason":"gap"}""") as StreamFrame.Resync
        assertEquals(0L, resync.headSeq)
        assertTrue(StreamFrame.parse("not json") is StreamFrame.Unknown)
        assertTrue(StreamFrame.parse("[1,2]") is StreamFrame.Unknown)
    }
}

class AgentEventLogEdgeTest {
    private fun e(id: String, seq: Long, kind: String, data: String = "{}", parent: String? = null) = AgentEvent(
        id = id, seq = seq, sessionId = "s", kind = kind, parentId = parent,
        data = ProtocolJson.parseToJsonElement(data).jsonObject,
    )

    @Test
    fun resultsOfSubagentsThatRepeatACallIdStayWithTheirOwnCall() {
        val log = AgentEventLog.of(
            listOf(
                e("a1", 1, "tool_call", """{"callId":"kA","name":"Agent"}"""),
                e("a2", 2, "tool_call", """{"callId":"kB","name":"Agent"}"""),
                e("x1", 3, "tool_call", """{"callId":"c1","name":"Bash"}""", parent = "a1"),
                e("x2", 4, "tool_call", """{"callId":"c1","name":"Bash"}""", parent = "kB"), // parent by callId alias
                e("y3", 5, "tool_call", """{"callId":"c2","name":"Read"}""", parent = "a1"),
                e("y4", 6, "tool_call", """{"callId":"c2","name":"Read"}""", parent = "a2"),
                e("rx2", 7, "tool_result", """{"callId":"c1","text":"two"}""", parent = "a2"),
                e("rx1", 8, "tool_result", """{"callId":"c1","text":"one"}""", parent = "kA"),
                // Only the second agent's c2 has finished.
                e("ry4", 9, "tool_result", """{"callId":"c2","text":"four"}""", parent = "a2"),
                // A result of a repeated callId that belongs to neither call stays a row of its own.
                e("stray", 10, "tool_result", """{"callId":"c1","text":"?"}""", parent = "elsewhere"),
            ),
        )
        val items = log.renderItems().associateBy { it.key }
        assertEquals("rx1", items["x1"]?.attachedResult?.id)
        assertEquals("rx2", items["x2"]?.attachedResult?.id)
        assertNull(items["y3"]?.attachedResult)
        assertEquals("ry4", items["y4"]?.attachedResult?.id)
        assertEquals(setOf("a1", "a2", "x1", "x2", "y3", "y4", "stray"), items.keys)
    }

    @Test(timeout = 5_000)
    fun aParentChainThatLoopsTerminates() {
        val events = listOf(
            e("a", 1, "assistant_message", parent = "b"),
            e("b", 2, "assistant_message", parent = "c"),
            e("c", 3, "tool_call", """{"callId":"kc"}""", parent = "a"),
            e("self", 4, "assistant_message", parent = "self"),
            e("alias", 5, "tool_call", """{"callId":"ka"}""", parent = "ka"),
        ) + (0 until 50).map { e("d$it", 10L + it, "assistant_message", parent = if (it == 0) null else "d${it - 1}") }
        val items = AgentEventLog.of(events).renderItems()
        assertEquals(events.size, items.size)
        assertTrue(items.all { it.depth in 0..8 })
        assertEquals(1, items.single { it.key == "self" }.depth)
        // Collapsing a member of the loop hides the loop without hanging.
        assertTrue(AgentEventLog.of(events).renderItems(setOf("a")).none { it.key in setOf("a", "b", "c") })
    }

    @Test(timeout = 5_000)
    fun subagentTreeWithALoopKeepsEveryNodeOnce() {
        val nodes = listOf(
            SubagentNode(agentId = "root", updatedAtMs = 1),
            SubagentNode(agentId = "a", parentAgentId = "b", updatedAtMs = 2),
            SubagentNode(agentId = "b", parentAgentId = "a", updatedAtMs = 3),
            SubagentNode(agentId = "kid", parentAgentId = "root", updatedAtMs = 4),
            SubagentNode(agentId = "selfish", parentAgentId = "selfish", updatedAtMs = 5),
        )
        val flat = SubagentTree.flatten(nodes)
        assertEquals(listOf("a", "b", "kid", "root", "selfish"), flat.map { it.node.agentId }.sorted())
        assertEquals(1, flat.single { it.node.agentId == "kid" }.depth)
        val trail = SubagentTree.trail(nodes, "a").map { it.agentId }
        assertEquals(trail.distinct(), trail)
        assertTrue(trail.size <= 2)
        assertEquals(listOf("root"), SubagentTree.trail(nodes, "kid").map { it.agentId })
    }
}

class CommandEdgeTest {
    private fun record(status: String) = CommandRecord(commandId = "c1", status = status)

    @Test
    fun aRetryNeverReusesTheIdOfADefinitiveFailure() = runBlocking {
        val failed = Commands.status("c1") { record("failed") }
        assertTrue(failed is CommandStatus.Failed)
        val first = Commands.retryCommandId(failed, "c1")
        val second = Commands.retryCommandId(failed, "c1")
        assertTrue(first != null && first != "c1")
        assertTrue(second != null && second != "c1" && second != first)
        assertTrue(Commands.status("c1") { record("ERROR") } is CommandStatus.Failed)
        // Outcome not known to the desktop either (it restarted mid-command), still running, or done: no retry is offered.
        for (s in listOf("unknown", "pending", "running", "", "succeeded")) {
            assertNull(s, Commands.retryCommandId(Commands.status("c1") { record(s) }, "c1"))
        }
        assertEquals("c1", Commands.retryCommandId(CommandStatus.NotFound, "c1"))
        assertNull(Commands.retryCommandId(CommandStatus.Unreachable(RuntimeException("x")), "c1"))
    }

    /** An answer that cannot be read (2xx with a broken body) is an unknown outcome, not a crash. */
    @Test
    fun anUnreadableAnswerIsAnUnknownOutcome() = runBlocking {
        val out = Commands.run("c2") { ProtocolJson.parseToJsonElement("{not json"); CommandResponse(JsonNull, false) }
        assertTrue("got $out", out is CommandOutcome.Unknown)
        assertEquals("c2", out.commandId)
    }
}

class UpdateParsingEdgeTest {
    private val h1 = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
    private val h2 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    private val apk = "SwitchTether-0.2.0-android.apk"

    @Test
    fun aDotSlashPrefixNamesTheSameFileAndNothingElseDoes() {
        assertEquals(h1, Sha256Sums.parse("$h1  ./$apk")[apk])
        assertEquals(h1, Sha256Sums.parse("$h1 *./$apk\r\n")[apk])
        // Any other path is a different file: no checksum for the asset.
        for (name in listOf("dist/$apk", "./dist/$apk", "../$apk", ".//$apk", "././$apk", "/$apk", ".\\$apk", "./$apk/", "x$apk", "$apk.sig", "./")) {
            assertNull(name, Sha256Sums.parse("$h1  $name")[apk])
        }
        // Not a SHA-256: no entry.
        assertNull(Sha256Sums.parse("${h1.dropLast(1)}  $apk")[apk])
        assertNull(Sha256Sums.parse("${h1}0  $apk")[apk])
        assertNull(Sha256Sums.parse("${h1.dropLast(1)}g  $apk")[apk])
    }

    @Test
    fun twoDifferentChecksumsForOneFileAreNoChecksum() {
        assertNull(Sha256Sums.parse("$h1  $apk\n$h2  $apk")[apk])
        assertNull(Sha256Sums.parse("$h1  $apk\n$h2  ./$apk")[apk])
        assertNull(Sha256Sums.parse("$h2  ./$apk\n$h1  $apk\n$h1  $apk")[apk])
        // The same line twice is harmless, in either case of the hex digits.
        assertEquals(h1, Sha256Sums.parse("$h1  $apk\n${h1.uppercase()} *./$apk")[apk])
        // Other files are not affected by a conflict.
        assertEquals(h2, Sha256Sums.parse("$h1  $apk\n$h2  $apk\n$h2  other.exe")["other.exe"])
    }

    @Test
    fun versionsThatDoNotFitAreNotVersions() {
        assertNull(SemVer.parse("v99999999999.0.0"))
        assertNull(SemVer.parse("1.99999999999.0"))
        assertNull(SemVer.parse("1.0.4294967297"))
        assertNull(SemVer.parse("-1.0.0"))
        assertNull(SemVer.parse("1..2"))
        assertNull(SemVer.parse(""))
        // Such a tag is skipped; it does not break the whole list.
        val list = ReleaseInfo.parseAll("""[{"tag_name":"v99999999999.0.0","assets":[]},{"tag_name":"v0.2.0","assets":[]}]""")
        assertEquals(listOf("v0.2.0"), list.map { it.tag })
    }

    @Test
    fun numericPrereleasePartsCompareAsNumbersWhateverTheirLength() {
        fun v(s: String) = SemVer.parse(s)!!
        assertTrue(v("1.0.0-rc.100000000000000000000") > v("1.0.0-rc.99999999999999999999"))
        assertTrue(v("1.0.0-rc.100000000000000000000") > v("1.0.0-rc.2"))
        assertTrue(v("1.0.0-rc.x") > v("1.0.0-rc.100000000000000000000"))
        assertTrue(v("1.0.0") > v("1.0.0-rc.100000000000000000000"))
        assertEquals(0, v("1.0.0-rc.1").compareTo(v("1.0.0-rc.1")))
        assertTrue(v("0.1.16") > v("0.1.15"))
        assertTrue(v("0.2.0-beta.1") < v("0.2.0"))
    }
}

class TerminalFrameEdgeTest {
    private val snapshot = """{"type":"snapshot","version":5,"cols":10,"rows":3,"lines":[{"runs":[{"t":"a"}]},{"runs":[{"t":"b"}]},{"runs":[]}]}"""

    @Test(timeout = 10_000)
    fun rowsOutsideTheScreenAreIgnored() {
        val s = TermScreen().apply(TermFrame.parse(snapshot))
        for (y in listOf("-1", "-2147483649", "2147483647", "4294967296", "1e30", "99999999", "\"x\"", "null", "{}")) {
            val patch = TermFrame.parse("""{"type":"patch","version":6,"lines":[{"y":$y,"line":{"runs":[{"t":"BAD"}]}},{"y":1,"line":{"runs":[{"t":"ok"}]}}]}""")
            val after = s.apply(patch)
            assertEquals("y=$y", listOf("a", "ok", ""), after.lines.map { it.text })
            assertEquals(6L, after.version)
        }
    }

    @Test
    fun malformedFramesAreReportedNotThrown() {
        for (text in listOf("", "{", "[]", "null", "\"x\"", """{"type":7}""", """{"type":"patch","lines":7}""", """{"type":"patch","lines":[7,null,{"y":0}]}""",
            """{"type":"snapshot","lines":[7,{"runs":"x"}],"cursor":7}""", "\ud83d", """{"type":"exit","code":"x"}""")) {
            val f = TermFrame.parse(text)
            TermScreen().apply(f) // must not throw
        }
        assertTrue(TermFrame.parse("{") is TermFrame.Unknown)
        val s = TermFrame.parse("""{"type":"snapshot","cols":-5,"rows":1e30,"lines":[]}""") as TermFrame.Snapshot
        assertTrue(s.cols >= 1)
        assertTrue(s.rows in 1..10_000)
        val broken = TermFrame.parse("""{"type":"snapshot","lines":[7,{"runs":"x"}],"cursor":7}""") as TermFrame.Snapshot
        assertEquals(listOf("", ""), broken.lines.map { it.text })
    }
}

class ForwardFrameEdgeTest {
    /** A tunnel whose desktop side is driven by the test. */
    private class Peer : TunnelConnector {
        val sent = CopyOnWriteArrayList<ForwardFrames.Frame>()
        val opened = CountDownLatch(1)
        @Volatile var events: TunnelEvents? = null

        override fun connect(events: TunnelEvents): TunnelLink {
            this.events = events
            Thread { events.onOpen() }.start()
            return object : TunnelLink {
                override fun send(frame: ByteArray): Boolean {
                    ForwardFrames.decode(frame)?.let {
                        sent += it
                        if (it.type == ForwardFrames.OPEN) opened.countDown()
                    }
                    return true
                }

                override fun close() = Unit
            }
        }
    }

    private fun waitFor(what: String, cond: () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        while (!cond()) {
            assertTrue("timed out waiting for $what", System.currentTimeMillis() < until)
            Thread.sleep(10)
        }
    }

    private fun withStream(block: (peer: Peer, stream: Int, client: Socket) -> Unit) {
        val peer = Peer()
        val f = LocalForwarder(ServerSocket(0).use { it.localPort }, peer, backoffMs = { 50 })
        f.start()
        try {
            waitFor("tunnel") { f.status == TunnelStatus.OPEN }
            Socket(InetAddress.getLoopbackAddress(), f.localPort).use { client ->
                assertTrue(peer.opened.await(10, TimeUnit.SECONDS))
                block(peer, peer.sent.first { it.type == ForwardFrames.OPEN }.stream, client)
            }
        } finally {
            f.stop()
        }
    }

    @Test
    fun malformedFramesAreDroppedWithoutThrowing() = withStream { peer, id, _ ->
        val events = peer.events!!
        val frames = listOf(
            ByteArray(0), ByteArray(4), byteArrayOf(0, 0, 0, 0, 1), byteArrayOf(9, 0, 0, 0, 1), byteArrayOf(-1, -1, -1, -1, -1),
            ForwardFrames.encode(ForwardFrames.WINDOW, id), // no amount
            ForwardFrames.encode(ForwardFrames.WINDOW, id, ByteArray(3)),
            ForwardFrames.encode(ForwardFrames.WINDOW, id, ByteArray(5)),
            ForwardFrames.encode(ForwardFrames.WINDOW, id, byteArrayOf(-1, -1, -1, -1)), // 4 GiB of credit
            ForwardFrames.encode(ForwardFrames.WINDOW, id, byteArrayOf(127, -1, -1, -1)),
            ForwardFrames.encode(ForwardFrames.WINDOW, id, byteArrayOf(127, -1, -1, -1)), // would overflow the credit counter
            ForwardFrames.encode(ForwardFrames.WINDOW, id, byteArrayOf(127, -1, -1, -1)),
            ForwardFrames.encode(ForwardFrames.DATA, 0x7fffffff, ByteArray(10)), // unknown streams
            ForwardFrames.encode(ForwardFrames.DATA, -1, ByteArray(10)),
            ForwardFrames.encode(ForwardFrames.OPENED, id, ByteArray(100)),
            ForwardFrames.encode(ForwardFrames.OPEN, id),
            ForwardFrames.encode(ForwardFrames.DATA, id), // empty data is not the end of the stream
            ForwardFrames.encode(ForwardFrames.RESET, id, byteArrayOf(-1, -2, 0xC3.toByte())), // not ASCII / broken UTF-8
            ForwardFrames.encode(ForwardFrames.RESET, id, "refused".toByteArray()), // already gone
            ForwardFrames.encode(ForwardFrames.FIN, id),
        )
        for (frame in frames) {
            try {
                events.onFrame(frame)
            } catch (t: Throwable) {
                throw AssertionError("frame ${frame.take(9)} threw $t", t)
            }
        }
        assertNull(ForwardFrames.decode(ByteArray(4)))
        assertNull(ForwardFrames.decode(byteArrayOf(0, 0, 0, 0, 1)))
        assertNull(ForwardFrames.u32(ByteArray(3)))
        assertEquals(0xffffffffL, ForwardFrames.u32(byteArrayOf(-1, -1, -1, -1)))
    }

    /** Credit beyond the window is not credit: the phone never has more than one window outstanding. */
    @Test
    fun aPeerCannotGrantMoreThanTheWindow() = withStream { peer, id, client ->
        val events = peer.events!!
        repeat(3) { events.onFrame(ForwardFrames.encode(ForwardFrames.WINDOW, id, byteArrayOf(127, -1, -1, -1))) }
        // Never acknowledged by the peer: the phone stops within one window.
        val out = client.getOutputStream()
        val chunk = ByteArray(ForwardFrames.MAX_DATA)
        val writer = Thread {
            runCatching { repeat(3 * ForwardFrames.INITIAL_WINDOW / chunk.size) { out.write(chunk) } }
        }.apply { isDaemon = true; start() }
        fun sentBytes() = peer.sent.filter { it.type == ForwardFrames.DATA && it.stream == id }.sumOf { it.payload.size }
        waitFor("a window of data") { sentBytes() > ForwardFrames.INITIAL_WINDOW - ForwardFrames.MAX_DATA }
        Thread.sleep(300)
        assertTrue("sent ${sentBytes()}", sentBytes() <= ForwardFrames.INITIAL_WINDOW)
        writer.interrupt()
    }

    /** A peer that ignores the window is cut off instead of filling the phone's memory. */
    @Test
    fun aPeerThatIgnoresTheWindowIsReset() = withStream { peer, id, _ ->
        val events = peer.events!!
        val chunk = ForwardFrames.encode(ForwardFrames.DATA, id, ByteArray(ForwardFrames.MAX_DATA))
        // The local app never reads: 32 MiB is far beyond the window and any socket buffer.
        repeat(1024) { events.onFrame(chunk) }
        waitFor("RESET window") { peer.sent.any { it.type == ForwardFrames.RESET && it.stream == id && String(it.payload, Charsets.US_ASCII) == "window" } }
    }
}
