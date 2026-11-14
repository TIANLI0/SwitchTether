package io.github.tianli0.switchtether.ui.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.suqi8.coui.kmp.basic.Button
import io.github.suqi8.coui.kmp.basic.ButtonDefaults
import io.github.suqi8.coui.kmp.basic.HorizontalDivider
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Pause
import io.github.suqi8.coui.kmp.icon.extended.Refresh
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.tianli0.switchtether.R
import io.github.tianli0.switchtether.remote.protocol.CapStatus
import io.github.tianli0.switchtether.remote.protocol.Capability
import io.github.tianli0.switchtether.remote.protocol.str
import io.github.tianli0.switchtether.remote.session.RenderItem
import io.github.tianli0.switchtether.ui.appContainer
import io.github.tianli0.switchtether.ui.common.CommandPromptDialog
import io.github.tianli0.switchtether.ui.common.ConfirmDialog
import io.github.tianli0.switchtether.ui.common.ConnectionBanner
import io.github.tianli0.switchtether.ui.common.Formatters
import io.github.tianli0.switchtether.ui.common.Loadable
import io.github.tianli0.switchtether.ui.common.Page
import io.github.tianli0.switchtether.ui.common.TextInput
import io.github.tianli0.switchtether.ui.common.ToolbarIcon
import io.github.tianli0.switchtether.ui.common.capColor
import io.github.tianli0.switchtether.ui.common.controlColor
import io.github.tianli0.switchtether.ui.common.controlLabel
import io.github.tianli0.switchtether.ui.common.title
import io.github.tianli0.switchtether.ui.components.ErrorState
import io.github.tianli0.switchtether.ui.components.LoadingState
import io.github.tianli0.switchtether.ui.components.StatusPill
import io.github.tianli0.switchtether.ui.containerViewModel
import io.github.tianli0.switchtether.ui.sessions.render.AgentEventRegistry
import io.github.tianli0.switchtether.ui.sessions.render.RenderContext
import io.github.tianli0.switchtether.ui.theme.DevType
import io.github.tianli0.switchtether.ui.theme.LocalDevColors

@Composable
fun SessionDetailScreen(sessionId: String, initialTitle: String?, onBack: () -> Unit) {
    val container = appContainer()
    val vm = containerViewModel(key = "session:$sessionId") { SessionDetailViewModel(sessionId, it.connection, it.sessions, it.draftStore) }
    val state by vm.state.collectAsStateWithLifecycle()
    val prompt by vm.commands.prompt.collectAsStateWithLifecycle()
    val busy by vm.commands.busy.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    var pendingLink by remember { mutableStateOf<String?>(null) }
    var pendingApproval by remember { mutableStateOf<Triple<String, String, String>?>(null) }
    var confirmInterrupt by remember { mutableStateOf(false) }

    val sendLabel = stringResource(R.string.session_send)
    val interruptLabel = stringResource(R.string.session_interrupt)
    val approveLabel = stringResource(R.string.render_approval)
    val answerLabel = stringResource(R.string.render_submit_answer)

    val ctx = remember(state.detail.valueOrNull?.capabilities, state.online) {
        val caps = state.detail.valueOrNull?.capabilities
        RenderContext(
            approve = caps?.status(Capability.APPROVE) ?: CapStatus.UNKNOWN,
            answer = caps?.status(Capability.ANSWER_QUESTION) ?: CapStatus.UNKNOWN,
            capabilityReason = caps?.reason,
            online = state.online,
            onApprove = { rid, decision, label -> pendingApproval = Triple(rid, decision, label) },
            onAnswer = { rid, answers -> vm.answer(answerLabel, rid, answers) },
            onLoadPayload = vm::loadPayload,
            payloadOf = { vm.payloads[it] },
            onToggleCollapse = vm::toggleCollapse,
            isCollapsed = { it in vm.state.value.collapsed },
            onLink = { pendingLink = it },
        )
    }

    SessionDetailContent(
        state = state,
        initialTitle = initialTitle,
        ctx = ctx,
        busy = busy,
        onBack = onBack,
        onReload = vm::load,
        onLoadOlder = vm::loadOlder,
        onDraft = vm::setDraft,
        onSend = { vm.send(sendLabel) },
        onInterrupt = { confirmInterrupt = true },
        onRetryConnection = { container.connection.retryNow() },
    )

    CommandPromptDialog(prompt, onCheckStatus = vm.commands::checkStatus, onRetry = vm.commands::retry, onDismiss = vm.commands::dismiss)
    ConfirmDialog(
        show = pendingLink != null,
        title = stringResource(R.string.link_open_title),
        message = pendingLink.orEmpty(),
        confirmText = stringResource(R.string.link_open),
        onConfirm = {
            pendingLink?.let { runCatching { uriHandler.openUri(it) } }
            pendingLink = null
        },
        onDismiss = { pendingLink = null },
    )
    ApprovalSheet(
        pending = pendingApproval,
        request = pendingApproval?.first?.let { rid -> state.log.events.lastOrNull { it.data.str("requestId") == rid } },
        onConfirm = {
            pendingApproval?.let { (rid, decision, label) -> vm.resolveApproval("$approveLabel: $label", rid, decision) }
            pendingApproval = null
        },
        onDismiss = { pendingApproval = null },
    )
    ConfirmDialog(
        show = confirmInterrupt,
        title = stringResource(R.string.session_interrupt),
        message = stringResource(R.string.session_interrupt_confirm),
        confirmText = stringResource(R.string.session_interrupt),
        onConfirm = {
            confirmInterrupt = false
            vm.interrupt(interruptLabel)
        },
        onDismiss = { confirmInterrupt = false },
    )
}

