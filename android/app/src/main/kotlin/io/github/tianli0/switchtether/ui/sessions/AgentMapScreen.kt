package io.github.tianli0.switchtether.ui.sessions

import io.github.tianli0.switchtether.ui.components.ExpandBody
import io.github.tianli0.switchtether.ui.common.TextGutter
import io.github.tianli0.switchtether.ui.components.ActionTone
import io.github.tianli0.switchtether.ui.components.SmallButton
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.tianli0.switchtether.R
import io.github.tianli0.switchtether.domain.SessionRepository
import io.github.tianli0.switchtether.remote.command.CommandOutcome
import io.github.tianli0.switchtether.remote.session.AgentMap
import io.github.tianli0.switchtether.remote.session.BackgroundTaskNode
import io.github.tianli0.switchtether.remote.session.SubagentNode
import io.github.tianli0.switchtether.remote.session.SubagentTree
import io.github.tianli0.switchtether.ui.common.Loadable
import io.github.tianli0.switchtether.ui.common.LoadableContent
import io.github.tianli0.switchtether.ui.common.Page
import io.github.tianli0.switchtether.ui.common.loadable
import io.github.tianli0.switchtether.ui.common.pageListPadding
import io.github.tianli0.switchtether.ui.common.pageScroll
import io.github.tianli0.switchtether.ui.common.rememberPageListState
import io.github.tianli0.switchtether.ui.components.PageCard
import io.github.tianli0.switchtether.ui.containerViewModel
import io.github.tianli0.switchtether.ui.nav.Route
import io.github.tianli0.switchtether.ui.theme.DevType
import io.github.tianli0.switchtether.ui.theme.LocalDevColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * State of one agent on the map, as Claude Code's Agent map names them: waiting for a
 * permission, working, finished, failed, stopped.
 */
enum class AgentMapState { Waiting, Working, Finished, Failed, Stopped }

fun SubagentNode.mapState(): AgentMapState = when {
    isRunning() && waiting == true -> AgentMapState.Waiting
    isRunning() -> AgentMapState.Working
    status == "failed" -> AgentMapState.Failed
    status == "interrupted" -> AgentMapState.Stopped
    else -> AgentMapState.Finished
}

fun BackgroundTaskNode.mapState(): AgentMapState = when (status) {
    "running", "pending" -> AgentMapState.Working
    "failed" -> AgentMapState.Failed
    "interrupted" -> AgentMapState.Stopped
    else -> AgentMapState.Finished
}

/** Epoch ms of an RFC 3339 time, or null. */
internal fun rfcMs(ts: String?): Long? = ts?.let { runCatching { java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }

/** "42s", "3m 05s", "1h 02m". */
internal fun compactDuration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "%dm %02ds".format(s / 60, s % 60)
        else -> "%dh %02dm".format(s / 3600, (s % 3600) / 60)
    }
}

/** How long it ran (live while working): the reported duration, else start → finish / now. */
internal fun agentElapsed(n: SubagentNode, now: Long): Long? {
    val start = rfcMs(n.startedAt)
    if (n.isRunning()) return start?.let { now - it } ?: n.durationMs
    return n.durationMs?.takeIf { it > 0 } ?: start?.let { s -> rfcMs(n.finishedAt)?.let { it - s } }
}

internal fun compactTokens(t: Long): String = when {
    t >= 1_000_000 -> "%.1fM".format(t / 1_000_000.0)
    t >= 1_000 -> "%.1fk".format(t / 1_000.0)
    else -> t.toString()
}

