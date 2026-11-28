package io.github.tianli0.switchtether.ui.sessions

import io.github.tianli0.coui17.patterns.ConfirmDialog
import io.github.tianli0.coui17.patterns.PageDefaults
import io.github.tianli0.coui17.patterns.ActionTone
import io.github.tianli0.coui17.patterns.LinkButton
import io.github.tianli0.coui17.patterns.SmallButton
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.basic.Button
import io.github.suqi8.coui.kmp.basic.ButtonDefaults
import io.github.suqi8.coui.kmp.basic.RotatingProgressIndicator
import io.github.suqi8.coui.kmp.basic.SmallTitle
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.cardListPositionOf
import io.github.suqi8.coui.kmp.squircle.SquircleShape
import io.github.suqi8.coui.kmp.input.couiLongPressSelection
import io.github.suqi8.coui.kmp.input.couiLongPressSelectionItem
import io.github.suqi8.coui.kmp.input.rememberCOUILongPressSelectionState
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.tianli0.switchtether.R
import io.github.tianli0.switchtether.connection.ConnectionManager
import io.github.tianli0.switchtether.connection.NotConnectedException
import io.github.tianli0.switchtether.domain.SessionRepository
import io.github.tianli0.switchtether.remote.command.CommandOutcome
import io.github.tianli0.switchtether.remote.protocol.PermissionModeOption
import io.github.tianli0.switchtether.remote.session.ClaudeTools
import io.github.tianli0.switchtether.remote.session.ContextUsage
import io.github.tianli0.switchtether.remote.session.ExportedConversation
import io.github.tianli0.switchtether.remote.session.McpServerStatus
import io.github.tianli0.switchtether.remote.session.PlanView
import io.github.tianli0.switchtether.remote.session.RewindResult
import io.github.tianli0.coui17.patterns.PanelSheet
import io.github.tianli0.coui17.patterns.GroupCard
import io.github.tianli0.switchtether.ui.common.Loadable
import io.github.tianli0.switchtether.ui.common.LoadableContent
import io.github.tianli0.coui17.patterns.Page
import io.github.tianli0.coui17.patterns.TextInput
import io.github.tianli0.switchtether.ui.common.UiError
import io.github.tianli0.coui17.patterns.pageListPadding
import io.github.tianli0.coui17.patterns.pageScroll
import io.github.tianli0.coui17.patterns.rememberPageListState
import io.github.tianli0.coui17.patterns.StatusDot
import io.github.tianli0.switchtether.ui.containerViewModel
import io.github.tianli0.switchtether.ui.markdown.MarkdownView
import io.github.tianli0.switchtether.ui.theme.LocalDevColors
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// ---- Shared helpers (also used by SessionDetailViewModel / AgentMapViewModel) ---------------------

/** A session tool the desktop refused or that did not get through, with the message to show. */
class ToolCallException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The response body of a Claude Code session tool, or why there is none. Never retried. */
fun claudeToolResult(out: CommandOutcome): Result<JsonElement> = when (out) {
    is CommandOutcome.Succeeded -> Result.success(out.response.body)
    is CommandOutcome.Rejected -> Result.failure(ToolCallException(out.error.body.message.ifBlank { out.error.code }, out.error))
    is CommandOutcome.NotSent ->
        if (out.cause is NotConnectedException) Result.failure(out.cause) else Result.failure(ToolCallException(ConnectionManager.describe(out.cause), out.cause))
    is CommandOutcome.Unknown -> Result.failure(ToolCallException(ConnectionManager.describe(out.cause), out.cause))
    is CommandOutcome.InProgress -> Result.failure(ToolCallException("command_in_progress"))
}

fun toolErrorMessage(t: Throwable): String =
    (t as? ToolCallException)?.message ?: UiError.from(t).detail ?: t.message ?: t.javaClass.simpleName

private fun toolUiError(t: Throwable): UiError =
    if (t is ToolCallException) UiError(UiError.Kind.SERVER, t.message) else UiError.from(t)

fun jsonOf(vararg pairs: Pair<String, String>): JsonObject = JsonObject(pairs.associate { (k, v) -> k to JsonPrimitive(v) })

fun rewindParams(userMessageId: String, dryRun: Boolean): JsonObject =
    JsonObject(mapOf("userMessageId" to JsonPrimitive(userMessageId), "dryRun" to JsonPrimitive(dryRun)))

/** `messageUuid` of a send response (Claude Code sessions SwitchTether started). */
fun queuedMessageUuid(body: JsonElement?): String? = ClaudeTools.messageUuidOf(body)

/** Runs one tool through [repo] and maps the outcome. */
suspend fun SessionRepository.runClaudeTool(sessionId: String, action: String, params: JsonObject = JsonObject(emptyMap())): Result<JsonElement> = try {
    claudeToolResult(claudeTool(sessionId, action, params))
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}

// ---- Session tools page ---------------------------------------------------------------------------

