package io.github.tianli0.switchtether.ui.sessions.render

import io.github.tianli0.switchtether.ui.icons.AppIcons
import io.github.suqi8.coui.kmp.squircle.SquircleShape

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
import androidx.compose.ui.graphics.asImageBitmap
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
import io.github.tianli0.switchtether.remote.session.InjectedText
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
import kotlinx.coroutines.launch
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
    Column(Modifier.fillMaxWidth().clip(SquircleShape(10.dp)).background(dev.codeBackground)) {
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
    val imageItems = d["imageItems"].arr().orEmpty().mapNotNull { it.obj() }
    val cleaned = remember(item.event) { InjectedText.clean(d.str("text").orEmpty()) }
    val text = cleaned.text
    // IDE context (opened file / selected lines): desktop-provided, else recovered from old events.
    val context = remember(item.event) {
        d["context"].arr().orEmpty().mapNotNull { it.obj() }.mapNotNull { c ->
            c.str("path")?.let { InjectedText.Context(c.str("kind") ?: "file", it, c.str("lines")) }
        }.ifEmpty { cleaned.context }
    }
    if (text.isBlank() && imageItems.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalAlignment = Alignment.End) {
        if (context.isNotEmpty()) {
            SubtleMeta(
                context.distinctBy { it.path to it.lines }.joinToString("  ·  ") { c ->
                    c.fileName + (c.lines?.let { " L$it" } ?: "")
                },
                Modifier.padding(bottom = 3.dp, end = 4.dp),
            )
        }
        ChatBubble {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (imageItems.isNotEmpty()) ImageStrip(imageItems, ctx)
                if (text.isNotBlank()) MarkdownView(text, onLinkClick = ctx.onLink)
            }
        }
        val images = d.long("images")?.toInt() ?: imageItems.size
        val meta = listOfNotNull(
            if (images > 0 && imageItems.isEmpty()) stringResource(R.string.render_images, images) else null,
            shortClock(item.event.ts).ifEmpty { null },
        ).joinToString(" · ")
        if (meta.isNotEmpty()) SubtleMeta(meta, Modifier.padding(top = 3.dp, end = 4.dp))
    }
}

@Composable
fun AssistantMessage(item: RenderItem, ctx: RenderContext) {
    val d = item.event.data
    val imageItems = d["imageItems"].arr().orEmpty().mapNotNull { it.obj() }
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
        Column(Modifier.padding(start = 32.dp, top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (imageItems.isNotEmpty()) ImageStrip(imageItems, ctx)
            MarkdownView(
                d.str("text").orEmpty(),
                onLinkClick = ctx.onLink,
                onImageClick = { alt, url -> ctx.onViewImage?.invoke(ImagePreview(alt = alt, url = url)) },
                imageContent = { alt, url -> MarkdownImageBlock(alt, url, ctx) },
            )
        }
    }
}

@Composable
private fun ImageStrip(items: List<JsonObject>, ctx: RenderContext) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { img ->
            val kind = img.str("kind")
            when (kind) {
                "base64" -> {
                    val b64 = img.str("data").orEmpty()
                    val bytes = remember(b64) { runCatching { android.util.Base64.decode(b64, android.util.Base64.DEFAULT) }.getOrNull() }
                    ImageThumb(bytes = bytes, label = null, onClick = { if (bytes != null) ctx.onViewImage?.invoke(ImagePreview(bytes = bytes)) })
                }
                "local" -> {
                    val path = img.str("path").orEmpty()
                    var bytes by remember(path) { mutableStateOf<ByteArray?>(null) }
                    androidx.compose.runtime.LaunchedEffect(path, ctx.online) {
                        if (path.isNotBlank() && bytes == null) bytes = ctx.onLoadImageBytes?.invoke(path)
                    }
                    ImageThumb(bytes = bytes, label = path.substringAfterLast('/').substringAfterLast('\\'), onClick = {
                        ctx.onViewImage?.invoke(ImagePreview(alt = path, bytes = bytes, path = path))
                    })
                }
                "url" -> {
                    val url = img.str("url").orEmpty()
                    if (url.startsWith("data:image/")) {
                        val b64 = url.substringAfter("base64,", "")
                        val bytes = remember(b64) { runCatching { android.util.Base64.decode(b64, android.util.Base64.DEFAULT) }.getOrNull() }
                        ImageThumb(bytes = bytes, label = null, onClick = { if (bytes != null) ctx.onViewImage?.invoke(ImagePreview(bytes = bytes)) })
                    } else {
                        SubtleMeta("[image: $url]")
                    }
                }
                else -> SubtleMeta(stringResource(R.string.render_images, 1))
            }
        }
    }
}

