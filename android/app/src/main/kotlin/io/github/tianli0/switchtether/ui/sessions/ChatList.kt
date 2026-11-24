package io.github.tianli0.switchtether.ui.sessions

import androidx.compose.animation.AnimatedVisibility
import io.github.suqi8.coui.kmp.squircle.SquircleShape
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.RotatingProgressIndicator
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.tianli0.switchtether.R
import io.github.tianli0.switchtether.remote.protocol.EventKinds
import io.github.tianli0.coui17.patterns.ChatBubble
import io.github.tianli0.switchtether.ui.icons.AppIcons
import io.github.tianli0.switchtether.ui.sessions.render.RenderContext
import io.github.tianli0.switchtether.ui.theme.LocalDevColors
import kotlinx.coroutines.launch

/** Rows that count as "messages" for the new-message pill. */
private fun TimelineRow.isMessage(): Boolean = when (this) {
    is TimelineRow.Event -> item.event.kind == EventKinds.ASSISTANT_MESSAGE || item.event.kind == EventKinds.USER_MESSAGE ||
        item.event.kind == EventKinds.APPROVAL_REQUEST || item.event.kind == EventKinds.QUESTION
    is TimelineRow.Outgoing -> true
    else -> false
}

/** Timeline rows + this phone's pending messages + the "正在回复…" placeholder. */
fun chatRows(state: SessionDetailState): List<TimelineRow> =
    state.timeline + state.outgoing.map { TimelineRow.Outgoing(it) } + (if (state.typing) listOf(TimelineRow.Typing) else emptyList())

/**
 * "Stick to bottom": while the user is at the end of the list, new rows and streaming growth keep it
 * pinned there (one scroll per frame at most, no animation per delta). Scrolling up to read stops
 * following; a pill then counts new messages and jumps back. Sending always returns to the bottom.
 * Prepending older pages keeps the position (LazyColumn anchors on the first visible item's key).
 */
class FollowState {
    var following by mutableStateOf(true)
    var autoScrolling by mutableStateOf(false)
    var baseMessages by mutableIntStateOf(0)
}

/** Scrolls to the very end of the list (the last row's bottom edge), clamped by the list itself. */
suspend fun LazyListState.scrollToEnd() {
    val last = layoutInfo.totalItemsCount - 1
    if (last >= 0) scrollToItem(last, scrollOffset = 1_000_000)
}

/** Saveable: returning to the page keeps "not following" while the user was reading further up. */
@Composable
fun rememberFollowState() = androidx.compose.runtime.saveable.rememberSaveable(
    saver = androidx.compose.runtime.saveable.Saver<FollowState, Boolean>(
        save = { it.following },
        restore = { FollowState().apply { following = it } },
    ),
) { FollowState() }