data class SessionToolsState(
    val context: Loadable<ContextUsage> = Loadable.Loading,
    val plan: Loadable<PlanView> = Loadable.Loading,
    val mcp: Loadable<List<McpServerStatus>> = Loadable.Loading,
    val reconnecting: Set<String> = emptySet(),
    val reconnected: Set<String> = emptySet(),
    val mcpErrors: Map<String, String> = emptyMap(),
    val asking: Boolean = false,
    val answer: String? = null,
    val askError: String? = null,
    val renaming: Boolean = false,
    val renameError: String? = null,
    val renamed: Boolean = false,
    val exporting: Boolean = false,
    val exportError: String? = null,
)

class SessionToolsViewModel(
    private val sessionId: String,
    private val repo: SessionRepository,
    autoLoad: Boolean = true,
) : ViewModel() {
    private val _state = MutableStateFlow(SessionToolsState())
    val state: StateFlow<SessionToolsState> = _state.asStateFlow()

    private val _exports = Channel<ExportedConversation>(Channel.BUFFERED)

    /** Exported conversations, handed to the share sheet once each. */
    val exports: Flow<ExportedConversation> = _exports.receiveAsFlow()

    private var loadedContext = false
    private var loadedPlan = false
    private var loadedMcp = false

    init {
        if (autoLoad) refreshAll()
    }

    fun refreshAll() {
        ensureLoaded(context = true, plan = true, mcp = true)
    }

    fun ensureLoaded(context: Boolean, plan: Boolean, mcp: Boolean) {
        if (context && !loadedContext) {
            loadedContext = true
            loadContext()
        }
        if (plan && !loadedPlan) {
            loadedPlan = true
            loadPlan()
        }
        if (mcp && !loadedMcp) {
            loadedMcp = true
            loadMcp()
        }
    }

    private suspend fun <T> load(action: String, parse: (JsonElement) -> T): Loadable<T> =
        repo.runClaudeTool(sessionId, action).fold({ Loadable.Ready(parse(it)) }, { Loadable.Failed(toolUiError(it)) })

    fun loadContext() {
        _state.update { it.copy(context = Loadable.Loading) }
        viewModelScope.launch {
            val r = load(ClaudeTools.CONTEXT_USAGE, ClaudeTools::parseContextUsage)
            _state.update { it.copy(context = r) }
        }
    }

    fun loadPlan() {
        _state.update { it.copy(plan = Loadable.Loading) }
        viewModelScope.launch {
            val r = load(ClaudeTools.PLAN, ClaudeTools::parsePlan)
            _state.update { it.copy(plan = r) }
        }
    }

    fun loadMcp() {
        _state.update { it.copy(mcp = Loadable.Loading) }
        viewModelScope.launch {
            val r = load(ClaudeTools.MCP_STATUS, ClaudeTools::parseMcpStatus)
            _state.update { it.copy(mcp = r) }
        }
    }

    fun reconnect(server: String) {
        if (server in _state.value.reconnecting) return
        _state.update { it.copy(reconnecting = it.reconnecting + server, reconnected = it.reconnected - server, mcpErrors = it.mcpErrors - server) }
        viewModelScope.launch {
            val r = repo.runClaudeTool(sessionId, ClaudeTools.MCP_RECONNECT, jsonOf("server" to server))
            _state.update { st ->
                st.copy(
                    reconnecting = st.reconnecting - server,
                    reconnected = if (r.isSuccess) st.reconnected + server else st.reconnected,
                    mcpErrors = r.exceptionOrNull()?.let { st.mcpErrors + (server to toolErrorMessage(it)) } ?: st.mcpErrors,
                )
            }
            if (r.isSuccess) {
                val fresh = load(ClaudeTools.MCP_STATUS, ClaudeTools::parseMcpStatus)
                if (fresh is Loadable.Ready) _state.update { it.copy(mcp = fresh) }
            }
        }
    }

    /** A quick question answered without interrupting the current task (/btw). */
    fun ask(question: String) {
        val q = question.trim()
        if (q.isEmpty() || _state.value.asking) return
        _state.update { it.copy(asking = true, answer = null, askError = null) }
        viewModelScope.launch {
            val r = repo.runClaudeTool(sessionId, ClaudeTools.SIDE_QUESTION, jsonOf("question" to q))
            _state.update { st ->
                r.fold(
                    { st.copy(asking = false, answer = ClaudeTools.parseAnswer(it)) },
                    { st.copy(asking = false, askError = toolErrorMessage(it)) },
                )
            }
        }
    }

    fun export() {
        if (_state.value.exporting) return
        _state.update { it.copy(exporting = true, exportError = null) }
        viewModelScope.launch {
            val r = repo.runClaudeTool(sessionId, ClaudeTools.EXPORT)
            r.onSuccess { _exports.trySend(ClaudeTools.parseExport(it)) }
            _state.update { it.copy(exporting = false, exportError = r.exceptionOrNull()?.let(::toolErrorMessage)) }
        }
    }

    fun setRenaming(busy: Boolean, error: String? = null, done: Boolean = false) {
        _state.update { it.copy(renaming = busy, renameError = error, renamed = done) }
    }
}

