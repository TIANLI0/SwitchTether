package io.github.tianli0.switchtether.ui.sessions

import io.github.tianli0.coui17.patterns.glassHeader
import io.github.tianli0.coui17.patterns.glassSurface
import io.github.tianli0.coui17.patterns.LocalGlassBackdrop
import io.github.suqi8.coui.kmp.blur.rememberLayerBackdrop
import io.github.suqi8.coui.kmp.blur.layerBackdrop
import io.github.suqi8.coui.kmp.blur.isRuntimeShaderSupported
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.CompositionLocalProvider
import io.github.tianli0.coui17.patterns.PageDefaults
import io.github.suqi8.coui.kmp.layout.DialogButtonBarAction
import io.github.tianli0.coui17.patterns.SheetButtons
import io.github.tianli0.coui17.patterns.ActionTone
import io.github.tianli0.coui17.patterns.SmallButton
import io.github.tianli0.coui17.patterns.LinkButton
import androidx.compose.ui.graphics.compositeOver

import io.github.tianli0.switchtether.ui.icons.AppIcons
import io.github.suqi8.coui.kmp.squircle.SquircleShape

import androidx.compose.foundation.background
import io.github.suqi8.coui.kmp.squircle.squircleClip
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import io.github.tianli0.switchtether.remote.protocol.arr
import io.github.tianli0.switchtether.remote.protocol.obj
import io.github.tianli0.switchtether.remote.protocol.long
import io.github.tianli0.switchtether.remote.protocol.double
import io.github.tianli0.coui17.patterns.StatFigure
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.asPaddingValues
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import io.github.tianli0.switchtether.ui.keys.chord
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
import io.github.tianli0.switchtether.R
import io.github.tianli0.switchtether.remote.protocol.CapStatus
import io.github.tianli0.switchtether.remote.protocol.Capability
import io.github.tianli0.switchtether.remote.protocol.SessionDetail
import io.github.tianli0.switchtether.remote.protocol.bool
import io.github.tianli0.switchtether.remote.protocol.str
import io.github.tianli0.switchtether.remote.session.RenderItem
import io.github.tianli0.switchtether.ui.appContainer
import io.github.tianli0.coui17.patterns.PanelSheet
import io.github.tianli0.switchtether.ui.common.CommandPromptDialog
import io.github.tianli0.coui17.patterns.ConfirmDialog
import io.github.tianli0.switchtether.ui.common.ConnectionBanner
import io.github.tianli0.switchtether.ui.common.Formatters
import io.github.tianli0.coui17.patterns.GroupCard
import io.github.tianli0.switchtether.ui.common.Loadable
import io.github.tianli0.coui17.patterns.Page
import io.github.tianli0.coui17.patterns.ToolbarIcon
import io.github.tianli0.switchtether.ui.common.capColor
import io.github.tianli0.switchtether.ui.common.capStatusLabel
import io.github.tianli0.switchtether.ui.common.controlColor
import io.github.tianli0.switchtether.ui.common.controlLabel
import io.github.tianli0.switchtether.ui.common.originLabel
import io.github.tianli0.coui17.patterns.pageContainerColor
import io.github.tianli0.switchtether.ui.common.relativeTime
import io.github.tianli0.switchtether.ui.common.sessionStatusColor
import io.github.tianli0.switchtether.ui.common.title
import io.github.tianli0.switchtether.ui.common.waitingLabel
import io.github.tianli0.coui17.patterns.CollapsibleRow
import io.github.tianli0.coui17.patterns.ErrorState
import io.github.tianli0.coui17.patterns.InfoChip
import io.github.tianli0.coui17.patterns.InputPillState
import io.github.tianli0.coui17.patterns.LoadingState
import io.github.tianli0.coui17.patterns.RoundIconButton
import io.github.tianli0.coui17.patterns.SegmentPosition
import io.github.tianli0.coui17.patterns.StatusDot
import io.github.tianli0.coui17.patterns.StatusPill
import io.github.tianli0.coui17.patterns.segment
import io.github.tianli0.switchtether.ui.containerViewModel
import io.github.tianli0.switchtether.ui.sessions.render.AgentEventRegistry
import io.github.tianli0.switchtether.ui.sessions.render.RenderContext
import io.github.tianli0.switchtether.ui.sessions.render.statusStateLabel
import io.github.tianli0.switchtether.ui.theme.LocalDevColors
import kotlinx.coroutines.launch


/** "Rewind files" is built but switched off for now (desktop refuses it too). */
const val REWIND_FILES_ENABLED = false

