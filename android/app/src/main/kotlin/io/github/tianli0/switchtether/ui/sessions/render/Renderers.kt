package io.github.tianli0.switchtether.ui.sessions.render

import io.github.tianli0.switchtether.ui.icons.AppIcons

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.Button
import io.github.suqi8.coui.kmp.basic.ButtonDefaults
import io.github.suqi8.coui.kmp.basic.Checkbox
import io.github.suqi8.coui.kmp.basic.ExpandArrow
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.LinearProgressIndicator
import io.github.suqi8.coui.kmp.basic.RadioButton
import io.github.suqi8.coui.kmp.basic.RotatingProgressIndicator
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
import io.github.tianli0.switchtether.ui.common.capStatusLabel
import io.github.tianli0.switchtether.ui.common.resolutionLabel
import io.github.tianli0.switchtether.ui.common.shortClock
import io.github.tianli0.switchtether.ui.common.stepStatusLabel
import io.github.tianli0.switchtether.ui.components.AttentionCard
import io.github.tianli0.switchtether.ui.components.ChatBubble
import io.github.tianli0.switchtether.ui.components.CollapsibleRow
import io.github.tianli0.switchtether.ui.components.StatusPill
import io.github.tianli0.switchtether.ui.components.StepStatus
import io.github.tianli0.switchtether.ui.components.StepStatusIndicator
import io.github.tianli0.switchtether.ui.components.TimelineCard
import io.github.tianli0.switchtether.ui.diff.DiffView
import io.github.tianli0.switchtether.ui.markdown.MarkdownView
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

/** Glyph per tool category (Material Icons via [AppIcons]). */
fun categoryIcon(category: String?): ImageVector = when (category) {
    "shell" -> AppIcons.Terminal
    "edit" -> AppIcons.Edit
    "write" -> AppIcons.Edit
    "read" -> AppIcons.File
    "search" -> AppIcons.Search
    "web" -> AppIcons.Web
    "mcp" -> AppIcons.Mcp
    "task" -> AppIcons.SubAgent
    "todo", "plan" -> AppIcons.Plan
    "question" -> AppIcons.Question
    else -> AppIcons.Tool
}