/**
 * Claude Code's session tools for a session SwitchTether started (the IDE extension's session
 * features): permission mode, context usage, plan, MCP servers, a side question, rename, export.
 * Permission mode and rename act through the conversation's own ViewModel so its header follows.
 */
@Composable
fun SessionToolsScreen(sessionId: String, onBack: () -> Unit) {
    val detailVm = containerViewModel(key = "session:$sessionId") { SessionDetailViewModel(sessionId, it.connection, it.sessions, it.draftStore, it::noteLocalAction, liveDispatcher = kotlinx.coroutines.Dispatchers.Default, stepUp = { scope -> it.stepUpGate.ensure(scope).isSuccess }, transfers = it.transfers) }
    val vm = containerViewModel(key = "session-tools:$sessionId") { SessionToolsViewModel(sessionId, it.sessions, autoLoad = false) }
    val detail by detailVm.state.collectAsStateWithLifecycle()
    val st by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val tooLarge = stringResource(R.string.tools_export_too_large)
    val empty = stringResource(R.string.tools_export_empty)
    val shareTitle = stringResource(R.string.tools_export)
    LaunchedEffect(vm) {
        vm.exports.collect { ex -> shareConversation(context, ex, shareTitle, empty, tooLarge) }
    }
    var confirmStop by rememberSaveable { mutableStateOf(false) }
    val stopLabel = stringResource(R.string.session_end)
    ConfirmDialog(
        show = confirmStop,
        title = stopLabel,
        message = stringResource(R.string.session_end_confirm),
        confirmText = stopLabel,
        onConfirm = {
            confirmStop = false
            detailVm.stopSession(stopLabel)
        },
        onDismiss = { confirmStop = false },
        destructive = true,
    )
    val summary = detail.detail.valueOrNull?.summary
    Page(
        title = stringResource(R.string.tools_title),
        subtitle = summary?.let { io.github.tianli0.switchtether.ui.common.sessionDisplayTitle(it) }.orEmpty(),
        onBack = onBack,
        immersive = true,
    ) {
        // A session started on the computer is observe-only: what its files hold, nothing that needs the process.
        val readOnly = detail.claudeReadTools
        val showContext = detail.hasSessionTool(ClaudeTools.CONTEXT_USAGE)
        val showPlan = detail.hasSessionTool(ClaudeTools.PLAN)
        val showMcp = detail.hasSessionTool(ClaudeTools.MCP_STATUS)
        val canReconnectMcp = detail.hasSessionTool(ClaudeTools.MCP_RECONNECT)
        val showSideQuestion = detail.hasSessionTool(ClaudeTools.SIDE_QUESTION)
        val showRename = detail.hasSessionTool(ClaudeTools.RENAME)
        val showExport = detail.hasSessionTool(ClaudeTools.EXPORT)
        LaunchedEffect(showContext, showPlan, showMcp) {
            vm.ensureLoaded(showContext, showPlan, showMcp)
        }
        val sessionCost = androidx.compose.runtime.remember(detail.log) { sessionCost(detail.log.events) }
        LazyColumn(state = rememberPageListState(), flingBehavior = io.github.tianli0.coui17.patterns.pageFlingBehavior(), modifier = Modifier.fillMaxSize().pageScroll().testTag("session-tools"), contentPadding = pageListPadding()) {
            if (!detail.online) {
                item { ToolsNote(stringResource(R.string.tools_offline), LocalDevColors.current.warn) }
            }
            if (readOnly) {
                item { ToolsNote(stringResource(R.string.tools_read_only_note)) }
            }
            // Also for a conversation that is not running: the mode applies when a message resumes it.
            if (detail.hasPermissionModes && (!readOnly || detail.resumable)) item {
                SmallTitle(stringResource(R.string.tools_permission))
                PermissionModeOptions(
                    current = detail.detail.valueOrNull?.permissionMode,
                    pending = detail.permissionPending,
                    enabled = detail.canPickPermission,
                    onSelect = detailVm::setPermissionMode,
                    modes = detail.permissionModes,
                    agent = detail.detail.valueOrNull?.summary?.agent,
                )
                detail.permissionError?.let { ToolsNote(stringResource(R.string.tools_permission_failed, it), LocalDevColors.current.danger) }
                ToolsNote(stringResource(R.string.tools_permission_hint))
            }
            if (showContext) item {
                SectionTitle(stringResource(R.string.tools_context), onRefresh = vm::loadContext)
                GroupCard { ToolLoadable(st.context, vm::loadContext) { ContextUsageView(it) } }
            }
            // What the loaded part of the conversation cost (nothing when no turn reported a cost).
            sessionCost?.let { cost ->
                item { SessionCostCard(cost, partial = detail.hasMoreBefore) }
            }
            if (showPlan) item {
                SectionTitle(stringResource(R.string.tools_plan), onRefresh = vm::loadPlan)
                GroupCard {
                    ToolLoadable(st.plan, vm::loadPlan) { p ->
                        Box(Modifier.fillMaxWidth().padding(16.dp)) {
                            val content = p.content
                            if (p.exists && content != null) {
                                MarkdownView(content)
                            } else {
                                Text(stringResource(R.string.tools_plan_none), style = COUITheme.textStyles.body2, color = COUITheme.colorScheme.onSurfaceVariantSummary)
                            }
                        }
                    }
                }
            }
            if (showMcp) item {
                SectionTitle(stringResource(R.string.tools_mcp), onRefresh = vm::loadMcp)
                GroupCard {
                    ToolLoadable(st.mcp, vm::loadMcp) { servers ->
                        if (servers.isEmpty()) {
                            Text(stringResource(R.string.tools_mcp_none), style = COUITheme.textStyles.body2, color = COUITheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(16.dp))
                        }
                        var mcpOpen by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
                        if (servers.isNotEmpty()) {
                            val dev = LocalDevColors.current
                            val connected = servers.count { it.status == "connected" }
                            val failing = servers.count { it.status == "failed" || it.status == "needs-auth" || it.status == "needs_auth" }
                            BasicComponent(
                                title = pluralStringResource(R.plurals.tools_mcp_servers, servers.size, servers.size),
                                summary = listOfNotNull(
                                    stringResource(R.string.tools_mcp_connected_count, connected),
                                    failing.takeIf { it > 0 }?.let { stringResource(R.string.tools_mcp_failing_count, it) },
                                ).joinToString(" · "),
                                startAction = { StatusDot(if (failing > 0) dev.danger else if (connected == servers.size) dev.ok else dev.warn, Modifier.padding(end = 12.dp)) },
                                endActions = { io.github.suqi8.coui.kmp.basic.ExpandArrow(expanded = mcpOpen) },
                                onClick = { mcpOpen = !mcpOpen },
                                cardListPosition = if (mcpOpen) io.github.suqi8.coui.kmp.basic.CardListPosition.Head else io.github.suqi8.coui.kmp.basic.CardListPosition.Full,
                                modifier = Modifier.testTag("tools-mcp-toggle"),
                            )
                        }
                        io.github.tianli0.coui17.patterns.ExpandBody(expanded = mcpOpen) {
                        servers.forEachIndexed { i, s ->
                            McpRow(
                                s,
                                position = if (i == servers.lastIndex) io.github.suqi8.coui.kmp.basic.CardListPosition.Tail else io.github.suqi8.coui.kmp.basic.CardListPosition.Middle,
                                reconnecting = s.name in st.reconnecting,
                                reconnected = s.name in st.reconnected,
                                error = st.mcpErrors[s.name],
                                enabled = detail.online && canReconnectMcp,
                                onReconnect = if (canReconnectMcp) ({ vm.reconnect(s.name) }) else null,
                            )
                        }
                        }
                    }
                }
            }
            if (showSideQuestion) item { SideQuestion(st, enabled = detail.online && showSideQuestion, onAsk = vm::ask) }
            if (showRename) item {
                RenameSession(
                    current = summary?.title.orEmpty(),
                    st = st,
                    enabled = detail.online && showRename,
                    onSave = { title ->
                        vm.setRenaming(true)
                        detailVm.renameSession(title) { err -> vm.setRenaming(false, err, done = err == null) }
                    },
                )
            }
            if (showExport) item {
                SmallTitle(stringResource(R.string.tools_export))
                GroupCard {
                    BasicComponent(
                        title = stringResource(R.string.tools_export_action),
                        titleColor = io.github.suqi8.coui.kmp.basic.BasicComponentDefaults.titleColor(color = COUITheme.colorScheme.primary),
                        onClick = vm::export,
                        enabled = detail.online && !st.exporting,
                        endActions = {
                            if (st.exporting) RotatingProgressIndicator(color = COUITheme.colorScheme.primary, size = 18.dp, ringDiameter = 14.4.dp, strokeWidth = 2.dp)
                        },
                        cardListPosition = io.github.suqi8.coui.kmp.basic.CardListPosition.Full,
                        modifier = Modifier.testTag("tools-export"),
                    )
                }
                st.exportError?.let { ToolsNote(it, LocalDevColors.current.danger) }
                ToolsNote(stringResource(R.string.tools_export_hint))
            }
            // Only for a session SwitchTether started: its process is ours to end.
            if (detail.canStop) item {
                GroupCard {
                    BasicComponent(
                        title = stopLabel,
                        summary = stringResource(R.string.session_end_summary),
                        endActions = { SmallButton(stopLabel, { confirmStop = true }, tone = ActionTone.Danger, modifier = Modifier.testTag("tools-end-session")) },
                        cardListPosition = io.github.suqi8.coui.kmp.basic.CardListPosition.Full,
                    )
                }
            }
        }
    }
}

