package io.github.tianli0.switchtether

import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.tianli0.switchtether.remote.protocol.CapStatus
import io.github.tianli0.switchtether.remote.protocol.SessionDetail
import io.github.tianli0.switchtether.remote.session.AgentEventLog
import io.github.tianli0.switchtether.ui.common.Loadable
import io.github.tianli0.switchtether.ui.sessions.OutgoingMessage
import io.github.tianli0.switchtether.ui.sessions.OutgoingStatus
import io.github.tianli0.switchtether.ui.sessions.SessionDetailContent
import io.github.tianli0.switchtether.ui.sessions.SessionDetailState
import io.github.tianli0.switchtether.ui.sessions.render.RenderContext
import io.github.tianli0.switchtether.ui.theme.SwitchTetherTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Session chat list: stick-to-bottom, the new-message pill and optimistic bubbles. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ChatListTest {
    @get:Rule
    val rule = createComposeRule()

    private val ctx = RenderContext(
        agent = "claude_code", agentLabel = "Claude Code", approve = CapStatus.VERIFIED, answer = CapStatus.VERIFIED, capabilityReason = null, online = true,
        onApprove = { _, _, _ -> }, onAnswer = { _, _ -> }, onLoadPayload = {}, payloadOf = { null }, onToggleCollapse = {}, isCollapsed = { false }, onLink = {},
    )

    private fun messages(n: Int, from: Int = 1) = (from until from + n).map { i ->
        event("m$i", i.toLong(), "assistant_message", """{"text":"Message number $i with some text to fill the row."}""")
    }

    private fun state(events: List<io.github.tianli0.switchtether.remote.protocol.AgentEvent>, outgoing: List<OutgoingMessage> = emptyList(), sendTick: Int = 0) =
        SessionDetailState(
            sessionId = "s1",
            detail = Loadable.Ready(SessionDetail(summary(control = "full", status = "busy"), caps(send = "VERIFIED", interrupt = "VERIFIED"), emptyList())),
            events = Loadable.Ready(Unit),
            connection = ONLINE,
        ).derive(log = AgentEventLog.of(events), outgoing = outgoing).copy(sendTick = sendTick)

    private var current by mutableStateOf<SessionDetailState?>(null)
    private val retried = mutableListOf<String>()
    private val deleted = mutableListOf<String>()

    private fun show(s: SessionDetailState) {
        current = s
        rule.setContent {
            SwitchTetherTheme {
                SessionDetailContent(
                    state = current!!, initialTitle = null, ctx = ctx, busy = null,
                    onBack = {}, onReload = {}, onLoadOlder = {}, onToggleSteps = {}, onDraft = {}, onSend = {}, onInterrupt = {},
                    onContinue = {}, onRetryConnection = {}, onRetryOutgoing = { retried += it }, onDeleteOutgoing = { deleted += it },
                )
            }
        }
        rule.waitForIdle()
    }

    private fun noPill() = rule.onAllNodesWithTag("new-messages-pill").fetchSemanticsNodes().isEmpty()

    @Test
    fun opensAtTheBottomAndFollowsNewMessages() {
        show(state(messages(40)))
        rule.onNodeWithText("Message number 40 with some text to fill the row.").assertIsDisplayed()
        current = state(messages(41))
        rule.waitForIdle()
        rule.onNodeWithText("Message number 41 with some text to fill the row.").assertIsDisplayed()
        assertTrue("no pill while following", noPill())
    }

    @Test
    fun theKeyboardPushesTheConversationUpInsteadOfCoveringIt() {
        // The keyboard shrinks the page from below (imePadding): the newest message must stay
        // above the composer, and come back down when the keyboard closes.
        var height by mutableStateOf(891)
        current = state(messages(40))
        rule.setContent {
            SwitchTetherTheme {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.height(height.dp)) {
                    SessionDetailContent(
                        state = current!!, initialTitle = null, ctx = ctx, busy = null,
                        onBack = {}, onReload = {}, onLoadOlder = {}, onToggleSteps = {}, onDraft = {}, onSend = {}, onInterrupt = {},
                        onContinue = {}, onRetryConnection = {},
                    )
                }
            }
        }
        rule.waitForIdle()
        val last = "Message number 40 with some text to fill the row."
        rule.onNodeWithText(last).assertIsDisplayed()
        height = 891 - 320
        rule.waitForIdle()
        rule.onNodeWithText(last).assertIsDisplayed()
        assertTrue("still following", noPill())
        height = 891
        rule.waitForIdle()
        rule.onNodeWithText(last).assertIsDisplayed()
        assertTrue(noPill())

        // Reading further up: the same lines stay above the composer, and it still counts as reading.
        rule.onNodeWithTag("event-list").performTouchInput { swipeDown(startY = height * 0.35f, endY = height * 0.8f) }
        rule.waitForIdle()
        rule.onNodeWithTag("new-messages-pill").assertIsDisplayed()
        val listBottom = rule.onNodeWithTag("event-list").fetchSemanticsNode().boundsInRoot.bottom
        val atEdge = rule.onAllNodes(androidx.compose.ui.test.hasText("Message number", substring = true)).fetchSemanticsNodes()
            .filter { it.boundsInRoot.bottom <= listBottom }.maxByOrNull { it.boundsInRoot.bottom }!!
        val edgeText = atEdge.config[androidx.compose.ui.semantics.SemanticsProperties.Text].first().text
        val gap = listBottom - atEdge.boundsInRoot.bottom
        height = 891 - 320
        rule.waitForIdle()
        val shrunkBottom = rule.onNodeWithTag("event-list").fetchSemanticsNode().boundsInRoot.bottom
        val after = rule.onNodeWithText(edgeText).fetchSemanticsNode().boundsInRoot.bottom
        assertEquals("the line above the composer stays there", gap, shrunkBottom - after, 2f)
        rule.onNodeWithTag("new-messages-pill").assertIsDisplayed()
    }

    @Test
    fun readingHistoryIsNotYankedAndThePillJumpsBack() {
        show(state(messages(40)))
        rule.onNodeWithTag("event-list").performTouchInput { swipeDown(startY = height * 0.35f, endY = height * 0.8f) }
        rule.waitForIdle()
        rule.onNodeWithTag("new-messages-pill").assertIsDisplayed()
        rule.onNodeWithText("Back to latest").assertExists()
        current = state(messages(42))
        rule.waitForIdle()
        // Still reading: the new rows did not scroll the list; the pill counts them.
        assertTrue(rule.onAllNodesWithText("Message number 42 with some text to fill the row.").fetchSemanticsNodes().isEmpty())
        rule.onNodeWithText("2 new messages").assertExists()
        rule.onNodeWithTag("new-messages-pill").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Message number 42 with some text to fill the row.").assertIsDisplayed()
        assertTrue(noPill())
    }

    @Test
    fun sendingAlwaysScrollsToTheOwnBubble() {
        show(state(messages(40)))
        rule.onNodeWithTag("event-list").performTouchInput { swipeDown(startY = height * 0.35f, endY = height * 0.8f) }
        rule.waitForIdle()
        val out = OutgoingMessage("c1", "please run the tests", OutgoingStatus.SENDING, afterSeq = 40)
        current = state(messages(40), listOf(out), sendTick = 1)
        rule.waitForIdle()
        rule.onNodeWithText("please run the tests").assertIsDisplayed()
        rule.onNodeWithText("Sending…").assertExists()
        assertTrue(noPill())
    }

    // The delete confirmation opens a dialog window: its vector icons need native graphics (as in
    // QuestionAnswerTest); the legacy mode hands Compose a null bitmap there.
    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Config(sdk = [35])
    fun failedBubbleOffersRetryAndDelete() {
        val out = OutgoingMessage("c9", "deploy it", OutgoingStatus.FAILED, afterSeq = 3, error = "session busy")
        show(state(messages(3), listOf(out)))
        rule.onNodeWithText("Not sent · session busy").assertExists()
        rule.onNodeWithTag("outgoing-retry").performClick()
        rule.onNodeWithTag("outgoing-delete").performClick()
        // The text would be lost: deleting asks first.
        assertTrue(deleted.isEmpty())
        rule.onNodeWithText("Delete message").performClick()
        assertEquals(listOf("c9"), retried)
        assertEquals(listOf("c9"), deleted)
    }

    @Test
    fun deliveredBubbleShowsTheTypingRow() {
        val out = OutgoingMessage("c2", "hello", OutgoingStatus.DELIVERED, afterSeq = 3)
        show(state(messages(3), listOf(out)))
        rule.onNodeWithText("Delivered").assertExists()
        rule.onNodeWithTag("typing").assertExists()
    }
}