@Composable
private fun SubtleMeta(text: String, modifier: Modifier = Modifier, align: TextAlign = TextAlign.Start) {
    Text(text, style = COUITheme.textStyles.footnote2, color = COUITheme.colorScheme.onSurfaceVariantSummary, modifier = modifier, textAlign = align, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = COUITheme.textStyles.footnote2, fontWeight = FontWeight.Medium, color = COUITheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
}

/** Monospace output with truncation and on-demand loading of the full payload. */
@Composable
private fun OutputBlock(eventId: String, text: String, truncated: Boolean, isError: Boolean, bytes: Long?, ctx: RenderContext) {
    val dev = LocalDevColors.current
    val payload = ctx.payloadOf(eventId)
    val shown = payload?.text?.takeIf { it.length > text.length } ?: text
    var showAll by rememberSaveable(eventId) { mutableStateOf(false) }
    val display = if (showAll || shown.length <= PREVIEW_CHARS) shown else shown.take(PREVIEW_CHARS)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(dev.codeBackground)) {
        SelectionContainer {
            Box(Modifier.horizontalScroll(rememberScrollState()).padding(10.dp)) {
                Text(display.ifEmpty { stringResource(R.string.render_empty_output) }, style = DevType.codeSmall, color = if (isError) dev.danger else dev.codeText, softWrap = false)
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
    Column(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalAlignment = Alignment.End) {
        ChatBubble { MarkdownView(d.str("text").orEmpty(), onLinkClick = ctx.onLink) }
        val images = d["images"].arr()?.size ?: 0
        val meta = listOfNotNull(
            if (images > 0) stringResource(R.string.render_images, images) else null,
            shortClock(item.event.ts).ifEmpty { null },
        ).joinToString(" · ")
        if (meta.isNotEmpty()) SubtleMeta(meta, Modifier.padding(top = 3.dp, end = 4.dp))
    }
}

@Composable
fun AssistantMessage(item: RenderItem, ctx: RenderContext) {
    val d = item.event.data
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            io.github.tianli0.switchtether.ui.common.AgentBadge(ctx.agent, size = 24.dp)
            Spacer(Modifier.size(8.dp))
            Text(ctx.agentLabel, style = COUITheme.textStyles.footnote1, fontWeight = FontWeight.Medium, color = COUITheme.colorScheme.onSurface)
            Spacer(Modifier.size(6.dp))
            SubtleMeta(listOfNotNull(d.str("model"), shortClock(item.event.ts).ifEmpty { null }).joinToString(" · "), Modifier.weight(1f, fill = false))
            if (item.event.streaming) {
                val streamingLabel = stringResource(R.string.render_streaming)
                Spacer(Modifier.size(6.dp))
                RotatingProgressIndicator(color = COUITheme.colorScheme.primary, size = 14.dp, ringDiameter = 11.2.dp, strokeWidth = 2.dp, modifier = Modifier.semantics { contentDescription = streamingLabel })
            }
        }
        Box(Modifier.padding(start = 32.dp, top = 4.dp)) { MarkdownView(d.str("text").orEmpty(), onLinkClick = ctx.onLink) }
    }
}

@Composable
fun Reasoning(item: RenderItem) {
    val d = item.event.data
    var open by rememberSaveable(item.key) { mutableStateOf(false) }
    val text = d.str("text").orEmpty()
    val secondary = COUITheme.colorScheme.onSurfaceVariantSummary
    Column(Modifier.fillMaxWidth()) {
        CollapsibleRow(
            icon = AppIcons.Thinking,
            title = stringResource(if (d.bool("summary") == true) R.string.render_reasoning_summary else R.string.render_thinking),
            subtitle = if (open) null else text.lineSequence().firstOrNull { it.isNotBlank() }?.trim(),
            expanded = open,
            // Some agents only report that they thought (redacted text): nothing to expand then.
            onToggle = { if (text.isNotBlank()) open = !open },
            toggleLabel = stringResource(R.string.render_toggle),
            titleColor = secondary,
            iconTint = secondary,
            showArrow = text.isNotBlank(),
        )
        if (open && text.isNotBlank()) {
            Row(Modifier.padding(start = 26.dp, end = 12.dp, bottom = 8.dp)) {
                Box(Modifier.size(width = 2.dp, height = 20.dp).background(secondary.copy(alpha = 0.3f)))
                Spacer(Modifier.size(12.dp))
                SelectionContainer {
                    Text(text, style = COUITheme.textStyles.body2, fontStyle = FontStyle.Italic, color = secondary)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Tools
// ---------------------------------------------------------------------------------------------

/** Effective step status: a failed result marks the call failed even if the call event lags. */
fun toolStatus(item: RenderItem): StepStatus {
    val st = StepStatus.parse(item.event.data.str("status"))
    val res = item.attachedResult?.data ?: return st
    return when {
        res.bool("isError") == true -> StepStatus.Failed
        st == StepStatus.Running || st == StepStatus.Pending || st == StepStatus.Unknown -> StepStatus.Completed
        else -> st
    }
}

@Composable
fun ToolCall(item: RenderItem, ctx: RenderContext) {
    val d = item.event.data
    val dev = LocalDevColors.current
    var open by rememberSaveable(item.key) { mutableStateOf(false) }
    val status = toolStatus(item)
    val result = item.attachedResult
    Column(Modifier.fillMaxWidth()) {
        CollapsibleRow(
            icon = categoryIcon(d.str("category")),
            iconTint = if (status == StepStatus.Failed) dev.danger else COUITheme.colorScheme.primary,
            title = d.str("title")?.takeIf { it.isNotBlank() } ?: d.str("name").orEmpty(),
            subtitle = listOfNotNull(d.str("name"), d.str("server")?.let { "@$it" }, shortClock(item.event.ts).ifEmpty { null }).joinToString(" · "),
            expanded = open,
            onToggle = { open = !open },
            toggleLabel = stringResource(R.string.render_toggle),
            titleMaxLines = if (open) 6 else 1,
        ) {
            StepStatusIndicator(status, stepStatusLabel(status))
            if (item.hasChildren) {
                TextButton(
                    text = if (ctx.isCollapsed(item.key)) stringResource(R.string.render_expand_sub) else stringResource(R.string.render_collapse_sub),
                    onClick = { ctx.onToggleCollapse(item.key) },
                )
            }
        }
        if (open) {
            Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp)) {
                val input = d["input"]
                if (input != null && input.toString() != "{}") {
                    SectionLabel(stringResource(R.string.render_input))
                    val command = input.obj()?.str("command")
                    if (command != null) CodeBlockView(command, "bash") else CodeBlockView(prettyJson(input).take(PREVIEW_CHARS * 2), "json")
                }
                if (result != null) {
                    SectionLabel(stringResource(R.string.render_output))
                    ResultBody(result.id, result.data, ctx)
                } else if (status == StepStatus.Running || status == StepStatus.Pending) {
                    Spacer(Modifier.size(8.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        } else if (result?.data?.bool("isError") == true) {
            Text(
                result.data.str("text").orEmpty().lineSequence().firstOrNull().orEmpty(),
                style = DevType.codeSmall,
                color = dev.danger,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 50.dp, end = 12.dp, bottom = 8.dp),
            )
        }
    }
}

@Composable
private fun ResultBody(eventId: String, d: JsonObject, ctx: RenderContext) {
    val dev = LocalDevColors.current
    val exit = d.long("exitCode")
    if (exit != null && exit != 0L) {
        Text(stringResource(R.string.render_exit_code, exit.toInt()), style = COUITheme.textStyles.footnote2, color = dev.danger, modifier = Modifier.padding(bottom = 4.dp))
    }
    OutputBlock(eventId, d.str("text").orEmpty(), d.bool("truncated") == true, d.bool("isError") == true, d.long("bytes"), ctx)
}

@Composable
fun ToolResultStandalone(item: RenderItem, ctx: RenderContext) {
    var open by rememberSaveable(item.key) { mutableStateOf(false) }
    val isError = item.event.data.bool("isError") == true
    Column(Modifier.fillMaxWidth()) {
        CollapsibleRow(
            icon = AppIcons.File,
            iconTint = if (isError) LocalDevColors.current.danger else COUITheme.colorScheme.onSurfaceSecondary,
            title = stringResource(R.string.render_tool_result),
            subtitle = item.event.data.str("text")?.lineSequence()?.firstOrNull { it.isNotBlank() },
            expanded = open,
            onToggle = { open = !open },
            toggleLabel = stringResource(R.string.render_toggle),
        )
        if (open) Box(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp)) { ResultBody(item.event.id, item.event.data, ctx) }
    }
}

@Composable
fun FileChange(item: RenderItem) {
    val d = item.event.data
    val dev = LocalDevColors.current
    var open by rememberSaveable(item.key) { mutableStateOf(true) }
    val path = d.str("path").orEmpty()
    val name = path.substringAfterLast('/').substringAfterLast('\\')
    val dir = path.dropLast(name.length).trimEnd('/', '\\')
    val change = d.str("change") ?: "modify"
    TimelineCard(padding = 0.dp) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button, onClickLabel = stringResource(R.string.render_toggle)) { open = !open }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(COUITheme.colorScheme.primary.copy(alpha = 0.10f)), contentAlignment = Alignment.Center) {
                Icon(if (change == "add") AppIcons.File else AppIcons.Edit, contentDescription = null, tint = COUITheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text(name.ifEmpty { path }, style = COUITheme.textStyles.body2, fontWeight = FontWeight.Medium, color = COUITheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (dir.isNotEmpty()) SubtleMeta(dir)
            }
            Spacer(Modifier.size(8.dp))
            Text("+${d.long("additions") ?: 0}", style = DevType.label, fontWeight = FontWeight.SemiBold, color = dev.addedText)
            Spacer(Modifier.size(4.dp))
            Text("−${d.long("deletions") ?: 0}", style = DevType.label, fontWeight = FontWeight.SemiBold, color = dev.removedText)
            Spacer(Modifier.size(6.dp))
            StatusPill(
                stringResource(
                    when (change) {
                        "add" -> R.string.render_file_add
                        "delete" -> R.string.render_file_delete
                        "rename" -> R.string.render_file_rename
                        else -> R.string.render_file_modify
                    },
                ),
                when (change) { "add" -> dev.ok; "delete" -> dev.danger; else -> dev.info },
            )
            ExpandArrow(expanded = open)
        }
        if (open) {
            val diff = d.str("diff").orEmpty()
            Column(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp)) {
                if (diff.isNotBlank()) {
                    val moreFmt = stringResource(R.string.render_show_more_lines)
                    DiffView(diff, showMoreLabel = { n -> moreFmt.format(n) })
                }
                if (d.bool("truncated") == true) Text(stringResource(R.string.render_diff_truncated), style = COUITheme.textStyles.footnote2, color = dev.warn, modifier = Modifier.padding(4.dp))
            }
        }
    }
}

@Composable
fun Plan(item: RenderItem) {
    val dev = LocalDevColors.current
    val items = item.event.data["items"].arr().orEmpty().mapNotNull { it.obj() }
    val done = items.count { it.str("status") == "completed" }
    val accent = COUITheme.colorScheme.primary
    TimelineCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.Plan, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.render_plan_title), style = COUITheme.textStyles.body1, fontWeight = FontWeight.Medium, color = COUITheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            SubtleMeta(stringResource(R.string.render_plan_progress, done, items.size))
        }
        if (items.isNotEmpty()) {
            LinearProgressIndicator(progress = done.toFloat() / items.size, modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp))
        }
        items.forEach {
            val st = it.str("status")
            Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                    when (st) {
                        "completed" -> Box(Modifier.size(18.dp).clip(CircleShape).background(dev.ok), contentAlignment = Alignment.Center) {
                            Icon(AppIcons.Done, contentDescription = stringResource(R.string.step_completed), tint = Color.White, modifier = Modifier.size(14.dp))
                        }
                        "in_progress" -> {
                            val runningLabel = stringResource(R.string.step_running)
                            RotatingProgressIndicator(color = accent, size = 18.dp, ringDiameter = 14.4.dp, strokeWidth = 2.dp, modifier = Modifier.semantics { contentDescription = runningLabel })
                        }
                        else -> Box(Modifier.size(16.dp).clip(CircleShape).border(1.5.dp, COUITheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.6f), CircleShape))
                    }
                }
                Spacer(Modifier.size(10.dp))
                Text(
                    it.str("text", "content").orEmpty(),
                    style = COUITheme.textStyles.body2,
                    color = if (st == "completed") COUITheme.colorScheme.onSurfaceVariantSummary else COUITheme.colorScheme.onSurface,
                    fontWeight = if (st == "in_progress") FontWeight.Medium else null,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Approvals & questions — controls are enabled unless the capability is BLOCKED/unknown.
// ---------------------------------------------------------------------------------------------

@Composable
private fun BlockedNote(status: CapStatus, reason: String?) {
    Text(
        stringResource(R.string.render_control_unavailable, capStatusLabel(status)) + (reason?.let { "\n$it" } ?: ""),
        style = COUITheme.textStyles.footnote2,
        color = capColor(status),
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun AttentionHeader(icon: ImageVector, title: String, ts: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(color.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.size(8.dp))
        Text(title, style = COUITheme.textStyles.footnote1, fontWeight = FontWeight.Medium, color = color, modifier = Modifier.weight(1f))
        SubtleMeta(shortClock(ts))
    }
}

@Composable
private fun ResolvedLine(text: String) {
    val dev = LocalDevColors.current
    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(AppIcons.Done, contentDescription = null, tint = dev.ok, modifier = Modifier.size(16.dp))
        Spacer(Modifier.size(6.dp))
        Text(text, style = COUITheme.textStyles.footnote1, color = dev.ok)
    }
}

@Composable
fun ApprovalRequest(item: RenderItem, ctx: RenderContext) {
    val d = item.event.data
    val resolution = item.resolution?.data
    val resolved = d.bool("resolved") == true || resolution != null
    val accent = COUITheme.colorScheme.primary
    AttentionCard(active = !resolved, color = accent) {
        AttentionHeader(AppIcons.Lock, stringResource(if (resolved) R.string.render_approval else R.string.render_approval_needed), item.event.ts, if (resolved) COUITheme.colorScheme.onSurfaceVariantSummary else accent)
        Text(d.str("title").orEmpty(), style = COUITheme.textStyles.body1, fontWeight = FontWeight.Medium, color = COUITheme.colorScheme.onSurface, modifier = Modifier.padding(top = 10.dp))
        d.str("detail")?.takeIf { it.isNotBlank() }?.let { Text(it, style = COUITheme.textStyles.body2, color = COUITheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(top = 2.dp)) }
        d.str("toolName")?.let { SubtleMeta(stringResource(R.string.render_tool, it), Modifier.padding(top = 4.dp)) }
        d.str("command")?.let { Spacer(Modifier.size(8.dp)); CodeBlockView(it, "bash", maxLines = if (resolved) 3 else Int.MAX_VALUE) }
        d.str("cwd")?.let { SubtleMeta(stringResource(R.string.render_cwd, it), Modifier.padding(top = 4.dp)) }
        if (!resolved && d.str("command") == null) {
            d["input"]?.takeIf { it.toString() != "{}" }?.let { Spacer(Modifier.size(8.dp)); CodeBlockView(prettyJson(it).take(PREVIEW_CHARS), "json") }
        }
        if (resolved) {
            ResolvedLine(resolutionLabel(d.str("decision") ?: resolution?.str("decision"), d.str("by") ?: resolution?.str("by")))
        } else {
            val options = d["options"].arr().orEmpty().mapNotNull { it.obj() }
            val enabled = ctx.approve.usable && ctx.online
            Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { o ->
                    val id = o.str("id") ?: return@forEach
                    val label = decisionOptionLabel(id, o.str("label"))
                    val primary = id == "allow"
                    Button(
                        onClick = { ctx.onApprove(d.str("requestId").orEmpty(), id, label) },
                        enabled = enabled,
                        colors = if (primary) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors(),
                        minHeight = 44.dp,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            label,
                            color = when {
                                primary -> COUITheme.colorScheme.onPrimary
                                id == "deny" || id == "abort" -> LocalDevColors.current.danger
                                else -> COUITheme.colorScheme.onSurface
                            },
                            style = COUITheme.textStyles.button,
                        )
                    }
                }
            }
            if (!ctx.approve.usable) BlockedNote(ctx.approve, ctx.capabilityReason)
        }
    }
}

