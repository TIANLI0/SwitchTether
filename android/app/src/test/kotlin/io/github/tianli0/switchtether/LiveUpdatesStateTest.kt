package io.github.tianli0.switchtether

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.tianli0.switchtether.data.PairedDesktop
import io.github.tianli0.switchtether.data.PairingStore
import io.github.tianli0.switchtether.notify.LiveActivityChannel
import io.github.tianli0.switchtether.notify.LiveUpdates
import io.github.tianli0.switchtether.remote.command.CommandOutcome
import io.github.tianli0.switchtether.remote.live.LiveUpdateSpec
import io.github.tianli0.switchtether.remote.protocol.AgentEvent
import io.github.tianli0.switchtether.remote.protocol.ProtocolJson
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What Live Updates remember: the persisted pin set (written in the order it changed), pins restored
 * after a restart, and per-session bookkeeping that must not outlive its session or its computer.
 */
@RunWith(AndroidJUnit4::class)
class LiveUpdatesStateTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs = context.getSharedPreferences("live_pins", Context.MODE_PRIVATE)
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private val store = object : PairingStore {
        override val desktop = MutableStateFlow<PairedDesktop?>(TEST_DESKTOP)
        override suspend fun current(): PairedDesktop? = desktop.value
        override suspend fun save(desktop: PairedDesktop) = Unit
        override suspend fun clear() = Unit
    }

    private val posts = mutableListOf<LiveUpdateSpec>()
    private val channel = object : LiveActivityChannel {
        override fun update(spec: LiveUpdateSpec, agent: String, subtitle: String?) { posts += spec }
        override fun end(sessionId: String) = Unit
        override fun canPromote() = true
    }

    /** Runs what was launched only when told to, in the order asked for (the app scope gives no order). */
    private class QueueDispatcher : CoroutineDispatcher() {
        private val queue = ArrayList<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { synchronized(queue) { queue += block } }
        fun drain(reversed: Boolean = false) {
            while (true) {
                val batch = synchronized(queue) { queue.toList().also { queue.clear() } }
                if (batch.isEmpty()) return
                (if (reversed) batch.reversed() else batch).forEach { it.run() }
            }
        }
    }

    private fun live(dispatcher: CoroutineDispatcher = Dispatchers.Unconfined): LiveUpdates {
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + dispatcher)
        return LiveUpdates(
            context, scope, FakeConnection(), store, channel,
            resolveApproval = { _, _, _, id -> CommandOutcome.NotSent(id, IllegalStateException("test")) },
            enabled = { true },
        ).also { it.start() }
    }

    private fun ev(sid: String, kind: String, data: String, id: String = kind) =
        AgentEvent(id, 1, sid, kind = kind, data = ProtocolJson.parseToJsonElement(data).jsonObject)

    @Before
    fun setUp() {
        prefs.edit().clear().commit()
    }

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun thePinSetIsStoredInTheOrderItChanged() {
        // Each change was written by its own coroutine: two quick pins could be stored out of order
        // and the older set restored after a restart.
        val queue = QueueDispatcher()
        val live = live(queue)
        queue.drain()
        val a = summary(id = "managed:a", control = "full", status = "busy")
        val b = summary(id = "managed:b", control = "full", status = "busy")
        live.pin(a, manual = true)
        live.pin(b, manual = true)
        queue.drain(reversed = true)
        assertEquals(setOf(a.id, b.id), prefs.getStringSet("pins:${TEST_DESKTOP.serverId}", emptySet()))
    }

    @Test
    fun aRestoredPinThatWasDroppedShowsAtOnceWhenPinnedAgain() {
        val s = summary(id = "managed:restored", control = "full", status = "busy")
        prefs.edit().putStringSet("pins:${TEST_DESKTOP.serverId}", setOf(s.id)).commit()
        val live = live()
        assertTrue(live.isPinned(s.id))
        assertFalse("not shown before a summary names it", live.covers(s.id))
        // Rotated out, or the computer no longer knows it (404), before its first summary.
        live.unpin(s.id, userInitiated = false)
        assertTrue(posts.isEmpty())

        assertTrue(live.pin(s, manual = true))
        assertTrue("the new pin posts its card", posts.isNotEmpty())
        assertTrue(live.covers(s.id))
    }

    @Test
    fun oneConversationKeepsOneCardTheControllableOne() {
        // The same VS Code conversation, first seen as observed, then taken over under another id
        // (or re-keyed after the desktop restarted): it had two cards taking turns at the top.
        val live = live()
        val observed = summary(id = "claude:conv-1", control = "observe", status = "busy").copy(nativeId = "conv-1")
        live.pin(observed, manual = false)
        assertTrue(live.isPinned(observed.id))
        val attached = summary(id = "attached:abc", control = "full", status = "busy").copy(nativeId = "conv-1")
        live.onStreamUpsert(attached)
        assertFalse("the observed twin's card is withdrawn", live.isPinned(observed.id))
        assertTrue("the taken-over session carries the card", live.isPinned(attached.id))

        // The observed id keeps streaming: it must not take a card back.
        live.onStreamUpsert(observed)
        assertFalse(live.isPinned(observed.id))
        assertTrue(live.isPinned(attached.id))
    }

    @Test
    fun anObservedTwinNeverPinsNextToTheTakenOverSession() {
        val live = live()
        val attached = summary(id = "attached:abc", control = "full", status = "busy").copy(nativeId = "conv-2")
        live.pin(attached, manual = false)
        live.onStreamUpsert(summary(id = "claude:conv-2", control = "observe", status = "busy").copy(nativeId = "conv-2"))
        assertEquals(setOf(attached.id), live.pins.value)
    }

    @Test
    fun nothingIsKeptForARemovedSession() {
        val live = live()
        val s = summary(id = "managed:gone", control = "full", status = "busy")
        live.pin(s, manual = false)
        live.onStreamEvent(ev(s.id, "tool_call", """{"status":"running","name":"Read","callId":"c1"}"""))
        live.onStreamEvent(ev(s.id, "status", """{"state":"idle"}"""))
        // A session that is not followed streams too.
        live.onStreamEvent(ev("managed:other", "status", """{"state":"running"}"""))
        assertEquals(setOf(s.id, "managed:other"), live.trackedSessions())

        live.onStreamRemoved(s.id)
        live.onStreamRemoved("managed:other")
        assertFalse(live.isPinned(s.id))
        assertEquals(emptySet<String>(), live.trackedSessions())
    }

    @Test
    fun nothingOfThePreviousComputerIsKept() {
        val live = live()
        val s = summary(id = "managed:old-pc", control = "full", status = "busy")
        live.pin(s, manual = false)
        live.onStreamEvent(ev(s.id, "tool_call", """{"status":"running","name":"Bash","callId":"b1","background":true}"""))
        live.onStreamEvent(ev(s.id, "status", """{"state":"idle"}"""))
        live.onStreamEvent(ev(s.id, "tool_call", """{"status":"completed","name":"Bash","callId":"b1","background":true}"""))
        assertTrue(live.trackedSessions().isNotEmpty())

        store.desktop.value = TEST_DESKTOP.copy(serverId = "srv-2", name = "DESKTOP-02")
        assertFalse(live.isPinned(s.id))
        assertEquals(emptySet<String>(), live.trackedSessions())

        // A pin made on the new computer is stored under it, like the other sets.
        val t = summary(id = "managed:new-pc", control = "full", status = "busy")
        live.pin(t, manual = true)
        assertEquals(setOf(t.id), prefs.getStringSet("pins:srv-2", emptySet()))
        assertEquals(setOf(t.id), prefs.getStringSet("manual:srv-2", emptySet()))
        assertEquals(setOf(s.id), prefs.getStringSet("pins:${TEST_DESKTOP.serverId}", emptySet()))
    }
}
