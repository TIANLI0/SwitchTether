package io.github.tianli0.switchtether

import io.github.tianli0.switchtether.notify.AutoFollow
import io.github.tianli0.switchtether.notify.AutoFollow.Run
import io.github.tianli0.switchtether.remote.protocol.SessionSummary
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fluid Cloud follow: every new run of a task is followed, not only the first sighting of its id. */
class AutoFollowTest {
    private val base = 1_000_000L
    private fun s(status: String, liveness: String = "live", created: Long? = base + 1) =
        SessionSummary(id = "claude:x", liveness = liveness, status = status, createdAtMs = created)

    @Test
    fun newSessionIsFollowedUnlessOlderThanTheBaseline() {
        assertTrue(AutoFollow.shouldFollow(s("idle"), null, isNew = true, baselineMs = base))
        assertFalse(AutoFollow.shouldFollow(s("busy", created = base - 60_000), null, isNew = true, baselineMs = base))
        assertFalse(AutoFollow.shouldFollow(s("busy"), null, isNew = true, baselineMs = 0))
    }

    @Test
    fun nextTurnOfAKnownSessionIsFollowedAgain() {
        // The first turn's follow ended with its final card; the session went idle and starts again.
        assertTrue(AutoFollow.shouldFollow(s("busy"), Run.IDLE, isNew = false, baselineMs = base))
        assertTrue(AutoFollow.shouldFollow(s("idle").copy(waitingFor = "permission prompt"), Run.IDLE, isNew = false, baselineMs = base))
        // Still the same run: no re-follow while it keeps working.
        assertFalse(AutoFollow.shouldFollow(s("busy"), Run.ACTIVE, isNew = false, baselineMs = base))
        assertFalse(AutoFollow.shouldFollow(s("idle"), Run.IDLE, isNew = false, baselineMs = base))
    }

    @Test
    fun resumedConversationIsFollowedAgain() {
        assertTrue(AutoFollow.shouldFollow(s("idle"), Run.ENDED, isNew = false, baselineMs = base))
        assertFalse(AutoFollow.shouldFollow(s("ended", liveness = "ended"), Run.IDLE, isNew = false, baselineMs = base))
    }

    @Test
    fun afterAnAppRestartOnlyWorkingSessionsAreFollowed() {
        assertTrue(AutoFollow.shouldFollow(s("busy"), null, isNew = false, baselineMs = base))
        assertFalse(AutoFollow.shouldFollow(s("idle"), null, isNew = false, baselineMs = base))
    }
}
