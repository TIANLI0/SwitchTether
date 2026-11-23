package io.github.tianli0.switchtether

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.tianli0.switchtether.data.PairingStore
import io.github.tianli0.switchtether.data.PairedDesktop
import io.github.tianli0.switchtether.notify.LiveActivityChannel
import io.github.tianli0.switchtether.notify.LiveUpdates
import io.github.tianli0.switchtether.remote.command.CommandOutcome
import io.github.tianli0.switchtether.remote.live.LiveUpdateSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression: ordinary notifications for a pinned session are suppressed only while its Live Update
 * card really shows. With Live Updates switched off (or the card swiped away) approvals and
 * questions were silently dropped.
 */
@RunWith(AndroidJUnit4::class)
class LiveUpdatesCoverTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var enabled = true

    private val store = object : PairingStore {
        override val desktop = MutableStateFlow<PairedDesktop?>(TEST_DESKTOP)
        override suspend fun current(): PairedDesktop? = desktop.value
        override suspend fun save(desktop: PairedDesktop) = Unit
        override suspend fun clear() = Unit
    }

    private val channel = object : LiveActivityChannel {
        override fun update(spec: LiveUpdateSpec, agent: String, subtitle: String?) = Unit
        override fun end(sessionId: String) = Unit
        override fun canPromote() = true
    }

    private fun live() = LiveUpdates(
        context, scope, FakeConnection(), store, channel,
        resolveApproval = { _, _, _, id -> CommandOutcome.NotSent(id, IllegalStateException("test")) },
        enabled = { enabled },
    )

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun aPinnedSessionIsCoveredOnlyWhileItsCardShows() {
        val live = live()
        val s = summary(id = "managed:cover", live = true, control = "full", status = "busy")
        assertTrue(live.pin(s, manual = true))
        assertTrue(live.isPinned(s.id))
        assertTrue(live.covers(s.id))

        enabled = false // Settings → notifications → Live Updates off: the card is withdrawn
        assertTrue(live.isPinned(s.id))
        assertFalse(live.covers(s.id))

        enabled = true
        live.onDismissed(s.id) // swiped away
        assertFalse(live.covers(s.id))

        assertFalse(live.covers("managed:not-pinned"))
    }
}
