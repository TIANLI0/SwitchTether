package io.github.tianli0.switchtether

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.tianli0.switchtether.remote.protocol.CapStatus
import io.github.tianli0.switchtether.remote.protocol.SessionCapabilities
import io.github.tianli0.switchtether.remote.protocol.SessionDetail
import io.github.tianli0.switchtether.remote.protocol.SessionSummary
import io.github.tianli0.switchtether.remote.session.AgentEventLog
import io.github.tianli0.switchtether.ui.common.Loadable
import io.github.suqi8.coui.kmp.basic.CardListPosition
import io.github.tianli0.switchtether.ui.sessions.SessionDetailContent
import io.github.tianli0.switchtether.ui.sessions.SessionDetailState
import io.github.tianli0.switchtether.ui.sessions.SessionRow
import io.github.tianli0.switchtether.ui.sessions.render.RenderContext
import io.github.tianli0.switchtether.ui.theme.SwitchTetherTheme
import io.github.tianli0.switchtether.ui.theme.ThemeMode
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A Codex Desktop thread SwitchTether is attached to (origin desktop_app, full control, not
 * managed, no title/model): the header, badge, chips and composer in light and dark. Images go to
 * build/outputs/screenshots/ for review.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi")
class AttachedCodexScreenshotTest {
    @get:Rule
    val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val codex = SessionSummary(
        id = "codex:019a2b3c-4d5e-6f70-8192-a3b4c5d6e7f8",
        agent = "codex",
        origin = "desktop_app",
        nativeId = "019a2b3c-4d5e-6f70-8192-a3b4c5d6e7f8",
        title = null,
        cwd = "C:\\Users\\me\\Documents\\Codex\\2026-11-14\\fix-flaky-login-test",
        project = null,
        liveness = "live",
        status = "busy",
        control = "full",
        managed = false,
        model = null,
        lastActiveAtMs = 1_794_600_000_000,
    )

    // Attached non-terminal session: history verified, the rest partial, questions untested.
    private val caps = SessionCapabilities(
        readHistory = "VERIFIED", liveEvents = "PARTIAL", sendMessage = "PARTIAL", approve = "PARTIAL",
        answerQuestion = "UNTESTED", interrupt = "PARTIAL", continueSession = null, reason = null,
    )

    private fun state(): SessionDetailState = SessionDetailState(
        sessionId = codex.id,
        detail = Loadable.Ready(SessionDetail(codex, caps, emptyList())),
        events = Loadable.Ready(Unit),
        connection = ONLINE,
    ).derive(
        log = AgentEventLog.of(
            listOf(
                event("u1", 1, "user_message", """{"text":"修一下登录测试偶尔失败的问题","source":"desktop"}""", session = codex.id),
                event("a1", 2, "assistant_message", """{"text":"我先跑一遍测试看看失败的用例。"}""", session = codex.id),
                event("t1", 3, "tool_call", """{"callId":"c1","name":"shell","title":"npm test -- login","category":"shell","status":"running","input":{"command":"npm test -- login"}}""", session = codex.id),
            ),
        ),
    )

    private val ctx = RenderContext(
        agent = "codex", agentLabel = "Codex", approve = CapStatus.PARTIAL, answer = CapStatus.UNTESTED, capabilityReason = null, online = true,
        onApprove = { _, _, _ -> }, onAnswer = { _, _ -> }, onLoadPayload = {}, payloadOf = { null }, onToggleCollapse = {}, isCollapsed = { false }, onLink = {},
    )

    private fun shot(name: String) {
        // Spinners animate forever: freeze the clock so the capture does not wait for idle.
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(500)
        // Software draw of the window (captureToImage stalls on Robolectric). Screens whose app bar
        // uses coui's RuntimeShader masks cannot be drawn this way; those tests assert semantics only.
        val view = rule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(android.graphics.Canvas(bitmap)) }
        val dir = File("build/outputs/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun render(mode: ThemeMode) {
        rule.setContent {
            SwitchTetherTheme(mode) {
                SessionDetailContent(
                    state = state(), initialTitle = null, ctx = ctx, busy = null,
                    onBack = {}, onReload = {}, onLoadOlder = {}, onToggleSteps = {}, onDraft = {}, onSend = {}, onInterrupt = {},
                    onContinue = {}, onRetryConnection = {},
                )
            }
        }
    }

    private fun assertAttachedChrome() {
        // No conversation title yet: shown as unnamed, and the project is NOT used as the task
        // title (it appears in the subtitle instead); the badge names the client.
        rule.onNodeWithText("未命名会话").assertExists()
        rule.onNodeWithText("fix-flaky-login-test", substring = true).assertExists()
        rule.onNodeWithText("已接管 · Codex Desktop").assertExists()
        rule.onNodeWithTag("send").assertExists()
        // Full control: no read-only / blocked tip and no "continue on phone".
        assertTrue(rule.onAllNodesWithTag("readonly-tip").fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodesWithTag("continue").fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodesWithText("在手机上继续", substring = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun detailLight() {
        render(ThemeMode.LIGHT)
        assertAttachedChrome()
    }

    @Test
    fun detailDark() {
        render(ThemeMode.DARK)
        assertAttachedChrome()
    }

    @Test
    @Config(qualifiers = "zh-rCN-w360dp-h740dp-xxhdpi")
    fun rowsLightAndDark() {
        val long = codex.copy(cwd = "C:\\Users\\me\\Documents\\Codex\\2026-11-14\\investigate-intermittent-login-timeout-on-ci")
        rule.setContent {
            Column {
                listOf(ThemeMode.LIGHT, ThemeMode.DARK).forEach { mode ->
                    SwitchTetherTheme(mode) {
                        Column(Modifier.background(io.github.tianli0.coui17.patterns.pageContainerColor()).padding(vertical = 8.dp)) {
                            SessionRow(codex, onClick = {}, position = CardListPosition.None)
                            SessionRow(long, onClick = {}, position = CardListPosition.None, onLongClick = {})
                            SessionRow(long.copy(status = "idle"), onClick = {}, position = CardListPosition.None, compact = true)
                        }
                    }
                }
            }
        }
        shot("attached-codex-rows")
    }
}
