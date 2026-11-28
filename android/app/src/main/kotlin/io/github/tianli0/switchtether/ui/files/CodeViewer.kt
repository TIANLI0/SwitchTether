package io.github.tianli0.switchtether.ui.files

import io.github.tianli0.coui17.patterns.MenuEntry
import io.github.tianli0.coui17.patterns.GlassMenu
import io.github.tianli0.coui17.patterns.FormDialog
import android.content.ClipData
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.CaretDown
import com.adamglin.phosphoricons.regular.CaretUp
import com.adamglin.phosphoricons.regular.MagnifyingGlass
import io.github.suqi8.coui.kmp.basic.DropdownImpl
import io.github.suqi8.coui.kmp.basic.ListPopupColumn
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.layout.DialogButtonBar
import io.github.suqi8.coui.kmp.layout.DialogButtonBarAction
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.window.WindowDialog
import io.github.suqi8.coui.kmp.window.WindowListPopup
import io.github.tianli0.switchtether.R
import io.github.tianli0.switchtether.files.TextDocument
import io.github.tianli0.switchtether.files.TextMatch
import io.github.tianli0.coui17.patterns.LocalBottomBarSpace
import io.github.tianli0.coui17.patterns.TextInput
import io.github.tianli0.coui17.patterns.ToolbarIcon
import io.github.tianli0.switchtether.ui.icons.AppIcons
import io.github.tianli0.switchtether.ui.theme.DevType
import io.github.tianli0.switchtether.ui.theme.LocalDevColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** A single line longer than this is cut for display (minified bundles); copy still gives the whole file. */
internal const val MAX_LINE_CHARS = 20_000

private const val MIN_FONT_SP = 8f
private const val MAX_FONT_SP = 24f
private const val DEFAULT_FONT_SP = 12f

/**
 * The viewer's view settings and panels, owned by whoever draws its controls. A page that puts the
 * viewer under its own title bar (the Files page) passes one and offers search / wrap / go to line
 * / text size there; without one the viewer draws its own action row, as in chat previews.
 */
class CodeViewerState(wrap: Boolean = false, fontSp: Float = DEFAULT_FONT_SP) {
    var wrap by mutableStateOf(wrap)
    var fontSp by mutableFloatStateOf(fontSp)
    var findOpen by mutableStateOf(false)
    var gotoOpen by mutableStateOf(false)

    fun larger() { fontSp = (fontSp + 1f).coerceAtMost(MAX_FONT_SP) }
    fun smaller() { fontSp = (fontSp - 1f).coerceAtLeast(MIN_FONT_SP) }

    companion object {
        val Saver = androidx.compose.runtime.saveable.listSaver<CodeViewerState, Any>(
            save = { listOf(it.wrap, it.fontSp, it.findOpen) },
            restore = { CodeViewerState(it[0] as Boolean, it[1] as Float).apply { findOpen = it[2] as Boolean } },
        )
    }
}

/** A [CodeViewerState] kept per file across rotation. */
@Composable
fun rememberCodeViewerState(path: String): CodeViewerState = rememberSaveable(path, saver = CodeViewerState.Saver) { CodeViewerState() }

/**
 * Read-only code/text viewer: a lazy list of lines (fast for multi-megabyte files) with line numbers, syntax
 * colours, a wrap / sideways-scroll toggle, find-in-file, go to line, pinch (or menu) text size, long-press a
 * line to copy it and "Copy all". [extraActions] go at the start of the action row (e.g. the Markdown toggle).
 */
