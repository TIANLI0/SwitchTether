package io.github.tianli0.switchtether.ui.sessions

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
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.basic.cardListPositionOf
import io.github.suqi8.coui.kmp.squircle.SquircleShape
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.tianli0.switchtether.R
import io.github.tianli0.switchtether.connection.ConnectionManager
import io.github.tianli0.switchtether.connection.NotConnectedException
import io.github.tianli0.switchtether.domain.SessionRepository
import io.github.tianli0.switchtether.remote.command.CommandOutcome
import io.github.tianli0.switchtether.remote.session.ClaudeTools
import io.github.tianli0.switchtether.remote.session.ContextUsage
import io.github.tianli0.switchtether.remote.session.ExportedConversation
import io.github.tianli0.switchtether.remote.session.McpServerStatus
import io.github.tianli0.switchtether.remote.session.PlanView
import io.github.tianli0.switchtether.remote.session.RewindResult
import io.github.tianli0.switchtether.ui.common.AppBottomSheet
import io.github.tianli0.switchtether.ui.common.GroupCard
import io.github.tianli0.switchtether.ui.common.Loadable
import io.github.tianli0.switchtether.ui.common.LoadableContent
import io.github.tianli0.switchtether.ui.common.Page
import io.github.tianli0.switchtether.ui.common.TextInput
import io.github.tianli0.switchtether.ui.common.UiError
import io.github.tianli0.switchtether.ui.common.pageListPadding
import io.github.tianli0.switchtether.ui.common.pageScroll
import io.github.tianli0.switchtether.ui.common.rememberPageListState
import io.github.tianli0.switchtether.ui.components.StatusDot
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

class SessionToolsViewModel(private val sessionId: String, private val repo: SessionRepository) : ViewModel() {
    private val _state = MutableStateFlow(SessionToolsState())
    val state: StateFlow<SessionToolsState> = _state.asStateFlow()

    private val _exports = Channel<ExportedConversation>(Channel.BUFFERED)

    /** Exported conversations, handed to the share sheet once each. */
    val exports: Flow<ExportedConversation> = _exports.receiveAsFlow()

    init {
        refreshAll()
    }

    fun refreshAll() {
        loadContext()
        loadPlan()
        loadMcp()
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
    val detailVm = containerViewModel(key = "session:$sessionId") { SessionDetailViewModel(sessionId, it.connection, it.sessions, it.draftStore, it::noteLocalAction) }
    val vm = containerViewModel(key = "session-tools:$sessionId") { SessionToolsViewModel(sessionId, it.sessions) }
    val detail by detailVm.state.collectAsStateWithLifecycle()
    val st by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val tooLarge = stringResource(R.string.tools_export_too_large)
    val empty = stringResource(R.string.tools_export_empty)
    val shareTitle = stringResource(R.string.tools_export)
    LaunchedEffect(vm) {
        vm.exports.collect { ex -> shareConversation(context, ex, shareTitle, empty, tooLarge) }
    }
    val summary = detail.detail.valueOrNull?.summary
    Page(
        title = stringResource(R.string.tools_title),
        subtitle = summary?.let { io.github.tianli0.switchtether.ui.common.sessionDisplayTitle(it) }.orEmpty(),
        onBack = onBack,
    ) {
        // A session started on the computer is observe-only: what its files hold, nothing that needs the process.
        val readOnly = detail.claudeReadTools
        LazyColumn(state = rememberPageListState(), modifier = Modifier.fillMaxSize().pageScroll().testTag("session-tools"), contentPadding = pageListPadding()) {
            if (!detail.online) {
                item { ToolsNote(stringResource(R.string.tools_offline), LocalDevColors.current.warn) }
            }
            if (readOnly) {
                item { ToolsNote(stringResource(R.string.tools_read_only_note)) }
            }
            // Also for a conversation that is not running: the mode applies when a message resumes it.
            if (!readOnly || detail.resumable) item {
                SmallTitle(stringResource(R.string.tools_permission))
                PermissionModeOptions(
                    current = detail.detail.valueOrNull?.permissionMode,
                    pending = detail.permissionPending,
                    enabled = detail.canUseClaudeTools || (detail.resumable && detail.online),
                    onSelect = detailVm::setPermissionMode,
                )
                detail.permissionError?.let { ToolsNote(stringResource(R.string.tools_permission_failed, it), LocalDevColors.current.danger) }
                ToolsNote(stringResource(R.string.tools_permission_hint))
            }
            if (!readOnly) item {
                SectionTitle(stringResource(R.string.tools_context), onRefresh = vm::loadContext)
                GroupCard { ToolLoadable(st.context, vm::loadContext) { ContextUsageView(it) } }
            }
            item {
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
            if (!readOnly) item {
                SectionTitle(stringResource(R.string.tools_mcp), onRefresh = vm::loadMcp)
                GroupCard {
                    ToolLoadable(st.mcp, vm::loadMcp) { servers ->
                        if (servers.isEmpty()) {
                            Text(stringResource(R.string.tools_mcp_none), style = COUITheme.textStyles.body2, color = COUITheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(16.dp))
                        }
                        servers.forEachIndexed { i, s ->
                            McpRow(
                                s,
                                position = cardListPositionOf(i, servers.size),
                                reconnecting = s.name in st.reconnecting,
                                reconnected = s.name in st.reconnected,
                                error = st.mcpErrors[s.name],
                                enabled = detail.canUseClaudeTools,
                                onReconnect = { vm.reconnect(s.name) },
                            )
                        }
                    }
                }
            }
            if (!readOnly) item { SideQuestion(st, enabled = detail.canUseClaudeTools, onAsk = vm::ask) }
            if (!readOnly) item {
                RenameSession(
                    current = summary?.title.orEmpty(),
                    st = st,
                    enabled = detail.canUseClaudeTools,
                    onSave = { title ->
                        vm.setRenaming(true)
                        detailVm.renameSession(title) { err -> vm.setRenaming(false, err, done = err == null) }
                    },
                )
            }
            item {
                SmallTitle(stringResource(R.string.tools_export))
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = vm::export,
                        enabled = (detail.canUseClaudeTools || (readOnly && detail.online)) && !st.exporting,
                        colors = ButtonDefaults.buttonColorsPrimary(),
                        modifier = Modifier.fillMaxWidth().testTag("tools-export"),
                    ) {
                        if (st.exporting) RotatingProgressIndicator(color = COUITheme.colorScheme.onPrimary, size = 18.dp, ringDiameter = 14.4.dp, strokeWidth = 2.dp)
                        else Text(stringResource(R.string.tools_export_action), color = COUITheme.colorScheme.onPrimary)
                    }
                }
                st.exportError?.let { ToolsNote(it, LocalDevColors.current.danger) }
                ToolsNote(stringResource(R.string.tools_export_hint))
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
    Text(text, style = COUITheme.textStyles.footnote2, color = color, modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp))
}

@Composable
private fun SectionTitle(text: String, onRefresh: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { SmallTitle(text) }
        TextButton(text = stringResource(R.string.tools_refresh), onClick = onRefresh)
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
            TextButton(text = stringResource(R.string.action_retry), onClick = onRetry)
        }
        is Loadable.Ready -> content(state.value)
    }
}

