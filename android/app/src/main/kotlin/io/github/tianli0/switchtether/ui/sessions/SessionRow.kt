package io.github.tianli0.switchtether.ui.sessions

import io.github.tianli0.coui17.patterns.groupRowShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.foundation.background
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.Column
import io.github.tianli0.switchtether.ui.common.sessionDisplayTitle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
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
import io.github.tianli0.coui17.patterns.StatusPill
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
    holdDown: Boolean = false,
    /** Lines under the row inside the same card slot (Home: what runs inside the session). */
    bottom: (@Composable () -> Unit)? = null,
    /** Sub-agents / background tasks the phone saw running itself, for desktops without `activeTasks`. */
    activeFallback: Int? = null,
) {
    val dev = LocalDevColors.current
    // A finished turn with sub-agents or background tasks still running reads as working.
    val work = session.backgroundWork(activeFallback)
    val shownStatus = session.displayStatus(activeFallback)
    val agent = Formatters.agentLabel(session.agent)
    val title = sessionDisplayTitle(session)
    // Two-pane layout: the session open beside the list keeps the press layer, as a selected COUI
    // card-list row does (COUICardListSelectedItemLayout: couiColorPress, #1F000000 / #33FFFFFF).
    val open = io.github.tianli0.switchtether.ui.nav.LocalSelectedSessionId.current == session.id
    val pressLayer = if (COUITheme.colorScheme.background.luminance() < 0.5f) androidx.compose.ui.graphics.Color(0x33FFFFFF) else androidx.compose.ui.graphics.Color(0x1F000000)
    val marked = if (open) {
        modifier.clip(groupRowShape(position)).background(pressLayer).testTag("session-row-selected")
    } else {
        modifier
    }
    BasicComponent(
        // Long-press rows get their press highlight clipped to the row's rounded card edges (never a square mask).
        modifier = if (onLongClick != null) marked.clip(groupRowShape(position)) else marked,
        // Two lines at most, whatever the font size (coui17 patch 0009); the subtitle stays one.
        title = title,
        titleMaxLines = 2,
        summaryMaxLines = 1,
        startAction = {
            // No extra end padding: the row's own icon gap is the same as every other list row's.
            AgentBadge(
                session.agent,
                size = if (compact) 32.dp else 40.dp,
                statusColor = if (session.isLive) sessionStatusColor(shownStatus) else null,
                running = session.isLive && shownStatus == "busy",
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
        // A segment never splits across lines ("Claude / Code"); a large font wraps between them.
        ).joinToString(" · ") { it.replace(' ', NBSP) },
        // Right column: relative time over the small origin / waiting tags, so a row stays two lines.
        endActions = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(relativeTime(session.lastActiveAtMs), style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurfaceVariantSummary)
                val tag: Pair<String, androidx.compose.ui.graphics.Color>? = when {
                    session.waitingFor != null -> waitingLabel(session.waitingFor!!) to dev.warn
                    // Home lists what runs under the row ([bottom]); elsewhere the tag says it.
                    work > 0 && bottom == null -> androidx.compose.ui.res.pluralStringResource(R.plurals.live_background_running, work, work) to dev.info
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
        bottomAction = bottom,
        holdDownState = holdDown,
        onClick = onClick,
        onLongClick = onLongClick,
    )
}

/**
 * Status line of a live session: "N background tasks running" while its turn has ended but
 * sub-agents or background tasks still run, else [statusLabel].
 */
@Composable
fun sessionStatusLabel(session: SessionSummary, activeFallback: Int? = null): String {
    val work = session.backgroundWork(activeFallback)
    return if (work > 0) androidx.compose.ui.res.pluralStringResource(R.plurals.live_background_running, work, work) else statusLabel(session.status)
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

/**
 * The long-press menu of a session row: follow it in Live Updates, or stop. Opens at the finger
 * ([touch] is recorded by `Modifier.preciseClickAnchor` on the list holding the rows).
 */
@Composable
fun PinMenu(session: SessionSummary?, pinned: Boolean, touch: io.github.suqi8.coui.kmp.basic.PreciseClickState, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val shownSession = io.github.tianli0.switchtether.ui.common.rememberLast(session)
    val shownPinned = io.github.tianli0.switchtether.ui.common.rememberLast(pinned.takeIf { session != null }) == true
    io.github.tianli0.coui17.patterns.GlassMenu(
        show = session != null,
        entries = listOf(
            io.github.tianli0.coui17.patterns.MenuEntry(stringResource(if (shownPinned) R.string.pin_remove else R.string.pin_add)) {
                shownSession?.let { io.github.tianli0.switchtether.ui.common.togglePin(context, it) }
            },
        ),
        onDismiss = onDismiss,
        touch = touch,
    )
}

/** No-break space: keeps a subtitle segment ("Claude Code") on one line. */
private const val NBSP = '\u00A0'
