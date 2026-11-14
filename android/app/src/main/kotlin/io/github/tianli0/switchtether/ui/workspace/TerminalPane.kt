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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import io.github.suqi8.coui.kmp.basic.EmptyState
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.window.WindowListPopup
import io.github.tianli0.switchtether.R
import io.github.tianli0.switchtether.remote.protocol.TermCursor
import io.github.tianli0.switchtether.remote.protocol.TermLine
import io.github.tianli0.switchtether.remote.terminal.TermKey
import io.github.tianli0.switchtether.remote.terminal.TermPalette
import io.github.tianli0.switchtether.ui.common.ConfirmDialog
import io.github.tianli0.switchtether.ui.common.GroupCard
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TerminalPane(state: TerminalUiState, vm: TerminalViewModel) {
    val clipboard = LocalClipboardManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    var showShells by remember { mutableStateOf(false) }
    var confirmKill by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        // Tabs + actions
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                if (state.terminals.isNotEmpty()) {
                    ChipTabGroup(
                        tabs = state.terminals.map { ChipTabData(it.title.ifBlank { it.shell }.take(18)) },
                        selectedIndex = state.terminals.indexOfFirst { it.id == state.selectedId }.coerceAtLeast(0),
                        onSelectedChange = { vm.select(state.terminals[it].id) },
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    )
                }
            }
            Box {
                ToolbarIcon(AppIcons.Add, stringResource(R.string.term_new), { showShells = true }, enabled = state.online && !state.busy)
                WindowListPopup(show = showShells, onDismissRequest = { showShells = false }) {
                    Column(Modifier.widthIn(min = 200.dp).padding(vertical = 8.dp)) {
                        val shells = state.shells.ifEmpty { listOf(io.github.tianli0.switchtether.remote.protocol.ShellInfo("", stringResource(R.string.term_default_shell), "")) }
                        shells.forEach { sh ->
                            Text(
                                sh.name.ifBlank { sh.id },
                                style = COUITheme.textStyles.body1,
                                color = COUITheme.colorScheme.onSurface,
                                modifier = Modifier.fillMaxWidth().clickable { showShells = false; vm.create(sh.id.ifBlank { null }) }.padding(horizontal = 20.dp, vertical = 12.dp),
                            )
                        }
                    }
                }
            }
            if (state.selectedId != null) {
                ToolbarIcon(AppIcons.Copy, stringResource(R.string.term_copy_screen), { clipboard.setText(AnnotatedString(state.screen.plainText())) })
                ToolbarIcon(AppIcons.Paste, stringResource(R.string.term_paste), { clipboard.getText()?.text?.let(vm::paste) }, enabled = state.link == TermLink.OPEN)
                ToolbarIcon(AppIcons.Delete, stringResource(R.string.term_kill), { confirmKill = true })
            }
        }
        state.error?.let { Text(it, style = COUITheme.textStyles.footnote1, color = LocalDevColors.current.danger, modifier = Modifier.padding(horizontal = 16.dp)) }
        when {
            state.link == TermLink.NO_STEP_UP_KEY -> GroupCard { EmptyState(title = stringResource(R.string.term_no_stepup), modifier = Modifier.padding(24.dp)) }
            state.link == TermLink.NEEDS_STEP_UP -> GroupCard {
                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.term_needs_stepup), style = COUITheme.textStyles.body1, color = COUITheme.colorScheme.onSurface)
                    Button(onClick = { vm.connect() }, colors = ButtonDefaults.buttonColorsPrimary(), modifier = Modifier.padding(top = 12.dp)) {
                        Text(stringResource(R.string.term_confirm), color = COUITheme.colorScheme.onPrimary)
                    }
                }
            }
            state.selectedId == null -> GroupCard {
                EmptyState(
                    title = stringResource(R.string.term_empty),
                    subtitle = stringResource(R.string.workspace_security),
                    actionText = stringResource(R.string.term_new),
                    onActionClick = { showShells = true },
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
            else -> {
                TerminalSurface(
                    state = state,
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp).clip(RoundedCornerShape(10.dp)).background(TermBg)
                        .clickable { focus.requestFocus(); keyboard?.show() },
                    onSize = vm::resize,
                    onCopyLine = { clipboard.setText(AnnotatedString(it)) },
                    onLoadScrollback = { vm.loadScrollback() },
                )
                if (state.screen.exited) {
                    Text(stringResource(R.string.term_exited, state.screen.flags.exitCode?.toString() ?: "?"), style = COUITheme.textStyles.footnote1, color = LocalDevColors.current.warn, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                } else if (state.link == TermLink.CLOSED) {
                    TextButton(text = stringResource(R.string.term_reconnect), onClick = { vm.connect() }, modifier = Modifier.padding(horizontal = 8.dp))
                }
                ExtraKeys(state, vm)
                HiddenInput(focus, onText = vm::typeText, onKey = vm::key)
            }
        }
    }
    LaunchedEffect(state.link) { if (state.link == TermLink.OPEN) runCatching { focus.requestFocus() } }
    ConfirmDialog(
        show = confirmKill,
        title = stringResource(R.string.term_kill),
        message = stringResource(R.string.term_kill_confirm),
        confirmText = stringResource(R.string.term_kill),
        onConfirm = { confirmKill = false; vm.kill() },
        onDismiss = { confirmKill = false },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TerminalSurface(state: TerminalUiState, modifier: Modifier, onSize: (Int, Int) -> Unit, onCopyLine: (String) -> Unit, onLoadScrollback: () -> Unit) {
    val measurer = rememberTextMeasurer()
    val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = state.fontSp.sp, lineHeight = (state.fontSp * 1.25f).sp, color = TermFg)
    val density = LocalDensity.current
    val cell = remember(state.fontSp) { measurer.measure("M", style) }
    val listState = rememberLazyListState()
    val lines = state.scrollback + state.screen.lines
    val cursor: TermCursor = state.screen.cursor
    val screenStart = state.scrollback.size
    val termDesc = androidx.compose.ui.res.stringResource(io.github.tianli0.switchtether.R.string.terminal_desc)
    BoxWithConstraints(modifier.semantics { contentDescription = termDesc }.testTag("terminal")) {
        val cols = with(density) { ((maxWidth - 16.dp).toPx() / cell.size.width).toInt() }
        val rows = with(density) { ((maxHeight - 8.dp).toPx() / cell.size.height).toInt() }
        LaunchedEffect(cols, rows) { onSize(cols, rows) }
        LaunchedEffect(lines.size, state.screen.version) { if (lines.isNotEmpty()) listState.scrollToItem(lines.size) }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp)) {
            item(key = "sb") {
                TextButton(
                    text = stringResource(R.string.term_scrollback, state.scrollback.size),
                    onClick = onLoadScrollback,
                    modifier = Modifier.alpha(0.7f),
                )
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
                        .combinedClickable(onClick = {}, onLongClick = { onCopyLine(line.text.trimEnd()) }),
                )
            }
        }
    }
}