@Composable
fun CodeViewer(
    text: String,
    path: String,
    language: String?,
    modifier: Modifier = Modifier,
    extraActions: @Composable RowScope.() -> Unit = {},
    /** Controls drawn by the caller (no action row here); null = the viewer's own action row. */
    controls: CodeViewerState? = null,
) {
    val dev = LocalDevColors.current
    val doc = remember(text) { TextDocument(text) }
    val highlighted by produceState<AnnotatedString?>(null, doc, language, dev) {
        value = null
        value = withContext(Dispatchers.Default) { CodeHighlighting.highlight(doc.display, path, language, dev) }
    }
    val own = rememberCodeViewerState(path)
    val st = controls ?: own
    var wrap by st::wrap
    var fontSp by st::fontSp
    var findOpen by st::findOpen
    var gotoOpen by st::gotoOpen
    var query by rememberSaveable(path) { mutableStateOf("") }
    var current by rememberSaveable(path) { mutableIntStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }
    val matches by produceState<List<TextMatch>>(emptyList(), doc, query, findOpen) {
        if (!findOpen || query.isEmpty()) {
            value = emptyList()
            return@produceState
        }
        delay(150) // typing debounce
        value = withContext(Dispatchers.Default) { doc.find(query) }
    }
    val listState = rememberLazyListState()
    io.github.suqi8.coui.kmp.basic.BindScrollToTop(listState)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val density = LocalDensity.current

    val style = DevType.code.copy(fontSize = fontSp.sp, lineHeight = (fontSp * 17f / 12f).sp, color = dev.codeText)
    val measurer = rememberTextMeasurer()
    val charWidthPx = remember(fontSp, measurer) { measurer.measure("0000000000", style).size.width / 10f }
    val digits = doc.lineCount.toString().length.coerceAtLeast(2)
    val gutterWidth = with(density) { (charWidthPx * digits).toDp() } + 16.dp

    var scrollX by remember(path) { mutableFloatStateOf(0f) }
    // "Go to line" lands the line with context above it and marks it for a moment.
    var flashLine by remember(path) { mutableStateOf<Int?>(null) }
    LaunchedEffect(flashLine) {
        if (flashLine != null) {
            delay(1400)
            flashLine = null
        }
    }
    var viewportPx by remember { mutableIntStateOf(0) }
    val textViewportPx = (viewportPx - with(density) { (gutterWidth + 12.dp).toPx() }).coerceAtLeast(1f)
    val contentPx = charWidthPx * doc.widestLineCells.coerceAtMost(MAX_LINE_CHARS + 64)
    val maxScrollX = if (wrap) 0f else (contentPx - textViewportPx + charWidthPx * 2).coerceAtLeast(0f)
    if (scrollX > maxScrollX) scrollX = maxScrollX
    val hScroll = rememberScrollableState { delta ->
        val old = scrollX
        scrollX = (scrollX - delta).coerceIn(0f, maxScrollX)
        old - scrollX
    }

    fun reveal(m: TextMatch) {
        scope.launch { listState.animateScrollToItem((m.line - 3).coerceAtLeast(0)) }
        if (!wrap) {
            val col = m.start - doc.lineStart(m.line)
            val x = col * charWidthPx
            if (x < scrollX || x > scrollX + textViewportPx * 0.8f) scrollX = (x - textViewportPx / 3f).coerceIn(0f, maxScrollX)
        }
    }
    LaunchedEffect(matches) {
        if (matches.isNotEmpty()) {
            current = current.coerceIn(0, matches.lastIndex)
            reveal(matches[current])
        }
    }

    Column(modifier.fillMaxSize()) {
        // Action row (only when the caller does not draw the controls in its own title bar).
        // Tools of the viewer: the label lines up with the code card's text, keys 4dp from the edge.
        if (controls == null) Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            extraActions()
            Text(
                listOfNotNull(
                    CodeHighlighting.label(path, language),
                    stringResource(R.string.files_lines, doc.lineCount),
                ).joinToString(" · "),
                style = DevType.label, color = dev.gutter, maxLines = 1,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            ToolbarIcon(PhosphorIcons.Regular.MagnifyingGlass, stringResource(R.string.files_find), { findOpen = !findOpen })
            ToolbarIcon(
                if (wrap) AppIcons.ScrollSideways else AppIcons.WrapLines,
                stringResource(if (wrap) R.string.command_scroll else R.string.command_wrap),
                { wrap = !wrap },
            )
            Box {
                ToolbarIcon(AppIcons.More, stringResource(R.string.files_more), { menuOpen = true })
                val copiedAll = stringResource(R.string.files_copied)
                val items = listOf(
                    stringResource(R.string.files_copy_all) to {
                        scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(path, text))) }
                        Toast.makeText(context, copiedAll, Toast.LENGTH_SHORT).show()
                    },
                    stringResource(R.string.files_goto_line) to { gotoOpen = true },
                    stringResource(R.string.files_text_larger) to { fontSp = (fontSp + 1f).coerceAtMost(MAX_FONT_SP) },
                    stringResource(R.string.files_text_smaller) to { fontSp = (fontSp - 1f).coerceAtLeast(MIN_FONT_SP) },
                )
                GlassMenu(
                    show = menuOpen,
                    onDismiss = { menuOpen = false },
                    entries = items.map { (label, act) -> MenuEntry(label, onClick = act) },
                )
            }
        }
        if (findOpen) {
            FindBar(
                query = query,
                onQuery = { query = it; current = 0 },
                count = matches.size,
                current = current,
                capped = matches.size >= TextDocument.MAX_MATCHES,
                onPrev = { if (matches.isNotEmpty()) { current = (current - 1 + matches.size) % matches.size; reveal(matches[current]) } },
                onNext = { if (matches.isNotEmpty()) { current = (current + 1) % matches.size; reveal(matches[current]) } },
                onClose = { findOpen = false; query = "" },
            )
        }
        if (doc.display.length > CodeHighlighting.MAX_CHARS) {
            Text(stringResource(R.string.files_highlight_off), style = DevType.label, color = dev.gutter, modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 6.dp))
        }
        val lineCopied = stringResource(R.string.files_line_copied)
        val shortened = stringResource(R.string.files_line_shortened)
        Box(
            // A rounded card on the page's 12dp edge, not a square slab against the screen.
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)
                .clip(io.github.suqi8.coui.kmp.squircle.SquircleShape(16.dp))
                .background(dev.codeBackground)
                .onSizeChanged { viewportPx = it.width }
                .pointerInput(Unit) {
                    // Two-finger pinch changes the text size; one finger keeps scrolling the list.
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.count { it.pressed } >= 2) {
                                val z = event.calculateZoom()
                                if (z != 1f) fontSp = (fontSp * z).coerceIn(MIN_FONT_SP, MAX_FONT_SP)
                                event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
                .scrollable(hScroll, Orientation.Horizontal, enabled = !wrap)
                .testTag("code-viewer"),
        ) {
            CodeLines(
                doc = doc,
                highlighted = highlighted,
                matches = matches,
                currentMatch = matches.getOrNull(current),
                wrap = wrap,
                scrollX = { scrollX },
                charWidthPx = charWidthPx,
                textViewportPx = textViewportPx,
                flashLine = flashLine,
                style = style,
                gutterWidth = gutterWidth,
                listState = listState,
                shortenedNote = shortened,
                onLongPress = { line ->
                    scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(path, doc.line(line)))) }
                    Toast.makeText(context, String.format(lineCopied, line + 1), Toast.LENGTH_SHORT).show()
                },
            )
        }
    }
    if (gotoOpen) {
        GotoLineDialog(doc.lineCount, onDismiss = { gotoOpen = false }) { line ->
            gotoOpen = false
            val target = (line - 1).coerceIn(0, doc.lineCount - 1)
            // Three lines of context above it, like find; the target was the second row, half cut
            // by the card's top edge.
            scope.launch { listState.scrollToItem((target - 3).coerceAtLeast(0)) }
            flashLine = target
        }
    }
}

