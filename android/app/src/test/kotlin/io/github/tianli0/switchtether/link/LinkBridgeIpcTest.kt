package io.github.tianli0.switchtether.link

import android.content.ComponentName
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.github.tianli0.switchtether.forward.ActiveForward
import io.github.tianli0.switchtether.forward.StartResult
import io.github.tianli0.switchtether.remote.forward.TunnelStatus
import io.github.tianli0.switchtether.remote.notify.LocalAction
import io.github.tianli0.switchtether.remote.protocol.ForwardView
import io.github.tianli0.switchtether.remote.protocol.SessionSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The UI ↔ `:link` messages end to end over a real [android.os.Messenger] binding (Robolectric
 * delivers the bridge's binder to [LinkClient.bindService]): foreground state, pins with their
 * answer, forwards handed over and reported back, state pushes, and what happens when the link
 * process goes away.
 */
@RunWith(RobolectricTestRunner::class)
class LinkBridgeIpcTest {
    private class FakeHost : LinkHost {
        override val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val foreground = mutableListOf<Boolean>()
        val pinned = mutableListOf<Pair<String, Boolean>>()
        val unpinned = mutableListOf<String>()
        val opened = mutableListOf<String>()
        val local = mutableListOf<Pair<String, LocalAction>>()
        val attached = mutableListOf<Triple<ForwardView, Int, String?>>()
        val leases = mutableListOf<ForwardView>()
        val stopped = mutableListOf<String>()
        var resets = 0
        val presence = mutableListOf<Boolean?>()
        var pinAnswer = true
        var attachAnswer: (ForwardView, Int) -> StartResult = { l, p -> StartResult.Started(forward(l, p), l.listening) }

        override fun onUiForeground(foreground: Boolean) { this.foreground += foreground }
        override fun pin(summary: SessionSummary, manual: Boolean): Boolean { pinned += summary.id to manual; return pinAnswer }
        override fun unpin(sessionId: String) { unpinned += sessionId }
        override fun onSessionOpened(sessionId: String) { opened += sessionId }
        override fun noteLocalAction(target: String, action: LocalAction) { local += target to action }
        override suspend fun attachForward(lease: ForwardView, localPort: Int, process: String?, listening: Boolean?): StartResult {
            attached += Triple(lease, localPort, process)
            return attachAnswer(lease, localPort)
        }
        override fun updateForwardLease(lease: ForwardView) { leases += lease }
        override fun stopForward(leaseId: String) { stopped += leaseId }
        override fun onReset() { resets++ }
        override fun onCompanionPresence(present: Boolean?) { presence += present }
    }

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var host: FakeHost
    private lateinit var bridge: LinkBridge
    private lateinit var client: LinkClient

    @Before
    fun setUp() {
        host = FakeHost()
        bridge = LinkBridge(host)
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .setComponentNameAndServiceForBindService(ComponentName(context, LinkBridgeService::class.java), bridge.messenger.binder)
        client = LinkClient(context)
    }

    @After
    fun tearDown() {
        host.appScope.cancel()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun foregroundBindsAndIsReportedInOrder() {
        client.setForeground(true)
        idle()
        client.setForeground(false)
        idle()
        assertEquals(listOf(true, false), host.foreground.takeLast(2))
        assertTrue(host.foreground.first())
    }

    @Test
    fun messagesSentBeforeTheBindingWaitAndArriveInOrder() {
        client.send(LinkProtocol.message(LinkProtocol.UNPIN) { putString(LinkProtocol.K_SESSION, "a") })
        client.send(LinkProtocol.message(LinkProtocol.SESSION_OPENED) { putString(LinkProtocol.K_SESSION, "b") })
        client.send(LinkProtocol.message(LinkProtocol.RESET))
        client.noteLocalAction("t1", LocalAction.KILL_TERMINAL)
        client.send(LinkProtocol.message(LinkProtocol.COMPANION, 1))
        idle()
        assertEquals(listOf("a"), host.unpinned)
        assertEquals(listOf("b"), host.opened)
        assertEquals(1, host.resets)
        assertEquals(listOf("t1" to LocalAction.KILL_TERMINAL), host.local)
        assertEquals(listOf<Boolean?>(true), host.presence)
    }

    @Test
    fun pinIsAnsweredWithTheHostsDecision() {
        val pins = LivePins(context, CoroutineScope(Dispatchers.Main), client, NoopPrefs)
        val answers = mutableListOf<Boolean>()
        pins.pin(SessionSummary(id = "s1", title = "t"), manual = false) { answers += it }
        idle()
        host.pinAnswer = false
        pins.pin(SessionSummary(id = "s2"), manual = true) { answers += it }
        idle()
        assertEquals(listOf("s1" to false, "s2" to true), host.pinned)
        assertEquals(listOf(true, false), answers)
    }

    @Test
    fun statePushesReachTheMirrorIncludingForRegisteredLateClients() {
        bridge.pushState(LinkState(pins = listOf("x"), forwards = emptyList()))
        client.setForeground(true)
        idle()
        assertEquals(listOf("x"), client.state.value.pins)
        bridge.pushState(LinkState(pins = listOf("x", "y"), forwards = listOf(forward(ForwardView("l", 80), 8080))))
        idle()
        assertEquals(listOf("x", "y"), client.state.value.pins)
        assertEquals(8080, client.state.value.forwards.single().localPort)
        bridge.forwardEnded(80)
        bridge.revoked("srv")
        idle()
    }

    @Test
    fun forwardsAreHandedToTheLinkProcessAndTheResultComesBack() = runBlocking {
        val forwards = LinkedForwards(CoroutineScope(Dispatchers.Main), client, NoopConnection, io.github.tianli0.switchtether.domain.StepUpGate({ _, _ -> error("no prompt") }), { true })
        val lease = ForwardView(id = "l1", port = 5173, expiresAtMs = 10, listening = true)
        val started = async(Dispatchers.Default) { forwards.attach(lease, 15173, "vite", true) }
        while (!started.isCompleted) { idle(); Thread.sleep(5) }
        val r = started.await()
        assertTrue(r is StartResult.Started)
        assertEquals(15173, (r as StartResult.Started).forward.localPort)
        assertEquals(true, r.listening)
        assertEquals(Triple(lease, 15173, "vite"), host.attached.single())

        host.attachAnswer = { l, p -> StartResult.LocalPortBusy(p, l.port) }
        val busy = async(Dispatchers.Default) { forwards.attach(lease, 15173) }
        while (!busy.isCompleted) { idle(); Thread.sleep(5) }
        assertEquals(StartResult.LocalPortBusy(15173, 5173), busy.await())

        forwards.stop("l1")
        idle()
        assertEquals(listOf("l1"), host.stopped)
    }

    @Test
    fun whenTheLinkProcessDiesForwardsAreGoneAndRequestsFail() = runBlocking {
        client.setForeground(true)
        idle()
        bridge.pushState(LinkState(pins = listOf("p"), forwards = listOf(forward(ForwardView("l", 80), 8080))))
        idle()
        assertFalse(client.state.value.forwards.isEmpty())
        val app = shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
        val conn = app.boundServiceConnections.single()
        conn.onServiceDisconnected(ComponentName(context, LinkBridgeService::class.java))
        idle()
        assertTrue(client.state.value.forwards.isEmpty())
        assertEquals(listOf("p"), client.state.value.pins)
        // Not connected: the request waits for a new binding and times out without an answer.
        assertNull(client.request(LinkProtocol.message(LinkProtocol.PIN), timeoutMs = 50))
    }

    private object NoopPrefs : io.github.tianli0.switchtether.data.LinkPrefs {
        override val companionNearbyOnly = kotlinx.coroutines.flow.flowOf(false)
        override suspend fun setCompanionNearbyOnly(value: Boolean) = Unit
        override val companionPresent = kotlinx.coroutines.flow.flowOf(true)
        override suspend fun setCompanionPresent(value: Boolean) = Unit
        override val autoPinPhoneSessions = kotlinx.coroutines.flow.flowOf(true)
        override suspend fun setAutoPinPhoneSessions(value: Boolean) = Unit
        override val pendingWipe = kotlinx.coroutines.flow.flowOf<String?>(null)
        override suspend fun setPendingWipe(serverId: String?) = Unit
        override suspend fun migrateLegacy(context: Context) = Unit
    }

    private object NoopConnection : io.github.tianli0.switchtether.connection.ConnectionController {
        override val state = kotlinx.coroutines.flow.MutableStateFlow<io.github.tianli0.switchtether.connection.ConnectionState>(io.github.tianli0.switchtether.connection.ConnectionState.Unpaired)
        override val events = kotlinx.coroutines.flow.MutableSharedFlow<io.github.tianli0.switchtether.remote.events.StreamFrame.Event>()
        override val resyncs = kotlinx.coroutines.flow.MutableSharedFlow<String>()
        override fun apiOrNull() = null
        override fun retryNow() = Unit
    }

    private companion object {
        fun forward(l: ForwardView, localPort: Int) = ActiveForward(l.id, l.port, localPort, null, l.expiresAtMs, TunnelStatus.OPEN, false, 0, 0, 0, 0)
    }
}