/** Hands the exported text to another app (ACTION_SEND); nothing is written to storage. */
private fun shareConversation(context: android.content.Context, ex: ExportedConversation, title: String, empty: String, tooLarge: String) {
    if (ex.text.isBlank()) {
        android.widget.Toast.makeText(context, empty, android.widget.Toast.LENGTH_SHORT).show()
        return
    }
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_TEXT, ex.text)
        ex.filename?.let { putExtra(android.content.Intent.EXTRA_SUBJECT, it) }
    }
    // Binder transactions are limited (~1 MB): a very long conversation cannot go through an intent.
    val ok = ex.text.length <= 400_000 && runCatching {
        context.startActivity(android.content.Intent.createChooser(send, title).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
    if (!ok) android.widget.Toast.makeText(context, tooLarge, android.widget.Toast.LENGTH_LONG).show()
}

@Composable
private fun ToolsNote(text: String, color: Color = COUITheme.colorScheme.onSurfaceVariantSummary) {
    Text(text, style = COUITheme.textStyles.footnote2, color = color, modifier = Modifier.padding(start = PageDefaults.TextGutter, end = PageDefaults.TextGutter, bottom = 12.dp))
}

@Composable
private fun SectionTitle(text: String, onRefresh: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { SmallTitle(text) }
        LinkButton(stringResource(R.string.tools_refresh), onRefresh)
    }
}

