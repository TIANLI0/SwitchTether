package io.github.tianli0.switchtether.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.tianli0.switchtether.R
import io.github.tianli0.switchtether.ui.components.AgentAvatar
import io.github.tianli0.switchtether.ui.icons.AppIcons

/** Claude's brand colour (Anthropic "Claude" orange). */
val ClaudeBrand = Color(0xFFD97757)
private val DeepSeekBrand = androidx.compose.ui.graphics.Color(0xFF4D6BFE)

/**
 * Agent avatar with the real brand mark: Claude (orange) for Claude Code, the OpenAI mark
 * (monochrome, follows light/dark) for Codex, a generic robot for anything else. Marks come from
 * LobeHub Icons (MIT), see android/third_party/brand-icons/NOTICE.md.
 */
@Composable
fun AgentBadge(agent: String, modifier: Modifier = Modifier, size: Dp = 28.dp, statusColor: Color? = null, running: Boolean = false) {
    val label = Formatters.agentLabel(agent)
    androidx.compose.foundation.layout.Box(modifier) {
        if (running) io.github.suqi8.coui.kmp.basic.RotatingProgressIndicator(
            color = COUITheme.colorScheme.primary, size = size, ringDiameter = size, strokeWidth = 1.5.dp,
        )
        when (agent) {
            "claude_code", "claude" -> AgentAvatar(painterResource(R.drawable.ic_agent_claude), ClaudeBrand, label, Modifier, size, statusColor)
            "codex" -> AgentAvatar(painterResource(R.drawable.ic_agent_openai), COUITheme.colorScheme.onSurface, label, Modifier, size, statusColor)
            "dsh" -> AgentAvatar(painterResource(R.drawable.ic_agent_deepseek), DeepSeekBrand, label, Modifier, size, statusColor)
            else -> AgentAvatar(rememberVectorPainter(AppIcons.SubAgent), COUITheme.colorScheme.primary, label, Modifier, size, statusColor)
        }
    }
}
