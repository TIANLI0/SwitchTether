package io.github.tianli0.switchtether

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.tianli0.switchtether.remote.protocol.CapStatus
import io.github.tianli0.switchtether.remote.protocol.ProtocolJson
import io.github.tianli0.switchtether.remote.session.AgentEventLog
import io.github.tianli0.switchtether.ui.sessions.render.Question
import io.github.tianli0.switchtether.ui.sessions.render.RenderContext
import io.github.tianli0.switchtether.ui.sessions.render.ToolCall
import io.github.tianli0.switchtether.ui.theme.SwitchTetherTheme
import io.github.tianli0.switchtether.ui.theme.ThemeMode
import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [32], qualifiers = "en-w411dp-h891dp-xxhdpi")
class TaskInteractionTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private fun context(answer: (JsonObject?) -> Unit = {}) = RenderContext(
        agent = "codex", agentLabel = "Codex", approve = CapStatus.PARTIAL, answer = CapStatus.PARTIAL,
        capabilityReason = null, online = true, onApprove = { _, _, _ -> }, onAnswer = { _, a -> answer(a) },
        onAnswerAction = { _, a, _ -> answer(a) }, onLoadPayload = {}, payloadOf = { null },
        onToggleCollapse = {}, isCollapsed = { false }, onLink = {},
    )
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
    private fun question(mode: ThemeMode) {
        var answer: JsonObject? = null
        val q = event("q", 1, "question", """{"requestId":"q1","questions":[{"id":"target","question":"Choose the target environment","multiSelect":false,"allowOther":true,"options":[{"label":"Staging","description":"Run checks before release"}]}]}""")
        val item = AgentEventLog.of(listOf(q)).renderItems().single()
        rule.setContent { SwitchTetherTheme(mode) {
            Column(Modifier.fillMaxSize().background(COUITheme.colorScheme.surface).padding(16.dp)) { Question(item, context { answer = it }) }
        } }
        rule.onNodeWithText("Submit answer").assertIsNotEnabled()
        rule.onNodeWithText("Other (custom answer)").performClick()
        rule.onNodeWithText("Submit answer").assertIsNotEnabled()
        rule.onNode(hasSetTextAction()).performTextInput("production, eu")
        rule.onNodeWithText("Submit answer").assertIsEnabled()
        screenshot("codex-question-${mode.name.lowercase()}")
        rule.onNodeWithText("Submit answer").performClick()
        assertEquals("production, eu", answer?.get("target")?.jsonPrimitive?.content)
    }
    @Test fun questionLight() = question(ThemeMode.LIGHT)
    @Test fun questionDark() = question(ThemeMode.DARK)

    @Test fun codexMultiSelectPreservesCommasAndOptionValues() {
        var answer: JsonObject? = null
        val q = event("q", 1, "question", """{"requestId":"q1","questions":[{"id":"checks","question":"Choose checks","multiSelect":true,"options":[{"label":"Unit, integration","value":"tests"},{"label":"Lint"}]}]}""")
        val item = AgentEventLog.of(listOf(q)).renderItems().single()
        rule.setContent { SwitchTetherTheme(ThemeMode.LIGHT) { Question(item, context { answer = it }) } }
        rule.onNodeWithText("Unit, integration").performClick()
        rule.onNodeWithText("Lint").performClick()
        rule.onNodeWithText("Submit answer").performClick()
        assertEquals(listOf("tests", "Lint"), answer?.get("checks")?.jsonArray?.map { it.jsonPrimitive.content })
    }

    private fun command(mode: ThemeMode) {
        val command = "\"C:\\Program Files\\WindowsApps\\OpenAI.Codex_1\\pwsh.exe\" -NoProfile -Command \"Get-Content 'D:\\projects\\SwitchTether\\android\\app\\src\\main\\kotlin\\ui\\SessionDetailScreen.kt'\""
        val data = """{"callId":"c1","name":"shell","title":"long wrapper","category":"shell","status":"completed","input":{"command":${ProtocolJson.encodeToString(kotlinx.serialization.serializer<String>(), command)},"cwd":"D:/projects/SwitchTether"}}"""
        val item = AgentEventLog.of(listOf(event("cmd", 1, "tool_call", data))).renderItems().single()
        rule.setContent { SwitchTetherTheme(mode) {
            Column(Modifier.fillMaxSize().background(COUITheme.colorScheme.surface).verticalScroll(rememberScrollState()).padding(12.dp)) { ToolCall(item, context()) }
        } }
        rule.onNodeWithText("Get-Content", substring = true).performClick()
        rule.onNodeWithText("Copy full command").assertExists()
        rule.onNodeWithText(command).assertExists()
        rule.onNodeWithText("Working directory").assertExists()
        screenshot("long-command-${mode.name.lowercase()}")
        rule.onNodeWithText("Horizontal scroll").performClick()
        rule.onNodeWithText("Wrap lines").assertExists()
    }
    @Test fun commandLight() = command(ThemeMode.LIGHT)
    @Test fun commandDark() = command(ThemeMode.DARK)
}