/** A tool's result inside its card: spinner, the error with retry, or the content. */
@Composable
private fun <T> ToolLoadable(state: Loadable<T>, onRetry: () -> Unit, content: @Composable (T) -> Unit) {
    when (state) {
        Loadable.Loading -> Box(Modifier.fillMaxWidth().heightIn(min = 72.dp), contentAlignment = Alignment.Center) {
            RotatingProgressIndicator(color = COUITheme.colorScheme.primary, size = 20.dp, ringDiameter = 16.dp, strokeWidth = 2.dp)
        }
        is Loadable.Failed -> Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(state.error.detail ?: stringResource(R.string.error_network), style = COUITheme.textStyles.footnote1, color = LocalDevColors.current.danger)
            SmallButton(stringResource(R.string.action_retry), onRetry)
        }
        is Loadable.Ready -> content(state.value)
    }
}

/** Short name of a permission mode; the ids are the same for every agent, the words follow the agent. */
@Composable
fun permissionModeLabel(mode: String?, agent: String? = null): String = when (mode) {
    "acceptEdits" -> stringResource(R.string.perm_accept_edits_short)
    "plan" -> stringResource(R.string.perm_plan_short)
    "auto" -> stringResource(if (isCodex(agent)) R.string.perm_codex_auto_short else R.string.perm_auto_short)
    "dontAsk" -> stringResource(R.string.perm_dont_ask_short)
    "bypassPermissions" -> stringResource(if (isCodex(agent)) R.string.perm_full_access_short else R.string.perm_bypass_short)
    "readOnly" -> stringResource(R.string.perm_read_only_short)
    "untrusted" -> stringResource(R.string.perm_untrusted_short)
    "default", null -> stringResource(R.string.perm_default_short)
    else -> stringResource(R.string.perm_unknown, mode)
}

private fun isCodex(agent: String?) = agent == "codex"

@Composable
private fun permissionModeSummary(mode: String, agent: String?, fallback: String?): String = when {
    isCodex(agent) -> when (mode) {
        "plan" -> stringResource(R.string.perm_codex_plan)
        "auto" -> stringResource(R.string.perm_codex_auto)
        "bypassPermissions" -> stringResource(R.string.perm_full_access)
        "readOnly" -> stringResource(R.string.perm_read_only)
        "untrusted" -> stringResource(R.string.perm_untrusted)
        "default" -> stringResource(R.string.perm_codex_default)
        else -> fallback ?: mode
    }
    else -> when (mode) {
        "acceptEdits" -> stringResource(R.string.perm_accept_edits)
        "plan" -> stringResource(R.string.perm_plan)
        "auto" -> stringResource(R.string.perm_auto)
        "dontAsk" -> stringResource(R.string.perm_dont_ask)
        "bypassPermissions" -> stringResource(R.string.perm_bypass)
        "default" -> stringResource(R.string.perm_default)
        else -> fallback ?: mode
    }
}

/** A mode that runs without asking: confirmed first, and the desktop wants the terminal step-up. */
fun isDangerousPermissionMode(mode: String, modes: List<PermissionModeOption>): Boolean =
    mode == "bypassPermissions" || modes.any { it.id == mode && it.dangerous }

/**
 * The modes the desktop offers for this session ([modes]; an older desktop sends none: Claude Code's
 * list), the current one selected; a switch in flight shows a spinner. A dangerous mode asks first.
 */
