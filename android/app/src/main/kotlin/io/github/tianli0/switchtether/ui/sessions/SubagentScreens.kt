package io.github.tianli0.switchtether.ui.sessions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.squircle.SquircleShape
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.tianli0.switchtether.R
import io.github.tianli0.switchtether.domain.SessionRepository
import io.github.tianli0.switchtether.remote.protocol.CapStatus
import io.github.tianli0.switchtether.remote.protocol.EventKinds
import io.github.tianli0.switchtether.remote.session.AgentEventLog
import io.github.tianli0.switchtether.remote.session.SubagentNode
import io.github.tianli0.switchtether.remote.session.SubagentPage
import io.github.tianli0.switchtether.remote.session.SubagentTree
import io.github.tianli0.switchtether.ui.appContainer
import io.github.tianli0.switchtether.ui.common.Formatters
import io.github.tianli0.switchtether.ui.common.Loadable
import io.github.tianli0.switchtether.ui.common.LoadableContent
import io.github.tianli0.switchtether.ui.common.Page
import io.github.tianli0.switchtether.ui.common.loadable
import io.github.tianli0.switchtether.ui.common.pageListPadding
import io.github.tianli0.switchtether.ui.common.pageScroll
import io.github.tianli0.switchtether.ui.common.rememberPageListState
import io.github.tianli0.switchtether.ui.components.PageCard
import io.github.tianli0.switchtether.ui.containerViewModel
import io.github.tianli0.switchtether.ui.icons.AppIcons
import io.github.tianli0.switchtether.ui.nav.Route
import io.github.tianli0.switchtether.ui.sessions.render.AgentEventRegistry
import io.github.tianli0.switchtether.ui.sessions.render.RenderContext
import io.github.tianli0.switchtether.ui.sessions.render.SubagentPreview
import io.github.tianli0.switchtether.ui.theme.DevType
import io.github.tianli0.switchtether.ui.theme.LocalDevColors
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Agents whose sub-agents can be opened (desktop `…/subagents` endpoints). */
val SUBAGENT_AGENTS = setOf("claude_code", "codex", "dsh")

/** How long a sub-agent without `finished` still counts as working after its last write. */
private const val ACTIVE_WINDOW_MS = 120_000L

/**
 * Running: the live status when the desktop knows it (the session log's row); otherwise not
 * marked finished and written to recently (Claude Code sets `finished` at the end).
 */
fun SubagentNode.isRunning(now: Long = System.currentTimeMillis()): Boolean =
    status?.let { it == "running" }
        ?: (finished != true && (updatedAtMs ?: 0L) > now - ACTIVE_WINDOW_MS)

data class SubagentState(
    val page: Loadable<SubagentPage> = Loadable.Loading,
    val tree: List<SubagentNode> = emptyList(),
    val collapsed: Set<String> = emptySet(),
)

/**
 * One Claude Code sub-agent's own steps (read-only). Follows a running sub-agent by polling the
 * desktop, which tails the sub-agent's transcript; stops once it is finished or the page closes.
 */
class SubagentViewModel(
    private val sessionId: String,
    private val agentId: String,
    private val repo: SessionRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(SubagentState())
    val state: StateFlow<SubagentState> = _state.asStateFlow()

    val previews = mutableStateMapOf<String, SubagentPreview>()
    private val previewAt = HashMap<String, Long>()

    init {
        viewModelScope.launch {
            var round = 0
            while (isActive) {
                refresh(withTree = round % 5 == 0)
                round++
                val me = _state.value.tree.firstOrNull { it.agentId == agentId }
                val meta = _state.value.page.valueOrNull?.meta
                val running = (me ?: meta)?.let { it.finished != true && (me?.isRunning() ?: true) } ?: false
                if (!running && _state.value.page is Loadable.Ready) {
                    if (round == 1) refresh(withTree = true) // children list once more, then stop
                    break
                }
                delay(if (_state.value.page is Loadable.Failed) 5_000 else 2_000)
            }
        }
    }

    fun retry() {
        _state.value = _state.value.copy(page = Loadable.Loading)
        viewModelScope.launch { refresh(withTree = true) }
    }

    fun toggleCollapse(key: String) {
        val c = _state.value.collapsed
        _state.value = _state.value.copy(collapsed = if (key in c) c - key else c + key)
    }

    private suspend fun refresh(withTree: Boolean) {
        val treeJob = if (withTree) viewModelScope.async { runCatching { repo.subagentTree(sessionId) }.getOrNull() } else null
        val page = loadable { repo.subagentEvents(sessionId, agentId, 400) }
        val tree = treeJob?.await()
        val cur = _state.value
        _state.value = cur.copy(
            // Keep what is shown when one poll fails.
            page = if (page is Loadable.Failed && cur.page is Loadable.Ready) cur.page else page,
            tree = tree ?: cur.tree,
        )
    }

    fun loadPreview(child: String) {
        val now = System.currentTimeMillis()
        if (now - (previewAt[child] ?: 0L) < 2_500) return
        previewAt[child] = now
        viewModelScope.launch {
            try {
                previews[child] = SubagentPreview.of(repo.subagentEvents(sessionId, child, 200).events)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (previews[child] == null) previews[child] = SubagentPreview(emptyList(), 0, error = true)
            }
        }
    }
}