@Composable
fun permissionModeLabel(mode: String?): String = when (mode) {
    "acceptEdits" -> stringResource(R.string.perm_accept_edits_short)
    "plan" -> stringResource(R.string.perm_plan_short)
    "bypassPermissions" -> stringResource(R.string.perm_bypass_short)
    "default", null -> stringResource(R.string.perm_default_short)
    else -> stringResource(R.string.perm_unknown, mode)
}

@Composable
private fun permissionModeSummary(mode: String): String = when (mode) {
    "acceptEdits" -> stringResource(R.string.perm_accept_edits)
    "plan" -> stringResource(R.string.perm_plan)
    "bypassPermissions" -> stringResource(R.string.perm_bypass)
    else -> stringResource(R.string.perm_default)
}

/** The four modes the phone offers, the current one selected; a switch in flight shows a spinner. */
@Composable
fun PermissionModeOptions(current: String?, pending: String?, enabled: Boolean, onSelect: (String) -> Unit) {
    val modes = ClaudeTools.PERMISSION_MODES
    GroupCard {
        modes.forEachIndexed { i, mode ->
            io.github.suqi8.coui.kmp.preference.RadioButtonPreference(
                title = permissionModeLabel(mode),
                summary = permissionModeSummary(mode),
                selected = (current ?: "default") == mode,
                onClick = { if (pending == null && mode != (current ?: "default")) onSelect(mode) },
                enabled = enabled && pending == null,
                startAction = if (pending == mode) {
                    { RotatingProgressIndicator(color = COUITheme.colorScheme.primary, size = 18.dp, ringDiameter = 14.4.dp, strokeWidth = 2.dp, modifier = Modifier.padding(end = 10.dp)) }
                } else null,
                cardListPosition = cardListPositionOf(i, modes.size),
                modifier = Modifier.testTag("perm-$mode"),
            )
        }
    }
}

/** Quick switch from the composer's mode capsule. */
@Composable
fun PermissionModeSheet(show: Boolean, current: String?, pending: String?, error: String?, enabled: Boolean, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    AppBottomSheet(show = show, title = stringResource(R.string.tools_permission), onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            PermissionModeOptions(current, pending, enabled, onSelect)
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
    onReconnect: () -> Unit,
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
        startAction = { StatusDot(color, Modifier.padding(end = 12.dp)) },
        endActions = {
            TextButton(
                text = stringResource(if (reconnecting) R.string.tools_mcp_reconnecting else R.string.tools_mcp_reconnect),
                onClick = { if (!reconnecting) onReconnect() },
                enabled = enabled && !reconnecting,
            )
        },
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
        Button(
            onClick = { onAsk(question) },
            enabled = enabled && !st.asking && question.isNotBlank(),
            colors = ButtonDefaults.buttonColorsPrimary(),
            modifier = Modifier.fillMaxWidth().testTag("btw-ask"),
        ) {
            if (st.asking) RotatingProgressIndicator(color = COUITheme.colorScheme.onPrimary, size = 18.dp, ringDiameter = 14.4.dp, strokeWidth = 2.dp)
            else Text(stringResource(R.string.tools_side_question_ask), color = COUITheme.colorScheme.onPrimary)
        }
        if (st.asking) {
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
            TextButton(
                text = stringResource(R.string.tools_rename_save),
                onClick = { onSave(title) },
                enabled = enabled && !st.renaming && title.isNotBlank() && title.trim() != current && title.trim().length <= 200,
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
    io.github.suqi8.coui.kmp.window.WindowAlertDialog(
        show = state != null,
        title = stringResource(R.string.rewind_title),
        summary = stringResource(R.string.rewind_explain),
        confirmText = stringResource(if (state?.applying == true) R.string.rewind_applying else R.string.rewind_confirm),
        cancelText = stringResource(R.string.action_cancel),
        onConfirm = { if (canConfirm) onConfirm() },
        onCancel = onDismiss,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp).testTag("rewind-dialog"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
