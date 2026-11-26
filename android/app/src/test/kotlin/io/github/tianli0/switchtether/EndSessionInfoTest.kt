package io.github.tianli0.switchtether

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.tianli0.switchtether.remote.protocol.CapStatus
import io.github.tianli0.switchtether.remote.protocol.SessionDetail
import io.github.tianli0.switchtether.ui.sessions.SessionDetailContent
import io.github.tianli0.switchtether.ui.sessions.SessionDetailViewModel
import io.github.tianli0.switchtether.ui.sessions.render.RenderContext
import io.github.tianli0.switchtether.ui.theme.SwitchTetherTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * "End session" in the session info sheet: the only place a Codex session SwitchTether started can
 * reach it (it has no Claude Code tools page). Wired like the screen: offered only while `canStop`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-w411dp-h891dp-xxhdpi")
class EndSessionInfoTest {
    @get:Rule
    val rule = createComposeRule()

    private val ctx = RenderContext(
        agent = "codex", agentLabel = "Codex", approve = CapStatus.VERIFIED, answer = CapStatus.VERIFIED, capabilityReason = null, online = true,
        onApprove = { _, _, _ -> }, onAnswer = { _, _ -> }, onLoadPayload = {}, payloadOf = { null }, onToggleCollapse = {}, isCollapsed = { false }, onLink = {},
    )

    private fun session(managed: Boolean, live: Boolean = true) = FakeSessionRepository().apply {
        detail = SessionDetail(
            summary(control = if (managed) "full" else "observe", status = "idle", agent = "codex", live = live)
                .let { if (managed) it.copy(managed = true, origin = "managed") else it },
            caps(send = if (managed) "VERIFIED" else "BLOCKED"),
            emptyList(),
        )
    }

    private fun show(repo: FakeSessionRepository, onEnd: () -> Unit): SessionDetailViewModel {
        val vm = SessionDetailViewModel("s1", FakeConnection(), repo, FakeDrafts())
        rule.setContent {
            val state by vm.state.collectAsState()
            SwitchTetherTheme {
                SessionDetailContent(
                    state = state, initialTitle = null, ctx = ctx, busy = null,
                    onBack = {}, onReload = {}, onLoadOlder = {}, onToggleSteps = {}, onDraft = vm::setDraft, onSend = vm::send, onInterrupt = {},
                    onContinue = {}, onRetryConnection = {},
                    onEndSession = if (state.canStop) onEnd else null,
                )
            }
        }
        rule.waitForIdle()
        return vm
    }

    @Test
    fun aManagedCodexSessionCanBeEndedFromTheInfoSheet() {
        var ended = 0
        val vm = show(session(managed = true)) { ended++ }
        assertTrue(vm.state.value.canStop)
        rule.onNodeWithTag("session-info-row").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("info-end-session").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(1, ended)
    }

    @Test
    fun aSessionStartedOnTheComputerHasNoEndEntry() {
        val vm = show(session(managed = false)) { error("not offered") }
        assertFalse(vm.state.value.canStop)
        rule.onNodeWithTag("session-info-row").performClick()
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithTag("info-end-session").fetchSemanticsNodes().isEmpty())
    }
}
