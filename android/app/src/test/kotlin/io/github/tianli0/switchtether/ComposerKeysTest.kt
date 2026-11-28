package io.github.tianli0.switchtether

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.tianli0.switchtether.remote.protocol.CapStatus
import io.github.tianli0.switchtether.ui.sessions.DropPayload
import io.github.tianli0.switchtether.ui.sessions.SessionDetailContent
import io.github.tianli0.switchtether.ui.sessions.SessionDetailViewModel
import io.github.tianli0.switchtether.ui.sessions.render.RenderContext
import io.github.tianli0.switchtether.ui.theme.SwitchTetherTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The composer's hardware keys and its state-based field, through the real view model (§35). */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ComposerKeysTest {
    @get:Rule
    val rule = createComposeRule()

    private val ctx = RenderContext(
        agent = "claude_code", agentLabel = "Claude Code", approve = CapStatus.VERIFIED, answer = CapStatus.VERIFIED, capabilityReason = null, online = true,
        onApprove = { _, _, _ -> }, onAnswer = { _, _ -> }, onLoadPayload = {}, payloadOf = { null }, onToggleCollapse = {}, isCollapsed = { false }, onLink = {},
    )
    private val intakes = mutableListOf<Pair<DropPayload, Boolean>>()

    private fun show(repo: FakeSessionRepository): SessionDetailViewModel {
        val vm = SessionDetailViewModel("s1", FakeConnection(), repo, FakeDrafts())
        rule.setContent {
            val state by vm.state.collectAsState()
            SwitchTetherTheme {
                SessionDetailContent(
                    state = state, initialTitle = null, ctx = ctx, busy = null,
                    onBack = {}, onReload = {}, onLoadOlder = {}, onToggleSteps = {}, onDraft = vm::setDraft, onSend = vm::send, onInterrupt = {},
                    onContinue = {}, onRetryConnection = {},
                    onIntake = { p, inField -> intakes += p to inField },
                    draftFlow = vm.draft,
                )
            }
        }
        rule.waitForIdle()
        return vm
    }

    @Test
    fun ctrlEnterSendsAndEnterDoesNot() {
        val repo = slashSession()
        val vm = show(repo)
        rule.onNodeWithTag("composer").performTextInput("hello")
        rule.waitForIdle()
        assertEquals("hello", vm.draft.value)

        rule.onNodeWithTag("composer").performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        assertTrue("Enter is a new line, not a send", repo.sent.isEmpty())

        rule.onNodeWithTag("composer").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Enter) } }
        rule.waitForIdle()
        assertEquals(1, repo.sent.size)
        assertEquals("hello", repo.sent.single().second.trim())
        assertEquals("the field follows the cleared draft", "", vm.draft.value)
        rule.onNodeWithTag("composer").performTextInput("next")
        rule.waitForIdle()
        assertEquals("next", vm.draft.value)
    }

    @Test
    fun ctrlEnterWithNothingToSendDoesNothing() {
        val repo = slashSession()
        show(repo)
        rule.onNodeWithTag("composer").performTextInput(" ")
        rule.onNodeWithTag("composer").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Enter) } }
        rule.waitForIdle()
        assertTrue(repo.sent.isEmpty())
    }

    @Test
    fun noDropHighlightWithoutADrag() {
        show(slashSession())
        assertTrue(rule.onAllNodesWithTag("drop-highlight").fetchSemanticsNodes().isEmpty())
        assertTrue(intakes.isEmpty())
    }
}