@Composable
fun PermissionModeOptions(
    current: String?,
    pending: String?,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    modes: List<PermissionModeOption> = emptyList(),
    agent: String? = null,
) {
    val ids = modes.map { it.id }.ifEmpty { ClaudeTools.PERMISSION_MODES }
    var confirm by remember { mutableStateOf<String?>(null) }
    val selection = rememberCOUILongPressSelectionState()
    GroupCard(Modifier.couiLongPressSelection(selection)) {
        ids.forEachIndexed { i, mode ->
            val choose = {
                if (enabled && pending == null && mode != (current ?: "default")) {
                    if (isDangerousPermissionMode(mode, modes)) confirm = mode else onSelect(mode)
                }
            }
            io.github.suqi8.coui.kmp.preference.RadioButtonPreference(
                title = permissionModeLabel(mode, agent),
                summary = permissionModeSummary(mode, agent, modes.firstOrNull { it.id == mode }?.description),
                selected = (selection.previewKey ?: (current ?: "default")) == mode,
                onClick = choose,
                enabled = enabled && pending == null,
                startAction = if (pending == mode) {
                    { RotatingProgressIndicator(color = COUITheme.colorScheme.primary, size = 18.dp, ringDiameter = 14.4.dp, strokeWidth = 2.dp, modifier = Modifier.padding(end = 10.dp)) }
                } else null,
                cardListPosition = cardListPositionOf(i, ids.size),
                modifier = Modifier.testTag("perm-$mode")
                    .couiLongPressSelectionItem(selection, mode, enabled && pending == null, choose),
            )
        }
    }
    DangerousModeDialog(confirm, onConfirm = { m -> confirm = null; onSelect(m) }, onDismiss = { confirm = null })
}

/** "Run without asking?" before a mode that skips every approval. */
@Composable
fun DangerousModeDialog(mode: String?, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    ConfirmDialog(
        show = mode != null,
        title = stringResource(R.string.perm_dangerous_title),
        message = stringResource(R.string.perm_dangerous_body),
        confirmText = stringResource(R.string.perm_dangerous_confirm),
        cancelText = stringResource(R.string.action_cancel),
        onConfirm = { mode?.let(onConfirm) },
        onDismiss = onDismiss,
    )
}

/** Quick switch from the composer's mode capsule. */
@Composable
fun PermissionModeSheet(
    show: Boolean,
    current: String?,
    pending: String?,
    error: String?,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    modes: List<PermissionModeOption> = emptyList(),
    agent: String? = null,
) {
    PanelSheet(show = show, title = stringResource(R.string.tools_permission), onDismissRequest = onDismiss, imePadding = false) {
        // Up to six modes with two-line descriptions: scrolls in landscape or with large text.
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            PermissionModeOptions(current, pending, enabled, onSelect, modes, agent)
            error?.let { ToolsNote(stringResource(R.string.tools_permission_failed, it), LocalDevColors.current.danger) }
            ToolsNote(stringResource(R.string.tools_permission_hint))
        }
    }
}

@Composable
private fun contextColors(): List<Color> {
    val dev = LocalDevColors.current
    return listOf(COUITheme.colorScheme.primary, dev.info, dev.ok, dev.warn, dev.danger)
}