/** Wires [listState] to [follow]; call once per list. [sendTick] changes on every send. */
@Composable
fun FollowBottomEffects(listState: LazyListState, follow: FollowState, rows: List<TimelineRow>, sendTick: Int) {
    val messages = rows.count { it.isMessage() }
    val messagesNow by rememberUpdatedState(messages)
    val atEnd by remember { derivedStateOf { !listState.canScrollForward } }
    // Moving the viewport UP (finger, fling, TalkBack / programmatic scroll) stops following;
    // reaching the end resumes it. Content growing below does not move the first visible item,
    // and prepended pages only raise its index, so neither counts as "the user left".
    LaunchedEffect(listState) {
        var last = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        snapshotFlow { Triple(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, atEnd) }.collect { (i, o, end) ->
            val movedUp = i < last.first || (i == last.first && o < last.second)
            last = i to o
            if (end) {
                follow.following = true
            } else if (movedUp && !follow.autoScrolling && follow.following) {
                follow.following = false
                follow.baseMessages = messagesNow
            }
        }
    }
    // Catch up only when the CONTENT grew (more rows, or the last row got taller while streaming),
    // never because the viewport moved: scrolling — by finger, fling, TalkBack or a test — is the
    // user's and is never fought.
    LaunchedEffect(listState) {
        var prevTotal = -1
        var prevLastSize = -1
        snapshotFlow {
            val info = listState.layoutInfo
            val lastItem = info.visibleItemsInfo.lastOrNull()?.takeIf { it.index == info.totalItemsCount - 1 }
            info.totalItemsCount to (lastItem?.size ?: -1)
        }.collect { (total, lastSize) ->
            val grew = total > prevTotal || (lastSize > 0 && prevLastSize > 0 && lastSize > prevLastSize)
            prevTotal = total
            if (lastSize > 0) prevLastSize = lastSize
            if (grew && follow.following && total > 0 && !atEnd) {
                // Scroll from the next frame's callback, never from inside the layout pass that
                // reported the growth (a scroll forces a remeasure).
                androidx.compose.runtime.withFrameNanos { }
                if (follow.following && listState.canScrollForward) {
                    follow.autoScrolling = true
                    try { listState.scrollToEnd() } finally { follow.autoScrolling = false }
                }
            }
        }
    }
    // The viewport got shorter or taller under the same content (the keyboard opening or closing,
    // the composer growing a line): the conversation moves with its bottom edge, as in any
    // messenger, so the message that sat above the composer still sits there. Without this the
    // keyboard simply covered the newest messages.
    LaunchedEffect(listState) {
        // Height of the part of the viewport that shows messages, and the row at its bottom edge
        // with its distance from that edge.
        data class Edge(val height: Int, val index: Int, val gap: Int)
        var prev: Edge? = null
        snapshotFlow {
            val info = listState.layoutInfo
            val end = info.viewportEndOffset - info.afterContentPadding
            val last = info.visibleItemsInfo.lastOrNull { it.offset < end }
            Edge(info.viewportSize.height - info.afterContentPadding, last?.index ?: -1, if (last != null) end - (last.offset + last.size) else 0)
        }.collect { now ->
            val before = prev
            prev = now
            if (before == null || before.height <= 0 || now.height <= 0 || now.height == before.height || before.index < 0) return@collect
            val info = listState.layoutInfo
            val delta = if (now.height < before.height) {
                // Shorter: what was at the edge is now below it by exactly the lost height.
                (before.height - now.height).toFloat()
            } else {
                // Taller: the list may already have followed (it does when it was at its end), so
                // move by what is still missing for the same row to sit at the same distance.
                val end = info.viewportEndOffset - info.afterContentPadding
                val row = info.visibleItemsInfo.firstOrNull { it.index == before.index } ?: return@collect
                (before.gap - (end - (row.offset + row.size))).toFloat()
            }
            if (delta != 0f) {
                follow.autoScrolling = true
                try { listState.dispatchRawDelta(delta) } finally { follow.autoScrolling = false }
            }
        }
    }
    // Sending always brings the newest message (the user's own bubble) into view.
    LaunchedEffect(sendTick) {
        if (sendTick == 0) return@LaunchedEffect
        follow.following = true
        follow.autoScrolling = true
        try { listState.scrollToEnd() } finally { follow.autoScrolling = false }
    }
}

/** Floating "N 条新消息 ↓" / "回到最新" pill above the composer while the user reads older rows. */
@Composable
fun BoxScope.NewMessagesPill(listState: LazyListState, follow: FollowState, rows: List<TimelineRow>, bottomInset: androidx.compose.ui.unit.Dp = 0.dp) {
    val scope = rememberCoroutineScope()
    val newCount = (rows.count { it.isMessage() } - follow.baseMessages).coerceAtLeast(0)
    AnimatedVisibility(
        visible = !follow.following,
        enter = fadeIn() + scaleIn(initialScale = 0.9f),
        exit = fadeOut() + scaleOut(targetScale = 0.9f),
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp + bottomInset),
    ) {
        val label = if (newCount > 0) pluralStringResource(R.plurals.new_messages, newCount, newCount) else stringResource(R.string.jump_latest)
        val shape = RoundedCornerShape(50)
        Row(
            Modifier
                .shadow(6.dp, shape)
                .clip(shape)
                .background(COUITheme.colorScheme.surfaceContainer)
                .clickable(role = Role.Button) {
                    follow.following = true
                    scope.launch {
                        follow.autoScrolling = true
                        try { listState.animateScrollToItem((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0), 1_000_000) } finally { follow.autoScrolling = false }
                    }
                }
                .heightIn(min = 40.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .testTag("new-messages-pill"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.primary)
            Spacer(Modifier.size(6.dp))
            Icon(AppIcons.ArrowDown, contentDescription = null, tint = COUITheme.colorScheme.primary, modifier = Modifier.size(16.dp))
        }
    }
}