@Composable
private fun MarkdownImageBlock(alt: String, url: String, ctx: RenderContext) {
    when {
        url.startsWith("data:image/") -> {
            val b64 = url.substringAfter("base64,", "")
            val bytes = remember(b64) { runCatching { android.util.Base64.decode(b64, android.util.Base64.DEFAULT) }.getOrNull() }
            ImageThumb(bytes = bytes, label = alt.ifBlank { null }, onClick = { if (bytes != null) ctx.onViewImage?.invoke(ImagePreview(alt = alt, bytes = bytes)) })
        }
        !url.startsWith("http://") && !url.startsWith("https://") && url.isNotBlank() -> {
            var bytes by remember(url) { mutableStateOf<ByteArray?>(null) }
            androidx.compose.runtime.LaunchedEffect(url, ctx.online) {
                if (bytes == null) bytes = ctx.onLoadImageBytes?.invoke(url)
            }
            if (bytes != null) {
                ImageThumb(bytes = bytes, label = alt.ifBlank { null }, onClick = { ctx.onViewImage?.invoke(ImagePreview(alt = alt, bytes = bytes, path = url)) })
            } else {
                SubtleMeta("[image: ${alt.ifBlank { url }}]")
            }
        }
        else -> SubtleMeta("[image: ${alt.ifBlank { url }}]")
    }
}