/** `/context`: one bar split by category, then each category with its tokens and share. */
@Composable
private fun ContextUsageView(u: ContextUsage) {
    val dev = LocalDevColors.current
    val palette = contextColors()
    val max = u.maxTokens.takeIf { it > 0 } ?: u.categories.sumOf { it.tokens }.coerceAtLeast(1)
    val used = u.categories.filter { it.kind == "used" }
    fun colorOf(c: io.github.tianli0.switchtether.remote.session.ContextCategory): Color = when (c.kind) {
        "used" -> palette[used.indexOf(c).coerceAtLeast(0) % palette.size]
        "deferred" -> dev.neutral.copy(alpha = 0.55f)
        "buffer" -> dev.neutral.copy(alpha = 0.3f)
        else -> Color.Transparent
    }
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.tools_context_total, compactTokens(u.totalTokens), compactTokens(u.maxTokens), "%.0f%%".format(u.percentage)),
            style = COUITheme.textStyles.body2,
            color = COUITheme.colorScheme.onSurface,
        )
        Row(Modifier.fillMaxWidth().height(10.dp).clip(SquircleShape(5.dp)).background(COUITheme.colorScheme.surfaceContainerHigh)) {
            u.categories.filter { it.kind != "free" && it.tokens > 0 }.forEach { c ->
                Box(Modifier.weight(c.tokens.toFloat() / max).height(10.dp).background(colorOf(c)))
            }
            val rest = (max - u.categories.filter { it.kind != "free" }.sumOf { it.tokens }).coerceAtLeast(0)
            if (rest > 0) Spacer(Modifier.weight(rest.toFloat() / max))
        }
        if (u.categories.isEmpty()) {
            Text(stringResource(R.string.tools_context_empty), style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurfaceVariantSummary)
        }
        u.categories.forEach { c ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(if (c.kind == "free") COUITheme.colorScheme.surfaceContainerHigh else colorOf(c))
                Spacer(Modifier.size(8.dp))
                val kind = when (c.kind) {
                    "free" -> stringResource(R.string.tools_context_kind_free)
                    "buffer" -> stringResource(R.string.tools_context_kind_buffer)
                    "deferred" -> stringResource(R.string.tools_context_kind_deferred)
                    else -> null
                }
                Text(
                    listOfNotNull(c.name, kind?.takeIf { !c.name.contains(it, ignoreCase = true) }).joinToString(" · "),
                    style = COUITheme.textStyles.footnote1,
                    color = COUITheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${compactTokens(c.tokens)} · ${"%.1f%%".format(c.tokens * 100.0 / max)}",
                    style = COUITheme.textStyles.footnote2,
                    color = COUITheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

/** "Session cost": the summed cost in the display currency, input / output tokens under the title. */
@Composable
private fun SessionCostCard(cost: SessionCost, partial: Boolean) {
    GroupCard {
        BasicComponent(
            title = stringResource(R.string.tools_session_cost),
            summary = stringResource(
                R.string.tools_session_cost_tokens,
                io.github.tianli0.switchtether.remote.usage.UsageFormat.tokens(cost.inputTokens),
                io.github.tianli0.switchtether.remote.usage.UsageFormat.tokens(cost.outputTokens),
            ),
            endActions = {
                Text(
                    io.github.tianli0.switchtether.remote.usage.UsageFormat.money(cost.costUsd, io.github.tianli0.switchtether.domain.LocalMoney.current, 4),
                    style = COUITheme.textStyles.body1,
                    color = COUITheme.colorScheme.onSurface,
                )
            },
            cardListPosition = io.github.suqi8.coui.kmp.basic.CardListPosition.Full,
            modifier = Modifier.testTag("tools-session-cost"),
        )
    }
    if (partial) ToolsNote(stringResource(R.string.tools_session_cost_partial))
}

@Composable
fun mcpStatusLabel(status: String): String = when (status) {
    "connected" -> stringResource(R.string.mcp_status_connected)
    "failed" -> stringResource(R.string.mcp_status_failed)
    "pending" -> stringResource(R.string.mcp_status_pending)
    "needs-auth", "needs_auth" -> stringResource(R.string.mcp_status_needs_auth)
    "disabled" -> stringResource(R.string.mcp_status_disabled)
    else -> status
}

@Composable
private fun McpRow(
    s: McpServerStatus,
    position: io.github.suqi8.coui.kmp.basic.CardListPosition,
    reconnecting: Boolean,
    reconnected: Boolean,
    error: String?,
    enabled: Boolean,
    onReconnect: (() -> Unit)?,
) {
    val dev = LocalDevColors.current
    val color = when (s.status) {
        "connected" -> dev.ok
        "failed" -> dev.danger
        "pending", "needs-auth", "needs_auth" -> dev.warn
        else -> dev.neutral
    }
    val summary = listOfNotNull(
        mcpStatusLabel(s.status),
        s.tools?.let { pluralStringResource(R.plurals.tools_mcp_tools, it, it) },
        s.transport,
        s.scope,
        s.version?.let { "v$it" },
    ).joinToString(" · ") +
        listOfNotNull(s.error, error).joinToString("") { "\n$it" } +
        (if (reconnected) "\n" + stringResource(R.string.tools_mcp_reconnected) else "")
    BasicComponent(
        title = s.title?.takeIf { it.isNotBlank() && it != s.name }?.let { "${s.name} · $it" } ?: s.name,
        summary = summary,
        startAction = { StatusDot(androidx.compose.animation.animateColorAsState(color, label = "mcp-dot").value, Modifier.padding(end = 12.dp)) },
        endActions = if (onReconnect != null) {
            {
                SmallButton(
                    text = stringResource(if (reconnecting) R.string.tools_mcp_reconnecting else R.string.tools_mcp_reconnect),
                    onClick = { if (!reconnecting) onReconnect() },
                    enabled = enabled && !reconnecting,
                )
            }
        } else null,
        cardListPosition = position,
    )
}

@Composable
private fun SideQuestion(st: SessionToolsState, enabled: Boolean, onAsk: (String) -> Unit) {
    var question by rememberSaveable { mutableStateOf("") }
    SmallTitle(stringResource(R.string.tools_side_question))
    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextInput(
            value = question,
            onValueChange = { question = it },
            label = stringResource(R.string.tools_side_question_field),
            singleLine = false,
            maxLines = 4,
            enabled = enabled && !st.asking,
            modifier = Modifier.fillMaxWidth().testTag("btw-field"),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            SmallButton(
                text = stringResource(R.string.tools_side_question_ask),
                onClick = { onAsk(question) },
                enabled = enabled && question.isNotBlank(),
                tone = ActionTone.Primary,
                loading = st.asking,
                modifier = Modifier.testTag("btw-ask"),
            )
        }
        io.github.tianli0.switchtether.ui.common.NoticeVisibility(st.asking) {
            Text(stringResource(R.string.tools_side_question_waiting), style = COUITheme.textStyles.footnote2, color = COUITheme.colorScheme.onSurfaceVariantSummary)
        }
        st.askError?.let { Text(it, style = COUITheme.textStyles.footnote2, color = LocalDevColors.current.danger) }
    }
    st.answer?.let { answer ->
        GroupCard {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.tools_side_question_answer), style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.weight(1f))
                    if (answer.isNotBlank()) io.github.tianli0.switchtether.ui.code.CopyButton(answer, stringResource(R.string.tools_copy_answer))
                }
                Box(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                    MarkdownView(answer.ifBlank { "—" })
                }
            }
        }
    }
    ToolsNote(stringResource(R.string.tools_side_question_hint))
}

