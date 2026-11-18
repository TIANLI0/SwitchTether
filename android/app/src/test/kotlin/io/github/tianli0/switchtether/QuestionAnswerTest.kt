package io.github.tianli0.switchtether

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.tianli0.switchtether.remote.protocol.AgentEvent
import io.github.tianli0.switchtether.remote.protocol.CapStatus
import io.github.tianli0.switchtether.remote.session.AgentEventLog
import io.github.tianli0.switchtether.ui.sessions.render.Question
import io.github.tianli0.switchtether.ui.sessions.render.RenderContext
import io.github.tianli0.switchtether.ui.theme.SwitchTetherTheme
import io.github.tianli0.switchtether.ui.theme.ThemeMode
import java.io.File
import androidx.compose.runtime.mutableStateOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [32], qualifiers = "en-w411dp-h891dp-xxhdpi")
class QuestionAnswerTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val ctx = RenderContext(
        agent = "claude_code", agentLabel = "Claude Code", approve = CapStatus.PARTIAL, answer = CapStatus.PARTIAL,
        capabilityReason = null, online = true, onApprove = { _, _, _ -> }, onAnswer = { _, _ -> },
        onLoadPayload = {}, payloadOf = { null }, onToggleCollapse = {}, isCollapsed = { false }, onLink = {},
    )
    private val questions = """[{"id":"env","question":"Which environment?","options":[{"label":"Staging","value":"stg"},{"label":"Production","value":"prod"}]},{"id":"note","question":"Anything else?"}]"""

    @Test
    fun resolvedCardShowsTheAnswersFromTheDesktop() {
        val q = event("q", 1, "question", """{"requestId":"q1","questions":$questions,"resolved":true,"decision":"answer","by":"phone","answers":{"env":"stg","note":"run migrations first"}}""")
        val item = AgentEventLog.of(listOf(q)).renderItems().single()
        rule.setContent {
            SwitchTetherTheme(ThemeMode.LIGHT) {
                Column(Modifier.fillMaxSize().background(COUITheme.colorScheme.surface).padding(16.dp)) { Question(item, ctx) }
            }
        }
        rule.onNodeWithText("Staging").assertExists() // value mapped back to its label
        rule.onNodeWithText("run migrations first").assertExists()
        rule.onNodeWithText("Production").assertDoesNotExist() // unchosen options are not listed
        screenshot("question-answered-light")
    }

    @Test
    fun withoutDesktopAnswersTheCardShowsWhatThisPhoneSent() {
        val open = event("q", 1, "question", """{"requestId":"q1","questions":[{"id":"env","question":"Which environment?","options":[{"label":"Staging"},{"label":"Production"}]}],"resolved":false}""")
        val state = mutableStateOf<AgentEvent>(open)
        rule.setContent {
            SwitchTetherTheme(ThemeMode.DARK) {
                val item = AgentEventLog.of(listOf(state.value)).renderItems().single()
                Column(Modifier.fillMaxSize().background(COUITheme.colorScheme.surface).padding(16.dp)) { Question(item, ctx) }
            }
        }
        rule.onNodeWithText("Production").performClick()
        // An older desktop marks it resolved without echoing the answer.
        rule.runOnIdle { state.value = event("q", 2, "question", """{"requestId":"q1","questions":[{"id":"env","question":"Which environment?","options":[{"label":"Staging"},{"label":"Production"}]}],"resolved":true,"decision":"answer"}""") }
        rule.onNodeWithTag("question-answer").assertExists()
        rule.onNodeWithText("Production").assertExists()
        rule.onNodeWithText("Staging").assertDoesNotExist()
    }

    private fun screenshot(name: String) {
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(500)
        val view = rule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(android.graphics.Canvas(bitmap)) }
        val dir = File("build/outputs/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        rule.mainClock.autoAdvance = true
    }
}