@Composable
fun SessionDetailScreen(
    sessionId: String,
    initialTitle: String?,
    onBack: () -> Unit,
    onOpenSession: (String, String?) -> Unit = { _, _ -> },
    onOpenRoute: (io.github.tianli0.switchtether.ui.nav.Route) -> Unit = {},
) {
    val container = appContainer()
    val vm = containerViewModel(key = "session:$sessionId") { SessionDetailViewModel(sessionId, it.connection, it.sessions, it.draftStore, it::noteLocalAction, liveDispatcher = kotlinx.coroutines.Dispatchers.Default, stepUp = { scope -> it.stepUpGate.ensure(scope).isSuccess }, transfers = it.transfers) }
    val state by vm.state.collectAsStateWithLifecycle()
    // "Share to SwitchTether": a share sent to this session fills the input box (never sends).
    io.github.tianli0.switchtether.share.ShareIntoComposer(sessionId, vm, state)
    val pins by container.livePins.pins.collectAsStateWithLifecycle()
    val pinned = sessionId in pins
    val context = androidx.compose.ui.platform.LocalContext.current
    val prompt by vm.commands.prompt.collectAsStateWithLifecycle()
    val busy by vm.commands.busy.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    var pendingLink by remember { mutableStateOf<String?>(null) }
    var viewingFile by remember { mutableStateOf<io.github.tianli0.switchtether.ui.files.RemoteFileRef?>(null) }
    var pendingApproval by remember { mutableStateOf<Triple<String, String, String>?>(null) }
    var viewingImage by remember { mutableStateOf<io.github.tianli0.switchtether.ui.sessions.render.ImagePreview?>(null) }
    // A file on the computer: a picture goes to the lightbox (the same one as pictures in messages),
    // anything else to the file viewer.
    val openDesktopFile: (String) -> Unit = { path ->
        val name = io.github.tianli0.switchtether.files.FileTypes.fileName(path)
        val kind = io.github.tianli0.switchtether.files.FileTypes.kindOf(name)
        if (kind == io.github.tianli0.switchtether.files.FileKind.IMAGE) viewingImage = io.github.tianli0.switchtether.ui.sessions.render.ImagePreview(path = path)
        else viewingFile = io.github.tianli0.switchtether.ui.files.RemoteFileRef(path, name, kind)
    }
    var confirmInterrupt by remember { mutableStateOf(false) }
    var confirmStop by rememberSaveable { mutableStateOf(false) }
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
    val cancelLabel = stringResource(R.string.action_cancel)
    val declineLabel = stringResource(R.string.render_elicitation_decline)
    val claudeTools = state.claudeTools
    val permissionLabel = stringResource(R.string.tools_permission)
    val backgrounding = state.backgrounding
    // Changing model / effort / permission mode is a run setting: it must not disable (and redraw)
    // every card of the conversation while the desktop applies it.
    val switchLabelForBusy = stringResource(R.string.model_switch_label)
    val cardsBusy = busy != null && busy != switchLabelForBusy && busy != permissionLabel
    val ctx = remember(state.detail.valueOrNull?.capabilities, state.online, agent, summary?.cwd, cardsBusy, claudeTools, backgrounding) {
        val caps = state.detail.valueOrNull?.capabilities
        RenderContext(
            agent = agent,
            agentLabel = agentLabel,
            approve = caps?.status(Capability.APPROVE) ?: CapStatus.UNKNOWN,
            answer = caps?.status(Capability.ANSWER_QUESTION) ?: CapStatus.UNKNOWN,
            capabilityReason = caps?.reason,
            online = state.online,
            requestBusy = cardsBusy,
            // The card already shows exactly what is being approved, so a plain allow / deny is one
            // tap. Broader decisions (allow for the whole session, stop the agent) still confirm.
            onApprove = { rid, decision, label ->
                if (decision == "allow" || decision == "deny") vm.resolveApproval("$approveLabel: $label", rid, decision)
                else pendingApproval = Triple(rid, decision, label)
            },
            onApprovePlan = if (state.canUseClaudeTools) {
                { rid, mode, label -> vm.approvePlan("$approveLabel: $label", permissionLabel, rid, mode) }
            } else null,
            onAnswer = { rid, answers -> vm.answer(answerLabel, rid, answers) },
            onAnswerAction = { rid, answers, action ->
                val label = when (action) {
                    "cancel" -> cancelLabel
                    "decline" -> declineLabel
                    else -> answerLabel
                }
                vm.answer(label, rid, answers, action)
            },
            onLoadPayload = vm::loadPayload,
            payloadOf = { vm.payloads[it] },
            onOpenSubagent = if (agent in SUBAGENT_AGENTS) {
                { aid, title -> onOpenRoute(io.github.tianli0.switchtether.ui.nav.Route.Subagent(sessionId, aid, title, agent)) }
            } else null,
            subagentPreview = { vm.subagentPreviews[it] },
            onWantSubagentPreview = vm::loadSubagentPreview,
            onToggleCollapse = vm::toggleCollapse,
            isCollapsed = { it in vm.state.value.collapsed },
            onLink = { link ->
                // A link to a file on the desktop opens in the local viewer (pdf / image / video / audio / other app).
                val path = io.github.tianli0.switchtether.files.FileTypes.resolveChatPath(link, summary?.cwd)
                if (path != null) {
                    openDesktopFile(path)
                } else if (link.startsWith("https://") || link.startsWith("http://")) {
                    pendingLink = link
                }
            },
            // The file a Read / Edit / Write step is about opens in the same viewer.
            onOpenFile = { file ->
                io.github.tianli0.switchtether.files.FileTypes.resolveChatPath(file, summary?.cwd)?.let(openDesktopFile)
            },
            onLoadImageBytes = vm::loadImageBytes,
            onViewImage = { viewingImage = it },
            // Claude Code session tools (a Claude process SwitchTether started).
            onRunInBackground = if (claudeTools) vm::runInBackground else null,
            isBackgrounding = { it in backgrounding },
            onRewindFiles = if (claudeTools && REWIND_FILES_ENABLED) vm::previewRewind else null,
        )
    }

    val scope = rememberCoroutineScope()
    // Picked photos and files are uploaded to the computer at once and reach the agent as paths.
    val addPicked: (List<android.net.Uri>) -> Unit = { uris ->
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            uris.forEach { uri ->
                val (name, size, mime) = pickedFileInfo(context, uri)
                vm.addAttachment(name, size, mime, localUri = uri.toString()) { context.contentResolver.openInputStream(uri) }
            }
        }
    }
    val pickMedia = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia(MAX_ATTACHMENTS),
    ) { addPicked(it) }
    val pickFiles = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments(),
    ) { addPicked(it) }
    var showAttach by remember { mutableStateOf(false) }
    var showModels by rememberSaveable { mutableStateOf(false) }
    val switchLabel = stringResource(R.string.model_switch_label)
    // The desktop's capability decides (sessions taken over from VS Code / Codex Desktop can change it too).
    val modelChangeable = state.canChangeModel
    ModelEffortSheet(
        show = showModels,
        agent = state.detail.valueOrNull?.summary?.agent.orEmpty(),
        options = state.modelOptions,
        model = state.detail.valueOrNull?.summary?.model,
        effort = state.detail.valueOrNull?.summary?.effort,
        // Only another command in flight (an approval, a continue) locks the chooser; a model or
        // effort change still being applied does not grey the sheet out and back.
        busy = busy != null && busy != switchLabel && busy != permissionLabel,
        onModel = { vm.setModel(it, switchLabel) },
        onEffort = { vm.setEffort(it, switchLabel) },
        modelNote = if (modelChangeable) null else stringResource(R.string.model_attached_note),
        optionsLoading = state.modelOptionsLoading,
        optionsFailed = state.modelOptionsFailed,
        onRetryOptions = vm::loadModelOptions,
        onDismiss = {
            showModels = false
            vm.clearPermissionError()
        },
        extra = {
            // The permission mode belongs with the run settings (one sheet, one chip), for every agent
            // the desktop offers modes for (Claude Code, Codex).
            if (state.hasPermissionModes) {
                SmallTitle(stringResource(R.string.tools_permission))
                PermissionModeOptions(
                    current = state.detail.valueOrNull?.permissionMode,
                    pending = state.permissionPending,
                    enabled = state.canPickPermission,
                    onSelect = vm::setPermissionMode,
                    modes = state.permissionModes,
                    agent = state.detail.valueOrNull?.summary?.agent,
                )
                state.permissionError?.let {
                    Text(stringResource(R.string.tools_permission_failed, it), style = COUITheme.textStyles.footnote1, color = LocalDevColors.current.danger, modifier = Modifier.padding(horizontal = PageDefaults.TextGutter).padding(bottom = 8.dp))
                }
            }
        },
    )

    var showPermission by rememberSaveable { mutableStateOf(false) }
    PermissionModeSheet(
        show = showPermission && state.hasPermissionModes,
        current = state.detail.valueOrNull?.permissionMode,
        pending = state.permissionPending,
        error = state.permissionError,
        enabled = state.canPickPermission,
        onSelect = vm::setPermissionMode,
        onDismiss = {
            showPermission = false
            vm.clearPermissionError()
        },
        modes = state.permissionModes,
        agent = state.detail.valueOrNull?.summary?.agent,
    )
    RewindDialog(state.rewind, onConfirm = vm::confirmRewind, onDismiss = vm::dismissRewind)
    // Slash commands: what the phone does itself opens here; the rest is a message (view model).
    var showSlashHelp by rememberSaveable { mutableStateOf(false) }
    var showSlashRename by rememberSaveable { mutableStateOf(false) }
    // Hidden while the tools are gone (offline, process ended): it must not pop up again by itself later.
    val canRename = state.hasSessionTool(io.github.tianli0.switchtether.remote.session.ClaudeTools.RENAME)
    LaunchedEffect(canRename) { if (!canRename) showSlashRename = false }
    val onSlashCommand: (SlashEntry) -> Unit = { entry ->
        when (vm.pickSlashCommand(entry)) {
            SlashAction.SEND, SlashAction.INSERT -> Unit
            SlashAction.OPEN_MODEL -> {
                if (modelChangeable) vm.loadModelOptions()
                showModels = true
            }
            SlashAction.OPEN_PERMISSION -> showPermission = true
            SlashAction.RENAME -> showSlashRename = true
            SlashAction.HELP -> showSlashHelp = true
            // Context, plan, MCP, export and the side question are sections of the session tools page.
            SlashAction.OPEN_TOOLS, SlashAction.CONTEXT, SlashAction.PLAN, SlashAction.MCP, SlashAction.EXPORT, SlashAction.SIDE_QUESTION ->
                onOpenRoute(io.github.tianli0.switchtether.ui.nav.Route.SessionTools(sessionId))
        }
    }
    SlashHelpSheet(
        show = showSlashHelp,
        commands = state.slashCommands,
        onPick = {
            showSlashHelp = false
            onSlashCommand(it)
        },
        onDismiss = { showSlashHelp = false },
    )
    SlashRenameDialog(
        show = showSlashRename && canRename,
        current = state.detail.valueOrNull?.summary?.title.orEmpty(),
        onRename = vm::renameSession,
        onDismiss = { showSlashRename = false },
    )
    val noticeText = when (val n = state.notice) {
        null -> null
        ToolNotice.Withdrawn -> stringResource(R.string.tools_withdrawn)
        ToolNotice.WithdrawTooLate -> stringResource(R.string.tools_withdraw_too_late)
        ToolNotice.Backgrounded -> stringResource(R.string.tools_backgrounded)
        ToolNotice.NotBackgrounded -> stringResource(R.string.tools_not_backgrounded)
        is ToolNotice.Rewound -> pluralStringResource(R.plurals.rewind_done, n.files, n.files)
        is ToolNotice.Failed -> n.message
    }
    LaunchedEffect(state.notice) {
        if (noticeText != null) {
            android.widget.Toast.makeText(context, noticeText, android.widget.Toast.LENGTH_SHORT).show()
            vm.consumeNotice()
        }
    }

    LaunchedEffect(state.continuedAs?.id) {
        val created = state.continuedAs ?: return@LaunchedEffect
        showContinue = false
        vm.consumeContinued()
        onOpenSession(created.id, created.title)
    }

    val turnEdited = remember(state.log) { io.github.tianli0.switchtether.remote.session.TurnFiles.latestTurn(state.log.events).isNotEmpty() }
    // Hardware keyboard (§35): Esc interrupts the running turn (asking first, like the stop
    // capsule), Ctrl+/ lists the slash commands. Only what the page offers right now.
    val keyState = io.github.tianli0.switchtether.ui.keys.SessionKeyState(
        canInterrupt = state.canInterrupt,
        turnRunning = summary != null && summary.isLive && summary.status != "idle",
        composerEnabled = state.canSend || state.canQueue,
        hasSlashCommands = state.slashCommands.isNotEmpty(),
    )
    io.github.tianli0.switchtether.ui.keys.ShortcutHandler { action ->
        if (!keyState.allows(action)) return@ShortcutHandler false
        when (action) {
            io.github.tianli0.switchtether.ui.keys.ShortcutAction.INTERRUPT -> confirmInterrupt = true
            io.github.tianli0.switchtether.ui.keys.ShortcutAction.SLASH_PANEL -> showSlashHelp = true
            else -> return@ShortcutHandler false
        }
        true
    }
    val intake = rememberComposerIntake(vm)
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
        onSend = vm::send,
        onRetryOutgoing = vm::retrySend,
        onDeleteOutgoing = vm::deleteOutgoing,
        onEditOutgoing = vm::editOutgoing,
        onInterrupt = { confirmInterrupt = true },
        onModelClick = {
            if (modelChangeable) vm.loadModelOptions()
            showModels = true
        },
        // An ended session has no window on the computer that could disagree: resume at once.
        // A session still open there gets the short explanation first.
        onContinue = {
            val s = state.detail.valueOrNull?.summary
            if (s != null && !s.isLive) vm.continueOnPhone(continueLabel, false) else showContinue = true
        },
        onRetryConnection = { container.connection.retryNow() },
        onOpenTerminal = { id -> container.openTerminalRequest.value = id },
        onOpenSubagentTree = { onOpenRoute(io.github.tianli0.switchtether.ui.nav.Route.SubagentTree(sessionId, agent)) },
        pinned = pinned,
        onTogglePin = { state.detail.valueOrNull?.summary?.let { s -> io.github.tianli0.switchtether.ui.common.togglePin(context, s) } },
        onPickImage = { showAttach = true },
        onRemoveImage = vm::removeImage,
        onRemoveAttachment = vm::removeAttachment,
        onWithdrawOutgoing = vm::withdrawOutgoing,
        onOpenTools = if (state.claudeTools || state.claudeReadTools) {
            { onOpenRoute(io.github.tianli0.switchtether.ui.nav.Route.SessionTools(sessionId)) }
        } else null,
        // DeepSeek Harness sessions: its own web UI, through the desktop (feature dsh_web).
        onOpenDshWeb = if (agent == "dsh" && io.github.tianli0.switchtether.ui.workspace.rememberDshWebAvailable()) {
            { onOpenRoute(io.github.tianli0.switchtether.ui.nav.Route.DshWeb) }
        } else null,
        // "本轮改动": the session folder's Git review with the latest turn's files first. For a folder
        // the desktop reported a branch for, or once the latest turn edited files (observed sessions
        // often carry no branch; the review says so itself when the folder is not a repository).
        onOpenChanges = summary?.cwd?.takeIf { summary.gitBranch != null || turnEdited }?.let { cwd ->
            {
                val files = io.github.tianli0.switchtether.remote.session.TurnFiles.latestTurn(state.log.events)
                onOpenRoute(io.github.tianli0.switchtether.ui.nav.Route.GitReview(cwd, files))
            }
        },
        onPermissionClick = { showPermission = true },
        onSlashCommand = onSlashCommand,
        onOpenProjectUsage = { onOpenRoute(io.github.tianli0.switchtether.ui.nav.Route.ProjectUsage(it)) },
        // Also here, not only on the Claude Code tools page: a Codex session SwitchTether started
        // has no tools page, and its process is just as much ours to end.
        onEndSession = if (state.canStop) {
            { confirmStop = true }
        } else null,
        onIntake = intake,
    )
    val endLabel = stringResource(R.string.session_end)
    ConfirmDialog(
        show = confirmStop && state.canStop,
        title = endLabel,
        message = stringResource(R.string.session_end_confirm),
        confirmText = endLabel,
        onConfirm = {
            confirmStop = false
            vm.stopSession(endLabel)
        },
        onDismiss = { confirmStop = false },
    )

    AttachSheet(
        show = showAttach,
        onImages = {
            showAttach = false
            pickMedia.launch(
                androidx.activity.result.PickVisualMediaRequest(
                    androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageAndVideo,
                ),
            )
        },
        onFiles = {
            showAttach = false
            pickFiles.launch(arrayOf("*/*"))
        },
        onDismiss = { showAttach = false },
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
    viewingImage?.let { img ->
        ImageViewerDialog(preview = img, onLoadBytes = vm::loadImageBytes, onDismiss = { viewingImage = null })
    }
    viewingFile?.let { ref ->
        io.github.tianli0.switchtether.ui.common.AppFullScreenDialog(onDismissRequest = { viewingFile = null }) {
            // The page colour runs under the status and gesture bars; the viewer sits between them.
            Box(Modifier.fillMaxSize().background(pageContainerColor()).statusBarsPadding().navigationBarsPadding()) {
                io.github.tianli0.switchtether.ui.files.RemoteFileViewer(ref, onBack = { viewingFile = null }, onManageDownloads = null)
            }
        }
    }
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
    onOpenSubagentTree: (() -> Unit)? = null,
    pinned: Boolean = false,
    onTogglePin: () -> Unit = {},
    onRetryOutgoing: (String) -> Unit = {},
    onDeleteOutgoing: (String) -> Unit = {},
    onEditOutgoing: (String) -> Unit = {},
    onPickImage: () -> Unit = {},
    onRemoveImage: (Int) -> Unit = {},
    onRemoveAttachment: (String) -> Unit = {},
    onModelClick: () -> Unit = {},
    onWithdrawOutgoing: ((String) -> Unit)? = null,
    /** Claude Code session tools page (null = not offered for this session). */
    onOpenTools: (() -> Unit)? = null,
    onOpenDshWeb: (() -> Unit)? = null,
    /** Git review of the session's folder ("本轮改动"; null = not a repository). */
    onOpenChanges: (() -> Unit)? = null,
    onPermissionClick: (() -> Unit)? = null,
    /** A command picked from the "/" panel (null = no panel). */
    onSlashCommand: ((SlashEntry) -> Unit)? = null,
    /** "End session" in the info sheet (null = not offered: only a live session SwitchTether started). */
    onEndSession: (() -> Unit)? = null,
    /** "Usage of project …" in the cost sheet (null = not offered). */
    onOpenProjectUsage: ((String) -> Unit)? = null,
    /**
     * Content from other apps (drag and drop, keyboard images, pasted pictures, §35): the payload and
     * whether the text field already inserted its text. Null = the page takes no drops.
     */
    onIntake: ((DropPayload, Boolean) -> Unit)? = null,
) {
    val summary = state.detail.valueOrNull?.summary
    var showInfo by rememberSaveable { mutableStateOf(false) }
    // A drag from another app over the conversation (page) or over the input field.
    var pageDropHover by remember { mutableStateOf(false) }
    var fieldDropHover by remember { mutableStateOf(false) }
    // Cost and tokens of the loaded turns (§26); the capsule only shows when a turn reported usage.
    var showCost by rememberSaveable { mutableStateOf(false) }
    val usageBreakdown = remember(state.log, summary?.agent) { sessionUsageBreakdown(state.log.events, summary?.agent) }
    // Scans of the whole log, redone only when the log changes (not on every recomposition).
    val hasAgents = summary?.agent in SUBAGENT_AGENTS && remember(state.log) {
        state.log.events.any {
            it.kind == io.github.tianli0.switchtether.remote.protocol.EventKinds.SUBAGENT ||
                (it.kind == io.github.tianli0.switchtether.remote.protocol.EventKinds.TOOL_CALL && it.data.bool("background") == true)
        }
    }
    val agentsDot = remember(state.log, summary?.waitingFor) { agentsPillState(state) }
    // What runs right now: nothing does once the session has ended (a row left "running" by an
    // interrupted turn or a closed process is not shown as running).
    val sessionLive = summary?.isLive == true
    val backgroundTasks = remember(state.log, sessionLive) { backgroundTaskCounts(state).let { (all, running) -> all to if (sessionLive) running else 0 } }
    val subagentCount = remember(state.log) { state.log.events.count { it.kind == io.github.tianli0.switchtether.remote.protocol.EventKinds.SUBAGENT } }
    val subagentRunning = remember(state.log, sessionLive) {
        if (!sessionLive) 0 else state.log.events.count { it.kind == io.github.tianli0.switchtether.remote.protocol.EventKinds.SUBAGENT && it.data.str("status") == "running" }
    }
    // The read-only tip explains once; the read-only composer bar keeps saying it afterwards.
    val appCtx = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val hints = remember(appCtx) { (appCtx as? io.github.tianli0.switchtether.SwitchTetherApp)?.container?.hintPrefs }
    var tipDismissed by rememberSaveable { mutableStateOf(hints?.getBoolean("readonly_tip_dismissed", false) == true) }
    val listState = rememberLazyListState()
    // Shared by the list (follows the newest message) and the floating "back to latest" pill.
    val follow = rememberFollowState()
    val scope = rememberCoroutineScope()
    val displayTitle = summary?.let { io.github.tianli0.switchtether.ui.common.sessionDisplayTitle(it) } ?: initialTitle ?: stringResource(R.string.session_unnamed)
    Page(
        title = displayTitle,
        subtitle = summary?.let { s ->
            listOfNotNull(
                Formatters.agentLabel(s.agent),
                io.github.tianli0.switchtether.ui.common.sessionProjectName(s)?.takeIf { it != displayTitle },
                s.model?.let(::shortModelName),
            ).joinToString(" · ")
        } ?: "",
        onBack = onBack,
        actions = {
            // Live Update tracking (Fluid Cloud) stays in the title bar; the other functions are
            // capsules in the row over the conversation.
            if (summary != null && (summary.isLive || pinned)) {
                ToolbarIcon(
                    if (pinned) AppIcons.Unpin else AppIcons.Pin,
                    stringResource(if (pinned) R.string.pin_remove else R.string.pin_add),
                    onTogglePin,
                )
            }
        },
        modifier = Modifier.imePadding(),
        // The conversation starts at the top of the screen and scrolls under the title bar, whose own
        // progressive blur is then the only fade up there (a second, page-coloured fade under the
        // capsules used to sit on top of it).
        immersive = true,
        ownBarMaterial = true,
    ) {
        val topInset = io.github.tianli0.coui17.patterns.LocalPageTopInset.current
        // Notices that used to take rows above the list now float over its top, under the title bar.
        val topNotices: @Composable () -> Unit = {
        ConnectionBanner(state.connection, onRetryConnection)
        io.github.tianli0.switchtether.ui.common.OfflineSnapshotNotice(state.snapshotAtMs)
        val d = state.detail.valueOrNull
        if (d != null && state.online && !state.canSend) {
            val readOnly = !d.summary.managed && d.summary.origin != "managed" && d.summary.controlLevel != io.github.tianli0.switchtether.remote.protocol.ControlLevel.FULL
            val tip = if (readOnly) stringResource(R.string.session_readonly_tip) else stringResource(R.string.session_blocked_tip, capStatusLabel(state.cap(Capability.SEND_MESSAGE)))
            // On a short screen (landscape) the tip is left out: the composer already reads
            // "read-only on the phone", and title + tip + capsules took half the height.
            val shortScreen = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp < 480
            TopTips(
                text = tip,
                visible = !tipDismissed && !shortScreen,
                startIcon = { Icon(AppIcons.Info, contentDescription = null, tint = COUITheme.colorScheme.onSurfaceSecondary, modifier = Modifier.size(20.dp)) },
                onClose = {
                    tipDismissed = true
                    hints?.edit()?.putBoolean("readonly_tip_dismissed", true)?.apply()
                },
                title = null,
                ignoreText = null,
                onIgnore = null,
                marqueeEnabled = false,
                content = { Text(tip, style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurfaceSecondary, maxLines = 4) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("readonly-tip"),
            )
        }
        // Folds away once the last request is answered, still showing the count it had.
        val pendingShown = io.github.tianli0.switchtether.ui.common.rememberLast(state.pending.size.takeIf { it > 0 })
        io.github.tianli0.switchtether.ui.common.NoticeVisibility(state.pending.isNotEmpty()) {
            val n = pendingShown ?: 1
            TopTips(
                text = pluralStringResource(R.plurals.pending_banner, n, n),
                startIcon = { Icon(AppIcons.Lock, contentDescription = null, tint = COUITheme.colorScheme.primary, modifier = Modifier.size(20.dp)) },
                actionText = stringResource(R.string.pending_banner_action),
                onAction = {
                    val idx = state.timeline.indexOfFirst { it.key == state.pending.firstOrNull() }
                    if (idx >= 0) scope.launch { listState.animateScrollToItem(idx + 1) }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("pending-banner"),
            )
        }
        }
        // The conversation runs the full height of the page and scrolls under the capsules at its
        // top and the composer at its bottom. Both float over it as frosted glass: the list is
        // recorded as a backdrop layer that they blur (they are its siblings, never inside it).
        val pageColor = pageContainerColor()
        val chatBackdrop = if (isRuntimeShaderSupported()) {
            rememberLayerBackdrop {
                drawRect(pageColor)
                drawContent()
            }
        } else {
            null
        }
        var composerHeightPx by remember { mutableStateOf(0) }
        var topOverlayPx by remember { mutableStateOf(0) }
        // The "/" panel opens over the conversation: it is not part of what the list makes room for.
        var slashPanelPx by remember { mutableStateOf(0) }
        val density = androidx.compose.ui.platform.LocalDensity.current
        val composerHeight = with(density) { (composerHeightPx - slashPanelPx).coerceAtLeast(0).toDp() }
        // Title bar + notices + capsules: where the first message starts at rest.
        val topOverlay = with(density) { topOverlayPx.toDp() }.coerceAtLeast(topInset)
        val dropModifier = if (onIntake != null) {
            Modifier.contentDropTarget(onHover = { pageDropHover = it }, onDrop = { onIntake(it, false) })
        } else {
            Modifier
        }
        Box(Modifier.weight(1f).fillMaxWidth().then(dropModifier)) {
            Box(Modifier.fillMaxSize().then(if (chatBackdrop != null) Modifier.layerBackdrop(chatBackdrop) else Modifier)) {
                when (val ev = state.events) {
                    // Offline and this conversation is not on the phone: say so instead of spinning.
                    Loadable.Loading -> if (state.notInSnapshot && !state.online) {
                        ErrorState(
                            stringResource(R.string.session_not_in_snapshot),
                            Modifier.padding(top = topOverlay).testTag("not-in-snapshot"),
                            stringResource(R.string.session_not_in_snapshot_detail),
                            stringResource(R.string.action_retry),
                            onRetryConnection,
                        )
                    } else {
                        LoadingState(stringResource(R.string.state_loading), Modifier.padding(top = topOverlay))
                    }
                    is Loadable.Failed -> ErrorState(ev.error.title(), Modifier.padding(top = topOverlay), ev.error.detail, stringResource(R.string.action_retry), onReload)
                    is Loadable.Ready -> EventList(state, ctx, listState, follow, onLoadOlder, onToggleSteps, onRetryOutgoing, onDeleteOutgoing, onEditOutgoing, onWithdrawOutgoing, bottomInset = composerHeight, topInset = topOverlay)
                }
            }
            CompositionLocalProvider(LocalGlassBackdrop provides chatBackdrop) {
                // Notices and capsules float over the top of the conversation, below the title bar.
                // One material lies behind the bar and them: full blur and tint behind the title,
                // tapering to nothing just below the capsules, so both stay readable over text.
                // Full material down to the capsules' bottom edge, tapering only in the strip below
                // them: tapering from the title bar down left the capsules on nearly clear glass,
                // with bright message text running through their labels (dark theme above all).
                val taperPx = with(density) { HEADER_TAPER.toPx() }
                val barFraction = ((topOverlayPx - taperPx) / topOverlayPx.coerceAtLeast(1).toFloat())
                    .coerceAtLeast(with(density) { topInset.toPx() } / topOverlayPx.coerceAtLeast(1))
                Column(
                    Modifier.align(Alignment.TopStart).fillMaxWidth().onSizeChanged { topOverlayPx = it.height }
                        .glassHeader(pageColor, barFraction)
                        .padding(top = topInset, bottom = HEADER_TAPER),
                ) {
                    topNotices()
                    StatusChipRow(
                        state,
                        onInfo = { showInfo = true },
                        onOpenSubagents = onOpenSubagentTree?.takeIf { hasAgents },
                        subagentCount = subagentCount,
                        subagentRunning = subagentRunning,
                        agentsDot = agentsDot,
                        backgroundTasks = backgroundTasks,
                        onOpenTools = onOpenTools,
                        onOpenDshWeb = onOpenDshWeb,
                        onOpenTerminal = summary?.terminalId?.let { tid -> { onOpenTerminal(tid) } },
                        onOpenChanges = onOpenChanges,
                        costLabel = usageBreakdown?.let { costChipLabel(it) },
                        onOpenCost = { showCost = true },
                    )
                }
                // "Back to latest" floats with the other controls, outside the recorded list, so its
                // glass has the conversation to blur (inside the list it could only paint a flat fill).
                if (state.events is Loadable.Ready) {
                    Box(Modifier.fillMaxSize()) {
                        NewMessagesPill(listState, follow, remember(state.timeline, state.outgoing, state.typing) { chatRows(state) }, composerHeight)
                    }
                }
                // Under the gesture handle the conversation fades into the page: text scrolling past
                // the composer used to run sharp beneath the handle at the screen's bottom edge.
                val navBottom = androidx.compose.foundation.layout.WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(navBottom + 12.dp)
                        .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(pageColor.copy(alpha = 0f), pageColor.copy(alpha = 0.92f)))),
                )
                // The composer floats too. Nothing is laid behind it: only its own keys and field
                // are glass, the conversation stays sharp around them (the title bar is the page's
                // one progressive blur).
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { composerHeightPx = it.height }) {
                    // One blur for the whole composer (field, keys, capsules, notes) instead of one per control.
                    io.github.tianli0.coui17.patterns.GlassGroup(Modifier.fillMaxWidth()) {
                        Composer(
                            state, busy, onDraft, onSend, onInterrupt, onContinue, onPickImage, onRemoveImage, onRemoveAttachment, onModelClick, onPermissionClick,
                            onViewImage = { ctx.onViewImage?.invoke(it) }, onSlashCommand = onSlashCommand, onSlashPanelHeight = { slashPanelPx = it },
                            onIntake = onIntake, onDropHover = { fieldDropHover = it },
                        )
                    }
                }
            }
            // While something from another app is dragged over the page: what a drop would do here.
            val gate = composerGate(state)
            DropHighlight(
                visible = onIntake != null && (pageDropHover || fieldDropHover),
                label = intakeBlockText(gate.block) ?: stringResource(R.string.drop_here),
                blocked = gate.block != IntakeBlock.NONE,
            )
        }
    }
    SessionInfoSheet(
        show = showInfo,
        detail = state.detail.valueOrNull,
        activity = state.rollingStatus?.data?.takeIf { it.str("state") !in setOf("idle", "completed", null) }?.let { listOfNotNull(statusStateLabel(it.str("state")).ifEmpty { null }, it.str("detail")).joinToString(" · ") },
        activeFallback = subagentRunning + backgroundTasks.second,
        onDismiss = { showInfo = false },
        // The sheet closes first: the confirmation is a dialog of the page, not of the sheet.
        onEndSession = onEndSession?.let { end -> { showInfo = false; end() } },
    )
    SessionCostSheet(
        show = showCost && usageBreakdown != null,
        breakdown = usageBreakdown,
        partial = state.hasMoreBefore,
        project = summary?.let { io.github.tianli0.switchtether.ui.common.sessionProjectName(it) },
        // The sheet closes first, so back from the project page returns to the conversation.
        onOpenProjectUsage = onOpenProjectUsage?.let { open -> { p: String -> showCost = false; open(p) } },
        onDismiss = { showCost = false },
    )
}

