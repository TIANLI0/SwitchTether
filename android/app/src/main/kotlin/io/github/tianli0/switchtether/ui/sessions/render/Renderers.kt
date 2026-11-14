package io.github.tianli0.switchtether.ui.sessions.render

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.Button
import io.github.suqi8.coui.kmp.basic.ButtonDefaults
import io.github.suqi8.coui.kmp.basic.Checkbox
import io.github.suqi8.coui.kmp.basic.LinearProgressIndicator
import io.github.suqi8.coui.kmp.basic.RadioButton
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.tianli0.switchtether.R
import io.github.tianli0.switchtether.remote.protocol.CapStatus
import io.github.tianli0.switchtether.remote.protocol.ProtocolJson
import io.github.tianli0.switchtether.remote.protocol.arr
import io.github.tianli0.switchtether.remote.protocol.bool
import io.github.tianli0.switchtether.remote.protocol.double
import io.github.tianli0.switchtether.remote.protocol.long
import io.github.tianli0.switchtether.remote.protocol.obj
import io.github.tianli0.switchtether.remote.protocol.str
import io.github.tianli0.switchtether.remote.session.RenderItem
import io.github.tianli0.switchtether.ui.code.CodeBlockView
import io.github.tianli0.switchtether.ui.common.Formatters
import io.github.tianli0.switchtether.ui.common.capColor
import io.github.tianli0.switchtether.ui.components.StatusPill
import io.github.tianli0.switchtether.ui.diff.DiffView
import io.github.tianli0.switchtether.ui.markdown.MarkdownView
import io.github.tianli0.switchtether.ui.theme.DevColors
import io.github.tianli0.switchtether.ui.theme.DevType
import io.github.tianli0.switchtether.ui.theme.LocalDevColors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val prettyJson = Json(ProtocolJson) { prettyPrint = true }
private const val PREVIEW_CHARS = 4_000

fun prettyJson(e: JsonElement?): String = if (e == null) "" else runCatching { prettyJson.encodeToString(JsonElement.serializer(), e) }.getOrDefault(e.toString())

// ---------------------------------------------------------------------------------------------
// Shared building blocks
// ---------------------------------------------------------------------------------------------

@Composable
private fun RowHeader(label: String, color: Color, ts: String, trailing: (@Composable () -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = DevType.label, fontWeight = FontWeight.SemiBold, color = color)
        Spacer(Modifier.size(6.dp))
        Text(Formatters.clock(ts), style = DevType.label, color = LocalDevColors.current.gutter)
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
private fun DevCard(modifier: Modifier = Modifier, border: Color? = null, content: @Composable () -> Unit) {
    val dev = LocalDevColors.current
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(COUITheme.colorScheme.surfaceContainer)
            .then(if (border != null) Modifier.border(1.dp, border, shape) else Modifier.border(1.dp, dev.codeBackground, shape))
            .padding(10.dp),
    ) { content() }
}

