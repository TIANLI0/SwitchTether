package io.github.tianli0.switchtether.ui.keys

import android.content.Context
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.tianli0.switchtether.R

/**
 * The activity's shortcut dispatcher. Screens register what they can do while they are resumed
 * ([ShortcutHandler]); the newest registration is asked first, so the session page on top answers
 * before the shell. The activity routes its key events through [onKeyEvent].
 */
class ShortcutRegistry {
    /** Set by the terminal's input while it has focus: then no key is taken from it. */
    @Volatile
    var focus: KeyFocus = KeyFocus.ELSEWHERE

    /** The session list beside the conversation, in its on-screen order (Alt+↑ / Alt+↓). */
    @Volatile
    var sessionOrder: List<Pair<String, String?>> = emptyList()

    private val handlers = ArrayList<(ShortcutAction) -> Boolean>()

    /** Key code whose down was taken: its up is swallowed too. */
    private var takenDown: Int? = null

    fun register(handler: (ShortcutAction) -> Boolean): Any = synchronized(handlers) {
        handlers += handler
        handler
    }

    fun unregister(token: Any) = synchronized(handlers) { handlers.remove(token) }

    fun dispatch(action: ShortcutAction): Boolean {
        val snapshot = synchronized(handlers) { handlers.toList() }
        return snapshot.asReversed().any { it(action) }
    }

    /**
     * Global chords first (they mean nothing to the focused view), then the view hierarchy
     * ([superDispatch]), then Esc if nothing took it. Repeats never fire an action again.
     */
    fun onKeyEvent(event: KeyEvent, superDispatch: (KeyEvent) -> Boolean): Boolean {
        val chord = KeyChord.of(event)
        val down = event.action == KeyEvent.ACTION_DOWN
        if (down && event.repeatCount == 0) {
            KeyShortcuts.global(chord, focus)?.let { if (dispatch(it)) return taken(event) }
        }
        if (event.action == KeyEvent.ACTION_UP && takenDown == event.keyCode) {
            takenDown = null
            return true
        }
        if (superDispatch(event)) return true
        if (down && event.repeatCount == 0) {
            KeyShortcuts.unhandled(chord, focus)?.let { if (dispatch(it)) return taken(event) }
        }
        return false
    }

    private fun taken(event: KeyEvent): Boolean {
        takenDown = event.keyCode
        return true
    }
}

val LocalShortcuts = staticCompositionLocalOf<ShortcutRegistry?> { null }

/** Registers [handler] while the calling page is resumed (a page under a pushed page is paused). */
@Composable
fun ShortcutHandler(handler: (ShortcutAction) -> Boolean) {
    val registry = LocalShortcuts.current ?: return
    val current by rememberUpdatedState(handler)
    LifecycleResumeEffect(registry) {
        val token = registry.register { current(it) }
        onPauseOrDispose { registry.unregister(token) }
    }
}

/** The groups shown by the system's keyboard shortcuts helper (Meta+/), in the app's language. */
fun keyboardShortcutGroups(context: Context): List<KeyboardShortcutGroup> =
    KeyShortcuts.help.groupBy { it.group }.map { (group, entries) ->
        KeyboardShortcutGroup(
            context.getString(if (group == KeyShortcuts.HelpGroup.GENERAL) R.string.kbd_group_general else R.string.kbd_group_session),
            entries.map { KeyboardShortcutInfo(context.getString(helpLabel(it.label)), it.keyCode, it.modifiers) },
        )
    }

private fun helpLabel(l: KeyShortcuts.HelpLabel): Int = when (l) {
    KeyShortcuts.HelpLabel.SEARCH -> R.string.kbd_search
    KeyShortcuts.HelpLabel.NEW_SESSION -> R.string.kbd_new_session
    KeyShortcuts.HelpLabel.TAB_HOME -> R.string.kbd_tab_home
    KeyShortcuts.HelpLabel.TAB_SESSIONS -> R.string.kbd_tab_sessions
    KeyShortcuts.HelpLabel.TAB_WORKSPACE -> R.string.kbd_tab_workspace
    KeyShortcuts.HelpLabel.PREVIOUS_SESSION -> R.string.kbd_previous_session
    KeyShortcuts.HelpLabel.NEXT_SESSION -> R.string.kbd_next_session
    KeyShortcuts.HelpLabel.SEND -> R.string.kbd_send
    KeyShortcuts.HelpLabel.INTERRUPT -> R.string.kbd_interrupt
    KeyShortcuts.HelpLabel.SLASH_PANEL -> R.string.kbd_slash
    KeyShortcuts.HelpLabel.APPROVE -> R.string.kbd_approve
    KeyShortcuts.HelpLabel.DENY -> R.string.kbd_deny
}

/** A Compose key event as a [KeyChord]. */
fun androidx.compose.ui.input.key.KeyEvent.chord(): KeyChord = KeyChord.of(nativeKeyEvent)