/**
 * Capsules floating over the top of the conversation (what it is doing, who waits, how it is
 * attached, limits, branch, terminal, sub-agents, session tools): no background and no row of
 * their own; messages scroll underneath. Only the capsules take touches; tapping the row opens
 * the full info sheet.
 */
@Composable
private fun StatusChipRow(
    state: SessionDetailState,
    onInfo: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenSubagents: (() -> Unit)? = null,
    subagentCount: Int = 0,
    subagentRunning: Int = 0,
    agentsDot: AgentMapState? = null,
    onOpenTools: (() -> Unit)? = null,
    onOpenDshWeb: (() -> Unit)? = null,
    onOpenTerminal: (() -> Unit)? = null,
    /** Background shells of the session: (all, still running). */
    backgroundTasks: Pair<Int, Int> = 0 to 0,
    onOpenChanges: (() -> Unit)? = null,
    /** Cost capsule label (money, or tokens without a cost); null = no usage reported yet. */
    costLabel: String? = null,
    onOpenCost: () -> Unit = {},
) {
    val dev = LocalDevColors.current
    val s = state.detail.valueOrNull?.summary ?: return
    val terminalLabel = stringResource(R.string.open_terminal)
    val toolsLabel = stringResource(R.string.tools_open)
    val bg = io.github.tianli0.coui17.patterns.pageContainerColor().compositeOver(COUITheme.colorScheme.background)
    // No fade of their own: the header's material behind them (glassHeader) is the page's only one.
    Column(
        modifier.padding(horizontal = 8.dp, vertical = 2.dp).clip(SquircleShape(18.dp))
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.session_info), onClick = onInfo).padding(vertical = 4.dp)
            .testTag("session-info-row"),
    ) {
        val chipScroll = rememberScrollState()
        Row(Modifier.edgeFade(chipScroll).horizontalScroll(chipScroll).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            // A finished turn with sub-agents / background tasks still running is not idle: it says
            // so (the desktop's `activeTasks`, else what this page's log shows running).
            val activeFallback = subagentRunning + backgroundTasks.second
            if (s.isLive) InfoChip(sessionStatusLabel(s, activeFallback), dot = sessionStatusColor(s.displayStatus(activeFallback)), backdrop = bg)
            else InfoChip(stringResource(R.string.badge_ended), dot = dev.neutral, backdrop = bg)
            s.waitingFor?.let { InfoChip(waitingLabel(it), dot = dev.warn, backdrop = bg) }
            if (s.isAttached) InfoChip(io.github.tianli0.switchtether.ui.common.attachedLabel(s), dot = COUITheme.colorScheme.primary, backdrop = bg)
            // Full control is the normal case and needs no capsule; limits do.
            // How far the phone may act is not a state: an icon, not a second coloured dot beside "busy".
            if (s.controlLevel != io.github.tianli0.switchtether.remote.protocol.ControlLevel.FULL) {
                InfoChip(
                    controlLabel(s.controlLevel),
                    icon = if (s.controlLevel == io.github.tianli0.switchtether.remote.protocol.ControlLevel.OBSERVE) AppIcons.Watch else AppIcons.Clock,
                    color = COUITheme.colorScheme.onSurfaceSecondary,
                    backdrop = bg,
                )
            }
            s.gitBranch?.let { InfoChip(it, icon = AppIcons.Git, color = COUITheme.colorScheme.onSurfaceSecondary, backdrop = bg) }
            if (onOpenChanges != null) {
                val changesLabel = stringResource(R.string.git_turn_chip)
                InfoChip(changesLabel, icon = AppIcons.Edit, onClick = onOpenChanges, modifier = Modifier.testTag("chip-changes"), backdrop = bg)
            }
            if (onOpenTerminal != null) {
                InfoChip(terminalLabel, icon = AppIcons.Terminal, onClick = onOpenTerminal, backdrop = bg)
            }
            if (onOpenSubagents != null && subagentCount > 0) {
                // The ones running now; once none runs, how many there were.
                val label = if (subagentRunning > 0) pluralStringResource(R.plurals.subagent_chip_running, subagentRunning, subagentRunning)
                else pluralStringResource(R.plurals.subagent_chip, subagentCount, subagentCount)
                // Like Claude Code's agents pill: a dot while an agent waits, works or failed.
                val dot = when (agentsDot) {
                    AgentMapState.Waiting -> dev.warn
                    AgentMapState.Working -> dev.info
                    AgentMapState.Failed -> dev.danger
                    else -> null
                }
                val described = agentsDot?.takeIf { dot != null }?.let { "$label · ${agentStateLabel(it)}" } ?: label
                // The tree glyph breathes while agents work, turns the warning colour (still) while
                // one waits for the user, and rests once they are done.
                val glyph: @Composable () -> Unit = {
                    if (agentsDot == AgentMapState.Waiting) {
                        io.github.tianli0.switchtether.ui.common.WaitingGlyph(tint = dev.warn, size = 16.dp)
                    } else {
                        io.github.tianli0.switchtether.ui.common.SubagentGlyph(
                            tint = dot ?: COUITheme.colorScheme.primary,
                            running = agentsDot == AgentMapState.Working || (agentsDot == null && subagentRunning > 0),
                            size = 16.dp,
                        )
                    }
                }
                if (dot != null) {
                    InfoChip(label, leading = glyph, onClick = onOpenSubagents, modifier = Modifier.testTag("subagent-tree").clearAndSetSemantics { contentDescription = described; role = Role.Button; onClick { onOpenSubagents(); true } }, backdrop = bg)
                } else {
                    InfoChip(label, leading = glyph, onClick = onOpenSubagents, modifier = Modifier.testTag("subagent-tree"), backdrop = bg)
                }
            }
            // Commands left running in the background (`run_in_background`): their own capsule, so a
            // long build or dev server is visible without opening the agent map. Opens the map,
            // whose "background tasks" section lists them with their output.
            val (bgAll, bgRunning) = backgroundTasks
            // Shown while some run; with no sub-agents it also stays as the way into the agent map.
            if (onOpenSubagents != null && (bgRunning > 0 || (bgAll > 0 && subagentCount == 0))) {
                val label = if (bgRunning > 0) pluralStringResource(R.plurals.background_task_chip_running, bgRunning, bgRunning)
                else pluralStringResource(R.plurals.background_task_chip, bgAll, bgAll)
                // The hourglass turns over while a task runs.
                InfoChip(
                    label,
                    leading = { io.github.tianli0.switchtether.ui.common.BackgroundTaskGlyph(tint = if (bgRunning > 0) dev.info else COUITheme.colorScheme.primary, running = bgRunning > 0, size = 16.dp) },
                    onClick = onOpenSubagents,
                    modifier = Modifier.testTag("chip-background"),
                    backdrop = bg,
                )
            }
            costLabel?.let { label ->
                val described = stringResource(R.string.cost_chip_a11y, label)
                InfoChip(label, icon = AppIcons.Usage, onClick = onOpenCost, modifier = Modifier.testTag("chip-cost").clearAndSetSemantics { contentDescription = described; role = Role.Button; onClick { onOpenCost(); true } }, backdrop = bg)
            }
            // Extra functions sit in the same row as the sub-agents.
            if (onOpenTools != null) {
                InfoChip(toolsLabel, icon = AppIcons.SessionTools, onClick = onOpenTools, modifier = Modifier.testTag("chip-tools"), backdrop = bg)
            }
            if (onOpenDshWeb != null) {
                val webLabel = stringResource(R.string.dsh_web_chip)
                InfoChip(webLabel, icon = AppIcons.Web, onClick = onOpenDshWeb, modifier = Modifier.testTag("chip-dsh-web"), backdrop = bg)
            }
        }
    }
}