/** This phone's message before the desktop echoes it: same bubble as a sent message, plus its state. */
@Composable
fun OutgoingBubble(
    m: OutgoingMessage,
    onRetry: (String) -> Unit,
    onDelete: (String) -> Unit,
    onEdit: (String) -> Unit = {},
    /** Withdraw a message Claude Code queued behind a busy turn (null = not offered). */
    onWithdraw: ((String) -> Unit)? = null,
) {
    val dev = LocalDevColors.current
    val pending = m.status == OutgoingStatus.SENDING || m.status == OutgoingStatus.WAITING_CONNECTION
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).padding(top = 6.dp).testTag("outgoing-${m.commandId}"),
        horizontalAlignment = Alignment.End,
    ) {
        ChatBubble(Modifier.alpha(if (pending) 0.6f else 1f)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (m.attachmentNames.isNotEmpty()) io.github.tianli0.switchtether.ui.sessions.render.AttachmentNames(m.attachmentNames.map { it to null })
                if (m.text.isNotBlank()) Text(m.text, style = COUITheme.textStyles.body1, color = COUITheme.colorScheme.onSurface)
            }
        }
        Row(Modifier.padding(top = 3.dp, end = 4.dp).semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically) {
            when (m.status) {
                OutgoingStatus.SENDING -> {
                    Icon(AppIcons.Clock, contentDescription = null, tint = COUITheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.size(4.dp))
                    Meta(stringResource(R.string.outgoing_sending))
                }
                OutgoingStatus.DELIVERED -> if (m.messageUuid != null && onWithdraw != null) {
                    // Queued behind the running turn: it can still be taken back until the agent reads it.
                    Meta(stringResource(R.string.outgoing_delivered) + " · " + stringResource(R.string.outgoing_queued))
                    Spacer(Modifier.size(8.dp))
                    if (m.withdrawing) Meta(stringResource(R.string.outgoing_withdrawing))
                    else SmallAction(stringResource(R.string.outgoing_withdraw), "outgoing-withdraw") { onWithdraw(m.commandId) }
                } else {
                    Meta(stringResource(R.string.outgoing_delivered))
                }
                OutgoingStatus.WAITING_CONNECTION -> {
                    Icon(AppIcons.Clock, contentDescription = null, tint = dev.warn, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.size(4.dp))
                    Text(stringResource(R.string.outgoing_waiting), style = COUITheme.textStyles.footnote2, color = dev.warn)
                }
                OutgoingStatus.FAILED -> {
                    val why = when (m.error) {
                        SessionDetailViewModel.SESSION_ENDED -> stringResource(R.string.outgoing_session_ended)
                        null -> stringResource(R.string.outgoing_failed)
                        else -> stringResource(R.string.outgoing_failed) + " · " + m.error
                    }
                    Text(why, style = COUITheme.textStyles.footnote2, color = dev.danger, maxLines = 2, modifier = Modifier.weight(1f, fill = false))
                    if (m.error != SessionDetailViewModel.SESSION_ENDED) {
                        Spacer(Modifier.size(8.dp))
                        SmallAction(stringResource(R.string.outgoing_retry), "outgoing-retry") { onRetry(m.commandId) }
                    }
                    // Never lose a long prompt: "edit" puts the text back into the composer.
                    if (m.text.isNotBlank()) {
                        Spacer(Modifier.size(4.dp))
                        SmallAction(stringResource(R.string.outgoing_edit), "outgoing-edit") { onEdit(m.commandId) }
                    }
                    Spacer(Modifier.size(4.dp))
                    SmallAction(stringResource(R.string.outgoing_delete), "outgoing-delete") { onDelete(m.commandId) }
                }
            }
        }
    }
}

@Composable
private fun Meta(text: String) = Text(text, style = COUITheme.textStyles.footnote2, color = COUITheme.colorScheme.onSurfaceVariantSummary)

@Composable
private fun SmallAction(text: String, tag: String, onClick: () -> Unit) {
    Box(
        Modifier.clip(SquircleShape(8.dp)).clickable(role = Role.Button, onClick = onClick).heightIn(min = 40.dp).padding(horizontal = 8.dp).testTag(tag),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.primary) }
}

/** "正在回复…": the agent's mark and a small spinner until its first output arrives. */
@Composable
fun TypingRow(ctx: RenderContext) {
    val label = stringResource(R.string.session_typing)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).semantics { contentDescription = label; liveRegion = LiveRegionMode.Polite }.testTag("typing"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        io.github.tianli0.switchtether.ui.common.AgentBadge(ctx.agent, size = 24.dp)
        Spacer(Modifier.size(8.dp))
        Box(Modifier.clip(CircleShape).background(COUITheme.colorScheme.surfaceContainer).padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RotatingProgressIndicator(color = COUITheme.colorScheme.primary, size = 12.dp, ringDiameter = 9.6.dp, strokeWidth = 2.dp)
                Spacer(Modifier.size(6.dp))
                Text(label, style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurfaceVariantSummary)
            }
        }
    }
}