@Composable
private fun RenameSession(current: String, st: SessionToolsState, enabled: Boolean, onSave: (String) -> Unit) {
    var title by rememberSaveable(current) { mutableStateOf(current) }
    SmallTitle(stringResource(R.string.tools_rename))
    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextInput(
            value = title,
            onValueChange = { title = it },
            label = stringResource(R.string.tools_rename_field),
            enabled = enabled && !st.renaming,
            modifier = Modifier.fillMaxWidth().testTag("rename-field"),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                st.renameError != null -> Text(st.renameError, style = COUITheme.textStyles.footnote2, color = LocalDevColors.current.danger, modifier = Modifier.weight(1f))
                st.renamed -> Text(stringResource(R.string.tools_renamed), style = COUITheme.textStyles.footnote2, color = LocalDevColors.current.ok, modifier = Modifier.weight(1f))
                else -> Spacer(Modifier.weight(1f))
            }
            SmallButton(
                text = stringResource(R.string.tools_rename_save),
                onClick = { onSave(title) },
                enabled = enabled && !st.renaming && title.isNotBlank() && title.trim() != current && title.trim().length <= 200,
                tone = ActionTone.Primary,
                modifier = Modifier.testTag("rename-save"),
            )
        }
    }
}

// ---- Rewind dialog -----------------------------------------------------------------------------

/**
 * "Rewind files to before this message": the dry run's file list, then an explicit confirm. Only
 * files are restored; the conversation stays as it is (said in the dialog).
 */
@Composable
fun RewindDialog(state: RewindState?, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val dev = LocalDevColors.current
    val preview: RewindResult? = state?.preview
    val canConfirm = state != null && !state.loading && !state.applying && preview?.canRewind == true &&
        preview.error == null && preview.filesChanged.isNotEmpty()
    // A form dialog, not an alert: its confirm capsule is greyed out while there is nothing to
    // rewind yet (checking, unavailable, no files), instead of looking tappable and doing nothing.
    io.github.tianli0.coui17.patterns.FormDialog(
        show = state != null,
        title = stringResource(R.string.rewind_title),
        confirmText = stringResource(if (state?.applying == true) R.string.rewind_applying else R.string.rewind_confirm),
        cancelText = stringResource(R.string.action_cancel),
        confirmEnabled = canConfirm,
        onConfirm = { if (canConfirm) onConfirm() },
        onDismiss = onDismiss,
    ) {
        Column(Modifier.fillMaxWidth().testTag("rewind-dialog"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.rewind_explain), style = COUITheme.textStyles.body2, color = COUITheme.colorScheme.onSurfaceSecondary)
            when {
                state == null -> {}
                state.loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    RotatingProgressIndicator(color = COUITheme.colorScheme.primary, size = 18.dp, ringDiameter = 14.4.dp, strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.rewind_checking), style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurfaceSecondary)
                }
                preview == null -> Text(stringResource(R.string.rewind_unavailable, state.error.orEmpty()), style = COUITheme.textStyles.footnote1, color = dev.danger)
                !preview.canRewind || preview.error != null ->
                    Text(stringResource(R.string.rewind_unavailable, preview.error ?: state.error.orEmpty()), style = COUITheme.textStyles.footnote1, color = dev.danger)
                preview.filesChanged.isEmpty() -> Text(stringResource(R.string.rewind_nothing), style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurfaceSecondary)
                else -> {
                    Text(stringResource(R.string.rewind_files), style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurface)
                    Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                        SelectionContainer {
                            Text(preview.filesChanged.joinToString("\n"), style = io.github.tianli0.switchtether.ui.theme.DevType.codeSmall, color = COUITheme.colorScheme.onSurfaceSecondary)
                        }
                    }
                    if (preview.insertions != null || preview.deletions != null) {
                        Text(
                            stringResource(R.string.rewind_stats, (preview.insertions ?: 0).toInt(), (preview.deletions ?: 0).toInt()),
                            style = COUITheme.textStyles.footnote2,
                            color = COUITheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    if (state.error != null) Text(state.error, style = COUITheme.textStyles.footnote2, color = dev.danger)
                }
            }
        }
    }
}
