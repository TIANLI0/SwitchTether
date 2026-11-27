package io.github.tianli0.switchtether.ui.keys

import android.view.KeyEvent

/**
 * Hardware keyboard shortcuts (tablets, DeX, Bluetooth keyboards; docs/ANDROID_UI.md §35). The
 * tables here are plain Kotlin so they are unit tested on the JVM; `ShortcutHost.kt` feeds them
 * key events and runs what the screens on show registered.
 */
enum class ShortcutAction {
    /** Ctrl+Enter in the composer. */
    SEND,

    /** Esc: interrupt the running turn (asks first, like the stop capsule). */
    INTERRUPT,

    /** Ctrl+K / Ctrl+F: the session search. */
    SEARCH,

    /** Ctrl+N: the new-session launcher. */
    NEW_SESSION,
    TAB_HOME,
    TAB_SESSIONS,
    TAB_WORKSPACE,

    /** Ctrl+/: the list of slash commands. */
    SLASH_PANEL,

    /** Alt+↑ / Alt+↓: the session above / below in the list beside the conversation. */
    PREVIOUS_SESSION,
    NEXT_SESSION,
}

/** One key press with its modifiers (Android key codes). */
data class KeyChord(
    val keyCode: Int,
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
    val meta: Boolean = false,
) {
    val plain: Boolean get() = !ctrl && !alt && !shift && !meta

    /** Exactly Ctrl (no Alt, Shift or Meta). */
    val ctrlOnly: Boolean get() = ctrl && !alt && !shift && !meta

    /** Exactly Alt. */
    val altOnly: Boolean get() = alt && !ctrl && !shift && !meta

    companion object {
        fun of(e: KeyEvent) = KeyChord(e.keyCode, e.isCtrlPressed, e.isAltPressed, e.isShiftPressed, e.isMetaPressed)
    }
}

/** Where keyboard focus is, as far as shortcuts care. */
enum class KeyFocus {
    /** Anything but the terminal (a text field included: none of the chords below edits text). */
    ELSEWHERE,

    /** The terminal's input: every key, Ctrl and Esc included, belongs to the remote shell. */
    TERMINAL,
}

object KeyShortcuts {
    private fun isEnter(code: Int) = code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER

    /**
     * Chords taken before the focused view sees them (they mean nothing to a text field). Never
     * while the terminal has focus, and never with Meta (the system's own shortcuts, Meta+/ help).
     */
    fun global(c: KeyChord, focus: KeyFocus): ShortcutAction? {
        if (focus == KeyFocus.TERMINAL) return null
        if (c.ctrlOnly) {
            return when (c.keyCode) {
                KeyEvent.KEYCODE_K, KeyEvent.KEYCODE_F -> ShortcutAction.SEARCH
                KeyEvent.KEYCODE_N -> ShortcutAction.NEW_SESSION
                KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_NUMPAD_1 -> ShortcutAction.TAB_HOME
                KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_NUMPAD_2 -> ShortcutAction.TAB_SESSIONS
                KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_NUMPAD_3 -> ShortcutAction.TAB_WORKSPACE
                KeyEvent.KEYCODE_SLASH, KeyEvent.KEYCODE_NUMPAD_DIVIDE -> ShortcutAction.SLASH_PANEL
                else -> null
            }
        }
        if (c.altOnly) {
            return when (c.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> ShortcutAction.PREVIOUS_SESSION
                KeyEvent.KEYCODE_DPAD_DOWN -> ShortcutAction.NEXT_SESSION
                else -> null
            }
        }
        return null
    }

    /**
     * Keys taken only when the focused view left them alone: plain Esc. When no screen wants it
     * (nothing running) it stays unhandled and Android turns it into Back, as before.
     */
    fun unhandled(c: KeyChord, focus: KeyFocus): ShortcutAction? =
        if (focus != KeyFocus.TERMINAL && c.plain && c.keyCode == KeyEvent.KEYCODE_ESCAPE) ShortcutAction.INTERRUPT else null

    /**
     * The composer's own keys, looked at before the text field. Ctrl+Enter (or Meta+Enter) sends;
     * Enter and Shift+Enter stay a new line, as on the soft keyboard (the composer is multi-line
     * and has no "Enter sends" mode; an IME composing Chinese also needs Enter).
     */
    fun composer(c: KeyChord): ShortcutAction? =
        if (isEnter(c.keyCode) && (c.ctrl || c.meta) && !c.alt && !c.shift) ShortcutAction.SEND else null

    /**
     * An approval card that has keyboard focus: Y (or Ctrl+Enter) allows, N denies. Only plain
     * allow / deny cards (the plan card's three answers have no single "yes"), only options the card
     * offers, and only while its buttons are enabled. The decision goes through the same call as a
     * tap, so step-up and the command tracker apply unchanged.
     */
    fun approval(c: KeyChord, optionIds: Collection<String>, enabled: Boolean, planCard: Boolean): String? {
        if (!enabled || planCard) return null
        val id = when {
            c.plain && c.keyCode == KeyEvent.KEYCODE_Y -> "allow"
            c.plain && c.keyCode == KeyEvent.KEYCODE_N -> "deny"
            c.ctrlOnly && isEnter(c.keyCode) -> "allow"
            else -> null
        }
        return id?.takeIf { it in optionIds }
    }