/**
 * The agents pill's state from the session log: waiting (an agent works and the session waits
 * for an answer), working, failed (the latest finished agent failed), else none.
 */
private fun agentsPillState(state: SessionDetailState): AgentMapState? {
    val kinds = io.github.tianli0.switchtether.remote.protocol.EventKinds
    val rows = state.log.events.filter { it.kind == kinds.SUBAGENT }
    // Background shells have a capsule of their own (backgroundTaskCounts).
    val working = rows.any { it.data.str("status") == "running" }
    return when {
        working && state.detail.valueOrNull?.summary?.waitingFor != null -> AgentMapState.Waiting
        working -> AgentMapState.Working
        rows.lastOrNull()?.data?.str("status") == "failed" -> AgentMapState.Failed
        else -> null
    }
}


/** Background shells in the loaded log: (all, still running). */
private fun backgroundTaskCounts(state: SessionDetailState): Pair<Int, Int> {
    val shells = state.log.events.filter {
        it.kind == io.github.tianli0.switchtether.remote.protocol.EventKinds.TOOL_CALL && it.data.bool("background") == true
    }
    return shells.size to shells.count { it.data.str("status") == "running" }
}

@Composable
private fun EventList(
    state: SessionDetailState,
    ctx: RenderContext,
    listState: androidx.compose.foundation.lazy.LazyListState,
    follow: FollowState,
    onLoadOlder: () -> Unit,
    onToggleSteps: (String) -> Unit,
    onRetryOutgoing: (String) -> Unit,
    onDeleteOutgoing: (String) -> Unit,
    onEditOutgoing: (String) -> Unit,
    onWithdrawOutgoing: ((String) -> Unit)? = null,
    /** Height of the composer floating over the list's bottom edge. */
    bottomInset: androidx.compose.ui.unit.Dp = 0.dp,
    /** Height of the title bar, notices and capsules floating over the list's top. */
    topInset: androidx.compose.ui.unit.Dp = 0.dp,
) {
    // Remembered: the state also changes on every keystroke in the composer (draft), and the rows only
    // depend on these three fields; rebuilding them copied the whole timeline each time.
    val rows = remember(state.timeline, state.outgoing, state.typing) { chatRows(state) }
    // Opening a session lands at the bottom; only then may scrolling to the top load older pages.
    // Saveable: coming back from a sub-agent page keeps the reading position instead of jumping down.
    var initialScrollDone by androidx.compose.runtime.saveable.rememberSaveable(state.sessionId) { mutableStateOf(false) }
    LaunchedEffect(rows.isNotEmpty()) {
        if (rows.isNotEmpty() && !initialScrollDone) {
            listState.scrollToEnd()
            initialScrollDone = true
        }
    }
    FollowBottomEffects(listState, follow, rows, state.sendTick)
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { if (it <= 1 && initialScrollDone) onLoadOlder() }
    }
    if (rows.isEmpty()) {
        Text(stringResource(R.string.session_no_events), style = COUITheme.textStyles.body2, color = COUITheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp + topInset, bottom = 24.dp))
        return
    }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        // A readable measure in landscape / on tablets: the conversation keeps at most ~720dp of
        // width, centred (lines ran ~1200 px wide in landscape). The list itself stays full width,
        // so it still scrolls from anywhere on the screen.
        val side = ((maxWidth - MAX_READING_WIDTH) / 2).coerceAtLeast(0.dp)
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag("event-list"), contentPadding = PaddingValues(start = side, end = side, top = topInset + 4.dp, bottom = bottomInset + 8.dp)) {
            item(key = "older") {
                Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                    when {
                        state.loadingOlder -> RotatingProgressIndicator(size = 18.dp, ringDiameter = 14.4.dp, strokeWidth = 2.dp)
                        state.hasMoreBefore -> LinkButton(stringResource(R.string.session_load_older), onLoadOlder)
                        else -> Text(stringResource(R.string.session_start), style = COUITheme.textStyles.footnote2, color = COUITheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
            }
            items(rows, key = { it.key }, contentType = {
                when (it) {
                    is TimelineRow.Event -> it.item.event.kind
                    is TimelineRow.Steps -> "steps"
                    is TimelineRow.Outgoing -> "outgoing"
                    TimelineRow.Typing -> "typing"
                }
            }) { row ->
                // Rows fade in and out and slide to their new place: a step group opening or
                // closing, a new message, the "replying…" row giving way to the reply.
                Box(Modifier.animateItem()) {
                    when (row) {
                        is TimelineRow.Event -> TimelineEventRow(row.item, row.segment, ctx)
                        is TimelineRow.Steps -> StepsHeader(row, onToggleSteps)
                        is TimelineRow.Outgoing -> OutgoingBubble(row.message, onRetryOutgoing, onDeleteOutgoing, onEditOutgoing, onWithdraw = onWithdrawOutgoing?.takeIf { state.online })
                        TimelineRow.Typing -> TypingRow(ctx)
                    }
                }
            }
        }
    }
}

