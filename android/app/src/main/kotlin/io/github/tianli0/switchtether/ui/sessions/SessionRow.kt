package io.github.tianli0.switchtether.ui.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import io.github.tianli0.switchtether.ui.common.sessionDisplayTitle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    pinned: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    val dev = LocalDevColors.current
    val agent = Formatters.agentLabel(session.agent)
    val title = sessionDisplayTitle(session)
    BasicComponent(
        // Long-press rows get their press highlight clipped to the row's rounded card edges (never a square mask).
        modifier = if (onLongClick != null) modifier.clip(rowShape(position)).combinedClickable(onClick = onClick, onLongClick = onLongClick) else modifier,
        title = title,
        startAction = {
            // No extra end padding: the row's own icon gap is the same as every other list row's.
            AgentBadge(
                session.agent,
                size = if (compact) 32.dp else 40.dp,
                statusColor = if (session.isLive) sessionStatusColor(session.status) else null,
                running = session.isLive && session.status == "busy",
            )
        },
        // Attached sessions name their client here ("Codex · Codex Desktop"), so the tag stays short
        // and long thread slugs keep the width (a wide "已接管 · Codex Desktop" pill squeezed them).
        summary = listOfNotNull(
            // "Codex Desktop" already names the agent; "VS Code" / "终端" do not.
            agent.takeUnless { session.isAttached && session.origin == "desktop_app" },
            if (session.isAttached) io.github.tianli0.switchtether.ui.common.clientLabel(session.origin, session.agent) else null,
            io.github.tianli0.switchtether.ui.common.sessionProjectName(session)?.takeIf { it != title },
            session.gitBranch,
        ).joinToString(" · "),
        // Right column: relative time over the small origin / waiting tags, so a row stays two lines.
        endActions = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(relativeTime(session.lastActiveAtMs), style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurfaceVariantSummary)
                val tag: Pair<String, androidx.compose.ui.graphics.Color>? = when {
                    session.waitingFor != null -> waitingLabel(session.waitingFor!!) to dev.warn
                    pinned -> stringResource(R.string.pin_tag) to COUITheme.colorScheme.primary
                    session.isAttached -> stringResource(R.string.attached_tag) to COUITheme.colorScheme.primary
                    session.managed -> stringResource(R.string.session_managed_chip) to COUITheme.colorScheme.primary
                    else -> null
                }
                tag?.let { (text, color) -> StatusPill(text, color) }
            }
        },
        insideMargin = if (compact) androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 10.dp) else io.github.suqi8.coui.kmp.basic.BasicComponentDefaults.InsideMargin,
        cardListPosition = position,
        onClick = if (onLongClick != null) null else onClick,
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

/** Long-press menu of a session row: pin / unpin to Fluid Cloud (Live Update). */
@Composable
fun PinMenuDialog(session: SessionSummary?, pinned: Boolean, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    io.github.tianli0.switchtether.ui.common.ConfirmDialog(
        show = session != null,
        title = session?.let { sessionDisplayTitle(it) }.orEmpty(),
        message = stringResource(if (pinned) R.string.pin_menu_unpin_body else R.string.pin_menu_pin_body),
        confirmText = stringResource(if (pinned) R.string.pin_remove else R.string.pin_add),
        onConfirm = {
            session?.let { io.github.tianli0.switchtether.ui.common.togglePin(context, it) }
            onDismiss()
        },
        onDismiss = onDismiss,
    )
}

/** Outline of a row inside a card group (rounded only on the card's outer edges). */
private fun rowShape(position: CardListPosition): androidx.compose.ui.graphics.Shape {
    val r = 16.dp
    return when (position) {
        CardListPosition.Full -> androidx.compose.foundation.shape.RoundedCornerShape(r)
        CardListPosition.Head -> androidx.compose.foundation.shape.RoundedCornerShape(topStart = r, topEnd = r)
        CardListPosition.Tail -> androidx.compose.foundation.shape.RoundedCornerShape(bottomStart = r, bottomEnd = r)
        else -> androidx.compose.ui.graphics.RectangleShape
    }
}
