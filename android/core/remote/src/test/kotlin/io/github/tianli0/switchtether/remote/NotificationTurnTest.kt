package io.github.tianli0.switchtether.remote

import io.github.tianli0.switchtether.remote.notify.LocalAction
import io.github.tianli0.switchtether.remote.notify.NotificationEngine
import io.github.tianli0.switchtether.remote.notify.NotifyPrefs
import io.github.tianli0.switchtether.remote.notify.NotifySlot
import io.github.tianli0.switchtether.remote.notify.NotifyType
import io.github.tianli0.switchtether.remote.notify.slot
import io.github.tianli0.switchtether.remote.protocol.AgentEvent
import io.github.tianli0.switchtether.remote.protocol.ProtocolJson
import io.github.tianli0.switchtether.remote.protocol.SessionSummary
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Phase E: one notification per turn outcome, whatever mix of sources reports it. */
class NotificationTurnTest {
    private var now = 1_000_000L
    private fun engine(prefs: NotifyPrefs = NotifyPrefs()) = NotificationEngine({ prefs }, { 12 * 60 }, { now })

    private fun ev(id: String, seq: Long, kind: String, data: String, session: String = S.id) =
        AgentEvent(id, seq, session, kind = kind, data = ProtocolJson.parseToJsonElement(data).jsonObject)

    private val S = SessionSummary(id = "managed:1", agent = "claude_code", project = "app", title = "Fix bug", liveness = "live", status = "idle", control = "full", managed = true)
    private val attached = SessionSummary(id = "claude:x", agent = "claude_code", origin = "vscode", title = "Mine", liveness = "live", status = "idle", control = "full")

    @Test
    fun completionIsNotifiedOnceAcrossUpsertAndStatusEvents() {
        val e = engine()
        assertNull(e.onSessionUpsert(S))
        assertNull(e.onSessionEvent(ev("st", 1, "status", """{"state":"running"}"""), S)) // TASK_STARTED is off by default
        assertNull(e.onSessionUpsert(S.copy(status = "busy")))
        val done = e.onSessionEvent(ev("st", 2, "status", """{"state":"idle"}"""), S)
        assertEquals(NotifyType.TASK_COMPLETED, done?.type)
        assertEquals(NotifySlot.STATUS, done!!.type.slot)
        assertNull(e.onSessionUpsert(S.copy(status = "idle"))) // same turn, other source
        // The next turn completes again.
        e.onSessionUpsert(S.copy(status = "busy"))
        assertEquals(NotifyType.TASK_COMPLETED, e.onSessionUpsert(S.copy(status = "idle"))?.type)
    }

    @Test
    fun idleWithoutATurnIsSilent() {
        val e = engine()
        assertNull(e.onSessionEvent(ev("st", 1, "status", """{"state":"idle"}"""), S))
        assertNull(e.onSessionUpsert(S.copy(status = "completed")))
    }

    @Test
    fun interruptsAndTurnStoppedNotifyUnlessThePhoneDidIt() {
        val e = engine()
        e.onSessionEvent(ev("st", 1, "status", """{"state":"running"}"""), S)
        assertEquals(NotifyType.SESSION_INTERRUPTED, e.onSessionEvent(ev("st", 2, "status", """{"state":"interrupted"}"""), S)?.type)

        e.onSessionEvent(ev("st", 3, "status", """{"state":"running"}"""), S)
        val stopped = e.onSessionEvent(ev("sys", 4, "system", """{"subtype":"turn_stopped","text":"Turn stopped before completion"}"""), S)
        assertEquals(NotifyType.SESSION_INTERRUPTED, stopped?.type)

        // The phone interrupted: the echo is not notified.
        e.onSessionEvent(ev("st", 5, "status", """{"state":"running"}"""), S)
        e.noteLocalAction(S.id, LocalAction.INTERRUPT)
        assertNull(e.onSessionEvent(ev("st", 6, "status", """{"state":"interrupted"}"""), S))
        // …but only for a while.
        now += NotificationEngine.LOCAL_ACTION_GRACE_MS + 1
        e.onSessionEvent(ev("st", 7, "status", """{"state":"running"}"""), S)
        assertEquals(NotifyType.SESSION_INTERRUPTED, e.onSessionEvent(ev("st", 8, "status", """{"state":"interrupted"}"""), S)?.type)
    }

    @Test
    fun errorEventAndErrorStatusOfOneTurnNotifyOnce() {
        val e = engine()
        e.onSessionUpsert(S.copy(status = "busy"))
        val first = e.onSessionEvent(ev("err", 1, "error", """{"message":"rate limited"}"""), S)
        assertEquals(NotifyType.TASK_FAILED, first?.type)
        assertEquals("rate limited", first!!.body)
        assertNull(e.onSessionEvent(ev("st", 2, "status", """{"state":"error"}"""), S))
        assertNull(e.onSessionUpsert(S.copy(status = "error")))
    }

    @Test
    fun processExitWhileWorkingIsReported() {
        val e = engine()
        e.onSessionUpsert(S)
        e.onSessionUpsert(S.copy(status = "busy"))
        assertEquals(NotifyType.SESSION_EXITED, e.onSessionUpsert(S.copy(status = "busy", liveness = "ended"))?.type)

        // Ended while idle: normal, nothing to say.
        val e2 = engine()
        e2.onSessionUpsert(S)
        assertNull(e2.onSessionUpsert(S.copy(liveness = "ended")))

        // Stopped from the phone: no echo.
        val e3 = engine()
        e3.onSessionUpsert(S.copy(status = "busy"))
        e3.noteLocalAction(S.id, LocalAction.STOP)
        assertNull(e3.onSessionUpsert(S.copy(status = "busy", liveness = "ended")))
    }

    @Test
    fun terminalExitCarriesTheTerminalAndRespectsPhoneKills() {
        val e = engine()
        e.onTerminalCreated("t1", "pwsh")
        val n = e.onTerminalExited("t1", 1)
        assertEquals(NotifyType.TERMINAL_EXITED, n?.type)
        assertEquals("t1", n!!.terminalId)
        assertEquals("Terminal pwsh exited (1)", n.title)
        e.noteLocalAction("t2", LocalAction.KILL_TERMINAL)
        assertNull(e.onTerminalExited("t2", 0))
    }

    @Test
    fun waitingStatesUseTheAttentionSlotAndCategoryToggles() {
        val e = engine()
        val w = e.onSessionUpsert(S.copy(status = "waiting", waitingFor = "permission prompt"))
        assertEquals(NotifyType.WAITING_APPROVAL, w?.type)
        assertEquals(NotifySlot.ATTENTION, w!!.type.slot)
        assertEquals(NotifyType.WAITING_INPUT, engine().onSessionUpsert(S.copy(status = "waiting", waitingFor = "input"))?.type)
        val off = engine(NotifyPrefs(enabled = NotifyType.entries.toSet() - NotifyType.WAITING_APPROVAL))
        assertNull(off.onSessionUpsert(S.copy(status = "waiting", waitingFor = "permission prompt")))
        assertNull(engine(NotifyPrefs(enabled = emptySet())).onTerminalExited("t", 0))
    }

    @Test
    fun attachedSessionsBehaveLikeManagedOnes() {
        val e = engine()
        e.onSessionUpsert(attached)
        e.onSessionUpsert(attached.copy(status = "busy"))
        val done = e.onSessionUpsert(attached.copy(status = "idle"))
        assertEquals(NotifyType.TASK_COMPLETED, done?.type)
        assertEquals("claude:x", done!!.sessionId)
        assertEquals("Mine finished", done.title)
    }
}
