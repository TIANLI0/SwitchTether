package io.github.tianli0.switchtether.remote

import io.github.tianli0.switchtether.remote.files.TextDiff
import io.github.tianli0.switchtether.remote.files.UploadPlan
import io.github.tianli0.switchtether.remote.files.copyPathFor
import io.github.tianli0.switchtether.remote.notify.NotificationEngine
import io.github.tianli0.switchtether.remote.notify.NotifyPrefs
import io.github.tianli0.switchtether.remote.notify.NotifyType
import io.github.tianli0.switchtether.remote.protocol.AgentEvent
import io.github.tianli0.switchtether.remote.protocol.ProtocolJson
import io.github.tianli0.switchtether.remote.protocol.SessionSummary
import io.github.tianli0.switchtether.remote.terminal.KeyEncoder
import io.github.tianli0.switchtether.remote.terminal.TermFrame
import io.github.tianli0.switchtether.remote.terminal.TermKey
import io.github.tianli0.switchtether.remote.terminal.TermPalette
import io.github.tianli0.switchtether.remote.terminal.TermScreen
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalReducerTest {
    private val snapshot = """{"type":"snapshot","version":5,"cols":10,"rows":3,"cursor":{"x":2,"y":1,"visible":true},
        "lines":[{"runs":[{"t":"PS> "}]},{"runs":[{"t":"hi","fg":"2","b":true}]},{"runs":[]}],"title":"pwsh",
        "altScreen":false,"applicationCursor":false,"bracketedPaste":true,"exited":false,"exitCode":null}"""

    @Test
    fun snapshotThenPatchThenExit() {
        var s = TermScreen().apply(TermFrame.parse(snapshot))
        assertEquals(5L, s.version)
        assertEquals(3, s.lines.size)
        assertEquals("hi", s.lines[1].text)
        assertTrue(s.flags.bracketedPaste)
        assertTrue(s.lines[1].runs[0].b)

        s = s.apply(TermFrame.parse("""{"type":"patch","version":6,"lines":[{"y":2,"line":{"runs":[{"t":"echo hi"}]}}],"cursor":{"x":7,"y":2,"visible":true},"applicationCursor":true,"altScreen":false,"bracketedPaste":true,"exited":false}"""))
        assertEquals("echo hi", s.lines[2].text)
        assertEquals("hi", s.lines[1].text) // untouched rows keep their content
        assertEquals(7, s.cursor.x)
        assertTrue(s.flags.applicationCursor)

        // a stale patch (older version) is ignored
        val stale = s.apply(TermFrame.parse("""{"type":"patch","version":3,"lines":[{"y":0,"line":{"runs":[{"t":"OLD"}]}}]}"""))
        assertEquals("PS> ", stale.lines[0].text)

        // resize = new snapshot with a different geometry replaces everything
        val resized = s.apply(TermFrame.parse(snapshot.replace("\"cols\":10", "\"cols\":40")))
        assertEquals(40, resized.cols)

        s = s.apply(TermFrame.parse("""{"type":"exit","code":0}"""))
        assertTrue(s.exited)
        assertEquals(0, s.flags.exitCode)
        assertEquals("PS>\nhi\necho hi", s.plainText())
        assertTrue(TermFrame.parse("""{"type":"error","code":"step_up_required"}""") is TermFrame.Error)
    }

    @Test
    fun keyEncoding() {
        assertEquals("\u0003", KeyEncoder.text("c", ctrl = true))
        assertEquals("\u0003", KeyEncoder.text("C", ctrl = true))
        assertEquals("\u001bx", KeyEncoder.text("x", alt = true))
        assertEquals("ls\r", KeyEncoder.text("ls\n"))
        assertEquals("\u001b[A", KeyEncoder.key(TermKey.UP, applicationCursor = false))
        assertEquals("\u001bOA", KeyEncoder.key(TermKey.UP, applicationCursor = true))
        assertEquals("\u001bOH", KeyEncoder.key(TermKey.HOME, applicationCursor = true))
        assertEquals("\u001b[1;5C", KeyEncoder.key(TermKey.RIGHT, applicationCursor = true, ctrl = true))
        assertEquals("\u001b[5~", KeyEncoder.key(TermKey.PAGE_UP, false))
        assertEquals("\t", KeyEncoder.key(TermKey.TAB, false))
        assertEquals("\u001b", KeyEncoder.key(TermKey.ESC, false))
        assertEquals("\u007f", KeyEncoder.key(TermKey.BACKSPACE, false))
        assertEquals("\u001b[200~a\rb\u001b[201~", KeyEncoder.paste("a\nb", bracketed = true))
        assertEquals("a\rb", KeyEncoder.paste("a\r\nb", bracketed = false))
    }

    @Test
    fun palette() {
        assertEquals(0xFF0DBC79, TermPalette.argb("2"))
        assertEquals(0xFF112233, TermPalette.argb("#112233"))
        assertEquals(0xFFFF0000, TermPalette.argb("196"))
        assertEquals(0xFF080808, TermPalette.argb("232"))
        assertNull(TermPalette.argb(null))
    }
}