@Composable
private fun TimelineEventRow(item: RenderItem, segment: SegmentPosition, ctx: RenderContext) {
    val outer = when (segment) {
        SegmentPosition.None -> PaddingValues(horizontal = 16.dp, vertical = 6.dp)
        // Step cards share the 16dp margin of the approval / diff cards and the bubbles around them.
        SegmentPosition.Single -> PaddingValues(horizontal = 16.dp, vertical = 4.dp)
        SegmentPosition.First, SegmentPosition.Middle -> PaddingValues(horizontal = 16.dp)
        SegmentPosition.Last -> PaddingValues(start = 16.dp, end = 16.dp, bottom = 6.dp)
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
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = if (row.expanded) 0.dp else 4.dp)
            .segment(if (row.expanded) SegmentPosition.First else SegmentPosition.Single)
            .testTag("steps-${row.groupId}"),
    ) {
        CollapsibleRow(
            icon = AppIcons.Steps,
            iconTint = COUITheme.colorScheme.primary,
            title = pluralStringResource(R.plurals.session_steps, row.steps, row.steps),
            subtitle = row.latestTitle,
            subtitleMaxLines = 2,
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

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun Composer(
    state: SessionDetailState,
    busy: String?,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    onInterrupt: () -> Unit,
    onContinue: () -> Unit,
    onPickImage: () -> Unit = {},
    onRemoveImage: (Int) -> Unit = {},
    onRemoveAttachment: (String) -> Unit = {},
    onModelClick: () -> Unit = {},
    onPermissionClick: (() -> Unit)? = null,
    onViewImage: (io.github.tianli0.switchtether.ui.sessions.render.ImagePreview) -> Unit = {},
    onSlashCommand: ((SlashEntry) -> Unit)? = null,
    onSlashPanelHeight: (Int) -> Unit = {},
    onIntake: ((DropPayload, Boolean) -> Unit)? = null,
    onDropHover: (Boolean) -> Unit = {},
) {
    val s = state.detail.valueOrNull?.summary
    Column(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 8.dp),
    ) {
        // Full control (managed, or the user's own process attached via the wrapper) keeps the normal
        // composer; only observe/history sessions get the read-only bar.
        val blocked = state.online && !state.canSend && state.detail is Loadable.Ready &&
            s?.controlLevel != io.github.tianli0.switchtether.remote.protocol.ControlLevel.FULL
        if (blocked) {
            // Read-only: say so plainly instead of showing a dead input field.
            Row(
                Modifier.fillMaxWidth().heightIn(min = 44.dp).glassSurface(RoundedCornerShape(22.dp)).padding(start = 16.dp, end = 8.dp),
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
                    // COUI tinted text button (theme-coloured label on a light theme wash): an action
                    // inside the bar, not a second primary button competing with the page.
                    SmallButton(
                        text = stringResource(R.string.continue_action),
                        onClick = onContinue,
                        tone = ActionTone.Primary,
                        modifier = Modifier.padding(end = 6.dp).testTag("continue"),
                    )
                }
            }
            return@Column
        }
        if (!state.online) {
            ComposerNote(
                stringResource(
                    when {
                        state.canQueue -> R.string.composer_queue_hint
                        state.snapshotAtMs != null -> R.string.composer_offline_snapshot
                        else -> R.string.composer_offline
                    },
                ),
                LocalDevColors.current.warn,
            )
        }
        if (state.attachedImages.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 4.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.attachedImages.forEachIndexed { idx, img ->
                    val bytes = remember(img.dataBase64) {
                        runCatching { android.util.Base64.decode(img.dataBase64, android.util.Base64.DEFAULT) }.getOrNull()
                    }
                    val thumb = io.github.tianli0.switchtether.ui.common.rememberThumbnail(bytes, 56.dp).value
                    Box(Modifier.size(56.dp)) {
                        if (thumb is io.github.tianli0.switchtether.ui.common.Thumb.Ready) {
                            androidx.compose.foundation.Image(
                                bitmap = thumb.bitmap,
                                contentDescription = img.name ?: stringResource(R.string.render_image_preview),
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                modifier = Modifier.fillMaxSize().clip(SquircleShape(8.dp)),
                            )
                        }
                        // A 20dp mark in a 36dp touch area (the thumbnail's top-end corner).
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .size(36.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .clickable(role = androidx.compose.ui.semantics.Role.Button) { onRemoveImage(idx) },
                            contentAlignment = Alignment.TopEnd,
                        ) {
                            Box(
                                Modifier.size(20.dp).clip(androidx.compose.foundation.shape.CircleShape)
                                    .background(COUITheme.colorScheme.surface.copy(alpha = 0.85f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(AppIcons.Delete, contentDescription = stringResource(R.string.attach_remove), tint = COUITheme.colorScheme.onSurface, modifier = Modifier.size(12.dp))
                            }
                        }
                    }
                }
            }
        }
        if (state.draftAttachments.isNotEmpty()) {
            // Photos as thumbnails (tap to look at the whole picture), other files as chips.
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 4.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                state.draftAttachments.forEach { a ->
                    if (a.isImage && a.localUri != null) {
                        DraftImageTile(a, onRemove = { onRemoveAttachment(a.localId) }, onPreview = onViewImage)
                    } else {
                        DraftAttachmentChip(a, onRemove = { onRemoveAttachment(a.localId) })
                    }
                }
            }
        }
        val showPermission = state.hasPermissionModes && onPermissionClick != null
        val showModel = (state.canChangeModel || showPermission) && s != null
        val showInterrupt = state.canInterrupt && s != null && s.isLive && s.status != "idle"
        if (showModel || showInterrupt) {
            // Above the field: the run settings on the left, "stop" on the right while a turn runs.
            // Stop is a labelled capsule here, not a fourth key squeezed into the input row.
            // SpaceBetween, not a weighted spacer: the spacer took half the row, so the settings capsule
            // was cut at half the width with room to spare.
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                if (showModel && s != null) {
                    // Model and effort of the running session, one tap away (like the IDE's model picker).
                    io.github.tianli0.switchtether.ui.common.GlassCapsule(
                        onClick = onModelClick,
                        modifier = Modifier.weight(1f, fill = false).testTag("model-chip"),
                    ) {
                        Icon(AppIcons.Settings, contentDescription = null, tint = COUITheme.colorScheme.primary, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.size(5.dp))
                        val mode = state.detail.valueOrNull?.permissionMode?.takeIf { it != "default" }
                        Text(
                            listOfNotNull(modelEffortSummary(s.model, s.effort).ifBlank { null }, mode?.let { permissionModeLabel(it, s.agent) })
                                .joinToString(" · "),
                            style = COUITheme.textStyles.footnote1,
                            fontWeight = FontWeight.Medium,
                            color = COUITheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.size(8.dp))
                if (showInterrupt) {
                    val stopLabel = stringResource(R.string.session_interrupt)
                    Spacer(Modifier.size(8.dp))
                    // Glass like the settings capsule beside it (it floats over the conversation).
                    io.github.tianli0.switchtether.ui.common.GlassCapsule(
                        onClick = onInterrupt,
                        modifier = Modifier.semantics { contentDescription = stopLabel }.testTag("interrupt"),
                    ) {
                        Icon(AppIcons.Interrupt, contentDescription = null, tint = LocalDevColors.current.danger, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.size(5.dp))
                        Text(stopLabel, style = COUITheme.textStyles.footnote1, fontWeight = FontWeight.Medium, color = COUITheme.colorScheme.onSurface, maxLines = 1)
                    }
                }
            }
        } else if (state.resumesOnSend) {
            ComposerNote(stringResource(R.string.composer_resume_hint), COUITheme.colorScheme.onSurfaceSecondary)
        }
        // Slash commands matching the "/…" being typed, directly above the input row.
        if (onSlashCommand != null) {
            SlashSuggestionPanel(state.slashSuggestions, onSlashCommand, Modifier.onSizeChanged { onSlashPanelHeight(it.height) })
        }
        // The input row, as a ColorOS toolbar: attach key, field, send key.
        Row(verticalAlignment = Alignment.Bottom) {
            // Files are uploaded right away, so attaching needs the connection (sending can still queue).
            val supportsAttachments = (state.canSend || state.canQueue) && s?.terminalId == null
            if (supportsAttachments) {
                val canAttach = state.online && state.draftAttachments.size < MAX_ATTACHMENTS
                io.github.tianli0.coui17.patterns.ComposerKey(
                    label = stringResource(R.string.composer_attach),
                    onClick = onPickImage,
                    enabled = canAttach,
                    modifier = Modifier.testTag("attach"),
                ) {
                    Icon(AppIcons.Add, contentDescription = null, tint = COUITheme.colorScheme.onSurface.copy(alpha = if (canAttach) 1f else 0.4f), modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.size(io.github.tianli0.coui17.patterns.ComposerKeyGap))
            }
            val sendEnabled = (state.canSend || state.canQueue) &&
                (state.draft.isNotBlank() || state.attachedImages.isNotEmpty() || state.draftAttachments.isNotEmpty()) &&
                state.draftAttachments.all { it.ready }
            val receiver = remember(onIntake) {
                onIntake?.let { intake -> composerContentListener(onHover = onDropHover, onFiles = { intake(it, true) }) }
            }
            // InputPill with a caret that follows a draft set from outside (a picked slash command).
            DraftInputPill(
                value = state.draft,
                onValueChange = onDraft,
                placeholder = when {
                    state.canSend || state.canQueue -> stringResource(R.string.composer_hint)
                    state.online -> stringResource(R.string.composer_send_unavailable)
                    else -> stringResource(R.string.composer_draft_hint)
                },
                sendLabel = stringResource(R.string.session_send),
                sendIcon = AppIcons.Send,
                onSend = onSend,
                // Sending never blocks the field: the bubble shows the message's own progress.
                state = InputPillState(
                    enabled = state.canSend || state.canQueue,
                    sendEnabled = sendEnabled,
                    busy = false,
                ),
                modifier = Modifier.weight(1f),
                // Hardware keyboard: Ctrl+Enter sends; Enter and Shift+Enter stay a new line (§35).
                fieldModifier = Modifier.testTag("composer").onPreviewKeyEvent { ev ->
                    if (ev.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    if (io.github.tianli0.switchtether.ui.keys.KeyShortcuts.composer(ev.chord()) != io.github.tianli0.switchtether.ui.keys.ShortcutAction.SEND) return@onPreviewKeyEvent false
                    if (sendEnabled) onSend()
                    true
                },
                sendModifier = Modifier.testTag("send"),
                receiveContent = receiver,
            )
        }
    }
}

/** A line the composer says about itself (offline, resumes on send): a small glass capsule over the conversation. */
@Composable
private fun ComposerNote(text: String, color: Color) {
    Box(Modifier.padding(bottom = 8.dp).glassSurface(RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(text, style = COUITheme.textStyles.footnote2, color = color)
    }
}

/** Photos or any file: both go to the computer and reach the agent as file paths. */
@Composable
private fun AttachSheet(show: Boolean, onImages: () -> Unit, onFiles: () -> Unit, onDismiss: () -> Unit) {
    PanelSheet(show = show, title = stringResource(R.string.composer_attach), onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            GroupCard {
                BasicComponent(
                    title = stringResource(R.string.attach_photos),
                    summary = stringResource(R.string.attach_photos_hint),
                    startAction = { Icon(AppIcons.Image, contentDescription = null, tint = COUITheme.colorScheme.primary, modifier = Modifier.padding(end = 12.dp).size(22.dp)) },
                    onClick = onImages,
                    cardListPosition = io.github.suqi8.coui.kmp.basic.CardListPosition.Head,
                )
                BasicComponent(
                    title = stringResource(R.string.attach_files),
                    summary = stringResource(R.string.attach_files_hint),
                    startAction = { Icon(AppIcons.File, contentDescription = null, tint = COUITheme.colorScheme.primary, modifier = Modifier.padding(end = 12.dp).size(22.dp)) },
                    onClick = onFiles,
                    cardListPosition = io.github.suqi8.coui.kmp.basic.CardListPosition.Tail,
                )
            }
            Text(
                stringResource(R.string.attach_where),
                style = COUITheme.textStyles.footnote2,
                color = COUITheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = PageDefaults.TextGutter),
            )
        }
    }
}

/**
 * A picked photo in the composer: its thumbnail (decoded small from the phone's copy), the upload
 * progress or failure on top, a remove button, and a tap that shows the whole picture.
 */
@Composable
private fun DraftImageTile(a: DraftAttachment, onRemove: () -> Unit, onPreview: (io.github.tianli0.switchtether.ui.sessions.render.ImagePreview) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val dev = LocalDevColors.current
    val uri = a.localUri ?: return
    val thumb by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, uri) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { decodeThumbnail(context, android.net.Uri.parse(uri), 256) }
    }
    val scope = rememberCoroutineScope()
    Box(
        Modifier.size(64.dp).clip(SquircleShape(12.dp)).background(COUITheme.colorScheme.surfaceContainer)
            .clickable(role = androidx.compose.ui.semantics.Role.Image, onClickLabel = stringResource(R.string.render_image_preview)) {
                scope.launch {
                    val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching {
                            context.contentResolver.openInputStream(android.net.Uri.parse(uri))?.use { it.readBytes() }
                        }.getOrNull()
                    }
                    if (bytes != null) onPreview(io.github.tianli0.switchtether.ui.sessions.render.ImagePreview(alt = a.name, bytes = bytes))
                }
            }
            .testTag("draft-image"),
        contentAlignment = Alignment.Center,
    ) {
        thumb?.let {
            androidx.compose.foundation.Image(
                bitmap = it,
                contentDescription = a.name,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } ?: Icon(AppIcons.Image, contentDescription = null, tint = COUITheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.size(22.dp))
        when {
            a.failure != null -> Box(Modifier.fillMaxSize().background(dev.danger.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                Icon(AppIcons.Info, contentDescription = stringResource(R.string.attach_failed), tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(20.dp))
            }
            a.uploading -> Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
                RotatingProgressIndicator(color = androidx.compose.ui.graphics.Color.White, size = 20.dp, ringDiameter = 16.dp, strokeWidth = 2.dp)
            }
        }
        // A 20dp mark in a 36dp touch area (the thumbnail's top-end corner).
        Box(
            Modifier.align(Alignment.TopEnd).size(36.dp).clip(androidx.compose.foundation.shape.CircleShape)
                .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onRemove),
            contentAlignment = Alignment.TopEnd,
        ) {
            Box(
                Modifier.padding(3.dp).size(20.dp).clip(androidx.compose.foundation.shape.CircleShape)
                    .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(AppIcons.Close, contentDescription = stringResource(R.string.attach_remove), tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(11.dp))
            }
        }
    }
}