@Composable
private fun ImageThumb(bytes: ByteArray?, label: String?, onClick: () -> Unit) {
            val bmp = remember(bytes) {
        bytes?.let { runCatching { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() }
    }
    if (bmp != null) {
        androidx.compose.foundation.Image(
            bitmap = bmp,
            contentDescription = label ?: stringResource(R.string.render_image_preview),
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier
                .size(120.dp)
                .clip(SquircleShape(10.dp))
                .border(1.dp, COUITheme.colorScheme.dividerLine, SquircleShape(10.dp))
                .clickable(role = Role.Image, onClick = onClick),
        )
    } else if (label != null) {
        Box(
            Modifier
                .clip(SquircleShape(10.dp))
                .background(LocalDevColors.current.codeBackground)
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            SubtleMeta("[image: $label]")
        }
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
    val command = io.github.tianli0.switchtether.remote.session.ToolCommands.raw(d)
    var wrapCommand by rememberSaveable(item.key) { mutableStateOf(true) }
    val clipboard = androidx.compose.ui.platform.LocalClipboard.current
    val commandScope = androidx.compose.runtime.rememberCoroutineScope()
    val copyLabel = stringResource(R.string.command_copy)
    Column(Modifier.fillMaxWidth()) {
        CollapsibleRow(
            icon = categoryIcon(d.str("category")),
            iconTint = if (status == StepStatus.Failed) dev.danger else COUITheme.colorScheme.primary,
            title = io.github.tianli0.switchtether.ui.sessions.stepTitle(item),
            subtitle = listOfNotNull(d.str("name"), d.str("server")?.let { "@$it" }, shortClock(item.event.ts).ifEmpty { null }).joinToString(" · "),
            expanded = open,
            onToggle = { open = !open },
            toggleLabel = stringResource(R.string.render_toggle),
            titleMaxLines = if (open) 4 else 2,
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
                    if (command != null) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(text = stringResource(if (wrapCommand) R.string.command_scroll else R.string.command_wrap), onClick = { wrapCommand = !wrapCommand })
                            TextButton(text = copyLabel, onClick = { commandScope.launch { clipboard.setClipEntry(androidx.compose.ui.platform.ClipEntry(android.content.ClipData.newPlainText(copyLabel, command))) } })
                        }
                        SelectionContainer { CodeBlockView(command, "bash", softWrap = wrapCommand) }
                        input.obj()?.str("cwd")?.let { cwd ->
                            SectionLabel(stringResource(R.string.command_directory))
                            SelectionContainer { CodeBlockView(cwd, null, softWrap = true) }
                        }
                    } else CodeBlockView(prettyJson(input).take(PREVIEW_CHARS * 2), "json")
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
            Box(Modifier.size(28.dp).clip(SquircleShape(8.dp)).background(COUITheme.colorScheme.primary.copy(alpha = 0.10f)), contentAlignment = Alignment.Center) {
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
private fun QuestionOption(title: String, summary: String?, selected: Boolean, multi: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val fill by androidx.compose.animation.animateColorAsState(
        if (selected) COUITheme.colorScheme.primary.copy(alpha = if (LocalDevColors.current.isDark) 0.20f else 0.08f)
        else COUITheme.colorScheme.surfaceVariant,
        label = "question-selection",
    )
    val modifier = Modifier.fillMaxWidth().clip(SquircleShape(12.dp)).background(fill)
    val inset = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 10.dp)
    if (multi) {
        io.github.suqi8.coui.kmp.preference.CheckboxPreference(
            title = title, summary = summary, checked = selected, enabled = enabled,
            onCheckedChange = { onClick() }, modifier = modifier, insideMargin = inset,
        )
    } else {
        io.github.suqi8.coui.kmp.preference.RadioButtonPreference(
            title = title, summary = summary, selected = selected, enabled = enabled,
            onClick = onClick, modifier = modifier, insideMargin = inset,
        )
    }
}

@Composable
fun Question(item: RenderItem, ctx: RenderContext) {
    val d = item.event.data
    val resolution = item.resolution?.data
    val resolved = d.bool("resolved") == true || resolution != null
    val questions = d["questions"].arr().orEmpty().mapNotNull { it.obj() }
    val cancellable = d.bool("cancellable") ?: true
    // question key -> selected labels (UI state only)
    val selections = remember(item.key) { mutableStateMapOf<String, Set<String>>() }
    // question key -> free-text / "other" input
    val freeTexts = remember(item.key) { mutableStateMapOf<String, String>() }
    val otherChosen = remember(item.key) { mutableStateMapOf<String, Boolean>() }
    val enabled = !resolved && ctx.answer.usable && ctx.online && !ctx.requestBusy
    val accent = COUITheme.colorScheme.primary
    AttentionCard(active = !resolved, color = accent) {
        AttentionHeader(AppIcons.Question, stringResource(if (resolved) R.string.render_question else R.string.render_question_needed), item.event.ts, if (resolved) COUITheme.colorScheme.onSurfaceVariantSummary else accent)
        questions.forEach { q ->
            val key = q.str("id") ?: q.str("question") ?: ""
            val multi = q.bool("multiSelect") == true
            val opts = q["options"].arr().orEmpty().mapNotNull { it.obj() }
            val allowOther = q.bool("allowOther") == true || opts.isEmpty()
            val isSecret = q.bool("isSecret") == true
            q.str("header")?.let { SubtleMeta(it, Modifier.padding(top = 10.dp)) }
            Text(q.str("question").orEmpty(), style = COUITheme.textStyles.body1, fontWeight = FontWeight.Medium, color = COUITheme.colorScheme.onSurface, modifier = Modifier.padding(top = 4.dp))
            if (!resolved) Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                opts.forEach { o ->
                    val label = o.str("label").orEmpty()
                    val sel = selections[key].orEmpty()
                    val selected = label in sel && (multi || otherChosen[key] != true)
                    QuestionOption(label, o.str("description"), selected, multi, enabled) {
                        if (multi) selections[key] = if (selected) sel - label else sel + label
                        else {
                            otherChosen[key] = false
                            selections[key] = setOf(label)
                        }
                    }
                }
                if (allowOther && opts.isNotEmpty() && !resolved) {
                    val isOther = otherChosen[key] == true
                    QuestionOption(stringResource(R.string.render_question_other), null, isOther, multi, enabled) {
                        otherChosen[key] = if (multi) !isOther else true
                        if (!multi) selections[key] = emptySet()
                    }
                }
            }
            if ((opts.isEmpty() || otherChosen[key] == true) && !resolved) {
                val ft = freeTexts[key].orEmpty()
                io.github.suqi8.coui.kmp.basic.TextField(
                    value = ft,
                    onValueChange = { freeTexts[key] = it },
                    enabled = enabled,
                    singleLine = isSecret,
                    label = stringResource(R.string.render_question_input_hint),
                    visualTransformation = if (isSecret) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            }
        }
        if (resolved) {
            val dec = d.str("decision") ?: resolution?.str("decision")
            val by = d.str("by") ?: resolution?.str("by")
            val label = if (dec != null || by != null) resolutionLabel(dec, by) else stringResource(R.string.render_answered)
            ResolvedLine(label)
        } else {
            val complete = questions.all { q ->
                val key = q.str("id") ?: q.str("question") ?: ""
                val opts = q["options"].arr().orEmpty()
                val selOk = selections[key].orEmpty().isNotEmpty()
                val freeOk = (opts.isEmpty() || otherChosen[key] == true) && freeTexts[key].orEmpty().isNotBlank()
                q.bool("required") == false || if (otherChosen[key] == true || opts.isEmpty()) freeOk else selOk
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (cancellable) {
                    Button(
                        onClick = { ctx.onAnswerAction(d.str("requestId").orEmpty(), null, "cancel") },
                        enabled = enabled,
                        colors = ButtonDefaults.buttonColors(),
                        minHeight = 44.dp,
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.action_cancel), color = LocalDevColors.current.danger) }
                }
                Button(
                    onClick = {
                        val answers = buildJsonObject {
                            questions.forEach { q ->
                                val key = q.str("id") ?: q.str("question") ?: ""
                                val opts = q["options"].arr().orEmpty()
                                val picked = selections[key].orEmpty().toMutableList()
                                if ((opts.isEmpty() || otherChosen[key] == true)) {
                                    val ft = freeTexts[key].orEmpty().trim()
                                    if (ft.isNotEmpty()) picked += ft
                                }
                                if (picked.isNotEmpty()) {
                                    val values = picked.map { label ->
                                        q["options"].arr().orEmpty().mapNotNull { it.obj() }.firstOrNull { it.str("label") == label }?.str("value") ?: label
                                    }
                                    if (ctx.agent == "codex" && q.bool("multiSelect") == true) put(key, JsonArray(values.map(::JsonPrimitive)))
                                    else put(key, values.joinToString(", "))
                                }
                            }
                        }
                        ctx.onAnswerAction(d.str("requestId").orEmpty(), answers, "submit")
                    },
                    enabled = enabled && complete,
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    minHeight = 44.dp,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.render_submit_answer), color = COUITheme.colorScheme.onPrimary) }
            }
            if (!ctx.answer.usable) BlockedNote(ctx.answer, ctx.capabilityReason)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Rows
// ---------------------------------------------------------------------------------------------

@Composable
private fun durationLabel(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(1)
    return if (s < 60) stringResource(R.string.duration_seconds, s.toInt())
    else stringResource(R.string.duration_minutes, (s / 60).toInt(), (s % 60).toInt())
}

/**
 * A sub-agent launched by the Agent/Task tool. Background agents start `running` and are updated
 * in place when their task notification arrives (status, summary, result, tool uses, duration).
 * The arrow opens the result; nested steps, when the desktop sends any, have their own toggle.
 */
@Composable
fun Subagent(item: RenderItem, ctx: RenderContext) {
    val d = item.event.data
    val dev = LocalDevColors.current
    val status = StepStatus.parse(d.str("status"))
    val result = d.str("result")?.takeIf { it.isNotBlank() }
    val summary = d.str("summary")?.takeIf { it.isNotBlank() }
    val running = status == StepStatus.Running || status == StepStatus.Pending
    val hasBody = result != null || summary != null || running
    var open by rememberSaveable(item.key) { mutableStateOf(false) }
    val subtitle = listOfNotNull(
        d.str("agentType")?.takeIf { it.isNotBlank() } ?: stringResource(R.string.render_subagent),
        if (d.bool("background") == true) stringResource(R.string.subagent_background) else null,
        d.long("toolUses")?.takeIf { it > 0 }?.let { stringResource(R.string.subagent_tool_uses, it.toInt()) },
        d.long("durationMs")?.takeIf { it > 0 }?.let { durationLabel(it) },
        shortClock(item.event.ts).ifEmpty { null },
    ).joinToString(" · ")
    Column(Modifier.fillMaxWidth()) {
        CollapsibleRow(
            icon = AppIcons.SubAgent,
            iconTint = if (status == StepStatus.Failed) dev.danger else COUITheme.colorScheme.primary,
            title = d.str("description")?.takeIf { it.isNotBlank() } ?: stringResource(R.string.render_subagent),
            subtitle = subtitle,
            expanded = open,
            onToggle = { if (hasBody) open = !open },
            toggleLabel = stringResource(R.string.render_toggle),
            titleMaxLines = if (open) 4 else 2,
            showArrow = hasBody,
        ) {
            if (status != StepStatus.Unknown) StepStatusIndicator(status, stepStatusLabel(status))
            if (item.hasChildren) {
                TextButton(
                    text = if (ctx.isCollapsed(item.key)) stringResource(R.string.render_expand_sub) else stringResource(R.string.render_collapse_sub),
                    onClick = { ctx.onToggleCollapse(item.key) },
                )
            }
        }
        if (open && hasBody) {
            Column(Modifier.padding(start = 50.dp, end = 12.dp, bottom = 10.dp)) {
                if (summary != null && summary != result) {
                    SectionLabel(stringResource(R.string.subagent_summary))
                    Text(summary, style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurfaceSecondary)
                }
                if (result != null) {
                    SectionLabel(stringResource(R.string.subagent_result))
                    SelectionContainer { MarkdownView(result, onLinkClick = ctx.onLink) }
                    if (d.bool("resultTruncated") == true) SubtleMeta(stringResource(R.string.subagent_result_truncated), Modifier.padding(top = 4.dp))
                } else if (running) {
                    Text(stringResource(R.string.subagent_waiting_result), style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurfaceVariantSummary)
                    Spacer(Modifier.size(8.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        } else if (!open && summary != null && !running) {
            Text(
                summary,
                style = COUITheme.textStyles.footnote2,
                color = if (status == StepStatus.Failed) dev.danger else COUITheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 50.dp, end = 12.dp, bottom = 8.dp),
            )
        }
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
    "interrupted" -> stringResource(R.string.step_interrupted)
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
                "turn_stopped" -> stringResource(R.string.render_system_turn_stopped)
                "task_notification" -> stringResource(R.string.render_system_task, d.str("text").orEmpty())
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
    ) + (d.double("costUsd")?.let { " · " + Formatters.usd(it, io.github.tianli0.switchtether.domain.LocalMoney.current) } ?: "")
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