@Composable
fun SubagentScreen(sessionId: String, agentId: String, title: String?, onBack: () -> Unit, onOpen: (Route) -> Unit, agent: String = "claude_code") {
    val container = appContainer()
    val vm = containerViewModel(key = "subagent:$sessionId:$agentId") { SubagentViewModel(sessionId, agentId, it.sessions) }
    val state by vm.state.collectAsStateWithLifecycle()
    val online by container.connection.state.collectAsStateWithLifecycle()
    val meta = state.page.valueOrNull?.meta
    val me = state.tree.firstOrNull { it.agentId == agentId } ?: meta
    val running = me?.let { it.finished != true && (state.tree.firstOrNull { n -> n.agentId == agentId }?.isRunning() ?: true) } ?: false
    val heading = me?.description?.takeIf { it.isNotBlank() } ?: title ?: stringResource(R.string.render_subagent)
    val subtitle = listOfNotNull(
        me?.agentType?.takeIf { it.isNotBlank() },
        if (running) stringResource(R.string.render_status_running) else if (me?.finished == true) stringResource(R.string.step_completed) else null,
    ).joinToString(" · ")
    val ctx = RenderContext(
        agent = agent,
        agentLabel = Formatters.agentLabel(agent),
        // A sub-agent takes no input from outside: everything here is read-only.
        approve = CapStatus.BLOCKED,
        answer = CapStatus.BLOCKED,
        capabilityReason = null,
        online = online is io.github.tianli0.switchtether.connection.ConnectionState.Online,
        onApprove = { _, _, _ -> },
        onAnswer = { _, _ -> },
        onLoadPayload = {},
        payloadOf = { null },
        onToggleCollapse = vm::toggleCollapse,
        isCollapsed = { it in vm.state.value.collapsed },
        onLink = {},
        onOpenSubagent = { aid, t -> onOpen(Route.Subagent(sessionId, aid, t, agent)) },
        subagentPreview = { vm.previews[it] },
        onWantSubagentPreview = vm::loadPreview,
    )
    Page(title = heading, subtitle = subtitle, onBack = onBack, immersive = true) {
        LoadableContent(state.page, onRetry = vm::retry) { page ->
            val items = AgentEventLog.of(page.events).renderItems(state.collapsed)
                .filter { it.event.kind != EventKinds.STATUS }
            val trail = SubagentTree.trail(state.tree, agentId)
            val children = SubagentTree.flatten(state.tree).filter { it.node.parentAgentId == agentId }
            LazyColumn(state = rememberPageListState(), modifier = Modifier.fillMaxSize().pageScroll(), contentPadding = pageListPadding()) {
                item(key = "trail") { Breadcrumb(sessionId, agent, trail, heading, onOpen) }
                if (page.hasMoreBefore) {
                    item(key = "older") { Note(stringResource(R.string.subagent_older_omitted)) }
                }
                if (items.isEmpty()) {
                    item(key = "empty") { Note(stringResource(if (running) R.string.subagent_starting else R.string.subagent_no_steps)) }
                }
                items(items, key = { it.key }) { item ->
                    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).padding(start = 12.dp * item.depth.coerceAtMost(4))) {
                        AgentEventRegistry.rendererFor(item.event.kind)(item, ctx)
                    }
                }
                if (children.isNotEmpty()) {
                    item(key = "children") {
                        Text(
                            stringResource(R.string.subagent_children),
                            style = COUITheme.textStyles.footnote1,
                            color = COUITheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 6.dp),
                        )
                        PageCard { children.forEachIndexed { i, b -> BranchRow(b.node, last = i == children.lastIndex) { onOpen(Route.Subagent(sessionId, b.node.agentId, b.node.description, agent)) } } }
                    }
                }
                if (running) item(key = "live") { Note(stringResource(R.string.subagent_following)) }
            }
        }
    }
}

/** "主会话 › 父 agent › 当前": where this branch hangs; ancestors open their own page. */
@Composable
private fun Breadcrumb(sessionId: String, agent: String, trail: List<SubagentNode>, current: String, onOpen: (Route) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp).testTag("subagent-trail"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val crumbs = buildList {
            add(stringResource(R.string.subagent_main_session) to { onOpen(Route.SubagentTree(sessionId, agent)) })
            trail.forEach { n -> add((n.description ?: n.agentType ?: n.agentId) to { onOpen(Route.Subagent(sessionId, n.agentId, n.description, agent)) }) }
        }
        crumbs.forEach { (label, go) ->
            Text(
                label,
                style = COUITheme.textStyles.footnote1,
                color = COUITheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).clip(SquircleShape(6.dp)).clickable(role = Role.Button, onClick = go).padding(2.dp),
            )
            Text(" › ", style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurfaceVariantSummary)
        }
        Text(current, style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp))
}

/** A row of the branch tree: connector glyphs, status dot, description, type and time. */
@Composable
private fun BranchRow(node: SubagentNode, last: Boolean, onClick: () -> Unit) {
    val dev = LocalDevColors.current
    val running = node.isRunning()
    Row(
        Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp).testTag("branch-${node.agentId}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TreeGutter(depth = 0, rails = emptyList(), last = last)
        val dot = if (running) dev.info else if (node.finished == true) dev.ok else dev.neutral
        androidx.compose.foundation.Canvas(Modifier.size(8.dp)) { drawCircle(dot) }
        Spacer(Modifier.size(8.dp))
        Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
            Text(
                node.description?.takeIf { it.isNotBlank() } ?: node.agentType ?: node.agentId,
                style = COUITheme.textStyles.body2,
                color = COUITheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = listOfNotNull(
                node.agentType?.takeIf { it.isNotBlank() },
                if (running) stringResource(R.string.render_status_running) else if (node.finished == true) stringResource(R.string.step_completed) else stringResource(R.string.subagent_unfinished),
                node.updatedAtMs?.let { io.github.tianli0.switchtether.ui.common.relativeTime(it) },
            ).joinToString(" · ")
            Text(sub, style = COUITheme.textStyles.footnote2, color = COUITheme.colorScheme.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(AppIcons.ChevronRight, contentDescription = null, tint = COUITheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.size(16.dp))
    }
}