/** A small bitmap of a picked photo (at most [maxPx] on its long side), or null. */
private fun decodeThumbnail(context: android.content.Context, uri: android.net.Uri, maxPx: Int): androidx.compose.ui.graphics.ImageBitmap? = runCatching {
    val r = context.contentResolver
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    r.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
    val sample = io.github.tianli0.switchtether.ui.common.Thumbnails.sampleSize(bounds.outWidth, bounds.outHeight, maxPx)
    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
    r.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }?.asImageBitmap()
}.getOrNull()

/** A picked file in the composer: name, upload progress or what went wrong, and remove. */
@Composable
private fun DraftAttachmentChip(a: DraftAttachment, onRemove: () -> Unit) {
    val dev = LocalDevColors.current
    val status = when (a.failure) {
        AttachmentFailure.TOO_LARGE -> stringResource(R.string.attach_too_large)
        AttachmentFailure.EMPTY -> stringResource(R.string.attach_empty)
        AttachmentFailure.OLD_DESKTOP -> stringResource(R.string.attach_old_desktop)
        AttachmentFailure.FAILED -> stringResource(R.string.attach_failed)
        null -> if (a.ready) Formatters.bytes(a.size) else "${(a.sent * 100 / a.size.coerceAtLeast(1)).toInt()}%"
    }
    Row(
        Modifier.heightIn(min = 40.dp).widthIn(max = 240.dp).clip(SquircleShape(12.dp))
            .background(if (a.failure != null) dev.danger.copy(alpha = 0.10f) else COUITheme.colorScheme.surfaceContainer)
            .padding(start = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (a.uploading) {
            RotatingProgressIndicator(color = COUITheme.colorScheme.primary, size = 16.dp, ringDiameter = 12.8.dp, strokeWidth = 2.dp)
        } else {
            Icon(if (a.isImage) AppIcons.Image else AppIcons.File, contentDescription = null, tint = if (a.failure != null) dev.danger else COUITheme.colorScheme.primary, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.size(6.dp))
        Column(Modifier.weight(1f, fill = false).padding(vertical = 4.dp)) {
            Text(a.name, style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(status, style = COUITheme.textStyles.footnote2, color = if (a.failure != null) dev.danger else COUITheme.colorScheme.onSurfaceVariantSummary, maxLines = 1)
        }
        Box(
            Modifier.size(36.dp).clip(androidx.compose.foundation.shape.CircleShape).clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(AppIcons.Close, contentDescription = stringResource(R.string.attach_remove), tint = COUITheme.colorScheme.onSurfaceSecondary, modifier = Modifier.size(14.dp))
        }
    }
}

/** Display name, size and type of a picked document or photo. */
private fun pickedFileInfo(context: android.content.Context, uri: android.net.Uri): Triple<String, Long, String?> {
    val resolver = context.contentResolver
    var name: String? = null
    var size = -1L
    runCatching {
        resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0)
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
    }
    val mime = resolver.getType(uri)
    val fallback = "file" + (mime?.let { android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }?.let { ".$it" } ?: "")
    return Triple(name?.takeIf { it.isNotBlank() } ?: (uri.lastPathSegment ?: fallback), size, mime)
}

/** Bottom sheet with everything about the session that does not fit the chip row. */
@Composable
private fun SessionInfoSheet(show: Boolean, detail: SessionDetail?, activity: String?, onDismiss: () -> Unit, onEndSession: (() -> Unit)? = null, activeFallback: Int? = null) {
    PanelSheet(show = show, title = stringResource(R.string.session_info), onDismissRequest = onDismiss) {
        val d = detail ?: return@PanelSheet
        val s = d.summary
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            val rows = listOfNotNull(
                stringResource(R.string.session_info_agent) to Formatters.agentLabel(s.agent),
                s.model?.let { stringResource(R.string.session_info_model) to it },
                stringResource(R.string.session_info_status) to (if (s.isLive) sessionStatusLabel(s, activeFallback) else stringResource(R.string.badge_ended)),
                activity?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.session_info_activity) to it },
                stringResource(R.string.session_info_control) to controlLabel(s.controlLevel),
                stringResource(R.string.session_info_origin) to (if (s.isAttached) io.github.tianli0.switchtether.ui.common.attachedLabel(s) else originLabel(s.origin, s.managed)),
                s.project?.let { stringResource(R.string.session_info_project) to it },
                s.gitBranch?.let { stringResource(R.string.session_info_branch) to it },
                s.cwd?.let { stringResource(R.string.session_info_cwd) to it },
                stringResource(R.string.session_info_last_active) to relativeTime(s.lastActiveAtMs),
            )
            // "claude:<uuid>" is only the agent's own id with a prefix: show that id once, copyable.
            val sessionId = s.nativeId.takeIf { it.isNotBlank() && (s.id == it || s.id.endsWith(":$it")) } ?: s.id
            val copyLabel = stringResource(R.string.action_copy)
            GroupCard {
                rows.forEachIndexed { i, (k, v) -> BasicComponent(title = k, summary = v, cardListPosition = cardListPositionOf(i, rows.size + 1)) }
                BasicComponent(
                    title = stringResource(R.string.session_info_id),
                    summary = sessionId,
                    endActions = { io.github.tianli0.switchtether.ui.code.CopyButton(sessionId, copyLabel) },
                    cardListPosition = cardListPositionOf(rows.size, rows.size + 1),
                )
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
                    // A session only watched from the phone cannot be driven by design: its "不可用"
                    // is the expected state, not an error, so it stays grey instead of alarm red.
                    val color = if (st == CapStatus.BLOCKED && s.controlLevel == io.github.tianli0.switchtether.remote.protocol.ControlLevel.OBSERVE) LocalDevColors.current.neutral else capColor(st)
                    BasicComponent(
                        title = stringResource(label),
                        endActions = {
                            StatusDot(color)
                            Spacer(Modifier.size(6.dp))
                            Text(capStatusLabel(st), style = COUITheme.textStyles.body2, color = color)
                        },
                        cardListPosition = cardListPositionOf(i, caps.size),
                    )
                }
            }
            // The computer explains in English; for a watched session the phone says it itself,
            // naming its own button ("恢复会话" rather than the desktop's "Continue on phone").
            val observedReason = if (s.controlLevel == io.github.tianli0.switchtether.remote.protocol.ControlLevel.OBSERVE && d.capabilities.continueSession != null) {
                when (s.agent) {
                    "claude_code" -> stringResource(R.string.cap_reason_observed_claude, stringResource(R.string.continue_action))
                    "codex" -> stringResource(R.string.cap_reason_observed_codex, stringResource(R.string.continue_action))
                    "dsh" -> stringResource(R.string.cap_reason_observed_dsh, stringResource(R.string.continue_action))
                    else -> null
                }
            } else if (s.agent == "agy") {
                stringResource(R.string.cap_reason_history_agy)
            } else {
                null
            }
            (observedReason ?: d.capabilities.reason)?.let {
                Text(it, style = COUITheme.textStyles.footnote2, color = COUITheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(horizontal = PageDefaults.TextGutter, vertical = 8.dp))
            }
            // Only for a live session SwitchTether started: its process is ours to end.
            if (onEndSession != null) {
                val endLabel = stringResource(R.string.session_end)
                Spacer(Modifier.size(8.dp))
                GroupCard {
                    BasicComponent(
                        title = endLabel,
                        summary = stringResource(R.string.session_end_summary),
                        endActions = { SmallButton(endLabel, onEndSession, tone = ActionTone.Danger, modifier = Modifier.testTag("info-end-session")) },
                        cardListPosition = io.github.suqi8.coui.kmp.basic.CardListPosition.Full,
                    )
                }
            }
        }
    }
}

