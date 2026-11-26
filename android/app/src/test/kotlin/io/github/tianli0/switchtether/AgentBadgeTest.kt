package io.github.tianli0.switchtether

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.tianli0.switchtether.ui.common.AgentBadge
import io.github.tianli0.switchtether.ui.theme.SwitchTetherTheme
import io.github.tianli0.switchtether.ui.theme.ThemeMode
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-w411dp-h891dp-xxhdpi")
class AgentBadgeTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test
    fun everyManagedAgentHasItsMark() {
        rule.setContent {
            SwitchTetherTheme(ThemeMode.LIGHT) {
                Row(Modifier.background(Color.White).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AgentBadge("claude_code", size = 48.dp)
                    AgentBadge("codex", size = 48.dp)
                    AgentBadge("dsh", size = 48.dp)
                }
            }
        }
        rule.onNodeWithContentDescription("DeepSeek Harness", substring = true, useUnmergedTree = true).assertExists()
        val view = rule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, 300, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(android.graphics.Canvas(bitmap)) }
        File("build/outputs/screenshots").apply { mkdirs() }.resolve("agent-badges.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