/** Localized label for the protocol's decision ids; unknown ids keep the desktop's label. */
@Composable
fun decisionOptionLabel(id: String, serverLabel: String?): String = when (id) {
    "allow" -> stringResource(R.string.option_allow)
    "allow_session" -> stringResource(R.string.option_allow_session)
    "deny" -> stringResource(R.string.option_deny)
    "abort" -> stringResource(R.string.option_abort)
    else -> serverLabel ?: id
}

@Composable
fun ApprovalResolved(item: RenderItem) {
    val d = item.event.data
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
        SubtleMeta(resolutionLabel(d.str("decision"), d.str("by")), align = TextAlign.Center)
    }
}

@Composable
fun Question(item: RenderItem, ctx: RenderContext) {
    val d = item.event.data
    val resolved = d.bool("resolved") == true || item.resolution != null
    val questions = d["questions"].arr().orEmpty().mapNotNull { it.obj() }
    // question key -> selected labels (UI state only)
    val selections = remember(item.key) { mutableStateMapOf<String, Set<String>>() }
    val enabled = !resolved && ctx.answer.usable && ctx.online
    val accent = COUITheme.colorScheme.primary
    AttentionCard(active = !resolved, color = accent) {
        AttentionHeader(AppIcons.Question, stringResource(if (resolved) R.string.render_question else R.string.render_question_needed), item.event.ts, if (resolved) COUITheme.colorScheme.onSurfaceVariantSummary else accent)
        questions.forEach { q ->
            val key = q.str("question") ?: q.str("id") ?: ""
            val multi = q.bool("multiSelect") == true
            q.str("header")?.let { SubtleMeta(it, Modifier.padding(top = 10.dp)) }
            Text(q.str("question").orEmpty(), style = COUITheme.textStyles.body1, fontWeight = FontWeight.Medium, color = COUITheme.colorScheme.onSurface, modifier = Modifier.padding(top = 4.dp))
            q["options"].arr().orEmpty().mapNotNull { it.obj() }.forEach { o ->
                val label = o.str("label").orEmpty()
                val sel = selections[key].orEmpty()
                val selected = label in sel
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp)).clickable(enabled = enabled, role = if (multi) Role.Checkbox else Role.RadioButton) {
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
            ResolvedLine(stringResource(R.string.render_answered))
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
                minHeight = 44.dp,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            ) { Text(stringResource(R.string.render_submit_answer), color = COUITheme.colorScheme.onPrimary) }
            if (!ctx.answer.usable) BlockedNote(ctx.answer, ctx.capabilityReason)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Rows
// ---------------------------------------------------------------------------------------------

@Composable
fun Subagent(item: RenderItem, ctx: RenderContext) {
    val d = item.event.data
    val status = StepStatus.parse(d.str("status"))
    CollapsibleRow(
        icon = AppIcons.SubAgent,
        iconTint = COUITheme.colorScheme.primary,
        title = d.str("description")?.takeIf { it.isNotBlank() } ?: stringResource(R.string.render_subagent),
        subtitle = listOfNotNull(stringResource(R.string.render_subagent), d.str("agentType"), shortClock(item.event.ts).ifEmpty { null }).joinToString(" · "),
        expanded = item.hasChildren && !ctx.isCollapsed(item.key),
        onToggle = { if (item.hasChildren) ctx.onToggleCollapse(item.key) },
        toggleLabel = if (ctx.isCollapsed(item.key)) stringResource(R.string.render_expand_sub) else stringResource(R.string.render_collapse_sub),
        showArrow = item.hasChildren,
    ) {
        if (status != StepStatus.Unknown) StepStatusIndicator(status, stepStatusLabel(status))
    }
}

@Composable
fun statusStateLabel(state: String?): String = when (state) {
    "running", "busy" -> stringResource(R.string.render_status_running)
    "waiting_approval" -> stringResource(R.string.render_status_waiting_approval)
    "waiting_input" -> stringResource(R.string.render_status_waiting_input)
    "completed" -> stringResource(R.string.render_status_completed)
    "error" -> stringResource(R.string.render_status_error)
    "idle" -> stringResource(R.string.render_status_idle)
    null -> ""
    else -> state
}

@Composable
fun StatusRow(item: RenderItem) {
    val d = item.event.data
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
        SubtleMeta(listOfNotNull(statusStateLabel(d.str("state")).ifEmpty { null }, d.str("detail"), shortClock(item.event.ts).ifEmpty { null }).joinToString(" · "), align = TextAlign.Center)
    }
}

@Composable
fun ErrorRow(item: RenderItem) {
    val dev = LocalDevColors.current
    TimelineCard(border = dev.danger.copy(alpha = 0.6f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.Info, contentDescription = null, tint = dev.danger, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.render_error), style = COUITheme.textStyles.footnote1, fontWeight = FontWeight.Medium, color = dev.danger, modifier = Modifier.weight(1f))
            SubtleMeta(shortClock(item.event.ts))
        }
        SelectionContainer { Text(item.event.data.str("message", "text").orEmpty(), style = DevType.code, color = dev.danger, modifier = Modifier.padding(top = 6.dp)) }
    }
}

