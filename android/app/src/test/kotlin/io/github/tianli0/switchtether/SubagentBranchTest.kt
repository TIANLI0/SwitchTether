package io.github.tianli0.switchtether

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.tianli0.switchtether.remote.protocol.CapStatus
import io.github.tianli0.switchtether.remote.session.AgentEventLog
import io.github.tianli0.switchtether.remote.session.SubagentNode
import io.github.tianli0.switchtether.remote.session.SubagentTree
import io.github.tianli0.switchtether.ui.components.StepStatus
import io.github.tianli0.switchtether.ui.sessions.render.RenderContext
import io.github.tianli0.switchtether.ui.sessions.render.Subagent
import io.github.tianli0.switchtether.ui.sessions.render.SubagentPreview
import io.github.tianli0.switchtether.ui.theme.SwitchTetherTheme
import io.github.tianli0.switchtether.ui.theme.ThemeMode
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [32], qualifiers = "en-w411dp-h891dp-xxhdpi")
class SubagentBranchTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test
    fun treeIsFlattenedDepthFirstWithRails() {
        val nodes = listOf(
            SubagentNode("a", description = "A", updatedAtMs = 1),
            SubagentNode("b", description = "B", updatedAtMs = 2),
            SubagentNode("a1", description = "A1", parentAgentId = "a", updatedAtMs = 3),
            SubagentNode("a2", description = "A2", parentAgentId = "a", updatedAtMs = 4),
            SubagentNode("a2x", description = "A2x", parentAgentId = "a2", updatedAtMs = 5),
            SubagentNode("orphan", description = "O", parentAgentId = "gone", updatedAtMs = 6),
        )
        val flat = SubagentTree.flatten(nodes)
        assertEquals(listOf("a", "a1", "a2", "a2x", "b", "orphan"), flat.map { it.node.agentId })
        assertEquals(listOf(0, 1, 1, 2, 0, 0), flat.map { it.depth })
        assertEquals(
            listOf("├─ ", "│  ├─ ", "│  └─ ", "│     └─ ", "├─ ", "└─ "),
            flat.map { SubagentTree.prefix(it) },
        )
        assertEquals(listOf("a", "a2"), SubagentTree.trail(nodes, "a2x").map { it.agentId })
        // A cycle in broken metadata does not loop forever.
        assertTrue(SubagentTree.flatten(listOf(SubagentNode("x", parentAgentId = "y"), SubagentNode("y", parentAgentId = "x"))).size <= 2)
    }

    @Test
    fun previewKeepsTheLatestTopLevelSteps() {
        val events = (1..5).map { i ->
            event("t$i", i.toLong(), "tool_call", """{"callId":"c$i","name":"Read","title":"Read f$i.kt","category":"read","status":"completed"}""")
        } + event("t6", 6, "tool_call", """{"callId":"c6","name":"Bash","title":"cargo test","category":"shell","input":{"command":"cargo test"},"status":"running"}""")
        val p = SubagentPreview.of(events)
        assertEquals(6, p.total)
        assertEquals(SubagentPreview.SHOWN, p.steps.size)
        assertEquals("cargo test", p.steps.last().title)
        assertEquals(StepStatus.Running, p.steps.last().status)
    }

    private fun render(mode: ThemeMode, opened: MutableList<String>, status: String) {
        val sa = event(
            "sa", 1, "subagent",
            """{"agentId":"a1","description":"Audit Android screens","agentType":"Explore","background":true,"status":"$status","callId":"c0","toolUses":7}""",
        )
        val item = AgentEventLog.of(listOf(sa)).renderItems().single()
        val preview = SubagentPreview.of(
            (1..7).map { i -> event("t$i", i.toLong(), "tool_call", """{"callId":"c$i","name":"Read","title":"Read Screen$i.kt","category":"read","status":"${if (i == 7) "running" else "completed"}"}""") },
        )
        val ctx = RenderContext(
            agent = "claude_code", agentLabel = "Claude Code", approve = CapStatus.BLOCKED, answer = CapStatus.BLOCKED,
            capabilityReason = null, online = true, onApprove = { _, _, _ -> }, onAnswer = { _, _ -> },
            onLoadPayload = {}, payloadOf = { null }, onToggleCollapse = {}, isCollapsed = { false }, onLink = {},
            onOpenSubagent = { id, _ -> opened += id },
            subagentPreview = { preview },
        )
        rule.setContent {
            SwitchTetherTheme(mode) {
                Column(Modifier.fillMaxSize().background(COUITheme.colorScheme.surface).padding(16.dp)) { Subagent(item, ctx) }
            }
        }
    }

    @Test
    fun runningSubagentShowsBranchLinesAndOpensItsProcess() {
        val opened = mutableListOf<String>()
        render(ThemeMode.LIGHT, opened, "running")
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithText("Read Screen7.kt").assertExists()
        rule.onNodeWithText("… 4 earlier steps").assertExists()
        screenshot("subagent-branch-light")
        rule.onNodeWithTag("subagent-open-a1").performClick()
        assertEquals(listOf("a1"), opened)
    }

    @Test
    fun finishedSubagentHidesBranchLinesButKeepsTheLink() {
        val opened = mutableListOf<String>()
        render(ThemeMode.DARK, opened, "completed")
        rule.onNodeWithText("Read Screen7.kt").assertDoesNotExist()
        rule.onNodeWithText("View process · 7 steps").assertExists()
    }

    private fun screenshot(name: String) {
        val view = rule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(android.graphics.Canvas(bitmap)) }
        val dir = File("build/outputs/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
