package io.github.tianli0.switchtether

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.tianli0.switchtether.ui.files.MarkdownDocView
import io.github.tianli0.switchtether.ui.theme.SwitchTetherTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * README_ZH.md crashed the rich-text preview on a phone while scrolling into its ```mermaid fence and
 * the table after it ("Horizontally scrollable component was measured with an infinity maximum
 * width"). The same content must scroll to the end.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class MarkdownDocCrashTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun aMermaidFenceAndATableScrollWithoutCrashing() = scrollThrough(fence = true, table = true)

    @Test
    fun aFenceAloneScrollsWithoutCrashing() = scrollThrough(fence = true, table = false)

    @Test
    fun aTableAloneScrollsWithoutCrashing() = scrollThrough(fence = false, table = true)

    private fun scrollThrough(fence: Boolean, table: Boolean) {
        val doc = buildString {
            appendLine("# SwitchTether")
            repeat(12) { appendLine("段落 $it，一些说明文字。"); appendLine() }
            if (fence) {
            appendLine("```mermaid")
            appendLine("flowchart LR")
            appendLine("  subgraph PC[\"你的电脑\"]")
            appendLine("    A[\"Claude Code · Codex · DeepSeek Harness\"] --- D[\"SwitchTether 桌面端<br/>（CC Switch + 远程核心）\"]")
            appendLine("  end")
            appendLine("  P[\"SwitchTether<br/>Android\"] <-->|\"双向 TLS 1.3，证书固定\"| D")
            appendLine("```")
            appendLine()
            }
            if (table) {
            appendLine("| Agent | 查看（历史 + 实时） | 控制你自己启动的会话 | 从手机新建会话 |")
            appendLine("|---|:---:|---|---|")
            appendLine("| Claude Code CLI | ✅ | 可选开启：`st-claude`（终端镜像） | ✅ 已验证 |")
            appendLine("| Claude Code VS Code 扩展 | ✅ | 可选开启：VS Code 接管 | ✅ 已验证 |")
            appendLine()
            }
            repeat(12) { appendLine("尾部段落 $it。"); appendLine() }
        }
        rule.setContent {
            SwitchTetherTheme {
                Box(Modifier.fillMaxSize()) { MarkdownDocView(doc, "/r/README_ZH.md", null, Modifier.fillMaxSize()) }
            }
        }
        rule.waitForIdle()
        repeat(8) {
            rule.onRoot().performTouchInput { swipeUp() }
            rule.waitForIdle()
        }
    }
}
