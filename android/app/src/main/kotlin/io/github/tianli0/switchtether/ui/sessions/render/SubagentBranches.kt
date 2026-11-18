package io.github.tianli0.switchtether.ui.sessions.render

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.squircle.SquircleShape
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.tianli0.switchtether.R
import io.github.tianli0.switchtether.remote.protocol.AgentEvent
import io.github.tianli0.switchtether.remote.protocol.EventKinds
import io.github.tianli0.switchtether.remote.protocol.str
import io.github.tianli0.switchtether.remote.session.AgentEventLog
import io.github.tianli0.switchtether.ui.components.StepStatus
import io.github.tianli0.switchtether.ui.icons.AppIcons
import io.github.tianli0.switchtether.ui.sessions.stepTitle
import io.github.tianli0.switchtether.ui.theme.DevType
import io.github.tianli0.switchtether.ui.theme.LocalDevColors
import kotlinx.coroutines.delay

/** One step line of a sub-agent branch ("├─ Read src/main.rs"). */
data class BranchStep(val title: String, val status: StepStatus, val isAgent: Boolean)

/**
 * What a sub-agent row shows under itself, like Claude Code's `⎿` lines: the latest steps of the
 * sub-agent's own transcript and how many there are in total.
 */
data class SubagentPreview(
    val steps: List<BranchStep>,
    val total: Int,
    val error: Boolean = false,
) {
    companion object {
        const val SHOWN = 3

        fun of(events: List<AgentEvent>): SubagentPreview {
            val items = AgentEventLog.of(events).renderItems()
                .filter { it.depth == 0 && (it.event.kind == EventKinds.TOOL_CALL || it.event.kind == EventKinds.SUBAGENT) }
            val steps = items.map {
                val agent = it.event.kind == EventKinds.SUBAGENT
                BranchStep(
                    title = stepTitle(it).ifBlank { it.event.data.str("name").orEmpty() },
                    status = if (agent) StepStatus.parse(it.event.data.str("status")) else toolStatus(it),
                    isAgent = agent,
                )
            }
            return SubagentPreview(steps.takeLast(SHOWN), steps.size)
        }
    }
}

/**
 * Branch lines under a Claude Code sub-agent row plus "查看过程" (opens the sub-agent's page).
 * While the sub-agent runs the preview refreshes every few seconds; finished ones load once.
 */
@Composable
fun SubagentBranchPreview(agentId: String, running: Boolean, title: String?, ctx: RenderContext) {
    val open = ctx.onOpenSubagent ?: return
    LaunchedEffect(agentId, running) {
        while (true) {
            ctx.onWantSubagentPreview(agentId)
            if (!running) break
            delay(3_000)
        }
    }
    val p = ctx.subagentPreview(agentId)
    val dev = LocalDevColors.current
    Column(Modifier.fillMaxWidth().padding(start = 50.dp, end = 12.dp, bottom = 8.dp)) {
        // Only while it works: what it is doing right now. Finished agents keep the summary line.
        if (running && p != null && p.steps.isNotEmpty()) {
            val hidden = p.total - p.steps.size
            if (hidden > 0) BranchLine("│  ", pluralStringResource(R.plurals.subagent_more_steps, hidden, hidden), muted = true)
            p.steps.forEachIndexed { i, s ->
                BranchLine(
                    glyph = if (i == p.steps.lastIndex) "└─ " else "├─ ",
                    text = s.title,
                    muted = s.status == StepStatus.Completed,
                    danger = s.status == StepStatus.Failed,
                )
            }
            Spacer(Modifier.size(2.dp))
        }
        Row(
            Modifier
                .clip(SquircleShape(8.dp))
                .clickable(role = Role.Button) { open(agentId, title) }
                .padding(vertical = 4.dp)
                .testTag("subagent-open-$agentId"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                when {
                    p == null || p.error -> stringResource(R.string.subagent_view_process)
                    else -> stringResource(R.string.subagent_view_process_steps, p.total)
                },
                style = COUITheme.textStyles.footnote1,
                color = COUITheme.colorScheme.primary,
            )
            Icon(AppIcons.ChevronRight, contentDescription = null, tint = COUITheme.colorScheme.primary, modifier = Modifier.size(16.dp))
        }
        if (p?.error == true) {
            Text(stringResource(R.string.subagent_process_unavailable), style = COUITheme.textStyles.footnote2, color = dev.neutral)
        }
    }
}

@Composable
fun BranchLine(glyph: String, text: String, muted: Boolean = false, danger: Boolean = false, modifier: Modifier = Modifier) {
    val dev = LocalDevColors.current
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(glyph, style = DevType.codeSmall, color = COUITheme.colorScheme.onSurfaceVariantSummary)
        Text(
            text,
            style = DevType.codeSmall,
            color = when {
                danger -> dev.danger
                muted -> COUITheme.colorScheme.onSurfaceVariantSummary
                else -> COUITheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}