class FileOpsTest {
    @Test
    fun unifiedDiffOfConflict() {
        val d = TextDiff.unified("a\nb\nc\nd", "a\nB\nc\nd\ne")
        assertTrue(d.startsWith("--- a/desktop\n+++ b/mine\n@@ -1,4 +1,5 @@"))
        assertTrue(d.contains("\n-b\n+B\n"))
        assertTrue(d.contains("\n+e\n"))
        assertEquals("", TextDiff.unified("same", "same"))
    }

    @Test
    fun uploadChunks() {
        assertEquals(listOf(0L to 4L, 4L to 4L, 8L to 2L), UploadPlan.chunks(10, 4))
        assertEquals(listOf(8L to 2L), UploadPlan.chunks(10, 4, resumeFrom = 8))
        assertTrue(UploadPlan.chunks(0).isEmpty())
        assertEquals(1, UploadPlan.chunks(UploadPlan.DEFAULT_CHUNK).size)
    }

    @Test
    fun copyPath() {
        assertEquals("D:\\p\\notes (phone copy).txt", copyPathFor("D:\\p\\notes.txt"))
        assertEquals("/a/Makefile (phone copy)", copyPathFor("/a/Makefile"))
    }
}

class NotificationRulesTest {
    private fun ev(id: String, seq: Long, kind: String, data: String, session: String = "managed:1") =
        AgentEvent(id, seq, session, kind = kind, data = ProtocolJson.parseToJsonElement(data).jsonObject)

    private val session = SessionSummary(id = "managed:1", agent = "claude_code", project = "app", title = "Fix bug", status = "busy")

    @Test
    fun approvalsAreNotifiedOnceAndRedactedOnLockScreen() {
        val e = NotificationEngine({ NotifyPrefs() }, { 12 * 60 })
        val n = e.onSessionEvent(ev("a1", 3, "approval_request", """{"requestId":"r1","title":"Run rm -rf build","resolved":false}"""), session)
        assertNotNull(n)
        assertEquals(NotifyType.WAITING_APPROVAL, n!!.type)
        assertEquals("managed:1", n.sessionId)
        assertTrue(n.body.contains("rm -rf"))
        assertFalse(n.publicBody.contains("rm -rf"))
        // same request updated in place → no duplicate; resolved → nothing
        assertNull(e.onSessionEvent(ev("a1", 3, "approval_request", """{"requestId":"r1","title":"Run rm -rf build","resolved":false}"""), session))
        assertNull(e.onSessionEvent(ev("a2", 4, "approval_request", """{"requestId":"r2","resolved":true}"""), session))
    }

    @Test
    fun quietHoursWrapMidnightAndMakeSilent() {
        val p = NotifyPrefs(quietHoursEnabled = true, quietStartMin = 22 * 60, quietEndMin = 7 * 60)
        assertTrue(p.inQuietHours(23 * 60))
        assertTrue(p.inQuietHours(3 * 60))
        assertFalse(p.inQuietHours(12 * 60))
        val e = NotificationEngine({ p }, { 23 * 60 + 30 })
        val n = e.onSessionEvent(ev("q", 1, "question", """{"requestId":"q1","questions":[]}"""), session)
        assertTrue(n!!.silent)
    }

    @Test
    fun perProjectAndAgentMutesAndDisabledTypes() {
        val muted = NotificationEngine({ NotifyPrefs(mutedProjects = setOf("app")) }, { 0 })
        assertNull(muted.onSessionEvent(ev("a", 1, "approval_request", """{"requestId":"r"}"""), session))
        val agentMuted = NotificationEngine({ NotifyPrefs(mutedAgents = setOf("claude_code")) }, { 0 })
        assertNull(agentMuted.onSessionEvent(ev("a", 1, "approval_request", """{"requestId":"r"}"""), session))
        val toolsOff = NotificationEngine({ NotifyPrefs() }, { 0 }) // TOOL_CALL is off by default
        assertNull(toolsOff.onSessionEvent(ev("t", 1, "tool_call", """{"status":"running","name":"Bash"}"""), session))
    }

    @Test
    fun statusTransitionsAndUpserts() {
        val e = NotificationEngine({ NotifyPrefs(enabled = NotifyType.entries.toSet()) }, { 0 })
        assertEquals(NotifyType.TASK_STARTED, e.onSessionEvent(ev("s1", 1, "status", """{"state":"running"}"""), session)?.type)
        assertNull(e.onSessionEvent(ev("s2", 2, "status", """{"state":"running"}"""), session)) // no change
        assertEquals(NotifyType.TASK_COMPLETED, e.onSessionEvent(ev("s3", 3, "status", """{"state":"idle"}"""), session)?.type)
        assertEquals(NotifyType.TASK_FAILED, e.onSessionEvent(ev("s4", 4, "status", """{"state":"error"}"""), session)?.type)
        val waiting = e.onSessionUpsert(session.copy(status = "waiting", waitingFor = "permission prompt"))
        assertEquals(NotifyType.WAITING_APPROVAL, waiting?.type)
        assertNull(e.onSessionUpsert(session.copy(status = "waiting", waitingFor = "permission prompt")))
    }

    @Test
    fun longRunningReminderOnce() {
        var now = 0L
        val e = NotificationEngine({ NotifyPrefs(longRunningMinutes = 30) }, { 0 }, { now })
        e.onSessionUpsert(session)
        now = 31 * 60_000L
        assertEquals(1, e.longRunning(listOf(session)).size)
        assertEquals(0, e.longRunning(listOf(session)).size)
    }
}