/** Output of a background shell task (Claude Code session tools `task_output`). */
data class TaskOutputState(
    val output: io.github.tianli0.switchtether.remote.session.TaskOutput? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

class AgentMapViewModel(private val sessionId: String, private val repo: SessionRepository) : ViewModel() {
    private val _map = MutableStateFlow<Loadable<AgentMap>>(Loadable.Loading)
    val map: StateFlow<Loadable<AgentMap>> = _map.asStateFlow()

    /** The session offers Claude Code session tools (task output); from the session detail. */
    val claudeTools = MutableStateFlow(false)

    /** Loaded background task outputs by task id (memory only). */
    val taskOutputs = androidx.compose.runtime.mutableStateMapOf<String, TaskOutputState>()

    fun loadTaskOutput(taskId: String) {
        val cur = taskOutputs[taskId]
        if (cur?.loading == true) return
        taskOutputs[taskId] = (cur ?: TaskOutputState()).copy(loading = true)
        viewModelScope.launch {
            val r = repo.runClaudeTool(sessionId, io.github.tianli0.switchtether.remote.session.ClaudeTools.TASK_OUTPUT, jsonOf("taskId" to taskId))
            val prev = taskOutputs[taskId]
            taskOutputs[taskId] = r.fold(
                { TaskOutputState(io.github.tianli0.switchtether.remote.session.ClaudeTools.parseTaskOutput(it)) },
                { TaskOutputState(prev?.output, error = toolErrorMessage(it)) },
            )
        }
    }
    /** Task ids being stopped, and the ones that could not be. */
    val stopping = MutableStateFlow<Set<String>>(emptySet())
    val stopFailed = MutableStateFlow<Set<String>>(emptySet())

    init {
        viewModelScope.launch {
            // Task output: full tools, or read-only from the output file for an observed session.
            claudeTools.value = runCatching { repo.detail(sessionId) }.getOrNull()?.let { it.claudeTools || it.claudeReadTools } == true
        }
        viewModelScope.launch {
            while (isActive) {
                refresh()
                // Follow the map while anything on it is still working.
                val m = _map.value.valueOrNull
                val working = m != null && (m.agents.any { it.isRunning() } || m.backgroundTasks.any { it.mapState() == AgentMapState.Working })
                if (!working && _map.value is Loadable.Ready) {
                    delay(10_000) // a finished map can still grow (a new turn)
                } else {
                    delay(2_000)
                }
            }
        }
    }

    private suspend fun refresh() {
        val next = loadable { repo.agentMap(sessionId) }
        if (next is Loadable.Ready || _map.value !is Loadable.Ready) _map.value = next
    }

    fun retry() {
        _map.value = Loadable.Loading
        viewModelScope.launch { refresh() }
    }

    fun stop(taskId: String) {
        stopping.value = stopping.value + taskId
        stopFailed.value = stopFailed.value - taskId
        viewModelScope.launch {
            val ok = runCatching { repo.stopSubagent(sessionId, taskId) }.getOrNull() is CommandOutcome.Succeeded
            if (!ok) stopFailed.value = stopFailed.value + taskId
            refresh()
            stopping.value = stopping.value - taskId
        }
    }
}

/**
 * The Agent map (Claude Code's agents view, for the phone): the session at the root, every
 * sub-agent under the agent that started it with its live state, then the background tasks.
 * Tapping a row opens its card in place: what it does now or why it waits, its result or
 * failure, the prompt, its transcript, and Stop for a session SwitchTether started.
 */
@Composable
fun SubagentTreeScreen(sessionId: String, onBack: () -> Unit, onOpen: (Route) -> Unit, agent: String = "claude_code") {
    val vm = containerViewModel(key = "agent-map:$sessionId") { AgentMapViewModel(sessionId, it.sessions) }
    val map by vm.map.collectAsStateWithLifecycle()
    val stopping by vm.stopping.collectAsStateWithLifecycle()
    val stopFailed by vm.stopFailed.collectAsStateWithLifecycle()
    val claudeTools by vm.claudeTools.collectAsStateWithLifecycle()
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    // Durations of working agents tick.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    val n = map.valueOrNull?.agents?.size ?: 0
    Page(
        title = stringResource(R.string.agent_map_title),
        subtitle = if (n > 0) pluralStringResource(R.plurals.agent_map_subtitle, n, n) else "",
        onBack = onBack,
        immersive = true,
    ) {
        LoadableContent(map, onRetry = vm::retry) { m ->
            val branches = SubagentTree.flatten(m.agents)
            val byId = m.agents.associateBy { it.agentId }
            LazyColumn(state = rememberPageListState(), modifier = Modifier.fillMaxSize().pageScroll(), contentPadding = pageListPadding()) {
                item {
                    PageCard {
                        Column {
                            MainRow(m.agents, hasChildren = branches.isNotEmpty())
                            if (branches.isEmpty()) {
                                MapNote(stringResource(R.string.subagent_tree_empty))
                            }
                            branches.forEach { b ->
                                val node = b.node
                                val open = selected == "a:${node.agentId}"
                                val hasChildren = branches.getOrNull(branches.indexOf(b) + 1)?.let { it.depth > b.depth } == true
                                AgentRow(node, b.depth, b.rails, b.last, hasChildren && !open, now) {
                                    selected = if (open) null else "a:${node.agentId}"
                                }
                                ExpandBody(open) {
                                    val taskId = node.agentId
                                    AgentCard(
                                        node = node,
                                        spawnedBy = node.parentAgentId?.let { byId[it] }?.let { it.description ?: it.agentType ?: it.agentId },
                                        canStop = m.stoppable && node.isRunning(),
                                        stopping = taskId in stopping,
                                        stopFailed = taskId in stopFailed,
                                        onOpenTranscript = { onOpen(Route.Subagent(sessionId, node.agentId, node.description, agent)) },
                                        onStop = { vm.stop(taskId) },
                                    )
                                }
                            }
                        }
                    }
                }
                if (m.backgroundTasks.isNotEmpty()) {
                    item {
                        val k = m.backgroundTasks.size
                        MapNote(pluralStringResource(R.plurals.agent_map_tasks, k, k))
                    }
                    item {
                        PageCard {
                            Column {
                                m.backgroundTasks.forEach { t ->
                                    val key = "t:${t.taskId ?: t.callId}"
                                    val open = selected == key
                                    TaskRow(t, now) { selected = if (open) null else key }
                                    ExpandBody(open) {
                                        val taskId = t.taskId
                                        TaskCard(
                                            task = t,
                                            canStop = m.stoppable && taskId != null && t.mapState() == AgentMapState.Working,
                                            stopping = taskId != null && taskId in stopping,
                                            stopFailed = taskId != null && taskId in stopFailed,
                                            onStop = { taskId?.let(vm::stop) },
                                            output = if (claudeTools && taskId != null) taskId.let { id -> vm.taskOutputs[id] ?: TaskOutputState() } else null,
                                            onLoadOutput = { taskId?.let(vm::loadTaskOutput) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                if (!m.stoppable && m.agents.any { it.isRunning() }) {
                    item { MapNote(stringResource(R.string.agent_map_observe_only)) }
                }
            }
        }
    }
}

@Composable
private fun stateColor(s: AgentMapState): Color {
    val dev = LocalDevColors.current
    return when (s) {
        AgentMapState.Waiting -> dev.warn
        AgentMapState.Working -> dev.info
        AgentMapState.Finished -> dev.ok
        AgentMapState.Failed -> dev.danger
        AgentMapState.Stopped -> dev.neutral
    }
}

@Composable
fun agentStateLabel(s: AgentMapState): String = stringResource(
    when (s) {
        AgentMapState.Waiting -> R.string.agent_map_state_waiting
        AgentMapState.Working -> R.string.agent_map_state_working
        AgentMapState.Finished -> R.string.agent_map_state_finished
        AgentMapState.Failed -> R.string.agent_map_state_failed
        AgentMapState.Stopped -> R.string.agent_map_state_stopped
    },
)

@Composable
private fun Dot(c: Color) {
    Canvas(Modifier.size(8.dp)) { drawCircle(c) }
}

@Composable
private fun MapNote(text: String) {
    Text(text, style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(horizontal = TextGutter, vertical = 10.dp))
}

/** The session itself, the root of the tree. */
@Composable
private fun MainRow(agents: List<SubagentNode>, hasChildren: Boolean) {
    val working = agents.any { it.isRunning() }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        TreeGutter(depth = -1, rails = emptyList(), last = true)
        StemDot(if (working) LocalDevColors.current.info else LocalDevColors.current.neutral, stem = hasChildren)
        Spacer(Modifier.size(8.dp))
        Text(stringResource(R.string.agent_map_this_session), style = COUITheme.textStyles.body2, color = COUITheme.colorScheme.onSurface, modifier = Modifier.weight(1f).padding(vertical = 12.dp))
        val counts = AgentMapState.entries.mapNotNull { s ->
            agents.count { it.mapState() == s }.takeIf { it > 0 }?.let { "$it ${agentStateLabel(s)}" }
        }.joinToString(" · ")
        Text(counts, style = COUITheme.textStyles.footnote2, color = COUITheme.colorScheme.onSurfaceVariantSummary, maxLines = 1)
    }
}

/** Width of one tree level; level lines sit on the status dots of the level above. */
private val TreeLevel = 18.dp

/** A status dot in a full-height slot; with [stem], a line runs from under it to the row's bottom
 *  edge, where the first child's elbow starts. */
@Composable
private fun StemDot(color: Color, stem: Boolean) {
    val line = COUITheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.45f)
    Box(
        Modifier.width(DotSize).fillMaxHeight().drawBehind {
            if (stem) {
                val x = size.width / 2
                drawLine(line, Offset(x, size.height / 2 + size.width / 2 + 2.dp.toPx()), Offset(x, size.height), 1.dp.toPx())
            }
        },
        contentAlignment = Alignment.Center,
    ) { Dot(color) }
}
private val DotSize = 8.dp

/**
 * Tree connectors drawn as lines (not box-drawing glyphs, which never meet across rows): a
 * guide for every ancestor that has later siblings, this row's elbow into its dot (`├` or
 * `└`). Spans the whole row height; the line down to a row's children is [StemDot].
 */
@Composable
internal fun TreeGutter(depth: Int, rails: List<Boolean>, last: Boolean) {
    val color = COUITheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.45f)
    val levels = depth + 1 // gutter levels before this row's dot
    Canvas(Modifier.width(TreeLevel * levels).fillMaxHeight()) {
        val stroke = 1.dp.toPx()
        val half = (DotSize / 2).toPx()
        val levelPx = TreeLevel.toPx()
        val mid = size.height / 2
        fun x(level: Int) = level * levelPx + half
        rails.forEachIndexed { k, more ->
            if (more) drawLine(color, Offset(x(k), 0f), Offset(x(k), size.height), stroke)
        }
        if (depth >= 0) {
            val xe = x(depth)
            drawLine(color, Offset(xe, 0f), Offset(xe, if (last) mid else size.height), stroke)
            drawLine(color, Offset(xe, mid), Offset(size.width, mid), stroke)
        }
    }
}

@Composable
private fun AgentRow(node: SubagentNode, depth: Int, rails: List<Boolean>, last: Boolean, childLine: Boolean, now: Long, onClick: () -> Unit) {
    val st = node.mapState()
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp).testTag("agent-map-${node.agentId}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TreeGutter(depth, rails, last)
        StemDot(stateColor(st), stem = childLine)
        Spacer(Modifier.size(8.dp))
        Column(Modifier.weight(1f).padding(vertical = 9.dp)) {
            Text(
                node.description?.takeIf { it.isNotBlank() } ?: node.agentType ?: node.agentId,
                style = COUITheme.textStyles.body2,
                color = COUITheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val line = when (st) {
                AgentMapState.Waiting -> stringResource(R.string.agent_map_waiting_permission)
                AgentMapState.Working -> node.activity
                else -> null
            }
            val meta = listOfNotNull(
                line ?: agentStateLabel(st),
                agentElapsed(node, now)?.takeIf { it >= 1000 }?.let(::compactDuration),
                node.tokens?.takeIf { it > 0 }?.let { stringResource(R.string.agent_map_tokens, compactTokens(it)) },
            ).joinToString(" · ")
            Text(meta, style = COUITheme.textStyles.footnote2, color = if (st == AgentMapState.Waiting) LocalDevColors.current.warn else COUITheme.colorScheme.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun CardText(text: String, color: Color = COUITheme.colorScheme.onSurfaceSecondary) {
    Text(text, style = COUITheme.textStyles.footnote1, color = color)
}

/** The card of one agent (Claude Code's agent details). */
@Composable
private fun AgentCard(
    node: SubagentNode,
    spawnedBy: String?,
    canStop: Boolean,
    stopping: Boolean,
    stopFailed: Boolean,
    onOpenTranscript: () -> Unit,
    onStop: () -> Unit,
) {
    val dev = LocalDevColors.current
    val st = node.mapState()
    var promptOpen by rememberSaveable(node.agentId) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(start = 40.dp, end = 14.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        CardText(
            listOfNotNull(
                agentStateLabel(st),
                node.agentType?.takeIf { it.isNotBlank() },
                node.model?.takeIf { it.isNotBlank() },
                node.background?.let { stringResource(if (it) R.string.agent_map_background else R.string.agent_map_foreground) },
                node.toolUses?.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.agent_map_tool_uses, it.toInt(), it.toInt()) },
            ).joinToString(" · "),
        )
        CardText(stringResource(R.string.agent_map_spawned_by, spawnedBy ?: stringResource(R.string.agent_map_this_session)))
        when (st) {
            AgentMapState.Waiting -> CardText(stringResource(R.string.agent_map_waiting_detail), dev.warn)
            AgentMapState.Working -> node.activity?.let { CardText(it, COUITheme.colorScheme.onSurface) }
            AgentMapState.Failed, AgentMapState.Stopped -> node.summary?.takeIf { it != node.description }?.let { CardText(it, dev.danger) }
            AgentMapState.Finished -> {}
        }
        (node.result ?: node.summary.takeIf { st == AgentMapState.Finished })?.takeIf { it.isNotBlank() }?.let {
            SelectionContainer { Text(it, style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurface, maxLines = 12, overflow = TextOverflow.Ellipsis) }
        }
        node.prompt?.takeIf { it.isNotBlank() }?.let { p ->
            Text(
                stringResource(if (promptOpen) R.string.agent_map_hide_prompt else R.string.agent_map_show_prompt),
                style = COUITheme.textStyles.footnote1,
                color = COUITheme.colorScheme.primary,
                modifier = Modifier.clickable(role = Role.Button) { promptOpen = !promptOpen },
            )
            if (promptOpen) SelectionContainer { Text(p, style = DevType.code, color = COUITheme.colorScheme.onSurfaceSecondary) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallButton(stringResource(R.string.agent_map_open_transcript), onOpenTranscript)
            if (canStop) {
                SmallButton(
                    text = stringResource(if (stopping) R.string.agent_map_stopping else R.string.agent_map_stop_agent),
                    onClick = { if (!stopping) onStop() },
                    enabled = !stopping,
                    tone = ActionTone.Danger,
                    modifier = Modifier.testTag("agent-map-stop"),
                )
            }
        }
        if (stopFailed) CardText(stringResource(R.string.agent_map_stop_failed), dev.danger)
    }
}

@Composable
private fun TaskRow(t: BackgroundTaskNode, now: Long, onClick: () -> Unit) {
    val st = t.mapState()
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Dot(stateColor(st))
        Spacer(Modifier.size(8.dp))
        Column(Modifier.weight(1f)) {
            Text(t.title ?: t.taskId.orEmpty(), style = COUITheme.textStyles.body2, color = COUITheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val start = rfcMs(t.startedAt)
            val end = rfcMs(t.finishedAt) ?: if (st == AgentMapState.Working) now else null
            val meta = listOfNotNull(
                agentStateLabel(st),
                if (start != null && end != null && end - start >= 1000) compactDuration(end - start) else null,
                t.exitCode?.let { stringResource(R.string.agent_map_exit_code, it.toInt()) },
            ).joinToString(" · ")
            Text(meta, style = COUITheme.textStyles.footnote2, color = COUITheme.colorScheme.onSurfaceVariantSummary, maxLines = 1)
        }
    }
}

@Composable
private fun TaskCard(
    task: BackgroundTaskNode,
    canStop: Boolean,
    stopping: Boolean,
    stopFailed: Boolean,
    onStop: () -> Unit,
    /** Claude Code session tools: the task's output (null = not offered). */
    output: TaskOutputState? = null,
    onLoadOutput: () -> Unit = {},
) {
    var showOutput by rememberSaveable(task.taskId) { mutableStateOf(false) }
    val running = task.mapState() == AgentMapState.Working
    // Follow the output while the task runs and the output is shown.
    LaunchedEffect(showOutput, running) {
        if (!showOutput) return@LaunchedEffect
        onLoadOutput()
        while (running) {
            delay(2_000)
            onLoadOutput()
        }
    }
    Column(Modifier.fillMaxWidth().padding(start = 30.dp, end = 14.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        task.summary?.let { CardText(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (output != null) {
                SmallButton(
                    text = stringResource(if (showOutput) R.string.tools_task_output_hide else R.string.tools_task_output),
                    onClick = { showOutput = !showOutput },
                    modifier = Modifier.testTag("task-output"),
                )
            }
            if (canStop) {
                SmallButton(stringResource(if (stopping) R.string.agent_map_stopping else R.string.agent_map_stop_task), { if (!stopping) onStop() }, enabled = !stopping, tone = ActionTone.Danger)
            }
        }
        if (stopFailed) CardText(stringResource(R.string.agent_map_stop_failed), LocalDevColors.current.danger)
        if (showOutput && output != null) TaskOutputBlock(output)
    }
}

/** The newest output of a background shell task, monospace, newest lines visible. */
@Composable
private fun TaskOutputBlock(st: TaskOutputState) {
    val dev = LocalDevColors.current
    val out = st.output
    when {
        out == null && st.loading -> io.github.suqi8.coui.kmp.basic.RotatingProgressIndicator(color = COUITheme.colorScheme.primary, size = 18.dp, ringDiameter = 14.4.dp, strokeWidth = 2.dp)
        out == null -> CardText(st.error.orEmpty(), dev.danger)
        else -> {
            val total = out.totalBytes
            if (out.truncated && total != null) {
                CardText(
                    stringResource(
                        R.string.tools_task_output_truncated,
                        io.github.tianli0.switchtether.ui.common.Formatters.bytes(out.output.toByteArray().size.toLong()),
                        io.github.tianli0.switchtether.ui.common.Formatters.bytes(total),
                    ),
                )
            }
            val vertical = androidx.compose.foundation.rememberScrollState()
            LaunchedEffect(out.output.length) { vertical.scrollTo(vertical.maxValue) }
            androidx.compose.foundation.layout.Box(
                Modifier.fillMaxWidth().heightIn(max = 360.dp)
                    .clip(io.github.suqi8.coui.kmp.squircle.SquircleShape(10.dp))
                    .background(dev.codeBackground)
                    .verticalScroll(vertical),
            ) {
                SelectionContainer {
                    Text(
                        out.output.ifEmpty { stringResource(R.string.tools_task_output_empty) },
                        style = DevType.code,
                        color = dev.codeText,
                        modifier = Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(10.dp),
                        softWrap = false,
                    )
                }
            }
            st.error?.let { CardText(it, dev.danger) }
        }
    }
}
