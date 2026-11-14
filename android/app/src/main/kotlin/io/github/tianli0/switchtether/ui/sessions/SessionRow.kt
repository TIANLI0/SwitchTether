package io.github.tianli0.switchtether.ui.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.basic.CardListPosition
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.tianli0.switchtether.R
import io.github.tianli0.switchtether.remote.protocol.SessionSummary
import io.github.tianli0.switchtether.ui.common.Formatters
import io.github.tianli0.switchtether.ui.common.relativeTime
import io.github.tianli0.switchtether.ui.common.sessionStatusColor
import io.github.tianli0.switchtether.ui.common.waitingLabel
import io.github.tianli0.switchtether.ui.common.AgentBadge
import io.github.tianli0.switchtether.ui.components.StatusPill
import io.github.tianli0.switchtether.ui.theme.LocalDevColors

/**
 * Session row as a COUI `BasicComponent`: agent avatar with a status dot (live sessions only),
 * title, agent · project · branch, relative time; pills only for what needs attention.
 */
@Composable
fun SessionRow(
    session: SessionSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    position: CardListPosition = CardListPosition.None,
    /** Denser variant for Home: smaller avatar and vertical padding. */
    compact: Boolean = false,
) {
    val dev = LocalDevColors.current
    val agent = Formatters.agentLabel(session.agent)
    BasicComponent(
        modifier = modifier,
        title = session.title?.takeIf { it.isNotBlank() } ?: session.nativeId.ifEmpty { session.id }.take(12),
        summary = listOfNotNull(agent, session.project, session.gitBranch).joinToString(" · "),
        startAction = {
            AgentBadge(
                session.agent,
                modifier = Modifier.padding(end = 12.dp),
                size = if (compact) 32.dp else 40.dp,
                statusColor = if (session.isLive) sessionStatusColor(session.status) else null,
            )
        },
        endActions = {
            Text(relativeTime(session.lastActiveAtMs), style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurfaceVariantSummary)
        },
        bottomAction = if (session.waitingFor != null || session.managed || session.isAttached) {
            {
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    session.waitingFor?.let { StatusPill(waitingLabel(it), dev.warn) }
                    if (session.isAttached) StatusPill(io.github.tianli0.switchtether.ui.common.attachedLabel(session), COUITheme.colorScheme.primary)
                    if (session.managed) StatusPill(stringResource(R.string.session_managed_chip), COUITheme.colorScheme.primary)
                }
            }
        } else {
            null
        },
        insideMargin = if (compact) androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 10.dp) else io.github.suqi8.coui.kmp.basic.BasicComponentDefaults.InsideMargin,
        cardListPosition = position,
        onClick = onClick,
    )
}

@Composable
fun statusLabel(status: String): String = when (status) {
    "busy" -> stringResource(R.string.status_busy)
    "idle" -> stringResource(R.string.status_idle)
    "waiting" -> stringResource(R.string.status_waiting)
    "completed" -> stringResource(R.string.status_completed)
    "error" -> stringResource(R.string.status_error)
    else -> stringResource(R.string.step_unknown)
}
