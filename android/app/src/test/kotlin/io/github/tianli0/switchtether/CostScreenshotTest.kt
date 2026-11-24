package io.github.tianli0.switchtether

import android.graphics.Bitmap
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.tianli0.coui17.patterns.pageContainerColor
import io.github.tianli0.switchtether.remote.protocol.CapStatus
import io.github.tianli0.switchtether.remote.protocol.ProjectUsage
import io.github.tianli0.switchtether.remote.protocol.ProjectUsageView
import io.github.tianli0.switchtether.remote.protocol.SessionDetail
import io.github.tianli0.switchtether.remote.protocol.SessionUsage
import io.github.tianli0.switchtether.remote.protocol.UsageTotals
import io.github.tianli0.switchtether.remote.session.AgentEventLog
import io.github.tianli0.switchtether.ui.common.Loadable
import io.github.tianli0.switchtether.ui.manage.ProjectUsageContent
import io.github.tianli0.switchtether.ui.manage.ProjectUsageState
import io.github.tianli0.switchtether.ui.manage.UsageContent
import io.github.tianli0.switchtether.ui.manage.UsageState
import io.github.tianli0.switchtether.ui.sessions.SessionCostSheet
import io.github.tianli0.switchtether.ui.sessions.SessionDetailContent
import io.github.tianli0.switchtether.ui.sessions.SessionDetailState
import io.github.tianli0.switchtether.ui.sessions.render.RenderContext
import io.github.tianli0.switchtether.ui.sessions.sessionUsageBreakdown
import io.github.tianli0.switchtether.ui.theme.SwitchTetherTheme
import io.github.tianli0.switchtether.ui.theme.ThemeMode
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The session cost sheet, the cost capsule and usage per project (docs/ANDROID_UI.md §26), rendered
 * into build/outputs/screenshots/cost-*.png with their interactions. Synthetic data only.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [32], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi")
