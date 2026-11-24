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

    private fun request(id: String, resolved: Boolean = false, title: String = "Run: ls") =
        sessionEvent(id, "approval_request", """{"requestId":"$id","title":"$title","resolved":$resolved}""")

    private fun shown(): android.app.Notification = nm.activeNotifications.single().notification

    private fun android.app.Notification.text() = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()

    /** The request id each button of the notification acts on. */
    private fun android.app.Notification.buttonRequests(): List<String?> =
        actions.orEmpty().map { shadowOf(it.actionIntent).savedIntent.getStringExtra("request") }

    @Test
    fun resolvingTheShownRequestShowsTheOlderOneStillOpen() {
        emit("session.upserted", ProtocolJson.encodeToJsonElement(SessionSummary.serializer(), summary(id = "s-two", status = "waiting")))
        request("r1", title = "Run: ls")
        request("r2", title = "Run: rm -rf build")
        assertEquals("Run: rm -rf build", shown().text())
        assertEquals(listOf("r2", "r2"), shown().buttonRequests())

        // The newer one is answered (on the computer or from the notification): the notification now
        // offers the older one, and its allow / deny act on r1, never on the resolved r2.
        sessionEvent("x2", "approval_resolved", """{"requestId":"r2","decision":"allow"}""")
        assertEquals("Run: ls", shown().text())
        assertEquals(listOf("r1", "r1"), shown().buttonRequests())
    }

    @Test
    fun aQuestionResolvedInPlaceGivesWayToTheOpenApproval() {
        emit("session.upserted", ProtocolJson.encodeToJsonElement(SessionSummary.serializer(), summary(id = "s-two", status = "waiting")))
        request("r1", title = "Run: ls")
        val q = """{"requestId":"q2","questions":[{"question":"Which branch?","options":[{"label":"main"}]}],"resolved":%s}"""
        sessionEvent("q2", "question", q.format("false"))
        assertEquals("Which branch?", shown().text())

        sessionEvent("q2", "question", q.format("true"))
        assertEquals("Run: ls", shown().text())
        assertEquals(listOf("r1", "r1"), shown().buttonRequests())
    }

    @Test
    fun allowingFromTheNotificationShowsTheOlderOpenRequestWhicheverArrivesFirst() {
        emit("session.upserted", ProtocolJson.encodeToJsonElement(SessionSummary.serializer(), summary(id = "s-two", status = "waiting")))
        request("r1", title = "Run: ls")
        request("r2", title = "Run: rm -rf build")

        // The button's confirmation first, the desktop's approval_resolved after it.
        notifier.requestAnswered("s-two", "r2")
        assertEquals(listOf("r1", "r1"), shown().buttonRequests())
        sessionEvent("x2", "approval_resolved", """{"requestId":"r2","decision":"allow"}""")
        assertEquals("Run: ls", shown().text())
        assertEquals(listOf("r1", "r1"), shown().buttonRequests())

        // The other order: the event replaced the notification already, the confirmation keeps it.
        request("r3", title = "Run: make")
        sessionEvent("x3", "approval_resolved", """{"requestId":"r3","decision":"allow"}""")
        notifier.requestAnswered("s-two", "r3")
        assertEquals(listOf("r1", "r1"), shown().buttonRequests())

        notifier.requestAnswered("s-two", "r1") // the last one
        assertEquals(0, nm.activeNotifications.size)
    }

    @Test
    fun resolvingAnOlderRequestLeavesTheShownOneAlone() {
        emit("session.upserted", ProtocolJson.encodeToJsonElement(SessionSummary.serializer(), summary(id = "s-two", status = "waiting")))
        request("r1", title = "Run: ls")
        request("r2", title = "Run: rm -rf build")
        sessionEvent("x1", "approval_resolved", """{"requestId":"r1","decision":"deny"}""")
        assertEquals("Run: rm -rf build", shown().text())
        assertEquals(listOf("r2", "r2"), shown().buttonRequests())
    }

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
