package io.github.tianli0.switchtether.ui.sessions.render

import io.github.tianli0.coui17.patterns.ExpandBody
import androidx.compose.foundation.layout.offset
import io.github.tianli0.coui17.patterns.ActionTone
import io.github.tianli0.coui17.patterns.LinkButton
import io.github.tianli0.coui17.patterns.SmallButton
import io.github.tianli0.switchtether.ui.icons.AppIcons
import io.github.suqi8.coui.kmp.squircle.SquircleShape

import androidx.compose.foundation.background
import androidx.compose.ui.platform.testTag
import kotlinx.serialization.json.contentOrNull
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
import io.github.tianli0.coui17.patterns.AttentionCard
import io.github.tianli0.coui17.patterns.ChatBubble
import io.github.tianli0.coui17.patterns.CollapsibleRow
import io.github.tianli0.coui17.patterns.StatusPill
import io.github.tianli0.switchtether.ui.components.StepStatus
import io.github.tianli0.switchtether.ui.components.StepStatusIndicator
import io.github.tianli0.coui17.patterns.TimelineCard
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
                LinkButton(stringResource(R.string.render_show_all), { showAll = true })
            }
            val serverTruncated = truncated && payload?.complete != true
            if (serverTruncated) {
                LinkButton(
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
    // Files sent from the phone: the desktop moves the path block out of the text into this list.
    // Photos among them are shown as pictures (read from the computer), the rest as file rows.
    val attached = remember(item.event) { d["attachments"].arr().orEmpty().mapNotNull { it.obj() } }
    val photoItems = remember(attached) {
        attached.filter { a -> a.str("name")?.let { io.github.tianli0.switchtether.files.FileTypes.kindOf(it) } == io.github.tianli0.switchtether.files.FileKind.IMAGE && a.str("path") != null }
            .map { a -> kotlinx.serialization.json.buildJsonObject { put("kind", kotlinx.serialization.json.JsonPrimitive("local")); put("path", a["path"]!!) } }
    }
    val files = remember(attached) { attached.filter { a -> photoItems.none { it["path"] == a["path"] } }.mapNotNull { a -> a.str("name")?.let { it to a.str("path") } } }
    if (text.isBlank() && imageItems.isEmpty() && photoItems.isEmpty() && files.isEmpty()) return
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
                if (imageItems.isNotEmpty() || photoItems.isNotEmpty()) ImageStrip(imageItems + photoItems, ctx)
                if (files.isNotEmpty()) AttachmentNames(files, onOpen = { path -> ctx.onLink(path) })
                if (text.isNotBlank()) MarkdownView(text, onLinkClick = ctx.onLink)
            }
        }
        // Under the bubble, one line: the time, then the message's actions. Re-using an earlier
        // prompt is common, so one tap copies it (replies have the same button).
        val rewind = ctx.onRewindFiles?.takeIf { io.github.tianli0.switchtether.remote.session.ClaudeTools.canRewindTo(item.event) }
        val images = d.long("images")?.toInt() ?: imageItems.size
        val meta = listOfNotNull(
            if (images > 0 && imageItems.isEmpty()) stringResource(R.string.render_images, images) else null,
            shortClock(item.event.ts).ifEmpty { null },
        ).joinToString(" · ")
        val actions = text.isNotBlank() || rewind != null
        if (actions || meta.isNotEmpty()) {
            Row(Modifier.padding(top = if (actions) 0.dp else 3.dp), verticalAlignment = Alignment.CenterVertically) {
                if (meta.isNotEmpty()) SubtleMeta(meta, Modifier.padding(end = 4.dp))
                if (rewind != null) RewindFilesButton(enabled = ctx.online) { rewind(item.event.id) }
                // The copy glyph (not its 36dp touch area) ends at the bubble's edge.
                if (text.isNotBlank()) io.github.tianli0.switchtether.ui.code.CopyButton(text, stringResource(R.string.message_copy_mine), Modifier.offset(x = 10.dp))
            }
        }
    }
}

