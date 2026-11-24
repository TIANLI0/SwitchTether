package io.github.tianli0.switchtether

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.tianli0.switchtether.connection.ConnectionState
import io.github.tianli0.switchtether.notify.Notifier
import io.github.tianli0.switchtether.remote.events.StreamFrame
import io.github.tianli0.switchtether.remote.notify.NotifyPrefs
import io.github.tianli0.switchtether.remote.protocol.AgentEvent
import io.github.tianli0.switchtether.remote.protocol.ProtocolJson
import io.github.tianli0.switchtether.remote.protocol.SessionSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * Regressions: a session whose process exited while busy (`session.removed`) kept counting as
 * active, so the monitor service held its wake lock for good; and "connection lost" stayed in the
 * shade when the reconnect happened with the app in the foreground.
 */
@RunWith(AndroidJUnit4::class)
class NotifierLifecycleTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dispatcher = StandardTestDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val conn = FakeConnection()
    private var foreground = false
    private val notifier = Notifier(context, scope, conn, MutableStateFlow(NotifyPrefs()), { foreground })
    private val nm = context.getSystemService(NotificationManager::class.java)

    @Before
    fun setUp() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifier.start()
        dispatcher.scheduler.runCurrent()
    }

    @After
    fun tearDown() = scope.cancel()

    private fun emit(topic: String, data: kotlinx.serialization.json.JsonElement) {
        conn.eventFlow.tryEmit(StreamFrame.Event(1, "e", "2026-11-14T03:00:00Z", topic, data))
        dispatcher.scheduler.runCurrent()
    }

    @Test
    fun aRemovedSessionNoLongerCountsAsActive() {
        val s = summary(id = "s-busy", status = "busy")
        emit("session.upserted", ProtocolJson.encodeToJsonElement(SessionSummary.serializer(), s))
        assertEquals(1, notifier.activeSessions.value)

        emit("session.removed", buildJsonObject { put("sessionId", JsonPrimitive(s.id)) })
        assertEquals(0, notifier.activeSessions.value)
    }

    @Test
    fun connectionLostIsWithdrawnWhenReconnectedInTheForeground() {
        conn.state.value = ConnectionState.Offline(TEST_DESKTOP, null, null)
        dispatcher.scheduler.advanceTimeBy(31_000)
        dispatcher.scheduler.runCurrent()
        assertEquals(1, nm.activeNotifications.size)

        foreground = true // the user opened the app; it reconnects
        conn.state.value = ONLINE
        dispatcher.scheduler.runCurrent()
        assertEquals(0, nm.activeNotifications.size)
    }

    @Test
    fun reconnectedIsNotPostedWhenLostWasNeverShown() {
        foreground = true // the drop happens with the app open: nothing is posted
        conn.state.value = ConnectionState.Offline(TEST_DESKTOP, null, null)
        dispatcher.scheduler.advanceTimeBy(31_000)
        dispatcher.scheduler.runCurrent()
        assertEquals(0, nm.activeNotifications.size)

        foreground = false // the user leaves; it reconnects in the background
        conn.state.value = ONLINE
        dispatcher.scheduler.runCurrent()
        assertEquals(0, nm.activeNotifications.size)
    }

    @Test
    fun sessionsOfThePreviousComputerNoLongerCountAsActive() {
        emit("session.upserted", ProtocolJson.encodeToJsonElement(SessionSummary.serializer(), summary(id = "s-busy", status = "busy")))
        assertEquals(1, notifier.activeSessions.value)

        // Paired with another computer.
        conn.state.value = ConnectionState.Connecting(TEST_DESKTOP.copy(serverId = "srv-2", name = "DESKTOP-02"), 1, null)
        dispatcher.scheduler.runCurrent()
        assertEquals(0, notifier.activeSessions.value)
    }

    private val soon = java.time.Instant.now().plusSeconds(1).toString()

    private fun sessionEvent(id: String, kind: String, data: String) = emit(
        "session.event",
        ProtocolJson.encodeToJsonElement(AgentEvent.serializer(), event(id, 1, kind, data, session = "s-two").copy(ts = soon)),
    )

    private fun request(id: String, resolved: Boolean = false) =
        sessionEvent(id, "approval_request", """{"requestId":"$id","title":"Run: ls","resolved":$resolved}""")

    @Test
    fun theNeedsYouNotificationStaysWhileAnotherRequestIsOpen() {
        emit("session.upserted", ProtocolJson.encodeToJsonElement(SessionSummary.serializer(), summary(id = "s-two", status = "waiting")))
        request("r1")
        request("r2")
        assertEquals(1, nm.activeNotifications.size)

        // One answered (a separate approval_resolved event): the other still needs the user.
        sessionEvent("x1", "approval_resolved", """{"requestId":"r1","decision":"allow"}""")
        assertEquals(1, nm.activeNotifications.size)

        sessionEvent("x2", "approval_resolved", """{"requestId":"r2","decision":"allow"}""")
        assertEquals(0, nm.activeNotifications.size)
    }

    @Test
    fun aRequestResolvedInPlaceWithdrawsTheNotificationOnlyWhenItWasTheLast() {
        emit("session.upserted", ProtocolJson.encodeToJsonElement(SessionSummary.serializer(), summary(id = "s-two", status = "waiting")))
        request("r1")
        request("r2")
        request("r1", resolved = true)
        assertEquals(1, nm.activeNotifications.size)
        request("r2", resolved = true)
        assertEquals(0, nm.activeNotifications.size)
    }
}
