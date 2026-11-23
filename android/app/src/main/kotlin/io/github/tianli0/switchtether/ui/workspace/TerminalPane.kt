package io.github.tianli0.switchtether.ui.workspace

import io.github.tianli0.switchtether.ui.icons.AppIcons

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.suqi8.coui.kmp.basic.Button
import io.github.suqi8.coui.kmp.basic.ButtonDefaults
import io.github.suqi8.coui.kmp.basic.ChipTabData
import io.github.suqi8.coui.kmp.basic.ChipTabGroup
import io.github.suqi8.coui.kmp.basic.DropdownImpl
import io.github.suqi8.coui.kmp.basic.EmptyState
import io.github.suqi8.coui.kmp.basic.ListPopupColumn
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.squircle.SquircleShape
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.window.WindowListPopup
import io.github.tianli0.switchtether.R
import io.github.tianli0.switchtether.remote.protocol.TermCursor
import io.github.tianli0.switchtether.remote.protocol.TermLine
import io.github.tianli0.switchtether.remote.terminal.TermKey
import io.github.tianli0.switchtether.remote.terminal.TermPalette
import io.github.tianli0.switchtether.ui.common.ConfirmDialog
import io.github.tianli0.switchtether.ui.common.GroupCard
import io.github.tianli0.switchtether.ui.common.LocalBottomBarSpace
import io.github.tianli0.switchtether.ui.common.ToolbarIcon
import io.github.tianli0.switchtether.ui.theme.LocalDevColors

private val TermBg = Color(0xFF15171A)
private val TermFg = Color(0xFFDADDE1)
private const val SENTINEL = "\u200B\u200B"

/** Builds one terminal row; the cursor cell is drawn inverted. */
fun termLineAnnotated(line: TermLine, cursorX: Int?): AnnotatedString = buildAnnotatedString {
    var col = 0
    for (run in line.runs) {
        var fg = TermPalette.argb(run.fg)?.let { Color(it) } ?: TermFg
        var bg = TermPalette.argb(run.bg)?.let { Color(it) } ?: Color.Unspecified
        if (run.inv) {
            val f = fg
            fg = if (bg == Color.Unspecified) TermBg else bg
            bg = f
        }
        val style = SpanStyle(
            color = fg,
            background = bg,
            fontWeight = if (run.b) FontWeight.Bold else null,
            fontStyle = if (run.i) FontStyle.Italic else null,
            textDecoration = if (run.u) TextDecoration.Underline else null,
        )
        for (ch in run.t) {
            if (cursorX != null && col == cursorX) {
                pushStyle(style.copy(color = TermBg, background = TermFg)); append(ch); pop()
            } else {
                pushStyle(style); append(ch); pop()
            }
            col++
        }
    }
    if (cursorX != null && cursorX >= col) {
        repeat(cursorX - col) { append(' ') }
        pushStyle(SpanStyle(background = TermFg)); append(' '); pop()
    }
}

/** Dialog / popup state shared by the page toolbar ([TerminalActions]) and [TerminalPane]. */
class TerminalController {
    var showShells by mutableStateOf(false)
    var confirmKill by mutableStateOf(false)
}

/** Short name of a shell or terminal title: "C:\\WINDOWS\\system32\\cmd.exe" -> "cmd". */
fun shortShellName(raw: String): String =
    raw.trim().trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')
        .removeSuffix(".exe").removeSuffix(".EXE").ifBlank { raw }

/**
 * Grid size a terminal will get on this screen (full width, the height above the key row), used
 * when one is created before its page has measured itself, so the shell's first output (banner,
 * prompt) is not wrapped for a different width.
 */
@Composable
fun rememberTerminalSizeEstimate(fontSp: Int): Pair<Int, Int> {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val window = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize
    return remember(fontSp, window, density) {
        val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSp.sp, lineHeight = (fontSp * 1.25f).sp)
        val cellW = measurer.measure("M".repeat(128), style).size.width / 128f
        val cellH = measurer.measure("M", style).size.height.toFloat()
        val w = window.width - with(density) { 40.dp.toPx() }
        val h = window.height - with(density) { 300.dp.toPx() }
        (w / cellW).toInt().coerceIn(20, 400) to (h / cellH).toInt().coerceIn(8, 200)
    }
}