/** Claude Code session tools: restore files to before this message (the conversation stays). */
@Composable
private fun RewindFilesButton(enabled: Boolean, onClick: () -> Unit) {
    val label = stringResource(R.string.rewind_action)
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label }
            .testTag("rewind-files"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(AppIcons.Rewind, contentDescription = null, tint = LocalDevColors.current.gutter, modifier = Modifier.size(16.dp))
    }
}

/** "Run in background" for a running foreground Bash / Agent call (Ctrl+B in Claude Code's IDE extension). */
@Composable
private fun RunInBackgroundAction(item: RenderItem, ctx: RenderContext, modifier: Modifier = Modifier) {
    val onRun = ctx.onRunInBackground ?: return
    val callId = io.github.tianli0.switchtether.remote.session.ClaudeTools.backgroundableCallId(item.event) ?: return
    val busy = ctx.isBackgrounding(callId)
    SmallButton(
        text = stringResource(if (busy) R.string.tools_run_background_busy else R.string.tools_run_background),
        onClick = { if (!busy) onRun(callId) },
        enabled = ctx.online && !busy,
        modifier = modifier.testTag("run-in-background"),
    )
}

/** Files attached to a user message, one line each (icon by type, name); a tap opens the file. */
@Composable
fun AttachmentNames(files: List<Pair<String, String?>>, onOpen: ((String) -> Unit)? = null) {
    val label = stringResource(R.string.render_attachments)
    Column(Modifier.semantics { contentDescription = label + ": " + files.joinToString { it.first } }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        files.forEach { (n, path) ->
            val image = io.github.tianli0.switchtether.files.FileTypes.kindOf(n) == io.github.tianli0.switchtether.files.FileKind.IMAGE
            val open = path?.let { p -> onOpen?.let { { it(p) } } }
            Row(
                Modifier.clip(SquircleShape(8.dp)).then(if (open != null) Modifier.clickable(role = Role.Button, onClick = open) else Modifier).padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (image) AppIcons.Image else AppIcons.File, contentDescription = null, tint = COUITheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(6.dp))
                Text(n, style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
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
            // Time first: on a narrow row the model id is what gets cut, not the time.
            val copyable = !item.event.streaming && !d.str("text").isNullOrBlank()
            SubtleMeta(listOfNotNull(shortClock(item.event.ts).ifEmpty { null }, d.str("model")).joinToString(" · "), Modifier.weight(1f, fill = copyable))
            if (item.event.streaming) {
                val streamingLabel = stringResource(R.string.render_streaming)
                Spacer(Modifier.size(6.dp))
                RotatingProgressIndicator(color = COUITheme.colorScheme.primary, size = 14.dp, ringDiameter = 11.2.dp, strokeWidth = 2.dp, modifier = Modifier.semantics { contentDescription = streamingLabel })
            } else if (copyable) {
                // Selecting text inside a scrolling list is fiddly on a phone: one tap copies the whole reply.
                // The glyph (not the 36dp touch area) ends at the page's content edge.
                io.github.tianli0.switchtether.ui.code.CopyButton(d.str("text").orEmpty(), stringResource(R.string.message_copy), Modifier.offset(x = 10.dp))
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
        ExpandBody(open && text.isNotBlank()) {
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
        // The desktop already told a refusal or an interrupt apart from a failure: keep that.
        st == StepStatus.Denied || st == StepStatus.Interrupted -> st
        res.bool("isError") == true -> StepStatus.Failed
        // Background shell (`run_in_background`): the result only says it started; the call's own
        // status follows the task until its notification.
        item.event.data.bool("background") == true -> st
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
    // Read / Edit / Write: the step is about one file, shown by name and opened from the card.
    val filePath = remember(item.event) { io.github.tianli0.switchtether.remote.session.ToolFiles.path(d) }
    val fileRange = remember(item.event) { if (filePath != null) io.github.tianli0.switchtether.remote.session.ToolFiles.range(d) else null }
    Column(Modifier.fillMaxWidth()) {
        CollapsibleRow(
            icon = categoryIcon(d.str("category")),
            iconTint = if (status == StepStatus.Failed) dev.danger else COUITheme.colorScheme.primary,
            title = io.github.tianli0.switchtether.ui.sessions.stepTitle(item),
            subtitle = listOfNotNull(
                d.str("name"),
                fileRange,
                d.str("server")?.let { "@$it" },
                if (d.bool("background") == true) stringResource(R.string.subagent_background) else null,
                shortClock(item.event.ts).ifEmpty { null },
            ).joinToString(" · "),
            expanded = open,
            onToggle = { open = !open },
            toggleLabel = stringResource(R.string.render_toggle),
            titleMaxLines = if (open) 4 else 2,
        ) {
            StepStatusIndicator(status, stepStatusLabel(status))
            if (item.hasChildren) {
                io.github.tianli0.switchtether.ui.components.SubStepsToggle(
                    text = if (ctx.isCollapsed(item.key)) stringResource(R.string.render_expand_sub) else stringResource(R.string.render_collapse_sub),
                    onClick = { ctx.onToggleCollapse(item.key) },
                )
            }
        }
        ExpandBody(open) {
            Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp)) {
                val input = d["input"]
                val isRead = d.str("category") == "read"
                if (filePath != null) {
                    SectionLabel(stringResource(R.string.render_file))
                    FileTarget(filePath, fileRange, ctx)
                }
                // A read's input is only the path (and the range) the row above already shows.
                if (input != null && input.toString() != "{}" && !(filePath != null && isRead)) {
                    SectionLabel(stringResource(R.string.render_input))
                    if (command != null) {
                        // Wrap / copy are small icons in the block's own header, not buttons above it.
                        SelectionContainer { CodeBlockView(command, "bash", softWrap = true, wrapToggle = true) }
                        input.obj()?.str("cwd")?.let { cwd ->
                            SectionLabel(stringResource(R.string.command_directory))
                            SelectionContainer { CodeBlockView(cwd, null, softWrap = true) }
                        }
                    } else CodeBlockView(prettyJson(input).take(PREVIEW_CHARS * 2), "json")
                }
                d.str("backgroundSummary")?.let { summary ->
                    SectionLabel(stringResource(R.string.render_background_task))
                    Text(summary, style = COUITheme.textStyles.body2, color = if (status == StepStatus.Failed) dev.danger else COUITheme.colorScheme.onSurface)
                }
                val readImage = filePath != null && isRead && result?.data?.bool("isError") != true &&
                    io.github.tianli0.switchtether.files.FileTypes.kindOf(filePath) == io.github.tianli0.switchtether.files.FileKind.IMAGE
                if (readImage && ctx.onLoadImageBytes != null) {
                    // The agent looked at a picture: show the picture, not the "[image]" placeholder.
                    Spacer(Modifier.size(8.dp))
                    ReadImage(filePath!!, ctx)
                } else if (result != null) {
                    SectionLabel(stringResource(if (filePath != null && isRead) R.string.render_file_content else R.string.render_output))
                    ResultBody(result.id, result.data, ctx)
                } else if (status == StepStatus.Running || status == StepStatus.Pending) {
                    Spacer(Modifier.size(8.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    RunInBackgroundAction(item, ctx, Modifier.padding(top = 6.dp))
                }
            }
        }
        // Collapsed, a failed step still says why in one line.
        if (!open && result?.data?.bool("isError") == true && status == StepStatus.Failed) {
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

/** The file a step read or changed: name, folder and the part read. A tap opens it in the file viewer. */
@Composable
private fun FileTarget(path: String, range: String?, ctx: RenderContext) {
    val name = io.github.tianli0.switchtether.remote.session.ToolFiles.name(path)
    val folder = io.github.tianli0.switchtether.remote.session.ToolFiles.folder(path)
    val open = ctx.onOpenFile
    val openLabel = stringResource(R.string.render_open_file)
    val image = io.github.tianli0.switchtether.files.FileTypes.kindOf(name) == io.github.tianli0.switchtether.files.FileKind.IMAGE
    Row(
        Modifier
            .fillMaxWidth()
            .clip(SquircleShape(10.dp))
            .background(LocalDevColors.current.codeBackground)
            .then(if (open != null) Modifier.clickable(role = Role.Button, onClickLabel = openLabel) { open(path) } else Modifier)
            .heightIn(min = 48.dp)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .testTag("tool-file"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (image) AppIcons.Image else AppIcons.File, contentDescription = null, tint = COUITheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                name.ifEmpty { path } + (range?.let { "  ·  $it" } ?: ""),
                style = COUITheme.textStyles.body2,
                fontWeight = FontWeight.Medium,
                color = COUITheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // The end of a long folder says more than its start.
            if (folder.isNotEmpty()) Text(folder, style = COUITheme.textStyles.footnote2, color = COUITheme.colorScheme.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.StartEllipsis)
        }
        if (open != null) {
            Spacer(Modifier.size(8.dp))
            Text(openLabel, style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.primary)
            Icon(AppIcons.ChevronRight, contentDescription = null, tint = COUITheme.colorScheme.primary, modifier = Modifier.size(16.dp))
        }
    }
}

/** A picture the agent read, loaded from the computer; a tap opens it full screen. */
@Composable
private fun ReadImage(path: String, ctx: RenderContext) {
    var bytes by remember(path) { mutableStateOf<ByteArray?>(null) }
    androidx.compose.runtime.LaunchedEffect(path, ctx.online) {
        if (bytes == null) bytes = ctx.onLoadImageBytes?.invoke(path)
    }
    ImageThumb(bytes = bytes, label = io.github.tianli0.switchtether.remote.session.ToolFiles.name(path), onClick = {
        ctx.onViewImage?.invoke(ImagePreview(alt = path, bytes = bytes, path = path))
    })
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
        ExpandBody(open) { Box(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp)) { ResultBody(item.event.id, item.event.data, ctx) } }
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
        ExpandBody(open) {
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
            LinearProgressIndicator(progress = androidx.compose.animation.core.animateFloatAsState(done.toFloat() / items.size, label = "plan-progress").value, modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp))
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

/** The answer given to a question, shown on the resolved card (one line per chosen value). */
@Composable
private fun AnsweredValue(values: List<String>) {
    val dev = LocalDevColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(SquircleShape(12.dp))
            .background(COUITheme.colorScheme.primary.copy(alpha = 0.08f))
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("question-answer"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        values.forEach { v ->
            Row(verticalAlignment = Alignment.Top) {
                Icon(AppIcons.Done, contentDescription = null, tint = dev.ok, modifier = Modifier.padding(top = 2.dp).size(16.dp))
                Spacer(Modifier.size(8.dp))
                SelectionContainer {
                    Text(v, style = COUITheme.textStyles.body2, color = COUITheme.colorScheme.onSurface)
                }
            }
        }
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
            // Disabled while a decision is on its way: a second tap must not send a second decision.
            val enabled = ctx.approve.usable && ctx.online && !ctx.requestBusy
            // The agent presents its plan: Claude Code's three answers instead of allow / deny.
            val approvePlan = ctx.onApprovePlan?.takeIf { isPlanExitApproval(d.str("toolName"), options.mapNotNull { it.str("id") }) }
            // Compact, end-aligned actions (a card's buttons, not a page's): they wrap onto the next
            // line when the desktop's labels are long.
            androidx.compose.foundation.layout.FlowRow(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (approvePlan != null) {
                    val rid = d.str("requestId").orEmpty()
                    val keep = stringResource(R.string.plan_keep_planning)
                    val review = stringResource(R.string.plan_approve_review)
                    val auto = stringResource(R.string.plan_approve_auto)
                    if (options.any { it.str("id") == "deny" }) {
                        SmallButton(keep, { ctx.onApprove(rid, "deny", keep) }, enabled = enabled, modifier = Modifier.testTag("plan-keep"))
                    }
                    SmallButton(review, { approvePlan(rid, "default", review) }, enabled = enabled, modifier = Modifier.testTag("plan-approve-review"))
                    SmallButton(auto, { approvePlan(rid, "acceptEdits", auto) }, enabled = enabled, tone = ActionTone.Primary, modifier = Modifier.testTag("plan-approve-auto"))
                } else options.forEach { o ->
                    val id = o.str("id") ?: return@forEach
                    val label = decisionOptionLabel(id, o.str("label"))
                    val primary = id == "allow"
                    SmallButton(
                        text = label,
                        onClick = { ctx.onApprove(d.str("requestId").orEmpty(), id, label) },
                        enabled = enabled,
                        tone = when {
                            primary -> ActionTone.Primary
                            id == "deny" || id == "abort" -> ActionTone.Danger
                            else -> ActionTone.Default
                        },
                    )
                }
            }
            if (!ctx.approve.usable) BlockedNote(ctx.approve, ctx.capabilityReason)
        }
    }
}

/** Tool name of the approval with which Claude Code presents its plan and asks to leave plan mode. */
const val EXIT_PLAN_MODE_TOOL = "ExitPlanMode"

/** The plan choices replace allow / deny only for that tool, and only if the request can be allowed. */
fun isPlanExitApproval(toolName: String?, optionIds: List<String>): Boolean =
    toolName == EXIT_PLAN_MODE_TOOL && "allow" in optionIds

/** Question id of an MCP elicitation that only asks for a confirmation (URL, or no form). */
const val ELICITATION_CONFIRM_ID = "__confirm__"

/** A `question` that is an MCP elicitation: the desktop adds `elicitationMode` and `serverName` (possibly null). */
fun isElicitation(data: JsonObject): Boolean = "elicitationMode" in data || "serverName" in data

/** A question's text without the URL the desktop appended to it (the card shows the URL with its own action). */
fun questionText(text: String, url: String?): String =
    if (url.isNullOrEmpty()) text else text.replace("\n$url", "").let { if (it.trim() == url) "" else it }.trimEnd()

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
    // Saveable (per list item and page): a half-written answer survives scrolling away to re-read
    // the context, opening a sub-agent page and coming back, and rotation.
    // question key -> selected labels
    val selections = rememberSaveable(item.key, saver = stateMapSaver()) { mutableStateMapOf<String, Set<String>>() }
    // question key -> free-text / "other" input
    val freeTexts = rememberSaveable(item.key, saver = stateMapSaver()) { mutableStateMapOf<String, String>() }
    val otherChosen = rememberSaveable(item.key, saver = stateMapSaver()) { mutableStateMapOf<String, Boolean>() }
    val enabled = !resolved && ctx.answer.usable && ctx.online && !ctx.requestBusy
    val accent = COUITheme.colorScheme.primary
    // An MCP server asking through the agent (PROTOCOL.md "MCP elicitations"): who asks, what it
    // says, the page it wants opened, and decline next to cancel.
    val elicitation = isElicitation(d)
    val serverName = d.str("serverName")?.takeIf { it.isNotBlank() }
    val url = d.str("url")?.trim()?.takeIf { it.isNotEmpty() }
    // Claude Code's message is already the text of the (first) question; Codex sends it only there.
    val leadMessage = d.str("message")?.trim()?.takeIf { m -> m.isNotEmpty() && questions.none { it.str("question").orEmpty().contains(m) } }
    AttentionCard(active = !resolved, color = accent) {
        AttentionHeader(AppIcons.Question, stringResource(if (resolved) R.string.render_question else R.string.render_question_needed), item.event.ts, if (resolved) COUITheme.colorScheme.onSurfaceVariantSummary else accent)
        if (elicitation && serverName != null) SubtleMeta(stringResource(R.string.render_elicitation_server, serverName), Modifier.padding(top = 8.dp))
        leadMessage?.let { Text(it, style = COUITheme.textStyles.body2, color = COUITheme.colorScheme.onSurface, modifier = Modifier.padding(top = 6.dp)) }
        questions.forEach { q ->
            val key = q.str("id") ?: q.str("question") ?: ""
            val multi = q.bool("multiSelect") == true
            // A confirmation (URL elicitation, or one without a form): nothing to pick or type,
            // the desktop ignores its answer. Submit is "Accept".
            val confirm = key == ELICITATION_CONFIRM_ID
            val opts = if (confirm) emptyList() else q["options"].arr().orEmpty().mapNotNull { it.obj() }
            val allowOther = !confirm && (q.bool("allowOther") == true || opts.isEmpty())
            val isSecret = q.bool("isSecret") == true
            // The server name is said once above, not again over every field.
            q.str("header")?.takeIf { !(elicitation && it == serverName) }?.let { SubtleMeta(it, Modifier.padding(top = 10.dp)) }
            questionText(q.str("question").orEmpty(), url).takeIf { it.isNotBlank() || url == null }?.let {
                Text(it, style = COUITheme.textStyles.body1, fontWeight = FontWeight.Medium, color = COUITheme.colorScheme.onSurface, modifier = Modifier.padding(top = 4.dp))
            }
            if (resolved) {
                // What was answered: from the desktop (`data.answers`), else what this phone just sent.
                val fromDesktop = (d["answers"].obj() ?: resolution?.get("answers").obj())?.let { a ->
                    (a[key] ?: q.str("question")?.let { a[it] })?.let { v ->
                        v.arr()?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: listOfNotNull((v as? JsonPrimitive)?.contentOrNull)
                    }
                }
                val local = (selections[key].orEmpty().toList() + listOfNotNull(freeTexts[key]?.trim()?.takeIf { it.isNotEmpty() }))
                    .map { if (isSecret) "••••••" else it }
                val shown = fromDesktop?.takeIf { it.isNotEmpty() } ?: local.takeIf { it.isNotEmpty() }
                // Option values map back to their labels.
                val labels = shown?.map { v -> opts.firstOrNull { it.str("value") == v }?.str("label") ?: v }
                if (labels != null) AnsweredValue(labels)
            }
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
            if (!confirm && (opts.isEmpty() || otherChosen[key] == true) && !resolved) {
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
        // The page the server wants opened (sign-in, consent): through the app's link confirmation.
        if (url != null) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                SelectionContainer(Modifier.weight(1f)) {
                    Text(url, style = COUITheme.textStyles.footnote1, color = accent, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                if (!resolved) {
                    Spacer(Modifier.size(8.dp))
                    SmallButton(
                        text = stringResource(R.string.render_elicitation_open),
                        onClick = { ctx.onLink(url) },
                        modifier = Modifier.testTag("elicitation-open"),
                    )
                }
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
                if (key == ELICITATION_CONFIRM_ID) return@all true
                val opts = q["options"].arr().orEmpty()
                val selOk = selections[key].orEmpty().isNotEmpty()
                val freeOk = (opts.isEmpty() || otherChosen[key] == true) && freeTexts[key].orEmpty().isNotBlank()
                q.bool("required") == false || if (otherChosen[key] == true || opts.isEmpty()) freeOk else selOk
            }
            val onlyConfirm = questions.isNotEmpty() && questions.all { it.str("id") == ELICITATION_CONFIRM_ID }
            androidx.compose.foundation.layout.FlowRow(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (cancellable) {
                    SmallButton(
                        text = stringResource(R.string.action_cancel),
                        onClick = { ctx.onAnswerAction(d.str("requestId").orEmpty(), null, "cancel") },
                        enabled = enabled,
                        tone = if (elicitation) ActionTone.Default else ActionTone.Danger,
                        modifier = Modifier.testTag("question-cancel"),
                    )
                }
                // MCP's three answers: accept, decline (the user says no), cancel (dismissed).
                if (elicitation) {
                    SmallButton(
                        text = stringResource(R.string.render_elicitation_decline),
                        onClick = { ctx.onAnswerAction(d.str("requestId").orEmpty(), null, "decline") },
                        enabled = enabled,
                        tone = ActionTone.Danger,
                        modifier = Modifier.testTag("question-decline"),
                    )
                }
                SmallButton(
                    text = stringResource(if (onlyConfirm) R.string.render_elicitation_accept else R.string.render_submit_answer),
                    tone = ActionTone.Primary,
                    modifier = Modifier.testTag("question-submit"),
                    onClick = {
                        val answers = buildJsonObject {
                            questions.forEach { q ->
                                val key = q.str("id") ?: q.str("question") ?: ""
                                if (key == ELICITATION_CONFIRM_ID) {
                                    // Ignored by the desktop; the desktop's own option label when it has one.
                                    put(key, q["options"].arr().orEmpty().firstNotNullOfOrNull { it.obj()?.str("label") } ?: "Accept")
                                    return@forEach
                                }
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
                )
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
                io.github.tianli0.switchtether.ui.components.SubStepsToggle(
                    text = if (ctx.isCollapsed(item.key)) stringResource(R.string.render_expand_sub) else stringResource(R.string.render_collapse_sub),
                    onClick = { ctx.onToggleCollapse(item.key) },
                )
            }
        }
        ExpandBody(open && hasBody) {
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
                    RunInBackgroundAction(item, ctx, Modifier.padding(top = 6.dp))
                }
            }
        }
        if (!open && summary != null && !running) {
            Text(
                summary,
                style = COUITheme.textStyles.footnote2,
                color = if (status == StepStatus.Failed) dev.danger else COUITheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 50.dp, end = 12.dp, bottom = 8.dp),
            )
        }
        // Claude Code keeps every sub-agent's own transcript: show its branch (latest steps while
        // it runs) and open the whole process, like expanding a Task in Claude Code.
        val agentId = d.str("agentId")?.takeIf { it.isNotBlank() }
        if (agentId != null && ctx.agent in io.github.tianli0.switchtether.ui.sessions.SUBAGENT_AGENTS) {
            SubagentBranchPreview(agentId, running, d.str("description"), ctx)
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
        // Claude Code's "[Request interrupted by user…]" marker is a sentence of its own, not a detail to quote.
        val byUser = d.str("state") == "interrupted" && d.str("detail")?.startsWith("[Request interrupted by user") == true
        val head = if (byUser) stringResource(R.string.render_status_user_interrupted) else statusStateLabel(d.str("state"))
        SubtleMeta(listOfNotNull(head.ifEmpty { null }, d.str("detail").takeUnless { byUser }, shortClock(item.event.ts).ifEmpty { null }).joinToString(" · "), align = TextAlign.Center)
    }
}

/** Stack-trace frames, Python tracebacks, exception names or a JSON body: shown in the code face. */
private val ERROR_CODE_HINT = Regex("""(?m)^\s+at |Traceback|Exception:|^\s*\{""")

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
        val message = item.event.data.str("message", "text").orEmpty()
        // A sentence reads as text; only output that looks like a log or a trace keeps the code face.
        val looksLikeCode = message.lines().size > 2 || ERROR_CODE_HINT.containsMatchIn(message)
        SelectionContainer {
            Text(
                message,
                style = if (looksLikeCode) DevType.code else COUITheme.textStyles.body2,
                color = if (looksLikeCode) dev.danger else COUITheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
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
    ) + (
        // Claude Code's `total_cost_usd`: the session's running total, not this turn's cost (a zero
        // is a crash / startup-error result, nothing to show).
        d.double("costUsd")?.takeIf { it.isFinite() && it > 0 }?.let {
            " · " + stringResource(R.string.render_usage_cost_total, Formatters.usd(it, io.github.tianli0.switchtether.domain.LocalMoney.current))
        } ?: ""
        )
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

/** Saves a [androidx.compose.runtime.snapshots.SnapshotStateMap] whose values are bundle-able (String, Boolean, Set<String>). */
private fun <V : Any> stateMapSaver(): androidx.compose.runtime.saveable.Saver<androidx.compose.runtime.snapshots.SnapshotStateMap<String, V>, Any> =
    androidx.compose.runtime.saveable.Saver(
        save = { m -> HashMap(m.mapValues { (_, v) -> if (v is Set<*>) ArrayList(v) else v }) },
        restore = { saved ->
            @Suppress("UNCHECKED_CAST")
            val m = saved as Map<String, Any>
            androidx.compose.runtime.mutableStateMapOf<String, V>().apply {
                m.forEach { (k, v) -> put(k, (if (v is List<*>) v.toSet() else v) as V) }
            }
        },
    )