/** Confirm sheet for "continue on phone" (fork into a managed session with the same context). */
@Composable
private fun ContinueSheet(show: Boolean, agentLabel: String, originalBusy: Boolean, canFork: Boolean, enabled: Boolean, busy: Boolean, onConfirm: (fork: Boolean) -> Unit, onDismiss: () -> Unit) {
    PanelSheet(show = show, title = stringResource(R.string.continue_title), onDismissRequest = onDismiss) {
        // Scrolls so the continue / fork buttons stay reachable in landscape or with large text.
        Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).verticalScroll(rememberScrollState())) {
            Column(Modifier.fillMaxWidth().padding(horizontal = PageDefaults.TextGutter).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(
                    stringResource(R.string.continue_point_context, agentLabel),
                    stringResource(R.string.continue_point_window),
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
            }
            // Resuming the same conversation is the normal path (filled); a copy is the exception.
            val usable = enabled && !originalBusy && !busy
            SheetButtons(
                primary = DialogButtonBarAction(
                    text = stringResource(if (busy) R.string.state_loading else R.string.continue_confirm),
                    enabled = usable,
                    onClick = { onConfirm(false) },
                ),
                secondary = if (canFork) DialogButtonBarAction(text = stringResource(R.string.continue_fork), enabled = usable, onClick = { onConfirm(true) }) else null,
                modifier = Modifier.testTag("continue-actions"),
            )
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
    val decision = pending?.second.orEmpty()
    val danger = decision == "deny" || decision == "abort"
    ConfirmDialog(
        show = pending != null,
        title = stringResource(R.string.approval_confirm_title),
        message = stringResource(R.string.approval_confirm_body, pending?.third.orEmpty()),
        confirmText = pending?.third.orEmpty(),
        cancelText = stringResource(R.string.action_cancel),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    ) {
        val d = request?.data
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            d?.str("title")?.let { Text(it, style = COUITheme.textStyles.body1, color = if (danger) dev.danger else COUITheme.colorScheme.onSurface) }
            d?.str("toolName")?.let { Text(stringResource(R.string.render_tool, it), style = COUITheme.textStyles.footnote2, color = COUITheme.colorScheme.onSurfaceVariantSummary) }
            d?.str("command")?.let { io.github.tianli0.switchtether.ui.code.CodeBlockView(it, "bash", maxLines = 8) }
        }
    }
}