/** Monospace output with truncation and on-demand loading of the full payload. */
@Composable
private fun OutputBlock(eventId: String, text: String, truncated: Boolean, isError: Boolean, bytes: Long?, ctx: RenderContext) {
    val dev = LocalDevColors.current
    val payload = ctx.payloadOf(eventId)
    val shown = payload?.text?.takeIf { it.length > text.length } ?: text
    var showAll by rememberSaveable(eventId) { mutableStateOf(false) }
    val display = if (showAll || shown.length <= PREVIEW_CHARS) shown else shown.take(PREVIEW_CHARS)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(dev.codeBackground)) {
        SelectionContainer {
            Box(Modifier.horizontalScroll(rememberScrollState()).padding(8.dp)) {
                Text(display.ifEmpty { "∅" }, style = DevType.codeSmall, color = if (isError) dev.danger else dev.codeText, softWrap = false)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            if (!showAll && shown.length > PREVIEW_CHARS) {
                TextButton(text = stringResource(R.string.render_show_all), onClick = { showAll = true })
            }
            val serverTruncated = truncated && payload?.complete != true
            if (serverTruncated) {
                TextButton(
                    text = when {
                        payload?.loading == true -> stringResource(R.string.state_loading)
                        else -> stringResource(R.string.render_load_full, bytes?.let(Formatters::bytes) ?: "")
                    },
                    onClick = { ctx.onLoadPayload(eventId) },
                    enabled = ctx.online && payload?.loading != true,
                )
            }
            payload?.error?.let { Text(it, style = DevType.label, color = dev.danger, maxLines = 2) }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Messages
// ---------------------------------------------------------------------------------------------

@Composable
fun UserMessage(item: RenderItem, ctx: RenderContext) {
    val d = item.event.data
    val dev = LocalDevColors.current
    Column(Modifier.fillMaxWidth()) {
        RowHeader(stringResource(R.string.render_you) + (d.str("source")?.takeIf { it != "unknown" }?.let { " · $it" } ?: ""), COUITheme.colorScheme.primary, item.event.ts)
        Box(
            Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(10.dp))
                .background(COUITheme.colorScheme.primary.copy(alpha = if (dev.isDark) 0.18f else 0.08f)).padding(10.dp),
        ) {
            MarkdownView(d.str("text").orEmpty(), onLinkClick = ctx.onLink)
        }
        val images = d["images"].arr()?.size ?: 0
        if (images > 0) Text(stringResource(R.string.render_images, images), style = DevType.label, color = dev.gutter)
    }
}

@Composable
fun AssistantMessage(item: RenderItem, ctx: RenderContext) {
    val d = item.event.data
    Column(Modifier.fillMaxWidth()) {
        RowHeader(
            stringResource(R.string.render_assistant) + (d.str("model")?.let { " · $it" } ?: ""),
            LocalDevColors.current.ok,
            item.event.ts,
            trailing = if (item.event.streaming) ({ StatusPill(stringResource(R.string.render_streaming), LocalDevColors.current.info) }) else null,
        )
        Box(Modifier.padding(top = 4.dp)) { MarkdownView(d.str("text").orEmpty(), onLinkClick = ctx.onLink) }
    }
}

@Composable
fun Reasoning(item: RenderItem) {
    val d = item.event.data
    var open by rememberSaveable(item.key) { mutableStateOf(false) }
    val dev = LocalDevColors.current
    val text = d.str("text").orEmpty()
    Column(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = stringResource(R.string.render_toggle)) { open = !open }
            .semantics { stateDescription = if (open) "expanded" else "collapsed" },
    ) {
        RowHeader(
            (if (open) "▾ " else "▸ ") + stringResource(if (d.bool("summary") == true) R.string.render_reasoning_summary else R.string.render_reasoning),
            dev.gutter,
            item.event.ts,
        )
        Text(
            if (open) text else text.lineSequence().firstOrNull().orEmpty(),
            style = COUITheme.textStyles.body2,
            fontStyle = FontStyle.Italic,
            color = COUITheme.colorScheme.onSurfaceVariantSummary,
            maxLines = if (open) Int.MAX_VALUE else 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Tools
// ---------------------------------------------------------------------------------------------

/** Short monospace tag per tool category (dense developer view). */
fun categoryTag(category: String?): String = when (category) {
    "shell" -> "SH"
    "edit" -> "EDIT"
    "write" -> "WRITE"
    "read" -> "READ"
    "search" -> "FIND"
    "web" -> "WEB"
    "mcp" -> "MCP"
    "task" -> "TASK"
    "todo" -> "TODO"
    "question" -> "ASK"
    "plan" -> "PLAN"
    else -> "TOOL"
}

fun toolStatusColor(status: String?, dev: DevColors): Color = when (status) {
    "completed" -> dev.ok
    "failed" -> dev.danger
    "denied", "interrupted" -> dev.warn
    "running" -> dev.info
    else -> dev.neutral
}

@Composable
fun ToolCall(item: RenderItem, ctx: RenderContext) {
    val d = item.event.data
    val dev = LocalDevColors.current
    var open by rememberSaveable(item.key) { mutableStateOf(false) }
    val status = d.str("status")
    val result = item.attachedResult
    DevCard(border = if (status == "failed") dev.danger.copy(alpha = 0.5f) else null) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 40.dp).clickable(role = Role.Button, onClickLabel = stringResource(R.string.render_toggle)) { open = !open }
                .semantics { stateDescription = if (open) "expanded" else "collapsed" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.clip(RoundedCornerShape(4.dp)).background(dev.codeBackground).padding(horizontal = 5.dp, vertical = 2.dp)) {
                Text(categoryTag(d.str("category")), style = DevType.label, fontWeight = FontWeight.Bold, color = dev.keyword)
            }
            Spacer(Modifier.size(8.dp))
            Column(Modifier.weight(1f)) {
                Text(d.str("title")?.takeIf { it.isNotBlank() } ?: d.str("name").orEmpty(), style = DevType.code, color = COUITheme.colorScheme.onSurface, maxLines = if (open) 6 else 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(d.str("name"), d.str("server")?.let { "@$it" }, Formatters.clock(item.event.ts)).joinToString(" · "), style = DevType.label, color = dev.gutter, maxLines = 1)
            }
            Spacer(Modifier.size(6.dp))
            StatusPill(status ?: "?", toolStatusColor(status, dev))
            if (item.hasChildren) {
                TextButton(
                    text = if (ctx.isCollapsed(item.key)) stringResource(R.string.render_expand_sub) else stringResource(R.string.render_collapse_sub),
                    onClick = { ctx.onToggleCollapse(item.key) },
                )
            }
        }
        if (open) {
            val input = d["input"]
            if (input != null && input.toString() != "{}") {
                Spacer(Modifier.size(6.dp))
                val command = input.obj()?.str("command")
                if (command != null) {
                    CodeBlockView(command, "bash")
                } else {
                    CodeBlockView(prettyJson(input).take(PREVIEW_CHARS * 2), "json")
                }
            }
            if (result != null) {
                Spacer(Modifier.size(6.dp))
                ResultBody(result.id, result.data, ctx)
            } else if (status == "running" || status == "pending") {
                Spacer(Modifier.size(6.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        } else if (result?.data?.bool("isError") == true) {
            Text(result.data.str("text").orEmpty().lineSequence().firstOrNull().orEmpty(), style = DevType.codeSmall, color = dev.danger, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ResultBody(eventId: String, d: JsonObject, ctx: RenderContext) {
    val dev = LocalDevColors.current
    val exit = d.long("exitCode")
    if (exit != null || d.bool("isError") == true) {
        Text(
            listOfNotNull(exit?.let { "exit $it" }, if (d.bool("isError") == true) stringResource(R.string.render_error) else null).joinToString(" · "),
            style = DevType.label,
            color = if (d.bool("isError") == true || (exit ?: 0) != 0L) dev.danger else dev.gutter,
        )
    }
    OutputBlock(eventId, d.str("text").orEmpty(), d.bool("truncated") == true, d.bool("isError") == true, d.long("bytes"), ctx)
}

@Composable
fun ToolResultStandalone(item: RenderItem, ctx: RenderContext) {
    Column(Modifier.fillMaxWidth()) {
        RowHeader(stringResource(R.string.render_tool_result), LocalDevColors.current.gutter, item.event.ts)
        ResultBody(item.event.id, item.event.data, ctx)
    }
}

@Composable
fun FileChange(item: RenderItem) {
    val d = item.event.data
    val dev = LocalDevColors.current
    var open by rememberSaveable(item.key) { mutableStateOf(true) }
    DevCard {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 40.dp).clickable(role = Role.Button) { open = !open },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val change = d.str("change") ?: "modify"
            StatusPill(change.uppercase(), when (change) { "add" -> dev.ok; "delete" -> dev.danger; else -> dev.info })
            Spacer(Modifier.size(8.dp))
            Text(d.str("path").orEmpty(), style = DevType.code, color = COUITheme.colorScheme.onSurface, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("+${d.long("additions") ?: 0}", style = DevType.label, color = dev.addedText)
            Spacer(Modifier.size(4.dp))
            Text("−${d.long("deletions") ?: 0}", style = DevType.label, color = dev.removedText)
        }
        if (open) {
            val diff = d.str("diff").orEmpty()
            if (diff.isNotBlank()) {
                Spacer(Modifier.size(6.dp))
                val moreFmt = stringResource(R.string.render_show_more_lines)
                DiffView(diff, showMoreLabel = { n -> moreFmt.format(n) })
            }
            if (d.bool("truncated") == true) Text(stringResource(R.string.render_diff_truncated), style = DevType.label, color = dev.warn)
        }
    }
}

@Composable
fun Plan(item: RenderItem) {
    val dev = LocalDevColors.current
    val items = item.event.data["items"].arr().orEmpty().mapNotNull { it.obj() }
    DevCard {
        RowHeader(stringResource(R.string.render_plan, items.count { it.str("status") == "completed" }, items.size), dev.info, item.event.ts)
        items.forEach { it ->
            val st = it.str("status")
            Row(Modifier.padding(top = 3.dp)) {
                Text(
                    when (st) { "completed" -> "☑"; "in_progress" -> "◐"; else -> "☐" },
                    style = DevType.code,
                    color = when (st) { "completed" -> dev.ok; "in_progress" -> dev.info; else -> dev.gutter },
                    modifier = Modifier.widthIn(min = 20.dp),
                )
                Text(
                    it.str("text", "content").orEmpty(),
                    style = COUITheme.textStyles.body2,
                    color = if (st == "completed") COUITheme.colorScheme.onSurfaceVariantSummary else COUITheme.colorScheme.onSurface,
                    fontWeight = if (st == "in_progress") FontWeight.SemiBold else null,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Approvals & questions — controls are enabled only when the capability is VERIFIED.
// ---------------------------------------------------------------------------------------------

@Composable
private fun BlockedNote(status: CapStatus, reason: String?) {
    Text(
        stringResource(R.string.render_control_unavailable, status.name) + (reason?.let { "\n$it" } ?: ""),
        style = DevType.codeSmall,
        color = capColor(status),
        modifier = Modifier.padding(top = 6.dp).semantics { contentDescription = "control unavailable: ${status.name}" },
    )
}

@Composable
fun ApprovalRequest(item: RenderItem, ctx: RenderContext) {
    val d = item.event.data
    val dev = LocalDevColors.current
    val resolvedInPlace = d.bool("resolved") == true
    val resolution = item.resolution?.data
    val resolved = resolvedInPlace || resolution != null
    DevCard(border = if (resolved) null else dev.warn) {
        RowHeader(stringResource(R.string.render_approval) + " · " + (d.str("kind") ?: "other"), dev.warn, item.event.ts)
        Text(d.str("title").orEmpty(), style = COUITheme.textStyles.body1, fontWeight = FontWeight.SemiBold, color = COUITheme.colorScheme.onSurface, modifier = Modifier.padding(top = 4.dp))
        d.str("detail")?.takeIf { it.isNotBlank() }?.let { Text(it, style = COUITheme.textStyles.body2, color = COUITheme.colorScheme.onSurfaceVariantSummary) }
        d.str("toolName")?.let { Text(stringResource(R.string.render_tool, it), style = DevType.codeSmall, color = dev.gutter) }
        d.str("command")?.let { Spacer(Modifier.size(4.dp)); CodeBlockView(it, "bash") }
        d.str("cwd")?.let { Text("cwd: $it", style = DevType.codeSmall, color = dev.gutter) }
        if (d.str("command") == null) d["input"]?.takeIf { it.toString() != "{}" }?.let { Spacer(Modifier.size(4.dp)); CodeBlockView(prettyJson(it).take(PREVIEW_CHARS), "json") }
        if (resolved) {
            val decision = d.str("decision") ?: resolution?.str("decision") ?: "?"
            val by = d.str("by") ?: resolution?.str("by") ?: "?"
            Spacer(Modifier.size(6.dp))
            StatusPill(stringResource(R.string.render_resolved, decision, by), dev.ok)
        } else {
            val options = d["options"].arr().orEmpty().mapNotNull { it.obj() }
            val enabled = ctx.approve == CapStatus.VERIFIED && ctx.online
            FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                options.forEach { o ->
                    val id = o.str("id") ?: return@forEach
                    val label = o.str("label") ?: id
                    val danger = id == "deny" || id == "abort"
                    Button(
                        onClick = { ctx.onApprove(d.str("requestId").orEmpty(), id, label) },
                        enabled = enabled,
                        colors = if (danger) ButtonDefaults.buttonColors() else ButtonDefaults.buttonColorsPrimary(),
                        minHeight = 40.dp,
                    ) {
                        Text(label, color = if (danger) COUITheme.colorScheme.onSurface else COUITheme.colorScheme.onPrimary, style = COUITheme.textStyles.button)
                    }
                }
            }
            if (ctx.approve != CapStatus.VERIFIED) BlockedNote(ctx.approve, ctx.capabilityReason)
        }
    }
}

@Composable
fun ApprovalResolved(item: RenderItem) {
    val d = item.event.data
    RowHeader(stringResource(R.string.render_resolved, d.str("decision") ?: "?", d.str("by") ?: "?"), LocalDevColors.current.ok, item.event.ts)
}

@Composable
fun Question(item: RenderItem, ctx: RenderContext) {
    val d = item.event.data
    val dev = LocalDevColors.current
    val resolved = d.bool("resolved") == true || item.resolution != null
    val questions = d["questions"].arr().orEmpty().mapNotNull { it.obj() }
    // question key -> selected labels (UI state only)
    val selections = remember(item.key) { mutableStateMapOf<String, Set<String>>() }
    val enabled = !resolved && ctx.answer == CapStatus.VERIFIED && ctx.online
    DevCard(border = if (resolved) null else dev.info) {
        RowHeader(stringResource(R.string.render_question), dev.info, item.event.ts)
        questions.forEach { q ->
            val key = q.str("question") ?: q.str("id") ?: ""
            val multi = q.bool("multiSelect") == true
            q.str("header")?.let { Text(it, style = DevType.label, color = dev.gutter, modifier = Modifier.padding(top = 6.dp)) }
            Text(q.str("question").orEmpty(), style = COUITheme.textStyles.body1, color = COUITheme.colorScheme.onSurface)
            q["options"].arr().orEmpty().mapNotNull { it.obj() }.forEach { o ->
                val label = o.str("label").orEmpty()
                val sel = selections[key].orEmpty()
                val selected = label in sel
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(enabled = enabled, role = if (multi) Role.Checkbox else Role.RadioButton) {
                        selections[key] = if (multi) (if (selected) sel - label else sel + label) else setOf(label)
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (multi) {
                        Checkbox(state = ToggleableState(selected), onClick = null, enabled = enabled)
                    } else {
                        RadioButton(selected = selected, onClick = null, enabled = enabled)
                    }
                    Spacer(Modifier.size(8.dp))
                    Column {
                        Text(label, style = COUITheme.textStyles.body2, color = COUITheme.colorScheme.onSurface)
                        o.str("description")?.let { Text(it, style = COUITheme.textStyles.footnote2, color = COUITheme.colorScheme.onSurfaceVariantSummary) }
                    }
                }
            }
        }
        if (resolved) {
            StatusPill(stringResource(R.string.render_answered), dev.ok)
        } else {
            val complete = questions.all { q -> selections[q.str("question") ?: q.str("id") ?: ""].orEmpty().isNotEmpty() }
            Button(
                onClick = {
                    val answers = buildJsonObject {
                        questions.forEach { q ->
                            val key = q.str("question") ?: q.str("id") ?: ""
                            put(key, selections[key].orEmpty().joinToString(", "))
                        }
                    }
                    ctx.onAnswer(d.str("requestId").orEmpty(), answers)
                },
                enabled = enabled && complete,
                colors = ButtonDefaults.buttonColorsPrimary(),
                modifier = Modifier.padding(top = 6.dp),
            ) { Text(stringResource(R.string.render_submit_answer), color = COUITheme.colorScheme.onPrimary) }
            if (ctx.answer != CapStatus.VERIFIED) BlockedNote(ctx.answer, ctx.capabilityReason)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Rows
// ---------------------------------------------------------------------------------------------

@Composable
fun Subagent(item: RenderItem, ctx: RenderContext) {
    val d = item.event.data
    val dev = LocalDevColors.current
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("⤷ " + stringResource(R.string.render_subagent), style = DevType.label, fontWeight = FontWeight.SemiBold, color = dev.keyword)
        Spacer(Modifier.size(6.dp))
        Text(d.str("description").orEmpty(), style = COUITheme.textStyles.body2, color = COUITheme.colorScheme.onSurface, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        d.str("status")?.let { StatusPill(it, toolStatusColor(it, dev)) }
        if (item.hasChildren) {
            TextButton(
                text = if (ctx.isCollapsed(item.key)) stringResource(R.string.render_expand_sub) else stringResource(R.string.render_collapse_sub),
                onClick = { ctx.onToggleCollapse(item.key) },
            )
        }
    }
}

@Composable
fun StatusRow(item: RenderItem) {
    val d = item.event.data
    val dev = LocalDevColors.current
    val state = d.str("state") ?: "?"
    val color = when (state) { "error" -> dev.danger; "waiting_approval", "waiting_input" -> dev.warn; "running" -> dev.info; "completed" -> dev.ok; else -> dev.neutral }
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusPill(state, color)
        Spacer(Modifier.size(6.dp))
        Text(listOfNotNull(d.str("detail"), Formatters.clock(item.event.ts)).joinToString(" · "), style = DevType.label, color = dev.gutter, maxLines = 2)
    }
}

@Composable
fun ErrorRow(item: RenderItem) {
    val dev = LocalDevColors.current
    DevCard(border = dev.danger) {
        RowHeader(stringResource(R.string.render_error), dev.danger, item.event.ts)
        SelectionContainer { Text(item.event.data.str("message", "text").orEmpty(), style = DevType.code, color = dev.danger) }
    }
}

@Composable
fun SystemRow(item: RenderItem) {
    val d = item.event.data
    val dev = LocalDevColors.current
    Text(
        "— " + listOfNotNull(d.str("subtype"), d.str("text")).joinToString(": ") + " —",
        style = DevType.codeSmall,
        color = dev.gutter,
        maxLines = 4,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun UsageRow(item: RenderItem) {
    val d = item.event.data
    Text(
        "Σ in ${Formatters.tokens(d.long("inputTokens") ?: 0)} · out ${Formatters.tokens(d.long("outputTokens") ?: 0)} · cache r ${Formatters.tokens(d.long("cacheReadTokens") ?: 0)} w ${Formatters.tokens(d.long("cacheWriteTokens") ?: 0)}" +
            (d.double("costUsd")?.let { " · " + Formatters.usd(it) } ?: ""),
        style = DevType.label,
        color = LocalDevColors.current.gutter,
    )
}

@Composable
fun UiComponent(item: RenderItem, ctx: RenderContext) {
    val d = item.event.data
    val name = d.str("component").orEmpty()
    val props = d["props"].obj() ?: JsonObject(emptyMap())
    val renderer = UiComponentRegistry.components[name] ?: BuiltinUiComponents.components[name]
    if (renderer != null) {
        renderer(props, (d.long("version") ?: 1).toInt())
    } else {
        DevCard {
            RowHeader(stringResource(R.string.render_ui_unsupported, name), LocalDevColors.current.gutter, item.event.ts)
            CodeBlockView(prettyJson(props).take(PREVIEW_CHARS), "json")
        }
    }
}

/** Generic fallback for unknown kinds: kind + structured JSON (never crashes, never hides data). */
@Composable
fun GenericEventCard(item: RenderItem) {
    DevCard {
        RowHeader(stringResource(R.string.render_unknown_kind, item.event.kind), LocalDevColors.current.gutter, item.event.ts)
        CodeBlockView(prettyJson(item.event.data).take(PREVIEW_CHARS), "json")
    }
}

/** Small built-in `ui` components (namespace `core`). */
object BuiltinUiComponents {
    val components: Map<String, @Composable (JsonObject, Int) -> Unit> = mapOf(
        "core.progress" to { p, _ ->
            Column(Modifier.fillMaxWidth()) {
                Text(p.str("label").orEmpty(), style = COUITheme.textStyles.body2, color = COUITheme.colorScheme.onSurface)
                LinearProgressIndicator(progress = (p.double("value") ?: 0.0).toFloat().coerceIn(0f, 1f), modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            }
        },
        "core.keyValue" to { p, _ ->
            Column(Modifier.fillMaxWidth()) {
                (p["rows"] as? JsonArray).orEmpty().mapNotNull { it.obj() }.forEach { r ->
                    Row {
                        Text(r.str("key").orEmpty(), style = DevType.codeSmall, color = LocalDevColors.current.gutter, modifier = Modifier.widthIn(min = 96.dp))
                        Text((r["value"] as? JsonPrimitive)?.content ?: prettyJson(r["value"]), style = DevType.codeSmall, color = COUITheme.colorScheme.onSurface)
                    }
                }
            }
        },
    )
}