@Composable
private fun ExtraKeys(state: TerminalUiState, vm: TerminalViewModel) {
    val keys: List<Pair<String, () -> Unit>> = listOf(
        "Esc" to { vm.key(TermKey.ESC) },
        "Tab" to { vm.key(TermKey.TAB) },
        (if (state.ctrl) "CTRL" else "Ctrl") to vm::toggleCtrl,
        (if (state.alt) "ALT" else "Alt") to vm::toggleAlt,
        "←" to { vm.key(TermKey.LEFT) },
        "↑" to { vm.key(TermKey.UP) },
        "↓" to { vm.key(TermKey.DOWN) },
        "→" to { vm.key(TermKey.RIGHT) },
        "Home" to { vm.key(TermKey.HOME) },
        "End" to { vm.key(TermKey.END) },
        "PgUp" to { vm.key(TermKey.PAGE_UP) },
        "PgDn" to { vm.key(TermKey.PAGE_DOWN) },
        "|" to { vm.typeText("|") },
        "~" to { vm.typeText("~") },
        "/" to { vm.typeText("/") },
        "-" to { vm.typeText("-") },
        "A-" to { vm.fontSize(-1) },
        "A+" to { vm.fontSize(+1) },
    )
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        keys.forEach { (label, action) ->
            val latched = (label == "CTRL") || (label == "ALT")
            Box(
                Modifier.heightIn(min = 40.dp).widthIn(min = 44.dp).clip(RoundedCornerShape(8.dp))
                    .background(if (latched) COUITheme.colorScheme.primary else COUITheme.colorScheme.surfaceContainer)
                    .clickable(onClick = action)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = if (latched) COUITheme.colorScheme.onPrimary else COUITheme.colorScheme.onSurface)
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