@Composable
private fun CodeLines(
    doc: TextDocument,
    highlighted: AnnotatedString?,
    matches: List<TextMatch>,
    currentMatch: TextMatch?,
    wrap: Boolean,
    /**
     * Read at placement, not at composition: rows composed (or prefetched) at different times used
     * to keep different offsets, so one screen showed lines scrolled to two positions.
     */
    scrollX: () -> Float,
    charWidthPx: Float,
    textViewportPx: Float,
    flashLine: Int?,
    style: androidx.compose.ui.text.TextStyle,
    gutterWidth: androidx.compose.ui.unit.Dp,
    listState: LazyListState,
    shortenedNote: String,
    onLongPress: (Int) -> Unit,
) {
    val dev = LocalDevColors.current
    val primary = COUITheme.colorScheme.primary
    val density = LocalDensity.current
    // Matches grouped by line once, so each row looks up only its own.
    val byLine = remember(matches) { matches.groupBy { it.line } }
    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp + LocalBottomBarSpace.current),
    ) {
        items(doc.lineCount, key = { it }) { line ->
            val lineStart = doc.lineStart(line)
            val end = doc.lineEnd(line)
            val cut = end - lineStart > MAX_LINE_CHARS
            val shownEnd = if (cut) lineStart + MAX_LINE_CHARS else end
            // Wrapped, a line keeps its indentation as a hanging indent: the leading spaces become
            // the paragraph's indent (every row of the line starts there), so continuation rows no
            // longer restart at column 0 and a deep indent is never wrapped onto a row of its own.
            // The indent is capped at half the card so long code still has room.
            var lead = 0
            if (wrap) while (lineStart + lead < shownEnd && doc.display[lineStart + lead] == ' ') lead++
            val maxLead = if (charWidthPx > 0f) (textViewportPx * 0.5f / charWidthPx).toInt() else 0
            if (lead > maxLead) lead = maxLead
            if (lineStart + lead >= shownEnd) lead = 0
            val start = lineStart + lead
            val content = remember(highlighted, line, lead, byLine[line], currentMatch?.takeIf { it.line == line }) {
                buildAnnotatedString {
                    if (highlighted != null && highlighted.length >= shownEnd) append(highlighted.subSequence(start, shownEnd)) else append(doc.display, start, shownEnd)
                    byLine[line]?.forEach { m ->
                        val s = (m.start - start).coerceIn(0, shownEnd - start)
                        val e = (m.end - start).coerceIn(s, shownEnd - start)
                        if (e > s) addStyle(SpanStyle(background = primary.copy(alpha = if (m == currentMatch) 0.55f else 0.2f)), s, e)
                    }
                    if (cut) {
                        append("  ")
                        val at = length
                        append(String.format(shortenedNote, end - lineStart))
                        addStyle(SpanStyle(color = dev.gutter), at, length)
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth()
                    .then(if (flashLine == line) Modifier.background(primary.copy(alpha = 0.18f)) else Modifier)
                    .pointerInput(line) { detectTapGestures(onLongPress = { onLongPress(line) }) },
            ) {
                Text(
                    (line + 1).toString(),
                    style = style.copy(color = dev.gutter),
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(gutterWidth).padding(end = 12.dp),
                )
                Box(Modifier.weight(1f).clipToBounds().padding(end = 12.dp)) {
                    if (wrap) {
                        val indent = with(density) { (charWidthPx * lead).toSp() }
                        Text(content, style = style.copy(textIndent = androidx.compose.ui.text.style.TextIndent(firstLine = indent, restLine = indent)), softWrap = true)
                    } else {
                        Text(
                            content, style = style, softWrap = false, maxLines = 1,
                            modifier = Modifier.wrapContentWidth(Alignment.Start, unbounded = true).offset { IntOffset(-scrollX().roundToInt(), 0) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FindBar(
    query: String,
    onQuery: (String) -> Unit,
    count: Int,
    current: Int,
    capped: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
) {
    val dev = LocalDevColors.current
    // COUI17's animated search bar: it opens collapsed and expands on the next frame (the field
    // takes focus, cancel slides in). The match count and previous / next sit inside the capsule;
    // cancel collapses the bar and find closes once that has played.
    var expanded by remember { mutableStateOf(false) }
    var opened by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { expanded = true }
    LaunchedEffect(expanded) {
        if (expanded) {
            opened = true
        } else if (opened) {
            kotlinx.coroutines.delay(300)
            onClose()
        }
    }
    io.github.suqi8.coui.kmp.basic.SearchBar(
        inputField = {
            io.github.suqi8.coui.kmp.basic.InputField(
                query = query,
                onQueryChange = onQuery,
                onSearch = { onNext() },
                expanded = expanded,
                onExpandedChange = { expanded = it },
                label = stringResource(R.string.files_find_hint),
                modifier = Modifier.testTag("find-input"),
                trailingIcon = if (query.isEmpty()) {
                    null
                } else {
                    {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (count == 0) stringResource(R.string.files_find_none) else stringResource(R.string.files_find_count, current + 1, count) + if (capped) "+" else "",
                                style = DevType.label,
                                color = if (count == 0) dev.danger else dev.gutter,
                                modifier = Modifier.padding(horizontal = 4.dp),
                            )
                            FindStep(PhosphorIcons.Regular.CaretUp, stringResource(R.string.files_find_prev), count > 0, onPrev)
                            FindStep(PhosphorIcons.Regular.CaretDown, stringResource(R.string.files_find_next), count > 0, onNext)
                        }
                    }
                },
            )
        },
        expanded = expanded,
        onExpandedChange = { expanded = it },
        outsideEndAction = {
            io.github.suqi8.coui.kmp.basic.SearchBarActionButton(stringResource(R.string.action_cancel), onClick = { expanded = false })
        },
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    ) {}
}

/** Previous / next match inside the find capsule: a 32dp round key. */
@Composable
private fun FindStep(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    io.github.suqi8.coui.kmp.basic.IconButton(onClick = onClick, enabled = enabled, minWidth = 32.dp, minHeight = 32.dp) {
        io.github.suqi8.coui.kmp.basic.Icon(
            icon,
            contentDescription = description,
            tint = if (enabled) io.github.suqi8.coui.kmp.theme.COUITheme.colorScheme.onSurface else io.github.suqi8.coui.kmp.theme.COUITheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun GotoLineDialog(lineCount: Int, onDismiss: () -> Unit, onGo: (Int) -> Unit) {
    var value by remember { mutableStateOf("") }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val n = value.trim().toIntOrNull()
    val valid = n != null && n in 1..lineCount
    FormDialog(
        show = true,
        title = stringResource(R.string.files_goto_line),
        confirmText = stringResource(R.string.action_ok),
        confirmEnabled = valid,
        onConfirm = { if (valid) onGo(n!!) },
        onDismiss = onDismiss,
    ) {
        TextInput(
            value, { value = it.filter(Char::isDigit) }, stringResource(R.string.files_goto_line_hint, lineCount),
            modifier = Modifier.focusRequester(focus),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { if (valid) onGo(n!!) }),
        )

    }
}
