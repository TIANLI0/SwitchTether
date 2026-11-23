package io.github.tianli0.switchtether

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.tianli0.switchtether.data.PairedDesktop
import io.github.tianli0.switchtether.data.PairingStore
import io.github.tianli0.switchtether.notify.LiveActivityChannel
import io.github.tianli0.switchtether.notify.LiveUpdates
import io.github.tianli0.switchtether.notify.Notifier
import io.github.tianli0.switchtether.remote.command.CommandOutcome
import io.github.tianli0.switchtether.remote.live.LivePhase
import io.github.tianli0.switchtether.remote.live.LiveUpdateSpec
import io.github.tianli0.switchtether.remote.notify.NotifyIntent
import io.github.tianli0.switchtether.remote.notify.NotifyType
import io.github.tianli0.switchtether.remote.protocol.AgentEvent
import io.github.tianli0.switchtether.remote.protocol.ProtocolJson
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Live Update cards and notifications must never move backwards in time or reshuffle needlessly. */
@RunWith(AndroidJUnit4::class)
class LiveUpdatesOrderingTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private val store = object : PairingStore {
        override val desktop = MutableStateFlow<PairedDesktop?>(TEST_DESKTOP)
        override suspend fun current(): PairedDesktop? = desktop.value
        override suspend fun save(desktop: PairedDesktop) = Unit
        override suspend fun clear() = Unit
    }

    private val posts: MutableList<LiveUpdateSpec> = Collections.synchronizedList(mutableListOf())
    private val inUpdate = AtomicInteger()
    private val overlaps = AtomicInteger()

    private val ended: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val released: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private val channel = object : LiveActivityChannel {
        override fun update(spec: LiveUpdateSpec, agent: String, subtitle: String?) {
            if (inUpdate.incrementAndGet() > 1) overlaps.incrementAndGet()
            Thread.sleep(0, 200_000)
            posts += spec
            inUpdate.decrementAndGet()
        }
        override fun end(sessionId: String) { ended += sessionId }
        override fun release(sessionId: String) { released += sessionId }
        override fun canPromote() = true
    }

    /** Short timers so lifecycle tests run in real time. */
    private val fast = LiveUpdates.Timing(headlineMs = 50, detailMs = 150, offlineGraceMs = 100, settleMs = 150, bgSettleGraceMs = 150, bgFollowMaxMs = 60_000)

    private fun live(dispatcher: CoroutineDispatcher = Dispatchers.Unconfined, timing: LiveUpdates.Timing = fast, alertOnFinish: Boolean = false): LiveUpdates {
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + dispatcher)
        context.getSharedPreferences("live_pins", Context.MODE_PRIVATE).edit().clear().commit()
        return LiveUpdates(
            context, scope, FakeConnection(), store, channel,
            resolveApproval = { _, _, _, id -> CommandOutcome.NotSent(id, IllegalStateException("test")) },
            enabled = { true },
            alertOnFinish = { alertOnFinish },
            timing = timing,
        )
    }

    private fun waitUntil(ms: Long = 5_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < deadline && !cond()) Thread.sleep(20)
        assertTrue("condition not met in time", cond())
    }

    private fun ev(sid: String, kind: String, data: String, id: String = kind) =
        AgentEvent(id, 1, sid, kind = kind, data = ProtocolJson.parseToJsonElement(data).jsonObject)

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun aRestReadThatRacedTheStreamDoesNotTurnAFinishedCardBackToRunning() {
        val live = live()
        val s = summary(id = "managed:race", control = "full", status = "busy")
        assertTrue(live.pin(s, manual = true))
        assertEquals(LivePhase.RUNNING, posts.last().phase)

        // The stream says the turn finished while the reconnect's REST read was in flight…
        live.onStreamEvent(ev(s.id, "status", """{"state":"completed"}"""))
        assertEquals(LivePhase.DONE, posts.last().phase)

        // …then the read returns its older picture: still busy, with a request that is long answered.
        val pending = listOf(ev(s.id, "approval_request", """{"requestId":"r1","title":"Run: rm","resolved":false}""", id = "r1"))
        live.applyRefresh(s.id, s.copy(status = "busy"), pending, streamMovedSince = true)

        assertEquals(LivePhase.DONE, posts.last().phase)
        val afterDone = posts.dropWhile { it.phase != LivePhase.DONE }
        assertTrue("no RUNNING / WAITING card after DONE: ${afterDone.map { it.phase }}", afterDone.all { it.phase == LivePhase.DONE })
    }

    @Test
    fun aRequestResolvedWhileTheReadWasInFlightIsNotReopened() {
        val live = live()
        val s = summary(id = "managed:resolved", control = "full", status = "busy")
        live.pin(s, manual = true)
        live.onStreamEvent(ev(s.id, "approval_request", """{"requestId":"r1","title":"Run: ls","resolved":false}""", id = "r1"))
        assertEquals(LivePhase.WAITING_APPROVAL, posts.last().phase)
        live.onStreamEvent(ev(s.id, "approval_request", """{"requestId":"r1","title":"Run: ls","resolved":true}""", id = "r1"))
        assertEquals(LivePhase.RUNNING, posts.last().phase)

        val stale = listOf(ev(s.id, "approval_request", """{"requestId":"r1","title":"Run: ls","resolved":false}""", id = "r1"))
        live.applyRefresh(s.id, s, stale, streamMovedSince = true)
        assertEquals(LivePhase.RUNNING, posts.last().phase)
    }

    @Test
    fun parallelInputsAreNeitherLostNorPostedOutOfOrder() {
        // The app scope is multi-threaded; mutate() was an unguarded read-modify-write and post jobs
        // ran in parallel, so steps were lost and an older snapshot could be posted last.
        val live = live(Dispatchers.Default)
        val s = summary(id = "managed:parallel", control = "full", status = "busy")
        live.pin(s, manual = true)
        val threads = 8
        val perThread = 50
        val start = CountDownLatch(1)
        val workers = (0 until threads).map { t ->
            Thread {
                start.await()
                repeat(perThread) { i -> live.onStreamEvent(ev(s.id, "tool_call", """{"status":"running","name":"Bash"}""", id = "t$t-$i")) }
            }.apply { start() }
        }
        start.countDown()
        workers.forEach { it.join() }
        val total = (threads * perThread).toString()
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline && posts.lastOrNull()?.progressNote?.contains(total) != true) Thread.sleep(50)
        assertTrue("last card shows all $total steps: ${posts.lastOrNull()?.progressNote}", posts.last().progressNote.contains(total))
        assertEquals("channel updates never overlap", 0, overlaps.get())
    }

    @Test
    fun aNotificationKeepsItsTimeWhenTheSameMomentIsUpdated() {
        val n = NotifyIntent(NotifyType.TASK_COMPLETED, "done:1", "s", "Fix finished", "", "Agent activity", silent = false, atMs = 1_000L)
        assertEquals(1_000L, Notifier.notificationWhen(n, now = 5_000, previous = null))
        // Computer clock ahead: never in the future.
        assertEquals(5_000L, Notifier.notificationWhen(n.copy(atMs = 9_000), now = 5_000, previous = null))
        // Unknown time: now.
        assertEquals(5_000L, Notifier.notificationWhen(n.copy(atMs = null), now = 5_000, previous = null))
        // The same moment updated quietly in place (alertOnce) keeps the time already shown.
        assertEquals(1_000L, Notifier.notificationWhen(n.copy(atMs = null, alertOnce = true), now = 7_000, previous = 1_000))
        // A new moment in the same slot moves up.
        assertEquals(7_000L, Notifier.notificationWhen(n.copy(atMs = 7_000), now = 8_000, previous = 1_000))
    }

    @Test
    fun nothingIsPostedWhenNothingVisibleChanged() {
        val live = live(timing = fast.copy(headlineMs = 10_000, detailMs = 10_000))
        val s = summary(id = "managed:quiet", control = "full", status = "busy")
        live.pin(s, manual = true)
        live.onStreamEvent(ev(s.id, "tool_call", """{"status":"running","name":"Bash","callId":"c1","input":{"command":"npm test"}}"""))
        val before = posts.size
        // The same row again, a status that changes nothing: no post (Fluid Cloud would animate).
        repeat(5) { live.onStreamEvent(ev(s.id, "tool_call", """{"status":"running","name":"Bash","callId":"c1","input":{"command":"npm test"}}""")) }
        live.onStreamEvent(ev(s.id, "status", """{"state":"running"}"""))
        Thread.sleep(100)
        assertEquals(before, posts.size)
    }

    @Test
    fun aFinishedTurnSettlesIntoAResultAndFreesTheSlot() {
        val live = live(alertOnFinish = true)
        val s = summary(id = "managed:settle", control = "full", status = "busy")
        live.pin(s, manual = false)
        live.onStreamEvent(ev(s.id, "tool_call", """{"status":"running","name":"Read","callId":"c1"}"""))
        live.onStreamEvent(ev(s.id, "status", """{"state":"idle"}"""))
        val done = posts.last()
        assertEquals(LivePhase.DONE, done.phase)
        assertTrue(done.promoted)
        assertTrue("the outcome sounds once", done.alert)
        waitUntil { !live.isPinned(s.id) }
        val result = posts.last()
        assertTrue(result.dismissible)
        assertTrue(!result.promoted)
        assertTrue("sounded only once", !result.alert)
        assertTrue("the card stays up", s.id !in ended)
        assertTrue(s.id in released)
        // Opening the session in the app clears it.
        live.onSessionOpened(s.id)
        assertTrue(s.id in ended)
    }

    @Test
    fun backgroundWorkKeepsTheCardQuietlyUntilItEnds() {
        val live = live()
        val s = summary(id = "managed:bg", control = "full", status = "busy")
        live.pin(s, manual = false)
        live.onStreamEvent(ev(s.id, "tool_call", """{"status":"running","name":"Bash","callId":"b1","background":true}"""))
        live.onStreamEvent(ev(s.id, "status", """{"state":"idle"}"""))
        assertTrue(posts.last().promoted)
        // Demoted after the outcome was shown, but still followed.
        waitUntil { !posts.last().promoted }
        Thread.sleep(300)
        assertTrue(live.isPinned(s.id))
        assertTrue(!posts.last().dismissible)
        // The background shell reports back: settles after the grace.
        live.onStreamEvent(ev(s.id, "tool_call", """{"status":"completed","name":"Bash","callId":"b1","background":true}"""))
        waitUntil { !live.isPinned(s.id) }
        assertTrue(posts.last().dismissible)
    }

    @Test
    fun theAgentWakingUpForABackgroundResultContinuesOnTheSameCard() {
        val live = live()
        val s = summary(id = "managed:wake", control = "full", status = "busy")
        live.pin(s, manual = false)
        live.onStreamEvent(ev(s.id, "tool_call", """{"status":"running","name":"Bash","callId":"b1","background":true}"""))
        live.onStreamEvent(ev(s.id, "status", """{"state":"idle"}"""))
        Thread.sleep(300)
        live.onStreamEvent(ev(s.id, "tool_call", """{"status":"completed","name":"Bash","callId":"b1","background":true}"""))
        live.onStreamEvent(ev(s.id, "status", """{"state":"running"}"""))
        assertEquals(LivePhase.RUNNING, posts.last().phase)
        assertTrue(posts.last().promoted)
        Thread.sleep(400)
        assertTrue("still followed while running", live.isPinned(s.id))
    }

    @Test
    fun anExitSettlesAtOnce() {
        val live = live()
        val s = summary(id = "managed:exit", control = "full", status = "busy")
        live.pin(s, manual = false)
        live.onStreamUpsert(s.copy(liveness = "ended"))
        assertTrue(!live.isPinned(s.id))
        assertEquals(LivePhase.ENDED, posts.last().phase)
        assertTrue(posts.last().dismissible)
    }
}
