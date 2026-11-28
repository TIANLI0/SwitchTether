package io.github.tianli0.switchtether

import android.graphics.Bitmap
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.tianli0.switchtether.remote.protocol.CapStatus
import io.github.tianli0.switchtether.remote.protocol.SessionDetail
import io.github.tianli0.switchtether.remote.session.AgentEventLog
import io.github.tianli0.switchtether.ui.common.Loadable
import io.github.tianli0.switchtether.ui.home.HomeContent
import io.github.tianli0.switchtether.ui.home.HomeState
import io.github.tianli0.switchtether.ui.sessions.SessionDetailContent
import io.github.tianli0.switchtether.ui.sessions.SessionDetailState
import io.github.tianli0.switchtether.ui.sessions.SessionsContent
import io.github.tianli0.switchtether.ui.sessions.SessionsState
import io.github.tianli0.switchtether.ui.sessions.render.RenderContext
import io.github.tianli0.switchtether.ui.theme.SwitchTetherTheme
import io.github.tianli0.switchtether.ui.theme.ThemeMode
import java.io.File
import org.junit.Rule
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Whole pages rendered for review into build/outputs/screenshots/page-*.png (zh-CN, API 32 so the
 * title bar's non-shader fallback draws). Synthetic sessions; pages that read the app container
 * show their unpaired state.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi")
class PageGalleryScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private fun shot(name: String) {
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(1200)
        val base = rule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread {
            val canvas = android.graphics.Canvas(bitmap)
            rootViews().forEach { v ->
                val loc = IntArray(2).also(v::getLocationOnScreen)
                canvas.save()
                canvas.translate(loc[0].toFloat(), loc[1].toFloat())
                v.draw(canvas)
                canvas.restore()
            }
        }
        val dir = File("build/outputs/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        rule.mainClock.autoAdvance = true
    }

    @Suppress("UNCHECKED_CAST")
    private fun rootViews(): List<View> {
        val global = Class.forName("android.view.WindowManagerGlobal")
        val instance = global.getMethod("getInstance").invoke(null)
        val field = global.getDeclaredField("mViews").apply { isAccessible = true }
        return (field.get(instance) as List<View>).toList()
    }

    private fun page(mode: ThemeMode = ThemeMode.LIGHT, content: @Composable () -> Unit) {
        rule.setContent { SwitchTetherTheme(mode) { content() } }
    }

    private val sessions = listOf(
        summary(id = "w1", status = "waiting").copy(title = "修复登录测试偶发失败", waitingFor = "approval", project = "app", gitBranch = "fix/login"),
        summary(id = "r1", status = "busy", control = "full").copy(title = "重构设置页的按钮层级", project = "SwitchTether", managed = true),
        summary(id = "i1", status = "idle").copy(title = "整理发布说明", project = "docs"),
        summary(id = "i2", status = "idle", agent = "codex").copy(title = "investigate-intermittent-login-timeout-on-ci", project = "infra"),
        summary(id = "e1", live = false).copy(title = "升级 coui17 到 7135f75", project = "SwitchTether"),
        summary(id = "e2", live = false, agent = "codex").copy(title = "端口转发重连", project = "relay"),
    )

    @Test
    fun home() {
        page { HomeContent(HomeState(connection = ONLINE, sessions = Loadable.Ready(sessions)), onOpen = {}, onSelectTab = {}, onRetry = {}, onRefresh = {}) }
        shot("page-home")
    }

    @Test
    fun homeEmpty() {
        page { HomeContent(HomeState(connection = ONLINE, sessions = Loadable.Ready(sessions.takeLast(2))), onOpen = {}, onSelectTab = {}, onRetry = {}, onRefresh = {}) }
        shot("page-home-empty")
    }

    @Test
    fun sessionsList() {
        page {
            SessionsContent(
                SessionsState(items = Loadable.Ready(sessions), nextCursor = "more", connection = ONLINE, knownAgents = listOf("claude_code", "codex"), knownProjects = listOf("app", "docs")),
                onScope = {}, onAgent = {}, onProject = {}, onQuery = {}, onReload = {}, onLoadMore = {}, onRetryConnection = {}, onOpen = {},
            )
        }
        shot("page-sessions")
    }

    private fun detail(mode: ThemeMode, vararg events: io.github.tianli0.switchtether.remote.protocol.AgentEvent, control: String = "full", send: String = "VERIFIED") {
        val state = SessionDetailState(
            sessionId = "s1",
            detail = Loadable.Ready(SessionDetail(summary(control = control, status = "waiting").copy(managed = control == "full"), caps(send = send, approve = "VERIFIED", interrupt = "VERIFIED", continueSession = "PARTIAL"), emptyList())),
            events = Loadable.Ready(Unit),
            connection = ONLINE,
        ).derive(log = AgentEventLog.of(events.toList()))
        val ctx = RenderContext(
            agent = "claude_code", agentLabel = "Claude Code", approve = CapStatus.VERIFIED, answer = CapStatus.VERIFIED, capabilityReason = null, online = true,
            onApprove = { _, _, _ -> }, onAnswer = { _, _ -> }, onLoadPayload = {}, payloadOf = { null }, onToggleCollapse = {}, isCollapsed = { false }, onLink = {},
        )
        page(mode) {
            SessionDetailContent(
                state = state, initialTitle = null, ctx = ctx, busy = null,
                onBack = {}, onReload = {}, onLoadOlder = {}, onToggleSteps = {}, onDraft = {}, onSend = {}, onInterrupt = {},
                onContinue = {}, onRetryConnection = {},
            )
        }
    }

    private val approval = event(
        "ap1", 4, "approval_request",
        """{"requestId":"q1","kind":"command","title":"运行 rm -rf build","detail":"清理构建目录","command":"rm -rf build","options":[{"id":"allow","label":"Allow"},{"id":"allow_session","label":"Always allow"},{"id":"deny","label":"Deny"}],"resolved":false}""",
    )

    @Test
    fun sessionDetailApproval() {
        detail(
            ThemeMode.LIGHT,
            event("u1", 1, "user_message", """{"text":"把构建目录清掉再跑一遍测试","source":"phone"}"""),
            event("a1", 2, "assistant_message", """{"text":"好的，我先清理 **build** 目录。","model":"claude-x"}"""),
            event("t1", 3, "tool_result", """{"callId":"c0","isError":false,"text":"${"line of output\\n".repeat(400)}","truncated":true,"bytes":90000,"exitCode":0}"""),
            approval,
        )
        shot("page-session-approval")
    }

    @Test
    fun sessionDetailReadOnlyDark() {
        detail(
            ThemeMode.DARK,
            event("u1", 1, "user_message", """{"text":"修一下登录测试","source":"desktop"}"""),
            event("a1", 2, "assistant_message", """{"text":"我先跑一遍测试看看失败的用例。"}"""),
            control = "observe", send = "BLOCKED",
        )
        shot("page-session-readonly-dark")
    }

    @Test fun settings() { page { io.github.tianli0.switchtether.ui.settings.SettingsScreen(onOpen = {}) }; shot("page-settings") }

    @Test
    fun appearanceChoiceUpdatesTheSettingsRow() {
        page { io.github.tianli0.switchtether.ui.settings.SettingsScreen(onOpen = {}) }
        val appearance = rule.activity.getString(R.string.settings_appearance)
        val dark = rule.activity.getString(R.string.theme_dark)
        rule.onNodeWithText(appearance).performClick()
        rule.onNodeWithTag("choice-2").assertIsDisplayed().performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText(dark).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText(dark).assertIsDisplayed()
        rule.onNodeWithText(appearance).performClick()
        rule.onNodeWithTag("choice-0").performClick()
    }

    @Test
    fun searchBarCanFinishItsExpandSpring() {
        val expanded = mutableStateOf(false)
        page {
            io.github.suqi8.coui.kmp.basic.SearchBar(
                inputField = { Box(Modifier.fillMaxWidth().height(36.dp)) },
                onExpandedChange = { expanded.value = it },
                expanded = expanded.value,
                outsideEndAction = { io.github.suqi8.coui.kmp.basic.Text("Cancel") },
            ) {}
        }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        try {
            rule.runOnIdle { expanded.value = true }
            repeat(50) {
                rule.mainClock.advanceTimeByFrame()
                rule.waitForIdle()
            }
            rule.onNodeWithText("Cancel").assertIsDisplayed()
        } finally {
            rule.mainClock.autoAdvance = true
        }
    }

    @Test
    fun panelWrapsShortContentAndCapsTallContentAtTheTopInset() {
        val tall = mutableStateOf(false)
        page {
            io.github.tianli0.coui17.patterns.PanelSheet(
                show = true,
                title = "Panel",
                onDismissRequest = {},
                modifier = Modifier.testTag("measured-panel"),
            ) {
                Box(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    Box(Modifier.height(if (tall.value) 1_500.dp else 80.dp))
                }
            }
        }
        val panel = rule.onNodeWithTag("measured-panel")
        val shortHeight = panel.fetchSemanticsNode().size.height
        rule.runOnIdle { tall.value = true }
        val tallHeight = panel.fetchSemanticsNode().size.height
        val window = rule.activity.window.decorView
        val topInset = window.rootWindowInsets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.statusBars()).top
        val gap = 8 * window.resources.displayMetrics.density
        assertTrue("Short panel should wrap its content", shortHeight < tallHeight / 2)
        val unfilledTop = window.height - tallHeight
        assertTrue("Tall panel top was $unfilledTop px with status inset $topInset px",
            unfilledTop in (topInset - 2)..(topInset + gap + 4).toInt())
    }
    @Test fun manage() { page { io.github.tianli0.switchtether.ui.manage.ManageScreen(onOpen = {}) }; shot("page-manage") }
    @Test fun workspace() { page { io.github.tianli0.switchtether.ui.workspace.WorkspaceScreen(onOpen = {}) }; shot("page-workspace") }
    @Test fun notifications() { page { io.github.tianli0.switchtether.ui.settings.NotificationPrefsScreen(onBack = {}) }; shot("page-notifications") }
    @Test fun downloads() { page { io.github.tianli0.switchtether.ui.files.DownloadsScreen(onBack = {}) }; shot("page-downloads") }
    @Test fun portForward() { page { io.github.tianli0.switchtether.ui.workspace.PortForwardScreen(onBack = {}) }; shot("page-port-forward") }
    @Test fun pickFolder() { page { io.github.tianli0.switchtether.ui.sessions.PickFolderScreen(onBack = {}) }; shot("page-pick-folder") }
    @Test fun providers() { page { io.github.tianli0.switchtether.ui.manage.ProvidersScreen(onBack = {}) }; shot("page-providers") }
    @Test fun desktopInfo() { page { io.github.tianli0.switchtether.ui.settings.DesktopInfoScreen(onBack = {}) }; shot("page-desktop-info") }
}