@Composable
fun SessionDetailContent(
    state: SessionDetailState,
    initialTitle: String?,
    ctx: RenderContext,
    busy: String?,
    onBack: () -> Unit,
    onReload: () -> Unit,
    onLoadOlder: () -> Unit,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    onInterrupt: () -> Unit,
    onRetryConnection: () -> Unit,
) {
    val summary = state.detail.valueOrNull?.summary
    Page(
        title = summary?.title?.takeIf { it.isNotBlank() } ?: initialTitle ?: stringResource(R.string.session_untitled),
        subtitle = summary?.let { listOfNotNull(Formatters.agentLabel(it.agent), it.project, it.model).joinToString(" · ") } ?: "",
        onBack = onBack,
        actions = {
            if (state.canInterrupt) ToolbarIcon(COUIIcons.Pause, stringResource(R.string.session_interrupt), onInterrupt)
            ToolbarIcon(COUIIcons.Refresh, stringResource(R.string.action_refresh), onReload)
        },
        modifier = Modifier.imePadding(),
    ) {
        ConnectionBanner(state.connection, onRetryConnection)
        SessionHeader(state)
        HorizontalDivider()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val ev = state.events) {
                Loadable.Loading -> LoadingState(stringResource(R.string.state_loading))
                is Loadable.Failed -> ErrorState(ev.error.title(), ev.error.detail, stringResource(R.string.action_retry), onReload)
                is Loadable.Ready -> EventList(state, ctx, onLoadOlder)
            }
        }
        Composer(state, busy, onDraft, onSend)
    }
}

