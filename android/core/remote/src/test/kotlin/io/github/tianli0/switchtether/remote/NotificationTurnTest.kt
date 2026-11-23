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
    @Test
    fun historicalReplayAndChildOutcomesDoNotFinishTheParent() {
        val e = engine()
        val oldRunning = ev("old-running", 1, "status", """{"state":"running"}""").copy(ts = java.time.Instant.ofEpochMilli(now - 10_000).toString())
        assertNull(e.onSessionEvent(oldRunning, S))
        assertNull(e.onSessionEvent(oldRunning.copy(id = "old-done", data = ProtocolJson.parseToJsonElement("""{"state":"idle"}""").jsonObject), S))
        assertNull(e.onSessionUpsert(S.copy(liveness = "ended", status = "busy")))
        assertNull(e.onSessionUpsert(S.copy(liveness = "ended", status = "idle")))
        e.onSessionUpsert(S.copy(status = "busy"))
        assertNull(e.onSessionEvent(ev("child", 2, "status", """{"state":"completed"}""").copy(parentId = "spawn"), S))
        assertEquals(NotifyType.TASK_COMPLETED, e.onSessionUpsert(S.copy(status = "idle"))?.type)
    }


    @Test
    fun oneWaitAlertsOnceWhicheverSourceComesFirst() {
        // Request event first: the status change for the same wait stays quiet.
        val e = engine()
        val req = e.onSessionEvent(ev("a1", 1, "approval_request", """{"requestId":"r1","title":"Run npm test"}"""), S)
        assertEquals(NotifyType.WAITING_APPROVAL, req?.type)
        assertEquals("r1", req!!.requestId)
        assertEquals("Run npm test", req.body)
        assertEquals(false, req.alertOnce)
        assertNull(e.onSessionUpsert(S.copy(status = "waiting", waitingFor = "permission prompt")))

        // Status change first: the request event updates it with the details, without a second alert.
        val f = engine()
        val generic = f.onSessionUpsert(S.copy(status = "waiting", waitingFor = "permission prompt"))
        assertEquals(NotifyType.WAITING_APPROVAL, generic?.type)
        assertNull(generic!!.requestId)
        val detailed = f.onSessionEvent(ev("a2", 1, "approval_request", """{"requestId":"r2","title":"Edit main.rs"}"""), S)
        assertEquals("r2", detailed?.requestId)
        assertEquals(true, detailed!!.alertOnce)

        // The wait ends; the next wait is announced again.
        f.onSessionUpsert(S.copy(status = "busy"))
        val next = f.onSessionUpsert(S.copy(status = "waiting", waitingFor = "permission prompt"))
        assertEquals(NotifyType.WAITING_APPROVAL, next?.type)
        assertEquals(false, next!!.alertOnce)
    }

    @Test
    fun aSingleChoiceQuestionIsAnswerableFromTheNotification() {
        val e = engine()
        val q = e.onSessionEvent(
            ev(
                "q1", 1, "question",
                """{"requestId":"q-1","questions":[{"id":"target","question":"Which environment?","multiSelect":false,"allowOther":true,
                   "options":[{"label":"Staging","value":"stg"},{"label":"Production"}]}]}""",
            ),
            S,
        )
        assertEquals("Which environment?", q?.body)
        val p = q!!.prompt!!
        assertEquals("q-1", p.requestId)
        assertEquals("target", p.key)
        assertEquals(listOf("Staging", "Production"), p.labels)
        assertEquals("stg", p.valueFor("Staging"))
        assertEquals("Production", p.valueFor("Production"))
        assertEquals("eu-west", p.valueFor("eu-west"))
        assertEquals(true, p.freeText)

        // Several questions, multi-select or secret: shown, but answered in the app.
        val several = e.onSessionEvent(ev("q2", 2, "question", """{"requestId":"q-2","questions":[{"question":"A?"},{"question":"B?"}]}"""), S)
        assertEquals("A?  ·  B?", several?.body)
        assertNull(several!!.prompt)
        val multi = e.onSessionEvent(ev("q3", 3, "question", """{"requestId":"q-3","questions":[{"question":"Pick","multiSelect":true,"options":[{"label":"x"}]}]}"""), S)
        assertNull(multi!!.prompt)
        val secret = e.onSessionEvent(ev("q4", 4, "question", """{"requestId":"q-4","questions":[{"question":"Token?","isSecret":true}]}"""), S)
        assertNull(secret!!.prompt)
    }

    @Test
    fun replyIsOfferedOnlyForSessionsThePhoneControls() {
        val e = engine()
        e.onSessionUpsert(S.copy(status = "busy"))
        assertEquals(true, e.onSessionUpsert(S.copy(status = "idle"))?.replyable)
        val observed = S.copy(id = "claude:obs", control = "observe", managed = false)
        e.onSessionUpsert(observed.copy(status = "busy"))
        val done = e.onSessionUpsert(observed.copy(status = "idle"))
        assertEquals(NotifyType.TASK_COMPLETED, done?.type)
        assertEquals(false, done!!.replyable)
    }
}