class CostScreenshotTest {
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
        rule.setContent { SwitchTetherTheme(mode) { Box(Modifier.fillMaxSize().background(pageContainerColor())) { content() } } }
    }

    /** Twelve turns of a session SwitchTether runs: running totals, one restart after /clear. */
    private val costedEvents = buildList {
        var total = 0.0
        val prompts = listOf("把构建目录清掉再跑一遍测试", "修复登录测试偶发失败", "加上重试", "看看 CI 日志", "整理提交", "/clear", "重新读一下 README", "补充文档", "改一下按钮层级", "跑 lint", "修 lint", "写发布说明")
        prompts.forEachIndexed { i, p ->
            add(event("u$i", (i * 2 + 1).toLong(), "user_message", """{"text":"$p"}"""))
            if (i == 6) total = 0.0
            total += 0.02 + (i % 4) * 0.035 + if (i == 3) 0.21 else 0.0
            val input = 200 + i * 40
            val read = 9000 + i * 2600
            add(event("result:$i", (i * 2 + 2).toLong(), "usage", """{"inputTokens":$input,"outputTokens":${300 + (i % 5) * 220},"cacheReadTokens":$read,"cacheWriteTokens":${if (i % 3 == 0) 1800 else 200},"costUsd":$total,"durationMs":${12000 + i * 3500}}"""))
        }
    }

    private fun sheet(mode: ThemeMode, events: List<io.github.tianli0.switchtether.remote.protocol.AgentEvent>, agent: String = "claude_code", partial: Boolean = false, onProject: (String) -> Unit = {}) {
        page(mode) {
            SessionCostSheet(
                show = true,
                breakdown = sessionUsageBreakdown(events, agent),
                partial = partial,
                project = "SwitchTether",
                onOpenProjectUsage = onProject,
                onDismiss = {},
            )
        }
    }

    @Test
    fun costSheetLight() {
        var opened: String? = null
        sheet(ThemeMode.LIGHT, costedEvents, partial = true, onProject = { opened = it })
        rule.onNodeWithTag("cost-total").assertIsDisplayed()
        shot("cost-sheet-light")
        // Tapping the first bar picks turn 1.
        rule.onNodeWithTag("cost-chart").performTouchInput { click(androidx.compose.ui.geometry.Offset(4f, height / 2f)) }
        rule.onNodeWithText("第 1 轮").assertIsDisplayed()
        rule.onNodeWithText("把构建目录清掉再跑一遍测试").assertIsDisplayed()
        // Tokens: stacked bars and the legend.
        rule.onNodeWithText("Token", useUnmergedTree = true).performClick()
        shot("cost-sheet-tokens")
        rule.onNodeWithTag("cost-project-link").performScrollTo().performClick()
        assertEquals("SwitchTether", opened)
    }

    @Test
    fun costSheetDark() {
        sheet(ThemeMode.DARK, costedEvents)
        shot("cost-sheet-dark")
    }

    @Test
    fun observedSessionShowsTokensOnly() {
        val events = (0 until 30).flatMap { i ->
            listOf(
                event("u$i", i * 2L + 1, "user_message", """{"text":"第 $i 个问题"}"""),
                event("m$i:usage", i * 2L + 2, "usage", """{"inputTokens":${12000 + i * 900},"outputTokens":${150 + (i * 37) % 900},"cacheReadTokens":${8000 + i * 400}}"""),
            )
        }
        sheet(ThemeMode.LIGHT, events, agent = "codex")
        rule.onNodeWithText("本会话 Token").assertIsDisplayed()
        shot("cost-sheet-tokens-only")
    }

    @Test
    fun costCapsuleOnTheSessionPage() {
        val state = SessionDetailState(
            sessionId = "s1",
            detail = Loadable.Ready(SessionDetail(summary(control = "full", status = "idle").copy(managed = true, title = "修复登录测试偶发失败", gitBranch = "fix/login"), caps(send = "VERIFIED", approve = "VERIFIED", interrupt = "VERIFIED"), emptyList())),
            events = Loadable.Ready(Unit),
            connection = ONLINE,
        ).derive(log = AgentEventLog.of(costedEvents + event("a1", 99, "assistant_message", """{"text":"测试已经全部通过。"}""")))
        val ctx = RenderContext(
            agent = "claude_code", agentLabel = "Claude Code", approve = CapStatus.VERIFIED, answer = CapStatus.VERIFIED, capabilityReason = null, online = true,
            onApprove = { _, _, _ -> }, onAnswer = { _, _ -> }, onLoadPayload = {}, payloadOf = { null }, onToggleCollapse = {}, isCollapsed = { false }, onLink = {},
        )
        page {
            SessionDetailContent(
                state = state, initialTitle = null, ctx = ctx, busy = null,
                onBack = {}, onReload = {}, onLoadOlder = {}, onToggleSteps = {}, onDraft = {}, onSend = {}, onInterrupt = {},
                onContinue = {}, onRetryConnection = {}, onOpenProjectUsage = {},
            )
        }
        rule.onNodeWithTag("chip-cost", useUnmergedTree = true).assertIsDisplayed()
        shot("cost-capsule")
        rule.onNodeWithTag("chip-cost", useUnmergedTree = true).performClick()
        rule.onNodeWithTag("cost-total").assertIsDisplayed()
        shot("cost-capsule-sheet")
    }

    private fun totals(cost: Double, tokens: Long) = UsageTotals(requests = 12, costUsd = cost, inputTokens = tokens / 10, outputTokens = tokens / 10, cacheReadTokens = tokens * 8 / 10, lastMs = System.currentTimeMillis() - 3_600_000)

    private fun session(id: String, title: String?, cost: Double, app: String = "claude") = SessionUsage(
        id = id, app = app, title = title, cwd = "D:/develop/SwitchTether", requests = 20, costUsd = cost,
        inputTokens = 40_000, outputTokens = 12_000, cacheReadTokens = 1_800_000, cacheCreationTokens = 60_000, lastMs = System.currentTimeMillis() - 7_200_000,
    )

    private val projects = ProjectUsageView(
        projects = listOf(
            ProjectUsage("SwitchTether", "D:/develop/SwitchTether", 14, 410, 18.42, 520_000, 180_000, 24_000_000, 960_000, null, listOf(session("claude:a", "重构设置页的按钮层级", 6.1))),
            ProjectUsage("COUI17", "D:/develop/COUI17", 5, 120, 4.07, 120_000, 40_000, 5_000_000, 200_000),
            ProjectUsage("relay", "D:/develop/relay", 2, 33, 0.92, 30_000, 9_000, 900_000, 40_000),
        ),
        unattributed = totals(1.3, 400_000),
    )

    @Test
    fun usagePerProject() {
        var opened: String? = null
        page { UsageContent(UsageState(summary = Loadable.Loading, trend = Loadable.Loading, models = Loadable.Ready(emptyList()), providers = Loadable.Ready(emptyList()), logs = null, projects = Loadable.Ready(projects)), onRetry = {}, onOpenProject = { opened = it }) }
        rule.onNodeWithTag("usage-list").performTouchInput { swipeUp() }
        rule.onNodeWithText("COUI17").performClick()
        assertEquals("COUI17", opened)
        shot("cost-usage-projects")
    }

    @Test
    fun projectPage() {
        var opened: String? = null
        val p = projects.projects.first().copy(
            sessions = listOf(
                session("claude:a", "重构设置页的按钮层级", 6.1),
                session("codex:b", "investigate-intermittent-login-timeout-on-ci", 4.2, app = "codex"),
                session("claude:c", null, 2.4),
                session("claude:d", "升级 coui17 到 7135f75", 1.1),
            ),
        )
        page { ProjectUsageContent(ProjectUsageState("SwitchTether", usage = Loadable.Ready(p)), onPreset = {}, onRetry = {}, onOpenSession = { id, _ -> opened = id }) }
        rule.onNodeWithText("未命名会话").performClick()
        assertEquals("claude:c", opened)
        shot("cost-project-page")
    }

    @Test
    fun projectPageOnAnOlderDesktop() {
        page { Column { ProjectUsageContent(ProjectUsageState("SwitchTether", usage = null), onPreset = {}, onRetry = {}, onOpenSession = { _, _ -> }) } }
        rule.onNodeWithText("电脑上的 SwitchTether 还不支持按项目统计用量，更新后即可查看。").assertIsDisplayed()
    }
}