@Composable
private fun SessionHeader(state: SessionDetailState) {
    val dev = LocalDevColors.current
    val d = state.detail.valueOrNull ?: return
    val s = d.summary
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            StatusPill(if (s.isLive) stringResource(R.string.badge_live) else stringResource(R.string.badge_ended), if (s.isLive) dev.ok else dev.neutral)
            StatusPill(statusLabel(s.status), io.github.tianli0.switchtether.ui.common.sessionStatusColor(s.status))
            s.waitingFor?.let { StatusPill(it, dev.warn) }
            StatusPill(controlLabel(s.controlLevel), controlColor(s.controlLevel))
            StatusPill(s.origin, dev.neutral)
        }
        state.rollingStatus?.let { rs ->
            Text(
                listOfNotNull(rs.data.str("state"), rs.data.str("detail")).joinToString(" · "),
                style = DevType.codeSmall,
                color = dev.info,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // Honest capability strip: what this phone can actually do in this session.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(
                Capability.SEND_MESSAGE to R.string.cap_send,
                Capability.APPROVE to R.string.cap_approve,
                Capability.ANSWER_QUESTION to R.string.cap_answer,
                Capability.INTERRUPT to R.string.cap_interrupt,
            ).forEach { (cap, label) ->
                val st = d.capabilities.status(cap)
                StatusPill("${stringResource(label)}: ${st.name}", capColor(st), contentDescription = "${stringResource(label)} ${st.name}")
            }
        }
        d.capabilities.reason?.let { Text(it, style = DevType.label, color = dev.gutter, maxLines = 3, overflow = TextOverflow.Ellipsis) }
        s.cwd?.let { Text(it, style = DevType.label, color = dev.gutter, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
private fun EventList(state: SessionDetailState, ctx: RenderContext, onLoadOlder: () -> Unit) {
    val listState = rememberLazyListState()
    val items = state.items
    // Start at the newest event; follow new events only while the user is at the bottom.
    var initialScrollDone by remember(state.sessionId) { mutableStateOf(false) }
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= info.totalItemsCount - 2
        }
    }
    LaunchedEffect(items.size) {
        if (items.isEmpty()) return@LaunchedEffect
        if (!initialScrollDone) {
            listState.scrollToItem(items.size)
            initialScrollDone = true
        } else if (atBottom) {
            listState.animateScrollToItem(items.size)
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { if (it <= 1 && initialScrollDone) onLoadOlder() }
    }
    if (items.isEmpty()) {
        Text(stringResource(R.string.session_no_events), style = COUITheme.textStyles.body2, color = COUITheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(24.dp))
        return
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag("event-list")) {
        item(key = "older") {
            Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                when {
                    state.loadingOlder -> Text(stringResource(R.string.state_loading), style = DevType.label, color = COUITheme.colorScheme.onSurfaceVariantSummary)
                    state.hasMoreBefore -> TextButton(text = stringResource(R.string.session_load_older), onClick = onLoadOlder)
                    else -> Text(stringResource(R.string.session_start), style = DevType.label, color = COUITheme.colorScheme.onSurfaceVariantSummary)
                }
            }
        }
        items(items, key = { it.key }, contentType = { it.event.kind }) { item -> EventRow(item, ctx) }
        item(key = "bottom") { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun EventRow(item: RenderItem, ctx: RenderContext) {
    val dev = LocalDevColors.current
    Row(Modifier.fillMaxWidth().padding(start = 12.dp + (14.dp * item.depth.coerceAtMost(4)), end = 12.dp, top = 5.dp, bottom = 5.dp)) {
        if (item.depth > 0) {
            Box(Modifier.width(2.dp).height(24.dp).background(dev.keyword.copy(alpha = 0.5f)))
            Spacer(Modifier.size(8.dp))
        }
        Box(Modifier.weight(1f)) { AgentEventRegistry.rendererFor(item.event.kind)(item, ctx) }
    }
}

@Composable
private fun Composer(state: SessionDetailState, busy: String?, onDraft: (String) -> Unit, onSend: () -> Unit) {
    val dev = LocalDevColors.current
    val sendStatus = state.cap(Capability.SEND_MESSAGE)
    Column(Modifier.fillMaxWidth().background(io.github.tianli0.switchtether.ui.common.pageContainerColor()).navigationBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp)) {
        if (!state.canSend) {
            Text(
                when {
                    !state.online -> stringResource(R.string.composer_offline)
                    else -> stringResource(R.string.composer_unavailable, sendStatus.name)
                },
                style = DevType.label,
                color = if (state.online) capColor(sendStatus) else dev.warn,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextInput(
                value = state.draft,
                onValueChange = onDraft,
                label = if (state.canSend) stringResource(R.string.composer_hint) else stringResource(R.string.composer_draft_hint),
                singleLine = false,
                maxLines = 5,
                modifier = Modifier.weight(1f).testTag("composer"),
            )
            Spacer(Modifier.size(8.dp))
            Button(
                onClick = onSend,
                enabled = state.canSend && state.draft.isNotBlank() && busy == null,
                colors = ButtonDefaults.buttonColorsPrimary(),
                minHeight = 48.dp,
                modifier = Modifier.testTag("send"),
            ) { Text(if (busy != null) "…" else stringResource(R.string.session_send), color = COUITheme.colorScheme.onPrimary) }
        }
    }
}


/** Dialog confirming one approval decision, showing what is being approved. */
@Composable
private fun ApprovalSheet(
    pending: Triple<String, String, String>?,
    request: io.github.tianli0.switchtether.remote.protocol.AgentEvent?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val dev = LocalDevColors.current
    // Hide the keyboard first so the dialog is not pushed over the approval card.
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    androidx.compose.runtime.LaunchedEffect(pending) {
        if (pending != null) {
            focus.clearFocus()
            keyboard?.hide()
        }
    }
    io.github.suqi8.coui.kmp.window.WindowDialog(
        show = pending != null,
        title = stringResource(R.string.approval_confirm_title),
        onDismissRequest = onDismiss,
    ) {
        val d = request?.data
        val decision = pending?.second.orEmpty()
        val danger = decision == "deny" || decision == "abort"
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            d?.str("title")?.let { Text(it, style = COUITheme.textStyles.body1, color = COUITheme.colorScheme.onSurface) }
            d?.str("toolName")?.let { Text(stringResource(R.string.render_tool, it), style = DevType.codeSmall, color = dev.gutter) }
            d?.str("command")?.let { io.github.tianli0.switchtether.ui.code.CodeBlockView(it, "bash", maxLines = 8) }
            Text(
                stringResource(R.string.approval_confirm_body, pending?.third.orEmpty()),
                style = COUITheme.textStyles.body2,
                color = if (danger) dev.danger else COUITheme.colorScheme.onSurfaceVariantSummary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(text = stringResource(R.string.action_cancel), onClick = onDismiss, modifier = Modifier.weight(1f))
                Button(
                    onClick = onConfirm,
                    colors = if (danger) ButtonDefaults.buttonColors() else ButtonDefaults.buttonColorsPrimary(),
                    modifier = Modifier.weight(1f).testTag("approval-confirm"),
                ) { Text(pending?.third.orEmpty(), color = if (danger) dev.danger else COUITheme.colorScheme.onPrimary) }
            }
        }
    }
}
