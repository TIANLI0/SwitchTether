package io.github.tianli0.switchtether

import io.github.tianli0.switchtether.notify.ConversationPolicy
import io.github.tianli0.switchtether.notify.ConversationPolicy.Entry
import io.github.tianli0.switchtether.notify.ConversationPolicy.LiveDecision
import io.github.tianli0.switchtether.notify.ConversationPolicy.ReplyResult
import io.github.tianli0.switchtether.remote.api.ApiException
import io.github.tianli0.switchtether.remote.api.CommandResponse
import io.github.tianli0.switchtether.remote.command.CommandOutcome
import io.github.tianli0.switchtether.remote.notify.NotifyType
import io.github.tianli0.switchtether.remote.protocol.ApiErrorBody
import io.github.tianli0.switchtether.remote.protocol.CapStatus
import io.github.tianli0.switchtether.remote.protocol.SessionSummary
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure rules of conversation notifications (notify/ConversationPolicy.kt, docs/ANDROID_UI.md §34). */
class ConversationPolicyTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 100 * day

    // ---- shortcut ids, limit, eviction -----------------------------------------------------------

    @Test
    fun shortcutIdsRoundTripAndOnlySessionIdsParse() {
        val id = ConversationPolicy.shortcutId("claude:abc")
        assertEquals("session:claude:abc", id)
        assertEquals("claude:abc", ConversationPolicy.sessionIdOf(id))
        assertNull(ConversationPolicy.sessionIdOf("share:target"))
        assertNull(ConversationPolicy.sessionIdOf("session:"))
    }

    @Test
    fun limitLeavesARoomForOtherShortcutsAndIsCapped() {
        assertEquals(ConversationPolicy.MAX_SESSION_SHORTCUTS, ConversationPolicy.limit(15))
        assertEquals(4, ConversationPolicy.limit(5))
        assertEquals(1, ConversationPolicy.limit(1))
        assertEquals(1, ConversationPolicy.limit(0))
    }

    @Test
    fun evictsSessionsEndedLongAgoAndUnusedOnes() {
        val entries = listOf(
            Entry("live", now - 1_000),
            Entry("ended-recently", now - day, endedAtMs = now - day),
            Entry("ended-long-ago", now - 5 * day, endedAtMs = now - 4 * day),
            Entry("unused", now - 20 * day),
        )
        assertEquals(setOf("ended-long-ago", "unused"), ConversationPolicy.evictions(entries, now, limit = 6))
    }

    @Test
    fun beyondTheLimitTheLeastRecentlyPostedGoesFirst() {
        val entries = (1..8).map { Entry("s$it", now - it * 60_000L) } // s1 newest … s8 oldest
        assertEquals(setOf("s7", "s8"), ConversationPolicy.evictions(entries, now, limit = 6))
        assertEquals(setOf("s4", "s5", "s6", "s7", "s8"), ConversationPolicy.evictions(entries, now, limit = 3))
    }

    @Test
    fun theSessionBeingPostedIsNeverEvicted() {
        val entries = (1..8).map { Entry("s$it", now - it * 60_000L) } + Entry("old-ended", now - 10 * day, endedAtMs = now - 9 * day)
        val out = ConversationPolicy.evictions(entries, now, limit = 2, keep = "s8")
        assertFalse("s8" in out)
        assertFalse("s1" in out) // keep + the newest one fill the two slots
        assertTrue(out.containsAll(listOf("s2", "s7", "old-ended")))
        // Even an expired entry is kept while it is the one being posted.
        assertFalse("old-ended" in ConversationPolicy.evictions(entries, now, limit = 6, keep = "old-ended"))
    }

    @Test
    fun entriesSurviveEncodingAndBadLinesAreDropped() {
        val entries = listOf(Entry("a:1", 5), Entry("b:2", 7, endedAtMs = 9))
        assertEquals(entries, ConversationPolicy.decode(ConversationPolicy.encode(entries)))
        assertEquals(listOf(Entry("ok", 1)), ConversationPolicy.decode("garbage\n\tx\nok\t1\t\nbad\tnotanumber"))
        assertTrue(ConversationPolicy.decode(null).isEmpty())
    }

    // ---- reply gating -------------------------------------------------------------------------------

    private val live = SessionSummary(id = "claude:1", agent = "claude_code", liveness = "live", control = "full", status = "idle")

    @Test
    fun replyNeedsAControllableLiveSessionAndAUsableSendCapability() {
        assertTrue(ConversationPolicy.replyAllowed(true, NotifyType.TASK_COMPLETED, live, CapStatus.VERIFIED))
        assertTrue(ConversationPolicy.replyAllowed(true, NotifyType.WAITING_INPUT, live, CapStatus.UNTESTED))
        // The composer's rule: blocked / unknown capability → no reply.
        assertFalse(ConversationPolicy.replyAllowed(true, NotifyType.TASK_COMPLETED, live, CapStatus.BLOCKED))
        assertFalse(ConversationPolicy.replyAllowed(true, NotifyType.TASK_COMPLETED, live, CapStatus.UNKNOWN))
        assertFalse(ConversationPolicy.replyAllowed(true, NotifyType.TASK_COMPLETED, live, null))
        // Observed / ended sessions, approvals, and what the engine did not mark replyable.
        assertFalse(ConversationPolicy.replyAllowed(true, NotifyType.TASK_COMPLETED, live.copy(control = "observe"), CapStatus.VERIFIED))
        assertFalse(ConversationPolicy.replyAllowed(true, NotifyType.TASK_COMPLETED, live.copy(liveness = "ended"), CapStatus.VERIFIED))
        assertFalse(ConversationPolicy.replyAllowed(true, NotifyType.WAITING_APPROVAL, live, CapStatus.VERIFIED))
        assertFalse(ConversationPolicy.replyAllowed(false, NotifyType.TASK_COMPLETED, live, CapStatus.VERIFIED))
        assertFalse(ConversationPolicy.replyAllowed(true, NotifyType.TASK_COMPLETED, null, CapStatus.VERIFIED))
    }

    private fun rejected(status: Int, code: String, reason: String? = null, message: String = "") = CommandOutcome.Rejected(
        "c", ApiException(status, ApiErrorBody(code, message, details = reason?.let { buildJsonObject { put("reason", JsonPrimitive(it)) } }), null),
    )

    @Test
    fun replyOutcomesMapLikeTheComposerAndStepUpIsNeverDoneHere() {
        assertEquals(ReplyResult.Sent, ConversationPolicy.replyResult(CommandOutcome.Succeeded("c", CommandResponse(JsonNull, false))))
        assertEquals(ReplyResult.NeedsStepUp, ConversationPolicy.replyResult(rejected(403, "step_up_required")))
        assertEquals(ReplyResult.NeedsStepUp, ConversationPolicy.replyResult(rejected(403, "forbidden", reason = "no_step_up_key")))
        assertEquals(ReplyResult.Refused("busy"), ConversationPolicy.replyResult(rejected(422, "validation_failed", message = "busy")))
        assertEquals(ReplyResult.Refused("not_found"), ConversationPolicy.replyResult(rejected(404, "not_found")))
        assertEquals(ReplyResult.Offline, ConversationPolicy.replyResult(null))
        assertEquals(ReplyResult.Offline, ConversationPolicy.replyResult(CommandOutcome.NotSent("c", RuntimeException())))
    }

    @Test
    fun unconfirmedRepliesAreSettledByTheCommandRecordNotByResending() {
        val unknown = CommandOutcome.Unknown("c", RuntimeException())
        assertEquals(ReplyResult.Sent, ConversationPolicy.replyResult(unknown, recordSays = true))
        assertEquals(ReplyResult.Refused(""), ConversationPolicy.replyResult(unknown, recordSays = false))
        assertEquals(ReplyResult.Unsure, ConversationPolicy.replyResult(unknown, recordSays = null))
        // 409 for this id: the desktop has it running.
        assertEquals(ReplyResult.Sent, ConversationPolicy.replyResult(CommandOutcome.InProgress("c"), recordSays = null))
        assertEquals(ReplyResult.Refused(""), ConversationPolicy.replyResult(CommandOutcome.InProgress("c"), recordSays = false))
    }

    // ---- Live Update de-dup --------------------------------------------------------------------------

    @Test
    fun aPinnedTasksCardOwnsItsStateAndRepliesGoInQuietly() {
        listOf(
            NotifyType.WAITING_APPROVAL, NotifyType.WAITING_INPUT, NotifyType.TASK_STARTED, NotifyType.TASK_COMPLETED,
            NotifyType.TASK_FAILED, NotifyType.SESSION_INTERRUPTED, NotifyType.SESSION_EXITED, NotifyType.LONG_RUNNING, NotifyType.TOOL_CALL,
        ).forEach { t ->
            assertEquals(t.name, LiveDecision.SKIP, ConversationPolicy.liveDecision(t, covered = true))
            assertEquals(t.name, LiveDecision.POST, ConversationPolicy.liveDecision(t, covered = false))
        }
        assertEquals(LiveDecision.POST_SILENTLY, ConversationPolicy.liveDecision(NotifyType.AGENT_REPLY, covered = true))
        assertEquals(LiveDecision.POST, ConversationPolicy.liveDecision(NotifyType.AGENT_REPLY, covered = false))
    }

    // ---- content ---------------------------------------------------------------------------------------

    private fun content(
        type: NotifyType = NotifyType.TASK_COMPLETED,
        sessionId: String? = "claude:1",
        body: String = "",
        reply: Boolean = false,
        live: LiveDecision = LiveDecision.POST,
    ) = ConversationPolicy.content(
        type, sessionId, "fix login finished", body, "Claude Code", "fix login",
        replyAllowed = reply, silent = false, live = live,
    )

    @Test
    fun sessionNotificationsAreConversationsFromTheAgent() {
        val c = content(body = "All tests pass")
        assertTrue(c.conversation)
        assertEquals("Claude Code · fix login", c.sender)
        assertEquals("fix login finished\nAll tests pass", c.message)
        assertFalse(c.silent)
        // An agent reply is the reply text itself.
        assertEquals("Done.", content(type = NotifyType.AGENT_REPLY, body = "Done.").message)
        assertEquals("fix login finished", content().message)
        // Connection / terminal notifications are not conversations.
        assertFalse(content(type = NotifyType.CONNECTION, sessionId = null).conversation)
        assertFalse(content(type = NotifyType.TERMINAL_EXITED).conversation)
    }

    @Test
    fun senderNamesAreShortenedAndFallBackToTheAgent() {
        assertEquals("Codex", ConversationPolicy.senderName("Codex", null))
        assertEquals("Codex", ConversationPolicy.senderName("Codex", "  "))
        val long = ConversationPolicy.senderName("Codex", "x".repeat(100))
        assertEquals("Codex · " + "x".repeat(ConversationPolicy.MAX_NAME - 1) + "…", long)
    }

    @Test
    fun replyIsOfferedOnlyWhenGatedAndCoveredRepliesAreSilent() {
        assertTrue(content(reply = true).offerReply)
        assertFalse(content(reply = false).offerReply)
        assertTrue(content(type = NotifyType.AGENT_REPLY, live = LiveDecision.POST_SILENTLY).silent)
    }
}
