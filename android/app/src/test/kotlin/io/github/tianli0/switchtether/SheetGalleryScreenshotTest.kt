package io.github.tianli0.switchtether

import android.graphics.Bitmap
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.basic.CardListPosition
import io.github.suqi8.coui.kmp.basic.SmallTitle
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.layout.DialogButtonBarAction
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.tianli0.switchtether.connection.ConnectionState
import io.github.tianli0.switchtether.remote.pairing.PairingLink
import io.github.tianli0.switchtether.remote.update.ReleaseInfo
import io.github.tianli0.switchtether.remote.update.SemVer
import io.github.tianli0.coui17.patterns.PanelSheet
import io.github.tianli0.coui17.patterns.GroupCard
import io.github.tianli0.coui17.patterns.SheetButtons
import io.github.tianli0.coui17.patterns.SheetConfirm
import io.github.tianli0.coui17.patterns.PageDefaults
import io.github.tianli0.coui17.patterns.pageContainerColor
import io.github.tianli0.coui17.patterns.ActionTone
import io.github.tianli0.coui17.patterns.LinkButton
import io.github.tianli0.coui17.patterns.SmallButton
import io.github.tianli0.switchtether.ui.onboarding.OnboardingContent
import io.github.tianli0.switchtether.ui.onboarding.PairingUiState
import io.github.tianli0.switchtether.ui.sessions.NewSessionContent
import io.github.tianli0.switchtether.ui.sessions.NewSessionState
import io.github.tianli0.switchtether.ui.sessions.RecentProject
import io.github.tianli0.switchtether.ui.settings.UpdateSheet
import io.github.tianli0.switchtether.ui.theme.SwitchTetherTheme
import io.github.tianli0.switchtether.ui.theme.ThemeMode
import io.github.tianli0.switchtether.update.UpdateState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Half-screen panels, "new …" flows and the button tiers (docs/ANDROID_UI.md §22), rendered for
 * review into build/outputs/screenshots/ui-*.png, with the behaviour that must survive the layout:
 * the panel's confirm capsule and the button bar act and disable like the buttons they replaced.
 * Synthetic data only.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi")
class SheetGalleryScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private fun shot(name: String) {
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(1200)
        // A panel is a Dialog window: draw every root view of the process, the dialog last.
        val roots = rootViews()
        val base = rule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread {
            val canvas = android.graphics.Canvas(bitmap)
            roots.forEach { v ->
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

    private fun page(mode: ThemeMode, content: @Composable () -> Unit) {
        rule.setContent {
            SwitchTetherTheme(mode) {
                Box(Modifier.fillMaxSize().background(pageContainerColor())) { content() }
            }
        }
    }

    private val online = NewSessionState(
        agent = "claude_code",
        cwd = "D:\\develop\\SwitchTether",
        recent = listOf(
            RecentProject("D:\\develop\\SwitchTether", "SwitchTether"),
            RecentProject("D:\\develop\\coui17", "coui17"),
            RecentProject("D:\\work\\notes", "notes"),
        ),
        connection = ConnectionState.Unpaired,
    )

    @Composable
    private fun NewSession(state: NewSessionState, onStart: () -> Unit = {}, canStart: Boolean = true) {
        PanelSheet(
            show = true,
            title = "新建会话",
            onDismissRequest = {},
            confirm = SheetConfirm("启动", enabled = canStart, testTag = "start-session", onClick = onStart),
        ) {
            NewSessionContent(state, busy = false, onAgent = {}, onSelect = {}, onPickFolder = {}, onPrompt = {}, onModel = {}, onPermissionMode = {})
        }
    }

    @Test
    fun newSessionPanelStartsFromItsToolbar() {
        var started = 0
        page(ThemeMode.LIGHT) { NewSession(online, onStart = { started++ }) }
        rule.onNodeWithTag("start-session").assertIsEnabled().performClick()
        assertEquals(1, started)
        // The form says why it cannot start; the folder row is still there to fix it.
        rule.onNodeWithText("未连接到电脑。").assertExists()
        rule.onNodeWithTag("selected-cwd").assertExists()
        shot("ui-new-session-light")
    }

    @Test
    fun newSessionPanelDark() {
        page(ThemeMode.DARK) { NewSession(online.copy(cwd = null, recent = emptyList()), canStart = false) }
        rule.onNodeWithTag("start-session").assertIsNotEnabled()
        shot("ui-new-session-dark")
    }

    /** A panel that asks for a decision: text at the 24dp gutter, then the COUI17 button bar. */
    @Composable
    private fun Decision(onPrimary: () -> Unit = {}, onSecondary: () -> Unit = {}) {
        PanelSheet(show = true, title = "在手机上继续", onDismissRequest = {}) {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "对话会带着相同的上下文在手机上继续；电脑上原来的窗口不会显示从手机发出的内容。",
                    style = COUITheme.textStyles.body2,
                    color = COUITheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = PageDefaults.TextGutter).padding(bottom = 12.dp),
                )
                SheetButtons(
                    primary = DialogButtonBarAction(text = "接续", onClick = onPrimary),
                    secondary = DialogButtonBarAction(text = "以副本继续", onClick = onSecondary),
                )
            }
        }
    }

    @Test
    fun decisionPanelUsesTheButtonBar() {
        val taps = mutableListOf<String>()
        page(ThemeMode.LIGHT) { Decision({ taps += "continue" }, { taps += "fork" }) }
        rule.onNodeWithText("接续").performClick()
        rule.onNodeWithText("以副本继续").performClick()
        assertEquals(listOf("continue", "fork"), taps)
        shot("ui-decision-panel-light")
    }

    @Test
    fun decisionPanelDark() {
        page(ThemeMode.DARK) { Decision() }
        shot("ui-decision-panel-dark")
    }

    @Test
    fun longReleaseNotesKeepTheDownloadActionVisible() {
        val release = ReleaseInfo(
            tag = "v1.2.3",
            version = requireNotNull(SemVer.parse("1.2.3")),
            name = "版本元数据",
            notes = (1..45).joinToString("\n\n") { "更新说明第 $it 项。" },
            prerelease = false,
            htmlUrl = null,
            publishedAt = "2026-11-28T12:00:00Z",
            assets = emptyList(),
        )
        var download: ReleaseInfo? = null
        page(ThemeMode.LIGHT) {
            UpdateSheet(true, UpdateState.Available(release), { download = it }, {}, {}, {})
        }
        rule.onNodeWithText("更新说明第 1 项。").assertIsDisplayed()
        rule.onNodeWithText("版本元数据").assertDoesNotExist()
        val action = rule.activity.getString(R.string.update_download)
        rule.onNodeWithText(action).assertIsDisplayed().performClick()
        assertEquals(release, download)
        shot("ui-update-notes-light")
    }

    @Composable
    private fun Tiers() {
        Column(Modifier.fillMaxWidth().padding(top = 24.dp)) {
            SmallTitle("行内操作")
            GroupCard {
                BasicComponent(title = "读取失败", summary = "连接已断开", endActions = { SmallButton("重试", {}) }, cardListPosition = CardListPosition.Head)
                BasicComponent(title = "MacBook Pro", summary = "A4:83:E7:10:22:9C", endActions = { SmallButton("解除绑定", {}, tone = ActionTone.Danger) }, cardListPosition = CardListPosition.Middle)
                BasicComponent(title = "预览缓存", summary = "38 MB / 200 MB", endActions = { SmallButton("清除缓存", {}, enabled = false) }, cardListPosition = CardListPosition.Tail)
            }
            SmallTitle("卡片操作")
            GroupCard {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("localhost:5173", style = COUITheme.textStyles.body1, color = COUITheme.colorScheme.onSurface)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SmallButton("打开", {}, tone = ActionTone.Primary)
                        SmallButton("复制", {})
                        SmallButton("延长 2 小时", {})
                        SmallButton("停止", {}, tone = ActionTone.Danger)
                    }
                }
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { LinkButton("查看全部 12 个活动会话", {}) }
        }
    }

    @Test
    fun buttonTiers() {
        page(ThemeMode.LIGHT) { Tiers() }
        shot("ui-button-tiers-light")
    }

    @Test
    fun buttonTiersDark() {
        page(ThemeMode.DARK) { Tiers() }
        shot("ui-button-tiers-dark")
    }

    private val link = PairingLink.parse(
        "switchtether://pair?v=1&h=10.0.2.2,192.168.1.20&p=47615&fp=9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08&id=p1&s=${"A".repeat(43)}&n=DESKTOP-01",
    )

    private fun onboarding(state: PairingUiState) = page(ThemeMode.LIGHT) {
        OnboardingContent(
            state = state, linkText = "", deviceSecure = true, revokedReason = null, onLinkTextChange = {}, onSubmitLink = {},
            onScan = {}, onScanned = {}, onPair = {}, onBack = {}, onDone = {}, onRejectCodes = {}, nearbyOverride = emptyList(),
        )
    }

    @Test
    fun onboardingIntro() {
        onboarding(PairingUiState.Intro)
        shot("ui-onboarding-intro")
    }

    @Test
    fun onboardingConfirm() {
        onboarding(PairingUiState.Confirm(link))
        shot("ui-onboarding-confirm")
    }
}
