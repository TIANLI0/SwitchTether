package io.github.tianli0.switchtether

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.tianli0.switchtether.data.PairedDesktop
import io.github.tianli0.switchtether.data.PairingStore
import io.github.tianli0.switchtether.notify.LiveActivityChannel
import io.github.tianli0.switchtether.notify.LiveUpdates
import io.github.tianli0.switchtether.remote.command.CommandOutcome
import io.github.tianli0.switchtether.remote.live.LivePhase
import io.github.tianli0.switchtether.remote.live.LiveUpdateSpec
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression: a "running" Live Update titled with a fragment of the session id (looked like garbage)
 * that opened an empty page. A pin restored after a restart showed before any summary named it, and
 * cards used the raw summary title instead of the cleaned one the session list shows.
 */
@RunWith(AndroidJUnit4::class)
class LiveUpdatesNamingTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val posts: MutableList<LiveUpdateSpec> = Collections.synchronizedList(mutableListOf())

    private val store = object : PairingStore {
        override val desktop = MutableStateFlow<PairedDesktop?>(TEST_DESKTOP)
        override suspend fun current(): PairedDesktop? = desktop.value
        override suspend fun save(desktop: PairedDesktop) = Unit
        override suspend fun clear() = Unit
    }

    private val channel = object : LiveActivityChannel {
        override fun update(spec: LiveUpdateSpec, agent: String, subtitle: String?) { posts += spec }
        override fun end(sessionId: String) = Unit
        override fun canPromote() = true
    }

    private fun live(): LiveUpdates = LiveUpdates(
        context, scope, FakeConnection(), store, channel,
        resolveApproval = { _, _, _, id -> CommandOutcome.NotSent(id, IllegalStateException("test")) },
        enabled = { true },
    )

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun aRestoredPinShowsNothingUntilASummaryNamesIt() {
        val id = "claude:3fa9c2d1-0000-4000-8000-000000000000"
        context.getSharedPreferences("live_pins", Context.MODE_PRIVATE).edit().clear()
            .putStringSet("pins:${TEST_DESKTOP.serverId}", setOf(id)).commit()
        val live = live()
        live.start()
        assertTrue(live.isPinned(id))

        // Events of the restored session arrive before its summary: no card yet, and ordinary
        // notifications are not suppressed for it meanwhile.
        live.onStreamEvent(event("e1", 1, "status", """{"state":"running"}""", session = id).copy(ts = "2999-01-01T00:00:00Z"))
        assertTrue("no card before a summary: ${posts.map { it.title }}", posts.isEmpty())
        assertFalse(live.covers(id))

        live.onStreamUpsert(summary(id = id, status = "busy").copy(title = null, project = null, cwd = "/home/u/my-project"))
        assertEquals("my-project", posts.last().title)
        assertEquals(LivePhase.RUNNING, posts.last().phase)
        assertTrue(posts.none { it.title.contains("3fa9c2d1") })
    }

    @Test
    fun aCardUsesTheCleanedTitleNotTheRawOne() {
        context.getSharedPreferences("live_pins", Context.MODE_PRIVATE).edit().clear().commit()
        val live = live()
        // Older desktops sent the registry slug "<project>-<hex>" as the title.
        val s = summary(id = "claude:slug", status = "busy").copy(title = "app-3fa9", project = "app")
        assertTrue(live.pin(s, manual = true))
        live.onStreamUpsert(s)
        assertTrue(posts.isNotEmpty())
        assertTrue(posts.all { it.title == "app" })
    }
}