    /** What the system's keyboard shortcuts helper (Meta+/) lists. */
    data class HelpEntry(val group: HelpGroup, val label: HelpLabel, val keyCode: Int, val modifiers: Int)

    enum class HelpGroup { GENERAL, SESSION }

    enum class HelpLabel { SEARCH, NEW_SESSION, TAB_HOME, TAB_SESSIONS, TAB_WORKSPACE, PREVIOUS_SESSION, NEXT_SESSION, SEND, INTERRUPT, SLASH_PANEL, APPROVE, DENY }

    val help: List<HelpEntry> = listOf(
        HelpEntry(HelpGroup.GENERAL, HelpLabel.SEARCH, KeyEvent.KEYCODE_K, KeyEvent.META_CTRL_ON),
        HelpEntry(HelpGroup.GENERAL, HelpLabel.SEARCH, KeyEvent.KEYCODE_F, KeyEvent.META_CTRL_ON),
        HelpEntry(HelpGroup.GENERAL, HelpLabel.NEW_SESSION, KeyEvent.KEYCODE_N, KeyEvent.META_CTRL_ON),
        HelpEntry(HelpGroup.GENERAL, HelpLabel.TAB_HOME, KeyEvent.KEYCODE_1, KeyEvent.META_CTRL_ON),
        HelpEntry(HelpGroup.GENERAL, HelpLabel.TAB_SESSIONS, KeyEvent.KEYCODE_2, KeyEvent.META_CTRL_ON),
        HelpEntry(HelpGroup.GENERAL, HelpLabel.TAB_WORKSPACE, KeyEvent.KEYCODE_3, KeyEvent.META_CTRL_ON),
        HelpEntry(HelpGroup.GENERAL, HelpLabel.PREVIOUS_SESSION, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.META_ALT_ON),
        HelpEntry(HelpGroup.GENERAL, HelpLabel.NEXT_SESSION, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.META_ALT_ON),
        HelpEntry(HelpGroup.SESSION, HelpLabel.SEND, KeyEvent.KEYCODE_ENTER, KeyEvent.META_CTRL_ON),
        HelpEntry(HelpGroup.SESSION, HelpLabel.INTERRUPT, KeyEvent.KEYCODE_ESCAPE, 0),
        HelpEntry(HelpGroup.SESSION, HelpLabel.SLASH_PANEL, KeyEvent.KEYCODE_SLASH, KeyEvent.META_CTRL_ON),
        HelpEntry(HelpGroup.SESSION, HelpLabel.APPROVE, KeyEvent.KEYCODE_Y, 0),
        HelpEntry(HelpGroup.SESSION, HelpLabel.DENY, KeyEvent.KEYCODE_N, 0),
    )
}

/** What the session page on show can do right now (gates its shortcuts). */
data class SessionKeyState(
    val canInterrupt: Boolean = false,
    /** Live and not idle: the stop capsule is shown. */
    val turnRunning: Boolean = false,
    /** The composer takes input (send now or queued for the connection). */
    val composerEnabled: Boolean = false,
    val hasSlashCommands: Boolean = false,
) {
    fun allows(a: ShortcutAction): Boolean = when (a) {
        ShortcutAction.INTERRUPT -> canInterrupt && turnRunning
        ShortcutAction.SLASH_PANEL -> composerEnabled && hasSlashCommands
        else -> false
    }
}

/** The app shell's shortcuts: tabs, search, new session; the session neighbours only beside the list. */
data class ShellKeyState(val twoPanes: Boolean, val sessionOrder: List<String>, val selectedSession: String?) {
    fun allows(a: ShortcutAction): Boolean = when (a) {
        ShortcutAction.SEARCH, ShortcutAction.NEW_SESSION, ShortcutAction.TAB_HOME, ShortcutAction.TAB_SESSIONS, ShortcutAction.TAB_WORKSPACE -> true
        ShortcutAction.PREVIOUS_SESSION -> neighbour(-1) != null
        ShortcutAction.NEXT_SESSION -> neighbour(1) != null
        else -> false
    }

    /**
     * The session [delta] rows away from the selected one in the list's order; with nothing
     * selected, the first row (down) or the last (up). Null at either end and in one-pane layouts.
     */
    fun neighbour(delta: Int): String? {
        if (!twoPanes || sessionOrder.isEmpty()) return null
        val i = sessionOrder.indexOf(selectedSession)
        if (i < 0) return if (delta > 0) sessionOrder.first() else sessionOrder.last()
        return sessionOrder.getOrNull(i + delta)
    }
}
