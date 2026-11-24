package io.github.tianli0.switchtether

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.tianli0.switchtether.domain.SessionRepository
import io.github.tianli0.switchtether.remote.protocol.CapStatus
import io.github.tianli0.switchtether.remote.session.SlashCommand
import io.github.tianli0.switchtether.ui.sessions.SessionDetailContent
import io.github.tianli0.switchtether.ui.sessions.SessionDetailViewModel
import io.github.tianli0.switchtether.ui.sessions.SlashAction
import io.github.tianli0.switchtether.ui.sessions.render.RenderContext
import io.github.tianli0.switchtether.ui.theme.SwitchTetherTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The "/" panel over the composer, driven through the real view model. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class SlashPanelTest {
    @get:Rule
    val rule = createComposeRule()

    private val ctx = RenderContext(
        agent = "claude_code", agentLabel = "Claude Code", approve = CapStatus.VERIFIED, answer = CapStatus.VERIFIED, capabilityReason = null, online = true,
        onApprove = { _, _, _ -> }, onAnswer = { _, _ -> }, onLoadPayload = {}, payloadOf = { null }, onToggleCollapse = {}, isCollapsed = { false }, onLink = {},
    )
    private val picked = mutableListOf<SlashAction>()

    private fun show(repo: SessionRepository): SessionDetailViewModel {
        val vm = SessionDetailViewModel("s1", FakeConnection(), repo, FakeDrafts())
        rule.setContent {
            val state by vm.state.collectAsState()
            SwitchTetherTheme {
                SessionDetailContent(
                    state = state, initialTitle = null, ctx = ctx, busy = null,
                    onBack = {}, onReload = {}, onLoadOlder = {}, onToggleSteps = {}, onDraft = vm::setDraft, onSend = vm::send, onInterrupt = {},
                    onContinue = {}, onRetryConnection = {},
                    onSlashCommand = { picked += vm.pickSlashCommand(it) },
                )
            }
        }
        rule.waitForIdle()
        return vm
    }

    private fun panelShown() = rule.onAllNodesWithTag("slash-panel").fetchSemanticsNodes().isNotEmpty()

    @Test
    fun slashOpensThePanelAndCompactIsSentOnTap() {
        val base = slashSession()
        val vm = show(base)
        assertTrue("no panel before a slash", !panelShown())

        rule.onNodeWithTag("composer").performTextInput("/")
        rule.waitForIdle()
        rule.onNodeWithTag("slash-panel").assertIsDisplayed()
        rule.onNodeWithText("/compact").assertIsDisplayed()
        // The phone's own description is under the name (the older desktop gave none).
        rule.onNodeWithText("Summarise the conversation to free up context").assertExists()

        rule.onNodeWithTag("slash-compact").performClick()
        rule.waitForIdle()
        assertEquals(listOf("/compact"), base.sent.map { it.second })
        assertEquals(listOf(SlashAction.SEND), picked)
        assertEquals("", vm.state.value.draft)
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        assertTrue("the panel closes with the draft", !panelShown())
    }

    @Test
    fun typingNarrowsTheListAndASpaceClosesIt() {
        show(slashSession())
        rule.onNodeWithTag("composer").performTextInput("/co")
        rule.waitForIdle()
        rule.onNodeWithTag("slash-compact").assertExists()
        rule.onNodeWithTag("slash-context").assertExists()
        rule.onNodeWithTag("slash-cost").assertExists()
        assertTrue(rule.onAllNodesWithTag("slash-clear").fetchSemanticsNodes().isEmpty())

        rule.onNodeWithTag("composer").performTextInput("mpact now")
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        assertTrue(!panelShown())
    }

    @Test
    fun aCommandWithArgumentsIsInsertedAndTypingContinuesAfterIt() {
        val base = slashSession()
        val vm = show(SlashRepo(base) { listOf(SlashCommand("review", "Review a pull request", "[pr-number]")) })
        rule.onNodeWithTag("composer").performTextInput("/rev")
        rule.waitForIdle()
        rule.onNodeWithText("[pr-number]").assertExists()
        rule.onNodeWithText("Review a pull request").assertExists()

        rule.onNodeWithTag("slash-review").performClick()
        rule.waitForIdle()
        assertEquals("/review ", vm.state.value.draft)
        assertTrue("nothing is sent yet", base.sent.isEmpty())
        // The caret is behind the inserted command, not where it was in "/rev".
        rule.onNodeWithTag("composer").performTextInput("42")
        rule.waitForIdle()
        assertEquals("/review 42", vm.state.value.draft)

        rule.onNodeWithTag("send").performClick()
        rule.waitForIdle()
        assertEquals(listOf("/review 42"), base.sent.map { it.second })
    }

    @Test
    fun codexGetsNoPanel() {
        show(slashSession(agent = "codex", tools = false))
        rule.onNodeWithTag("composer").performTextInput("/")
        rule.waitForIdle()
        assertTrue(!panelShown())
    }
}