@Composable
private fun ImageViewerDialog(
    preview: io.github.tianli0.switchtether.ui.sessions.render.ImagePreview,
    onLoadBytes: suspend (String) -> ByteArray?,
    onDismiss: () -> Unit,
) {
    // The lightbox (ui/files/ImageLightbox.kt): zoom, swipe through the message's pictures, pull down to close.
    val all = preview.gallery.ifEmpty { listOf(preview) }
    val images = remember(preview) {
        all.map { p ->
            io.github.tianli0.switchtether.ui.files.LightboxImage(
                name = p.path?.let(io.github.tianli0.switchtether.files.FileTypes::fileName)?.takeIf { it.isNotBlank() }
                    ?: p.alt?.takeIf { it.isNotBlank() && !it.contains('/') && !it.contains('\\') && it.contains('.') }
                    ?: io.github.tianli0.switchtether.ui.files.imageNameFor(p.bytes),
                bytes = p.bytes,
                path = p.path,
            )
        }
    }
    io.github.tianli0.switchtether.ui.files.ImageLightbox(images, startIndex = if (preview.gallery.isEmpty()) 0 else preview.index, onDismiss = onDismiss, loadBytes = onLoadBytes)
}

/**
 * Fades the capsules out over the last 24dp on a side that can still scroll, so a row cut by the
 * screen edge reads as "more this way" instead of a capsule sliced in half. Only the capsules'
 * own alpha changes; no material is added behind them.
 */
private fun Modifier.edgeFade(scroll: androidx.compose.foundation.ScrollState): Modifier =
    graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val w = 24.dp.toPx().coerceAtMost(size.width / 2)
            if (scroll.value > 0) {
                drawRect(
                    androidx.compose.ui.graphics.Brush.horizontalGradient(0f to Color.Transparent, 1f to Color.Black, startX = 0f, endX = w),
                    size = androidx.compose.ui.geometry.Size(w, size.height),
                    blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                )
            }
            if (scroll.value < scroll.maxValue) {
                drawRect(
                    androidx.compose.ui.graphics.Brush.horizontalGradient(0f to Color.Black, 1f to Color.Transparent, startX = size.width - w, endX = size.width),
                    topLeft = androidx.compose.ui.geometry.Offset(size.width - w, 0f),
                    size = androidx.compose.ui.geometry.Size(w, size.height),
                    blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                )
            }
        }

/** The strip under the session header's capsules over which its material fades into the conversation. */
private val HEADER_TAPER = 24.dp

/** Widest the conversation column gets (landscape, tablets, unfolded foldables). */
private val MAX_READING_WIDTH = 720.dp
