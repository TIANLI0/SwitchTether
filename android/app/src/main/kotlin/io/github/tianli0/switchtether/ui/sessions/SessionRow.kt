package io.github.tianli0.switchtether.ui.sessions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.basic.CardListPosition
import io.github.suqi8.coui.kmp.basic.Text
import androidx.compose.foundation.layout.FlowRow
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.tianli0.switchtether.R
import io.github.tianli0.switchtether.remote.protocol.SessionSummary
import io.github.tianli0.switchtether.ui.common.Formatters
import io.github.tianli0.switchtether.ui.common.controlColor
import io.github.tianli0.switchtether.ui.common.controlLabel
import io.github.tianli0.switchtether.ui.common.sessionStatusColor
import io.github.tianli0.switchtether.ui.components.StatusPill
import io.github.tianli0.switchtether.ui.theme.DevType
import io.github.tianli0.switchtether.ui.theme.LocalDevColors

/**
 * Session row as a COUI `BasicComponent` (title + summary + badges), meant to sit inside a group
 * card with [position] from `cardListPositionOf`.
 */
@Composable
fun SessionRow(
    session: SessionSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    position: CardListPosition = CardListPosition.None,
) {
    val dev = LocalDevColors.current
    BasicComponent(
        modifier = modifier,
        title = session.title?.takeIf { it.isNotBlank() } ?: session.nativeId.ifEmpty { session.id }.take(12),
        summary = listOfNotNull(Formatters.agentLabel(session.agent), session.project, session.gitBranch?.let { "⎇ $it" }).joinToString(" · "),
        endActions = {
            Text(Formatters.relative(session.lastActiveAtMs), style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurfaceVariantSummary)
        },
        bottomAction = {
            FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (session.isLive) StatusPill(stringResource(R.string.badge_live), dev.ok)
                if (session.status != "unknown") StatusPill(statusLabel(session.status), sessionStatusColor(session.status))
                session.waitingFor?.let { StatusPill(it, dev.warn) }
                StatusPill(controlLabel(session.controlLevel), controlColor(session.controlLevel))
                if (session.managed) StatusPill(stringResource(R.string.badge_managed), dev.info)
            }
        },
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
    else -> status
}
