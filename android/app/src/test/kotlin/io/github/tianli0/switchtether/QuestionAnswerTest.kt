package io.github.tianli0.switchtether

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
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

    /** Records what the card sends and which links it asks to open. */
    private class Sent {
        val actions = mutableListOf<Pair<String?, String?>>() // action to answers JSON
        val links = mutableListOf<String>()
    }

    private fun elicitationCard(data: String, sent: Sent) {
        val ev = event("q", 1, "question", data)
        val item = AgentEventLog.of(listOf(ev)).renderItems().single()
        val c = ctx.copy(
            onAnswerAction = { _, answers, action -> sent.actions += action to answers?.toString() },
            onLink = { sent.links += it },
        )
        rule.setContent {
            SwitchTetherTheme(ThemeMode.LIGHT) {
                Column(Modifier.fillMaxSize().background(COUITheme.colorScheme.surface).padding(16.dp)) { Question(item, c) }
            }
        }
    }

    /** Claude Code URL elicitation as the desktop emits it (e3b247b38): `__confirm__` with one "Accept" option. */
    @Test
    fun urlElicitationShowsServerAndLinkAndAnswersAcceptDeclineCancel() {
        val sent = Sent()
        elicitationCard(
            """{"requestId":"e2","cancellable":true,"elicitationMode":"url","serverName":"Sign-in","message":"Open to sign in",
               "url":"https://example.com/auth","resolved":false,
               "questions":[{"id":"__confirm__","header":"Sign-in","question":"Open to sign in\nhttps://example.com/auth","multiSelect":false,"allowOther":false,
                 "options":[{"label":"Accept","description":"Confirm and continue"}]}]}""",
            sent,
        )
        rule.onNodeWithText("MCP server Sign-in asks").assertExists()
        rule.onNodeWithText("Open to sign in").assertExists() // the message once, without the URL glued to it
        rule.onNodeWithText("https://example.com/auth").assertExists()
        // No radio row for the single "Accept" option, no text field: the submit key is "Accept".
        rule.onNodeWithText("Confirm and continue").assertDoesNotExist()
        rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().let { assert(it.isEmpty()) { "no input field" } }
        rule.onNodeWithTag("elicitation-open").performClick()
        assert(sent.links == listOf("https://example.com/auth")) { sent.links.toString() }
        rule.onNodeWithTag("question-submit").assertTextEquals("Accept").performClick()
        rule.onNodeWithTag("question-decline").performClick()
        rule.onNodeWithTag("question-cancel").performClick()
        assert(
            sent.actions == listOf("submit" to """{"__confirm__":"Accept"}""", "decline" to null, "cancel" to null),
        ) { sent.actions.toString() }
        screenshot("question-elicitation-url-light")
    }

    /** Codex sends the message only on a `__confirm__` question; a form's message comes as `message` here. */
    @Test
    fun formElicitationShowsTheMessageOnceAndTheServerOnce() {
        val sent = Sent()
        elicitationCard(
            """{"requestId":"e1","cancellable":true,"elicitationMode":"form","serverName":"deploy","message":"Deploy where?","resolved":false,
               "questions":[{"id":"target","header":"deploy","question":"Target","options":[{"label":"staging"},{"label":"production"}],"required":true},
                            {"id":"note","header":"deploy","question":"Note","options":[],"allowOther":true,"required":false}]}""",
            sent,
        )
        rule.onNodeWithText("MCP server deploy asks").assertExists()
        rule.onNodeWithText("Deploy where?").assertExists()
        rule.onAllNodesWithText("deploy").fetchSemanticsNodes().let { assert(it.isEmpty()) { "the per-field header repeats the server" } }
        rule.onNodeWithTag("question-submit").assertTextEquals("Submit answer").assertIsNotEnabled()
        rule.onNodeWithText("staging").performClick()
        rule.onNodeWithTag("question-submit").assertIsEnabled().performClick()
        assert(sent.actions == listOf("submit" to """{"target":"staging"}""")) { sent.actions.toString() }
    }

    /** An ordinary AskUserQuestion is no elicitation: no decline key, cancel as before. */
    @Test
    fun ordinaryQuestionsHaveNoDecline() {
        val sent = Sent()
        elicitationCard("""{"requestId":"q1","questions":$questions,"resolved":false}""", sent)
        rule.onNodeWithTag("question-cancel").assertExists()
        rule.onAllNodesWithTag("question-decline").fetchSemanticsNodes().let { assert(it.isEmpty()) }
        rule.onAllNodesWithTag("elicitation-open").fetchSemanticsNodes().let { assert(it.isEmpty()) }
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
