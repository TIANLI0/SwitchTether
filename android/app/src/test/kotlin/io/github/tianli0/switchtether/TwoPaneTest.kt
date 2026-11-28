package io.github.tianli0.switchtether

import android.graphics.Bitmap
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.tianli0.coui17.patterns.pageContainerColor
import io.github.tianli0.switchtether.remote.protocol.CapStatus
import io.github.tianli0.switchtether.remote.protocol.SessionDetail
import io.github.tianli0.switchtether.remote.session.AgentEventLog
import io.github.tianli0.switchtether.ui.common.Loadable
import io.github.tianli0.switchtether.ui.nav.ListDetailPanes
import io.github.tianli0.switchtether.ui.nav.LocalSelectedSessionId
import io.github.tianli0.switchtether.ui.nav.NavViewModel
import io.github.tianli0.switchtether.ui.nav.PaneEmptyState
import io.github.tianli0.switchtether.ui.nav.Route
import io.github.tianli0.switchtether.ui.nav.Tab
import io.github.tianli0.switchtether.ui.nav.listPaneWidth
import io.github.tianli0.switchtether.ui.nav.usesTwoPanes
import io.github.tianli0.switchtether.ui.sessions.SessionDetailContent
import io.github.tianli0.switchtether.ui.sessions.SessionDetailState
import io.github.tianli0.switchtether.ui.sessions.SessionsContent
import io.github.tianli0.switchtether.ui.sessions.SessionsState
import io.github.tianli0.switchtether.ui.sessions.render.RenderContext
import io.github.tianli0.switchtether.ui.theme.SwitchTetherTheme
import io.github.tianli0.switchtether.ui.theme.ThemeMode
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Tablet / unfolded-foldable list | detail layout (docs/ANDROID_UI.md §27). Synthetic data. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "zh-rCN-w1280dp-h800dp-xhdpi")
class TwoPaneTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test
    fun phonesAndFoldedFoldablesKeepOnePage() {
        assertFalse(usesTwoPanes(411.dp)) // phone portrait
        assertFalse(usesTwoPanes(673.dp)) // a book foldable's inner screen in split screen
        assertFalse(usesTwoPanes(839.dp))
        assertTrue(usesTwoPanes(840.dp))
        assertTrue(usesTwoPanes(1280.dp))
        assertEquals(320.dp, listPaneWidth(700.dp))
        assertEquals(420.dp, listPaneWidth(1200.dp))
    }

    @Test
    fun rowsOpenedFromTheListReplaceTheDetailAndBackClearsIt() {
        val nav = NavViewModel()
        nav.select(Tab.SESSIONS)
        nav.openFromList(Route.SessionDetail("a", null))
        nav.openFromList(Route.SessionDetail("b", null))
        assertEquals(Route.SessionDetail("b", null), nav.selection)
        assertTrue(nav.back())
        assertEquals(null, nav.selection)
        assertEquals(Tab.SESSIONS, nav.tab)
    }

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

    private val sessions = listOf(
        summary(id = "w1", status = "waiting").copy(title = "修复登录测试偶发失败", waitingFor = "approval", project = "app", gitBranch = "fix/login"),
        summary(id = "r1", status = "busy", control = "full").copy(title = "重构设置页的按钮层级", project = "SwitchTether", managed = true),
        summary(id = "i1", status = "idle").copy(title = "整理发布说明", project = "docs"),
        summary(id = "e1", live = false).copy(title = "升级 coui17 到 7135f75", project = "SwitchTether"),
    )

    @Composable
    private fun list() = SessionsContent(
        SessionsState(items = Loadable.Ready(sessions), connection = ONLINE, knownAgents = listOf("claude_code", "codex"), knownProjects = listOf("app", "docs")),
        onScope = {}, onAgent = {}, onProject = {}, onQuery = {}, onReload = {}, onLoadMore = {}, onRetryConnection = {}, onOpen = {},
    )

    private fun panes(mode: ThemeMode, selected: String?, detail: @Composable () -> Unit) {
        rule.setContent {
            SwitchTetherTheme(mode) {
                Box(Modifier.fillMaxSize().background(pageContainerColor())) {
                    CompositionLocalProvider(LocalSelectedSessionId provides selected) {
                        ListDetailPanes(list = { list() }, detail = detail)
                    }
                }
            }
        }
    }

    @Test
    fun sessionsBesideTheOpenSession() {
        val state = SessionDetailState(
            sessionId = "r1",
            detail = Loadable.Ready(SessionDetail(sessions[1], caps(send = "VERIFIED", approve = "VERIFIED", interrupt = "VERIFIED"), emptyList())),
            events = Loadable.Ready(Unit),
            connection = ONLINE,
        ).derive(
            log = AgentEventLog.of(
                listOf(
                    event("u1", 1, "user_message", """{"text":"把设置页的按钮按层级整理一下"}"""),
                    event("a1", 2, "assistant_message", """{"text":"好的，我先看一下 **SettingsScreens.kt** 里用到的按钮。"}"""),
                    event("r", 3, "usage", """{"inputTokens":120,"outputTokens":400,"cacheReadTokens":18000,"costUsd":0.18}"""),
                ),
            ),
        )
        val ctx = RenderContext(
            agent = "claude_code", agentLabel = "Claude Code", approve = CapStatus.VERIFIED, answer = CapStatus.VERIFIED, capabilityReason = null, online = true,
            onApprove = { _, _, _ -> }, onAnswer = { _, _ -> }, onLoadPayload = {}, payloadOf = { null }, onToggleCollapse = {}, isCollapsed = { false }, onLink = {},
        )
        panes(ThemeMode.LIGHT, "r1") {
            SessionDetailContent(
                state = state, initialTitle = null, ctx = ctx, busy = null,
                onBack = {}, onReload = {}, onLoadOlder = {}, onToggleSteps = {}, onDraft = {}, onSend = {}, onInterrupt = {},
                onContinue = {}, onRetryConnection = {},
            )
        }
        rule.onNodeWithTag("session-row-selected", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag("pane-detail").assertIsDisplayed()
        shot("tablet-sessions-detail")
    }

    @Test
    fun nothingChosenYet() {
        panes(ThemeMode.DARK, null) { PaneEmptyState(Tab.SESSIONS) }
        rule.onNodeWithTag("pane-empty").assertIsDisplayed()
        shot("tablet-sessions-empty-dark")
    }
}