@Composable
fun SystemRow(item: RenderItem) {
    val d = item.event.data
    Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
        Text(
            when (d.str("subtype")) {
                // Known desktop notices are shown in the app language instead of the server's English.
                "continued" -> stringResource(R.string.render_system_continued)
                "init" -> stringResource(R.string.render_system_init) + (d.str("text")?.substringAfter(" · ", "")?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
                else -> stringResource(R.string.render_system, listOfNotNull(d.str("subtype"), d.str("text")).joinToString(": "))
            },
            style = COUITheme.textStyles.footnote2,
            color = COUITheme.colorScheme.onSurfaceVariantSummary,
            textAlign = TextAlign.Center,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun UsageRow(item: RenderItem) {
    val d = item.event.data
    val text = stringResource(
        R.string.render_usage,
        Formatters.tokens(d.long("inputTokens") ?: 0),
        Formatters.tokens(d.long("outputTokens") ?: 0),
        Formatters.tokens(d.long("cacheReadTokens") ?: 0),
        Formatters.tokens(d.long("cacheWriteTokens") ?: 0),
    ) + (d.double("costUsd")?.let { " · " + Formatters.usd(it) } ?: "")
    Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 2.dp), contentAlignment = Alignment.Center) {
        Text(text, style = COUITheme.textStyles.footnote2, color = COUITheme.colorScheme.onSurfaceVariantSummary, textAlign = TextAlign.Center)
    }
}

@Composable
fun UiComponent(item: RenderItem, ctx: RenderContext) {
    val d = item.event.data
    val name = d.str("component").orEmpty()
    val props = d["props"].obj() ?: JsonObject(emptyMap())
    val renderer = UiComponentRegistry.components[name] ?: BuiltinUiComponents.components[name]
    if (renderer != null) {
        TimelineCard { renderer(props, (d.long("version") ?: 1).toInt()) }
    } else {
        TimelineCard {
            SubtleMeta(stringResource(R.string.render_ui_unsupported, name))
            CodeBlockView(prettyJson(props).take(PREVIEW_CHARS), "json")
        }
    }
}

/** Generic fallback for unknown kinds: kind + structured JSON (never crashes, never hides data). */
@Composable
fun GenericEventCard(item: RenderItem) {
    TimelineCard {
        SubtleMeta(stringResource(R.string.render_unknown_kind, item.event.kind))
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
