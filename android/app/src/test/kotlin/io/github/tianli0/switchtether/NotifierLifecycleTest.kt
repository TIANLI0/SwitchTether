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
}
