package io.github.tianli0.switchtether.ui.files

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
import io.github.tianli0.switchtether.ui.common.LocalBottomBarSpace
import io.github.tianli0.switchtether.ui.common.TextInput
import io.github.tianli0.switchtether.ui.common.ToolbarIcon
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
) {
    val dev = LocalDevColors.current
    val doc = remember(text) { TextDocument(text) }
    val highlighted by produceState<AnnotatedString?>(null, doc, language, dev) {
        value = null
        value = withContext(Dispatchers.Default) { CodeHighlighting.highlight(doc.display, path, language, dev) }
    }
    var wrap by rememberSaveable(path) { mutableStateOf(false) }
    var fontSp by rememberSaveable { mutableFloatStateOf(DEFAULT_FONT_SP) }
    var findOpen by rememberSaveable(path) { mutableStateOf(false) }
    var query by rememberSaveable(path) { mutableStateOf("") }
    var current by rememberSaveable(path) { mutableIntStateOf(0) }
    var gotoOpen by remember { mutableStateOf(false) }
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
        // Action row.
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
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
                WindowListPopup(show = menuOpen, onDismissRequest = { menuOpen = false }) {
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
                    ListPopupColumn {
                        items.forEachIndexed { i, (label, act) ->
                            DropdownImpl(text = label, optionSize = items.size, isSelected = false, index = i, onSelectedIndexChange = { menuOpen = false; act() })
                        }
                    }
                }
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
            Text(stringResource(R.string.files_highlight_off), style = DevType.label, color = dev.gutter, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
        }
        val lineCopied = stringResource(R.string.files_line_copied)
        val shortened = stringResource(R.string.files_line_shortened)
        Box(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp)
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
                scrollX = scrollX,
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
            scope.launch { listState.scrollToItem((line - 1).coerceIn(0, doc.lineCount - 1)) }
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
    scrollX: Float,
    style: androidx.compose.ui.text.TextStyle,
    gutterWidth: androidx.compose.ui.unit.Dp,
    listState: LazyListState,
    shortenedNote: String,
    onLongPress: (Int) -> Unit,
) {
    val dev = LocalDevColors.current
    val primary = COUITheme.colorScheme.primary
    // Matches grouped by line once, so each row looks up only its own.
    val byLine = remember(matches) { matches.groupBy { it.line } }
    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(top = 6.dp, bottom = 24.dp + LocalBottomBarSpace.current),
    ) {
        items(doc.lineCount, key = { it }) { line ->
            val start = doc.lineStart(line)
            val end = doc.lineEnd(line)
            val cut = end - start > MAX_LINE_CHARS
            val shownEnd = if (cut) start + MAX_LINE_CHARS else end
            val content = remember(highlighted, line, byLine[line], currentMatch?.takeIf { it.line == line }) {
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
                        append(String.format(shortenedNote, end - start))
                        addStyle(SpanStyle(color = dev.gutter), at, length)
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().pointerInput(line) { detectTapGestures(onLongPress = { onLongPress(line) }) },
            ) {
                Text(
                    (line + 1).toString(),
                    style = style.copy(color = dev.gutter),
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(gutterWidth).padding(end = 10.dp),
                )
                Box(Modifier.weight(1f).clipToBounds().padding(end = 4.dp)) {
                    if (wrap) {
                        Text(content, style = style, softWrap = true)
                    } else {
                        Text(
                            content, style = style, softWrap = false, maxLines = 1,
                            modifier = Modifier.wrapContentWidth(Alignment.Start, unbounded = true).offset { IntOffset(-scrollX.roundToInt(), 0) },
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
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        TextInput(
            value = query,
            onValueChange = onQuery,
            label = stringResource(R.string.files_find_hint),
            modifier = Modifier.weight(1f).testTag("find-input"),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onNext() }),
        )
        Text(
            when {
                query.isEmpty() -> ""
                count == 0 -> stringResource(R.string.files_find_none)
                else -> stringResource(R.string.files_find_count, current + 1, count) + if (capped) "+" else ""
            },
            style = DevType.label, color = if (query.isNotEmpty() && count == 0) dev.danger else dev.gutter,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
        ToolbarIcon(PhosphorIcons.Regular.CaretUp, stringResource(R.string.files_find_prev), onPrev, enabled = count > 0)
        ToolbarIcon(PhosphorIcons.Regular.CaretDown, stringResource(R.string.files_find_next), onNext, enabled = count > 0)
        ToolbarIcon(AppIcons.Close, stringResource(R.string.action_close), onClose)
    }
}

@Composable
private fun GotoLineDialog(lineCount: Int, onDismiss: () -> Unit, onGo: (Int) -> Unit) {
    var value by remember { mutableStateOf("") }
    val n = value.trim().toIntOrNull()
    val valid = n != null && n in 1..lineCount
    WindowDialog(show = true, title = stringResource(R.string.files_goto_line), onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                TextInput(
                    value, { value = it.filter(Char::isDigit) }, stringResource(R.string.files_goto_line_hint, lineCount),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { if (valid) onGo(n!!) }),
                )
            }
            DialogButtonBar(
                negative = DialogButtonBarAction(text = stringResource(R.string.action_cancel), onClick = onDismiss),
                positive = DialogButtonBarAction(text = stringResource(R.string.action_ok), enabled = valid, onClick = { if (valid) onGo(n!!) }),
            )
        }
    }
}
