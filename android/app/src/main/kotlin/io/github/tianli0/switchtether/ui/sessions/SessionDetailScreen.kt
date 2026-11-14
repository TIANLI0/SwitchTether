package io.github.tianli0.switchtether.ui.sessions

import io.github.tianli0.switchtether.ui.icons.AppIcons

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.basic.Button
import io.github.suqi8.coui.kmp.basic.ButtonDefaults
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.RotatingProgressIndicator
import io.github.suqi8.coui.kmp.basic.SmallTitle
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.basic.TopTips
import io.github.suqi8.coui.kmp.basic.cardListPositionOf
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.window.WindowBottomSheet
import io.github.tianli0.switchtether.R
import io.github.tianli0.switchtether.remote.protocol.CapStatus
import io.github.tianli0.switchtether.remote.protocol.Capability
import io.github.tianli0.switchtether.remote.protocol.SessionDetail
import io.github.tianli0.switchtether.remote.protocol.str
import io.github.tianli0.switchtether.remote.session.RenderItem
import io.github.tianli0.switchtether.ui.appContainer
import io.github.tianli0.switchtether.ui.common.CommandPromptDialog
import io.github.tianli0.switchtether.ui.common.ConfirmDialog
import io.github.tianli0.switchtether.ui.common.ConnectionBanner
import io.github.tianli0.switchtether.ui.common.Formatters
import io.github.tianli0.switchtether.ui.common.GroupCard
import io.github.tianli0.switchtether.ui.common.Loadable
import io.github.tianli0.switchtether.ui.common.Page
import io.github.tianli0.switchtether.ui.common.ToolbarIcon
import io.github.tianli0.switchtether.ui.common.capColor
import io.github.tianli0.switchtether.ui.common.capStatusLabel
import io.github.tianli0.switchtether.ui.common.controlColor
import io.github.tianli0.switchtether.ui.common.controlLabel
import io.github.tianli0.switchtether.ui.common.originLabel
import io.github.tianli0.switchtether.ui.common.pageContainerColor
import io.github.tianli0.switchtether.ui.common.relativeTime
import io.github.tianli0.switchtether.ui.common.sessionStatusColor
import io.github.tianli0.switchtether.ui.common.title
import io.github.tianli0.switchtether.ui.common.waitingLabel
import io.github.tianli0.switchtether.ui.components.CollapsibleRow
import io.github.tianli0.switchtether.ui.components.ErrorState
import io.github.tianli0.switchtether.ui.components.InfoChip
import io.github.tianli0.switchtether.ui.components.InputPill
import io.github.tianli0.switchtether.ui.components.InputPillState
import io.github.tianli0.switchtether.ui.components.LoadingState
import io.github.tianli0.switchtether.ui.components.RoundIconButton
import io.github.tianli0.switchtether.ui.components.SegmentPosition
import io.github.tianli0.switchtether.ui.components.StatusDot
import io.github.tianli0.switchtether.ui.components.StatusPill
import io.github.tianli0.switchtether.ui.components.segment
import io.github.tianli0.switchtether.ui.containerViewModel
import io.github.tianli0.switchtether.ui.sessions.render.AgentEventRegistry
import io.github.tianli0.switchtether.ui.sessions.render.RenderContext
import io.github.tianli0.switchtether.ui.sessions.render.statusStateLabel
import io.github.tianli0.switchtether.ui.theme.LocalDevColors
import kotlinx.coroutines.launch