/** Page toolbar of a terminal: paste, and a menu with the rarer actions. */
@Composable
fun TerminalActions(state: TerminalUiState, vm: TerminalViewModel, controller: TerminalController) {
    val clipboard = LocalClipboardManager.current
    var menu by remember { mutableStateOf(false) }
    // No terminal on screen yet: its size is not measured, so estimate it from the screen.
    val estimate = rememberTerminalSizeEstimate(state.fontSp).takeIf { state.selectedId == null }
    if (state.selectedId != null) {
        ToolbarIcon(AppIcons.Paste, stringResource(R.string.term_paste), { clipboard.getText()?.text?.let(vm::paste) }, enabled = state.link == TermLink.OPEN)
    }
    Box {
        ToolbarIcon(AppIcons.More, stringResource(R.string.files_more), { menu = true })
        WindowListPopup(show = menu, onDismissRequest = { menu = false }) {
            val items = buildList<Pair<String, () -> Unit>> {
                add(stringResource(R.string.term_new) to { controller.showShells = true })
                if (state.selectedId != null) {
                    add(stringResource(R.string.term_copy_screen) to { clipboard.setText(AnnotatedString(state.screen.plainText())) })
                    add(stringResource(R.string.action_refresh) to { vm.refresh() })
                    add(stringResource(R.string.term_kill) to { controller.confirmKill = true })
                }
            }
            ListPopupColumn {
                items.forEachIndexed { idx, (label, act) ->
                    DropdownImpl(
                        text = label,
                        optionSize = items.size,
                        isSelected = false,
                        index = idx,
                        onSelectedIndexChange = {
                            menu = false
                            act()
                        },
                    )
                }
            }
        }
        WindowListPopup(show = controller.showShells, onDismissRequest = { controller.showShells = false }) {
            val shells = state.shells.ifEmpty { listOf(io.github.tianli0.switchtether.remote.protocol.ShellInfo("", stringResource(R.string.term_default_shell), "")) }
            ListPopupColumn {
                shells.forEachIndexed { idx, sh ->
                    DropdownImpl(
                        text = sh.name.ifBlank { sh.id },
                        optionSize = shells.size,
                        isSelected = false,
                        index = idx,
                        onSelectedIndexChange = {
                            controller.showShells = false
                            vm.create(sh.id.ifBlank { null }, estimate)
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TerminalPane(state: TerminalUiState, vm: TerminalViewModel, controller: TerminalController = remember { TerminalController() }) {
    val clipboard = LocalClipboardManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    Column(Modifier.fillMaxSize()) {
        // Tabs only when there is a choice; actions live in the page toolbar.
        if (state.terminals.size > 1) {
            ChipTabGroup(
                tabs = state.terminals.map { ChipTabData(shortShellName(it.title.ifBlank { it.shell }).take(18)) },
                selectedIndex = state.terminals.indexOfFirst { it.id == state.selectedId }.coerceAtLeast(0),
                onSelectedChange = { vm.select(state.terminals[it].id) },
                contentPadding = PaddingValues(horizontal = 16.dp),
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        state.error?.let { Text(it, style = COUITheme.textStyles.footnote1, color = LocalDevColors.current.danger, modifier = Modifier.padding(horizontal = 16.dp)) }
        when {
            state.link == TermLink.NO_STEP_UP_KEY -> Box(Modifier.padding(top = 8.dp)) { GroupCard { EmptyState(title = stringResource(R.string.term_no_stepup), modifier = Modifier.padding(24.dp)) } }
            state.link == TermLink.NEEDS_STEP_UP -> Box(Modifier.padding(top = 8.dp)) {
                GroupCard {
                    Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.term_needs_stepup), style = COUITheme.textStyles.body1, color = COUITheme.colorScheme.onSurface)
                        Button(onClick = { vm.connect() }, colors = ButtonDefaults.buttonColorsPrimary(), modifier = Modifier.padding(top = 12.dp)) {
                            Text(stringResource(R.string.term_confirm), color = COUITheme.colorScheme.onPrimary)
                        }
                    }
                }
            }
            state.selectedId == null -> Box(Modifier.padding(top = 8.dp)) {
                GroupCard {
                    EmptyState(
                        title = stringResource(R.string.term_empty),
                        subtitle = stringResource(R.string.workspace_security),
                        actionText = stringResource(R.string.term_new),
                        onActionClick = { controller.showShells = true },
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }
            else -> {
                TerminalSurface(
                    state = state,
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp).clip(SquircleShape(14.dp)).background(TermBg)
                        .clickable { focus.requestFocus(); keyboard?.show() },
                    onSize = vm::resize,
                    onCopyLine = { clipboard.setText(AnnotatedString(it)) },
                    onLoadScrollback = { vm.loadScrollback() },
                )
                if (state.screen.exited) {
                    Text(stringResource(R.string.term_exited, state.screen.flags.exitCode?.toString() ?: "?"), style = COUITheme.textStyles.footnote1, color = LocalDevColors.current.warn, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                } else if (state.link == TermLink.CLOSED || state.link == TermLink.IDLE) {
                    // Keys cannot reach the computer now: say so next to the keys, with the way back.
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.term_disconnected), style = COUITheme.textStyles.footnote1, color = LocalDevColors.current.warn, modifier = Modifier.weight(1f))
                        TextButton(text = stringResource(R.string.term_reconnect), onClick = { vm.connect() })
                    }
                } else if (state.link == TermLink.CONNECTING) {
                    Text(stringResource(R.string.term_connecting), style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }
                val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
                ExtraKeys(state, vm, imeVisible = imeVisible, onKeyboard = {
                    if (imeVisible) {
                        keyboard?.hide()
                    } else {
                        focus.requestFocus()
                        keyboard?.show()
                    }
                })
                HiddenInput(focus, onText = vm::typeText, onKey = vm::key)
                if (WindowInsets.ime.getBottom(LocalDensity.current) == 0) {
                    Spacer(Modifier.height(LocalBottomBarSpace.current))
                }
            }
        }
    }
    LaunchedEffect(state.link) { if (state.link == TermLink.OPEN) runCatching { focus.requestFocus() } }
    ConfirmDialog(
        show = controller.confirmKill,
        title = stringResource(R.string.term_kill),
        message = stringResource(R.string.term_kill_confirm),
        confirmText = stringResource(R.string.term_kill),
        onConfirm = { controller.confirmKill = false; vm.kill() },
        onDismiss = { controller.confirmKill = false },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TerminalSurface(state: TerminalUiState, modifier: Modifier, onSize: (Int, Int) -> Unit, onCopyLine: (String) -> Unit, onLoadScrollback: () -> Unit) {
    val measurer = rememberTextMeasurer()
    val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = state.fontSp.sp, lineHeight = (state.fontSp * 1.25f).sp, color = TermFg)
    val density = LocalDensity.current
    val cell = remember(state.fontSp) { measurer.measure("M", style) }
    // One glyph's width is rounded up to whole pixels; the real advance is fractional. Averaging a
    // long run gives the true cell width, so the column count we report fits the screen exactly
    // (otherwise the shell wraps lines well before the right edge).
    val cellWidthPx = remember(state.fontSp) { measurer.measure("M".repeat(128), style).size.width / 128f }
    val listState = rememberLazyListState()
    val lines = state.scrollback + state.screen.lines
    val cursor: TermCursor = state.screen.cursor
    val screenStart = state.scrollback.size
    val termDesc = androidx.compose.ui.res.stringResource(io.github.tianli0.switchtether.R.string.terminal_desc)
    BoxWithConstraints(modifier.semantics { contentDescription = termDesc }.testTag("terminal")) {
        val cols = with(density) { ((maxWidth - 16.dp).toPx() / cellWidthPx).toInt() }
        val rows = with(density) { ((maxHeight - 8.dp).toPx() / cell.size.height).toInt() }
        LaunchedEffect(cols, rows) { onSize(cols, rows) }
        // Follow new output only while the user is at the bottom; scrolling up to read stops it.
        var follow by remember { mutableStateOf(true) }
        LaunchedEffect(listState) {
            snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }.collect { (moving, more) -> if (moving) follow = !more }
        }
        LaunchedEffect(lines.size, state.screen.version) { if (lines.isNotEmpty() && follow) listState.scrollToItem(lines.size) }
        // Earlier output is prepended: shift the position by what was added so the text stays put.
        var prevScrollback by remember { mutableIntStateOf(state.scrollback.size) }
        LaunchedEffect(state.scrollback.size) {
            val added = state.scrollback.size - prevScrollback
            prevScrollback = state.scrollback.size
            if (added > 0 && !follow) listState.scrollToItem(listState.firstVisibleItemIndex + added, listState.firstVisibleItemScrollOffset)
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp)) {
            item(key = "sb") {
                // A quiet line, not a button over the output.
                if (!state.scrollbackDone) {
                    Text(
                        stringResource(R.string.term_scrollback_short),
                        fontSize = 12.sp,
                        color = TermFg.copy(alpha = 0.45f),
                        modifier = Modifier.clip(SquircleShape(6.dp)).clickable(onClick = onLoadScrollback).padding(vertical = 6.dp, horizontal = 2.dp),
                    )
                }
            }
            itemsIndexed(lines, key = { i, _ -> i }) { i, line ->
                val y = i - screenStart
                val cx = if (y >= 0 && y == cursor.y && cursor.visible && !state.screen.exited) cursor.x else null
                Text(
                    termLineAnnotated(line, cx),
                    style = style,
                    softWrap = false,
                    maxLines = 1,
                    modifier = Modifier.heightIn(min = with(density) { cell.size.height.toDp() })
                        // Long-press copies the line; no press highlight over the terminal grid.
                        .combinedClickable(interactionSource = null, indication = null, onClick = {}, onLongClick = { onCopyLine(line.text.trimEnd()) }),
                )
            }
        }
    }
}

@Composable
private fun ExtraKeys(state: TerminalUiState, vm: TerminalViewModel, imeVisible: Boolean, onKeyboard: () -> Unit) {
    val keys: List<Pair<String, () -> Unit>> = listOf(
        (if (imeVisible) "⌨↓" else "⌨") to onKeyboard,
        "⏎" to { vm.key(TermKey.ENTER) },
        "^C" to { vm.typeText("\u0003") },
        "^D" to { vm.typeText("\u0004") },
        "Tab" to { vm.key(TermKey.TAB) },
        "Esc" to { vm.key(TermKey.ESC) },
        (if (state.ctrl) "CTRL" else "Ctrl") to vm::toggleCtrl,
        (if (state.alt) "ALT" else "Alt") to vm::toggleAlt,
        "↑" to { vm.key(TermKey.UP) },
        "↓" to { vm.key(TermKey.DOWN) },
        "←" to { vm.key(TermKey.LEFT) },
        "→" to { vm.key(TermKey.RIGHT) },
        "/" to { vm.typeText("/") },
        "~" to { vm.typeText("~") },
        "-" to { vm.typeText("-") },
        "|" to { vm.typeText("|") },
        "Home" to { vm.key(TermKey.HOME) },
        "End" to { vm.key(TermKey.END) },
        "PgUp" to { vm.key(TermKey.PAGE_UP) },
        "PgDn" to { vm.key(TermKey.PAGE_DOWN) },
        "A-" to { vm.fontSize(-1) },
        "A+" to { vm.fontSize(+1) },
    )
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        keys.forEach { (label, action) ->
            val latched = (label == "CTRL") || (label == "ALT")
            Box(
                Modifier.heightIn(min = 38.dp).widthIn(min = 42.dp).clip(SquircleShape(10.dp))
                    .background(if (latched) COUITheme.colorScheme.primary else COUITheme.colorScheme.surfaceContainerHigh)
                    .clickable(onClick = action)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, fontFamily = FontFamily.Monospace, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = if (latched) COUITheme.colorScheme.onPrimary else COUITheme.colorScheme.onSurface)
            }
        }
    }
}

/**
 * Invisible text field that receives the soft keyboard. It starts with a 2-char sentinel so a
 * Backspace on an empty line is still observable; edits are diffed and forwarded.
 */
@Composable
private fun HiddenInput(focus: FocusRequester, onText: (String) -> Unit, onKey: (TermKey) -> Unit) {
    var value by remember { mutableStateOf(TextFieldValue(SENTINEL, TextRange(SENTINEL.length))) }
    BasicTextField(
        value = value,
        onValueChange = { nv ->
            // Diff against what the field held before: appended text is typed, removed text is sent
            // as Backspace, a replacement (IME correction) is both. Works with composing IMEs.
            val old = value.text
            val new = nv.text
            val common = old.commonPrefixWith(new).length
            repeat(old.length - common) { onKey(TermKey.BACKSPACE) }
            val added = new.substring(common).replace("\u200B", "")
            if (added.isNotEmpty()) onText(added)
            value = if (!new.startsWith(SENTINEL) || new.length > 256) TextFieldValue(SENTINEL, TextRange(SENTINEL.length)) else nv
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Send, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onSend = { onKey(TermKey.ENTER) }),
        modifier = Modifier.size(1.dp).alpha(0f).focusRequester(focus).onPreviewKeyEvent { ev ->
            if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val k = when (ev.key) {
                Key.Enter, Key.NumPadEnter -> TermKey.ENTER
                Key.DirectionUp -> TermKey.UP
                Key.DirectionDown -> TermKey.DOWN
                Key.DirectionLeft -> TermKey.LEFT
                Key.DirectionRight -> TermKey.RIGHT
                Key.Escape -> TermKey.ESC
                Key.Tab -> TermKey.TAB
                else -> null
            }
            if (k != null) onKey(k)
            k != null
        }.testTag("terminal-input"),
    )
}