@Composable
fun SessionDetailScreen(sessionId: String, initialTitle: String?, onBack: () -> Unit, onOpenSession: (String, String?) -> Unit = { _, _ -> }) {
    val container = appContainer()
    val vm = containerViewModel(key = "session:$sessionId") { SessionDetailViewModel(sessionId, it.connection, it.sessions, it.draftStore) }
    val state by vm.state.collectAsStateWithLifecycle()
    val prompt by vm.commands.prompt.collectAsStateWithLifecycle()
    val busy by vm.commands.busy.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    var pendingLink by remember { mutableStateOf<String?>(null) }
    var pendingApproval by remember { mutableStateOf<Triple<String, String, String>?>(null) }
    var confirmInterrupt by remember { mutableStateOf(false) }
    var showContinue by rememberSaveable { mutableStateOf(false) }

    val sendLabel = stringResource(R.string.session_send)
    val interruptLabel = stringResource(R.string.session_interrupt)
    val approveLabel = stringResource(R.string.render_approval)
    val answerLabel = stringResource(R.string.render_submit_answer)
    val continueLabel = stringResource(R.string.continue_command)
    val forkLabel = stringResource(R.string.continue_command_fork)

    val summary = state.detail.valueOrNull?.summary
    val agent = summary?.agent ?: "unknown"
    val agentLabel = Formatters.agentLabel(agent)
    val ctx = remember(state.detail.valueOrNull?.capabilities, state.online, agent) {
        val caps = state.detail.valueOrNull?.capabilities
        RenderContext(
            agent = agent,
            agentLabel = agentLabel,
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

    LaunchedEffect(state.continuedAs?.id) {
        val created = state.continuedAs ?: return@LaunchedEffect
        showContinue = false
        vm.consumeContinued()
        onOpenSession(created.id, created.title)
    }

    SessionDetailContent(
        state = state,
        initialTitle = initialTitle,
        ctx = ctx,
        busy = busy,
        onBack = onBack,
        onReload = vm::load,
        onLoadOlder = vm::loadOlder,
        onToggleSteps = vm::toggleSteps,
        onDraft = vm::setDraft,
        onSend = { vm.send(sendLabel) },
        onInterrupt = { confirmInterrupt = true },
        onContinue = { showContinue = true },
        onRetryConnection = { container.connection.retryNow() },
        onOpenTerminal = { id -> container.openTerminalRequest.value = id },
    )

    ContinueSheet(
        show = showContinue,
        agentLabel = agentLabel,
        originalBusy = state.originalBusy,
        canFork = state.canFork,
        enabled = state.online && busy == null,
        busy = busy == continueLabel || busy == forkLabel,
        onConfirm = { fork -> vm.continueOnPhone(if (fork) forkLabel else continueLabel, fork) },
        onDismiss = { showContinue = false },
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
    onToggleSteps: (String) -> Unit,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    onInterrupt: () -> Unit,
    onContinue: () -> Unit,
    onRetryConnection: () -> Unit,
    onOpenTerminal: (String) -> Unit = {},
) {
    val summary = state.detail.valueOrNull?.summary
    var showInfo by rememberSaveable { mutableStateOf(false) }
    var tipDismissed by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    Page(
        title = summary?.title?.takeIf { it.isNotBlank() } ?: initialTitle ?: stringResource(R.string.session_untitled),
        subtitle = summary?.let { listOfNotNull(Formatters.agentLabel(it.agent), it.model).joinToString(" · ") } ?: "",
        onBack = onBack,
        actions = {
            summary?.terminalId?.let { tid -> ToolbarIcon(AppIcons.Terminal, stringResource(R.string.open_terminal), { onOpenTerminal(tid) }) }
            ToolbarIcon(AppIcons.Info, stringResource(R.string.session_info), { showInfo = true })
            ToolbarIcon(AppIcons.Refresh, stringResource(R.string.action_refresh), onReload)
        },
        modifier = Modifier.imePadding(),
    ) {
        ConnectionBanner(state.connection, onRetryConnection)
        StatusChipRow(state, onClick = { showInfo = true })
        val d = state.detail.valueOrNull
        if (d != null && state.online && !state.canSend) {
            val readOnly = !d.summary.managed && d.summary.origin != "managed" && d.summary.controlLevel != io.github.tianli0.switchtether.remote.protocol.ControlLevel.FULL
            val tip = if (readOnly) stringResource(R.string.session_readonly_tip) else stringResource(R.string.session_blocked_tip, capStatusLabel(state.cap(Capability.SEND_MESSAGE)))
            TopTips(
                text = tip,
                visible = !tipDismissed,
                startIcon = { Icon(AppIcons.Info, contentDescription = null, tint = COUITheme.colorScheme.onSurfaceSecondary, modifier = Modifier.size(20.dp)) },
                onClose = { tipDismissed = true },
                title = null,
                ignoreText = null,
                onIgnore = null,
                marqueeEnabled = false,
                content = { Text(tip, style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurfaceSecondary, maxLines = 4) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("readonly-tip"),
            )
        }
        if (state.pending.isNotEmpty()) {
            val n = state.pending.size
            TopTips(
                text = pluralStringResource(R.plurals.pending_banner, n, n),
                startIcon = { Icon(AppIcons.Lock, contentDescription = null, tint = COUITheme.colorScheme.primary, modifier = Modifier.size(20.dp)) },
                actionText = stringResource(R.string.pending_banner_action),
                onAction = {
                    val idx = state.timeline.indexOfFirst { it.key == state.pending.first() }
                    if (idx >= 0) scope.launch { listState.animateScrollToItem(idx + 1) }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("pending-banner"),
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val ev = state.events) {
                Loadable.Loading -> LoadingState(stringResource(R.string.state_loading))
                is Loadable.Failed -> ErrorState(ev.error.title(), ev.error.detail, stringResource(R.string.action_retry), onReload)
                is Loadable.Ready -> EventList(state, ctx, listState, onLoadOlder, onToggleSteps)
            }
        }
        Composer(state, busy, onDraft, onSend, onInterrupt, onContinue)
    }
    SessionInfoSheet(show = showInfo, detail = state.detail.valueOrNull, activity = state.rollingStatus?.data?.takeIf { it.str("state") !in setOf("idle", "completed", null) }?.let { listOfNotNull(statusStateLabel(it.str("state")).ifEmpty { null }, it.str("detail")).joinToString(" · ") }, onDismiss = { showInfo = false })
}

/** Compact status chips under the app bar; tapping opens the info sheet. */
@Composable
private fun StatusChipRow(state: SessionDetailState, onClick: () -> Unit) {
    val dev = LocalDevColors.current
    val s = state.detail.valueOrNull?.summary ?: return
    Column(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = stringResource(R.string.session_info), onClick = onClick).padding(vertical = 4.dp),
    ) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            io.github.tianli0.switchtether.ui.common.AgentBadge(s.agent, size = 28.dp)
            if (s.isAttached) InfoChip(io.github.tianli0.switchtether.ui.common.attachedLabel(s), dot = COUITheme.colorScheme.primary, color = COUITheme.colorScheme.primary)
            if (s.isLive) {
                InfoChip(statusLabel(s.status), dot = sessionStatusColor(s.status))
            } else {
                InfoChip(stringResource(R.string.badge_ended), dot = dev.neutral)
            }
            s.waitingFor?.let { InfoChip(waitingLabel(it), dot = dev.warn) }
            InfoChip(controlLabel(s.controlLevel), dot = controlColor(s.controlLevel))
            s.project?.let { InfoChip(it) }
            s.gitBranch?.let { InfoChip(it) }
        }
        state.rollingStatus?.takeIf { it.data.str("state") !in setOf("idle", "completed", null) }?.let { rs ->
            val text = listOfNotNull(statusStateLabel(rs.data.str("state")).ifEmpty { null }, rs.data.str("detail")).joinToString(" · ")
            if (text.isNotBlank()) {
                Row(Modifier.padding(start = 20.dp, end = 16.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (rs.data.str("state") == "running") {
                        RotatingProgressIndicator(color = COUITheme.colorScheme.primary, size = 12.dp, ringDiameter = 9.6.dp, strokeWidth = 2.dp)
                        Spacer(Modifier.size(6.dp))
                    }
                    Text(text, style = COUITheme.textStyles.footnote2, color = COUITheme.colorScheme.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun EventList(
    state: SessionDetailState,
    ctx: RenderContext,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onLoadOlder: () -> Unit,
    onToggleSteps: (String) -> Unit,
) {
    val rows = state.timeline
    // Start at the newest event; follow new events only while the user is at the bottom.
    var initialScrollDone by remember(state.sessionId) { mutableStateOf(false) }
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= info.totalItemsCount - 2
        }
    }
    // Own messages always bring the view to the bottom (the keyboard may have shrunk the list).
    val lastIsOwnMessage = (rows.lastOrNull() as? TimelineRow.Event)?.item?.event?.kind == io.github.tianli0.switchtether.remote.protocol.EventKinds.USER_MESSAGE
    LaunchedEffect(rows.size) {
        if (rows.isEmpty()) return@LaunchedEffect
        if (!initialScrollDone) {
            listState.scrollToItem(rows.size)
            initialScrollDone = true
        } else if (atBottom || lastIsOwnMessage) {
            listState.animateScrollToItem(rows.size)
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { if (it <= 1 && initialScrollDone) onLoadOlder() }
    }
    if (rows.isEmpty()) {
        Text(stringResource(R.string.session_no_events), style = COUITheme.textStyles.body2, color = COUITheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(24.dp))
        return
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag("event-list"), contentPadding = PaddingValues(bottom = 8.dp)) {
        item(key = "older") {
            Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                when {
                    state.loadingOlder -> RotatingProgressIndicator(size = 18.dp, ringDiameter = 14.4.dp, strokeWidth = 2.dp)
                    state.hasMoreBefore -> TextButton(text = stringResource(R.string.session_load_older), onClick = onLoadOlder)
                    else -> Text(stringResource(R.string.session_start), style = COUITheme.textStyles.footnote2, color = COUITheme.colorScheme.onSurfaceVariantSummary)
                }
            }
        }
        items(rows, key = { it.key }, contentType = { if (it is TimelineRow.Event) it.item.event.kind else "steps" }) { row ->
            when (row) {
                is TimelineRow.Event -> TimelineEventRow(row.item, row.segment, ctx)
                is TimelineRow.Steps -> StepsHeader(row, onToggleSteps)
            }
        }
    }
}

@Composable
private fun TimelineEventRow(item: RenderItem, segment: SegmentPosition, ctx: RenderContext) {
    val outer = when (segment) {
        SegmentPosition.None -> PaddingValues(horizontal = 16.dp, vertical = 6.dp)
        SegmentPosition.Single -> PaddingValues(horizontal = 12.dp, vertical = 4.dp)
        SegmentPosition.First, SegmentPosition.Middle -> PaddingValues(horizontal = 12.dp)
        SegmentPosition.Last -> PaddingValues(start = 12.dp, end = 12.dp, bottom = 6.dp)
    }
    val indent = 12.dp * item.depth.coerceAtMost(4)
    Box(Modifier.fillMaxWidth().padding(outer).segment(segment)) {
        Box(Modifier.padding(start = indent, bottom = if (segment == SegmentPosition.Last) 4.dp else 0.dp)) {
            AgentEventRegistry.rendererFor(item.event.kind)(item, ctx)
        }
    }
}

@Composable
private fun StepsHeader(row: TimelineRow.Steps, onToggle: (String) -> Unit) {
    val dev = LocalDevColors.current
    Box(
        Modifier.fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = if (row.expanded) 0.dp else 4.dp)
            .segment(if (row.expanded) SegmentPosition.First else SegmentPosition.Single)
            .testTag("steps-${row.groupId}"),
    ) {
        CollapsibleRow(
            icon = AppIcons.Steps,
            iconTint = COUITheme.colorScheme.primary,
            title = pluralStringResource(R.plurals.session_steps, row.steps, row.steps),
            subtitle = row.latestTitle,
            expanded = row.expanded,
            onToggle = { onToggle(row.groupId) },
            toggleLabel = stringResource(R.string.render_toggle),
        ) {
            when {
                row.running -> RotatingProgressIndicator(color = COUITheme.colorScheme.primary, size = 18.dp, ringDiameter = 14.4.dp, strokeWidth = 2.dp)
                row.failed > 0 -> StatusPill(stringResource(R.string.session_steps_failed, row.failed), dev.danger)
                else -> Icon(AppIcons.Done, contentDescription = stringResource(R.string.step_completed), tint = dev.ok, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun Composer(
    state: SessionDetailState,
    busy: String?,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    onInterrupt: () -> Unit,
    onContinue: () -> Unit,
) {
    val s = state.detail.valueOrNull?.summary
    Column(
        Modifier.fillMaxWidth().background(pageContainerColor()).navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        // Full control (managed, or the user's own process attached via the wrapper) keeps the normal
        // composer; only observe/history sessions get the read-only bar.
        val blocked = state.online && !state.canSend && state.detail is Loadable.Ready &&
            s?.controlLevel != io.github.tianli0.switchtether.remote.protocol.ControlLevel.FULL
        if (blocked) {
            // Read-only: say so plainly instead of showing a dead input field.
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).segment(SegmentPosition.Single, radius = 24.dp).padding(start = 18.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(AppIcons.Lock, contentDescription = null, tint = COUITheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(
                    stringResource(if (s != null && !s.managed && s.origin != "managed") R.string.composer_readonly else R.string.composer_send_unavailable),
                    style = COUITheme.textStyles.body2,
                    color = COUITheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (state.canOfferContinue) {
                    Button(onClick = onContinue, colors = ButtonDefaults.buttonColorsPrimary(), minHeight = 36.dp, modifier = Modifier.testTag("continue")) {
                        Text(stringResource(R.string.continue_action), color = COUITheme.colorScheme.onPrimary, style = COUITheme.textStyles.button)
                    }
                }
            }
            return@Column
        }
        if (!state.online) {
            Text(
                stringResource(R.string.composer_offline),
                style = COUITheme.textStyles.footnote2,
                color = LocalDevColors.current.warn,
                modifier = Modifier.padding(start = 12.dp, bottom = 6.dp),
            )
        }
        Row(verticalAlignment = Alignment.Bottom) {
            val showInterrupt = state.canInterrupt && s != null && s.isLive && s.status != "idle"
            if (showInterrupt) {
                RoundIconButton(AppIcons.Interrupt, stringResource(R.string.session_interrupt), onInterrupt, tint = LocalDevColors.current.danger, modifier = Modifier.testTag("interrupt"))
                Spacer(Modifier.size(8.dp))
            }
            InputPill(
                value = state.draft,
                onValueChange = onDraft,
                placeholder = when {
                    state.canSend -> stringResource(R.string.composer_hint)
                    state.online -> stringResource(R.string.composer_send_unavailable)
                    else -> stringResource(R.string.composer_draft_hint)
                },
                sendLabel = stringResource(R.string.session_send),
                onSend = onSend,
                state = InputPillState(enabled = state.canSend, sendEnabled = state.canSend && state.draft.isNotBlank(), busy = busy != null),
                modifier = Modifier.weight(1f),
                fieldModifier = Modifier.testTag("composer"),
                sendModifier = Modifier.testTag("send"),
            )
        }
    }
}

/** Bottom sheet with everything about the session that does not fit the chip row. */
@Composable
private fun SessionInfoSheet(show: Boolean, detail: SessionDetail?, activity: String?, onDismiss: () -> Unit) {
    WindowBottomSheet(show = show, title = stringResource(R.string.session_info), onDismissRequest = onDismiss) {
        val d = detail ?: return@WindowBottomSheet
        val s = d.summary
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            val rows = listOfNotNull(
                stringResource(R.string.session_info_agent) to Formatters.agentLabel(s.agent),
                s.model?.let { stringResource(R.string.session_info_model) to it },
                stringResource(R.string.session_info_status) to (if (s.isLive) statusLabel(s.status) else stringResource(R.string.badge_ended)),
                activity?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.session_info_activity) to it },
                stringResource(R.string.session_info_control) to controlLabel(s.controlLevel),
                stringResource(R.string.session_info_origin) to (if (s.isAttached) io.github.tianli0.switchtether.ui.common.attachedLabel(s) else originLabel(s.origin, s.managed)),
                s.project?.let { stringResource(R.string.session_info_project) to it },
                s.gitBranch?.let { stringResource(R.string.session_info_branch) to it },
                s.cwd?.let { stringResource(R.string.session_info_cwd) to it },
                stringResource(R.string.session_info_last_active) to relativeTime(s.lastActiveAtMs),
            )
            GroupCard {
                rows.forEachIndexed { i, (k, v) -> BasicComponent(title = k, summary = v, cardListPosition = cardListPositionOf(i, rows.size)) }
            }
            SmallTitle(stringResource(R.string.session_info_capabilities))
            val caps = listOf(
                Capability.SEND_MESSAGE to R.string.cap_send,
                Capability.APPROVE to R.string.cap_approve,
                Capability.ANSWER_QUESTION to R.string.cap_answer,
                Capability.INTERRUPT to R.string.cap_interrupt,
            )
            GroupCard {
                caps.forEachIndexed { i, (cap, label) ->
                    val st = d.capabilities.status(cap)
                    BasicComponent(
                        title = stringResource(label),
                        endActions = {
                            StatusDot(capColor(st))
                            Spacer(Modifier.size(6.dp))
                            Text(capStatusLabel(st), style = COUITheme.textStyles.body2, color = capColor(st))
                        },
                        cardListPosition = cardListPositionOf(i, caps.size),
                    )
                }
            }
            d.capabilities.reason?.let {
                Text(it, style = COUITheme.textStyles.footnote2, color = COUITheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            }
        }
    }
}

/** Confirm sheet for "continue on phone" (fork into a managed session with the same context). */
@Composable
private fun ContinueSheet(show: Boolean, agentLabel: String, originalBusy: Boolean, canFork: Boolean, enabled: Boolean, busy: Boolean, onConfirm: (fork: Boolean) -> Unit, onDismiss: () -> Unit) {
    WindowBottomSheet(show = show, title = stringResource(R.string.continue_title), onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(
                stringResource(R.string.continue_point_context, agentLabel),
                stringResource(R.string.continue_point_window),
                stringResource(R.string.continue_point_busy),
            ).forEach { line ->
                Row(verticalAlignment = Alignment.Top) {
                    StatusDot(COUITheme.colorScheme.primary, Modifier.padding(top = 7.dp), size = 6.dp)
                    Spacer(Modifier.size(10.dp))
                    Text(line, style = COUITheme.textStyles.body2, color = COUITheme.colorScheme.onSurface)
                }
            }
            if (originalBusy) {
                Text(stringResource(R.string.continue_busy), style = COUITheme.textStyles.footnote1, color = LocalDevColors.current.warn, fontWeight = FontWeight.Medium)
            }
            if (canFork) {
                Text(stringResource(R.string.continue_fork_hint), style = COUITheme.textStyles.footnote2, color = COUITheme.colorScheme.onSurfaceVariantSummary)
            }
            Button(
                onClick = { onConfirm(false) },
                enabled = enabled && !originalBusy && !busy,
                colors = ButtonDefaults.buttonColorsPrimary(),
                minHeight = 48.dp,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp).testTag("continue-confirm"),
            ) {
                if (busy) {
                    RotatingProgressIndicator(color = COUITheme.colorScheme.onPrimary, size = 18.dp, ringDiameter = 14.4.dp, strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.continue_confirm), color = COUITheme.colorScheme.onPrimary)
                }
            }
            if (canFork) {
                Button(
                    onClick = { onConfirm(true) },
                    enabled = enabled && !originalBusy && !busy,
                    colors = ButtonDefaults.buttonColors(),
                    minHeight = 48.dp,
                    modifier = Modifier.fillMaxWidth().testTag("continue-fork"),
                ) { Text(stringResource(R.string.continue_fork), color = COUITheme.colorScheme.onSurface) }
            }
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
    LaunchedEffect(pending) {
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
            d?.str("toolName")?.let { Text(stringResource(R.string.render_tool, it), style = COUITheme.textStyles.footnote2, color = COUITheme.colorScheme.onSurfaceVariantSummary) }
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
