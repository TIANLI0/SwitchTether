package io.github.tianli0.switchtether

import android.view.KeyEvent
import io.github.tianli0.switchtether.ui.keys.KeyChord
import io.github.tianli0.switchtether.ui.keys.KeyFocus
import io.github.tianli0.switchtether.ui.keys.KeyShortcuts
import io.github.tianli0.switchtether.ui.keys.SessionKeyState
import io.github.tianli0.switchtether.ui.keys.ShellKeyState
import io.github.tianli0.switchtether.ui.keys.ShortcutAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The hardware keyboard dispatch tables and their gating (docs/ANDROID_UI.md §35). */
class KeyShortcutsTest {
    private fun ctrl(code: Int) = KeyChord(code, ctrl = true)
    private fun alt(code: Int) = KeyChord(code, alt = true)

    @Test
    fun globalChordsMapToTheirActions() {
        val e = KeyFocus.ELSEWHERE
        assertEquals(ShortcutAction.SEARCH, KeyShortcuts.global(ctrl(KeyEvent.KEYCODE_K), e))
        assertEquals(ShortcutAction.SEARCH, KeyShortcuts.global(ctrl(KeyEvent.KEYCODE_F), e))
        assertEquals(ShortcutAction.NEW_SESSION, KeyShortcuts.global(ctrl(KeyEvent.KEYCODE_N), e))
        assertEquals(ShortcutAction.TAB_HOME, KeyShortcuts.global(ctrl(KeyEvent.KEYCODE_1), e))
        assertEquals(ShortcutAction.TAB_SESSIONS, KeyShortcuts.global(ctrl(KeyEvent.KEYCODE_2), e))
        assertEquals(ShortcutAction.TAB_WORKSPACE, KeyShortcuts.global(ctrl(KeyEvent.KEYCODE_3), e))
        assertEquals(ShortcutAction.TAB_WORKSPACE, KeyShortcuts.global(ctrl(KeyEvent.KEYCODE_NUMPAD_3), e))
        assertEquals(ShortcutAction.SLASH_PANEL, KeyShortcuts.global(ctrl(KeyEvent.KEYCODE_SLASH), e))
        assertEquals(ShortcutAction.PREVIOUS_SESSION, KeyShortcuts.global(alt(KeyEvent.KEYCODE_DPAD_UP), e))
        assertEquals(ShortcutAction.NEXT_SESSION, KeyShortcuts.global(alt(KeyEvent.KEYCODE_DPAD_DOWN), e))
    }

    @Test
    fun globalChordsLeaveEverythingElseAlone() {
        val e = KeyFocus.ELSEWHERE
        // Text editing chords stay with the text field.
        for (code in listOf(KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_V, KeyEvent.KEYCODE_X, KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_Y)) {
            assertNull(KeyShortcuts.global(ctrl(code), e))
        }
        // Unbound digits, plain keys, extra modifiers, Meta (the system's own shortcuts).
        assertNull(KeyShortcuts.global(ctrl(KeyEvent.KEYCODE_4), e))
        assertNull(KeyShortcuts.global(KeyChord(KeyEvent.KEYCODE_K), e))
        assertNull(KeyShortcuts.global(KeyChord(KeyEvent.KEYCODE_K, ctrl = true, shift = true), e))
        assertNull(KeyShortcuts.global(KeyChord(KeyEvent.KEYCODE_N, ctrl = true, alt = true), e))
        assertNull(KeyShortcuts.global(KeyChord(KeyEvent.KEYCODE_SLASH, meta = true), e))
        assertNull(KeyShortcuts.global(KeyChord(KeyEvent.KEYCODE_DPAD_UP), e))
        assertNull(KeyShortcuts.global(KeyChord(KeyEvent.KEYCODE_DPAD_UP, ctrl = true), e))
        assertNull(KeyShortcuts.global(KeyChord(KeyEvent.KEYCODE_ESCAPE), e))
    }

    @Test
    fun theTerminalKeepsCtrlAndEsc() {
        val t = KeyFocus.TERMINAL
        for (code in listOf(KeyEvent.KEYCODE_K, KeyEvent.KEYCODE_F, KeyEvent.KEYCODE_N, KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_SLASH)) {
            assertNull("Ctrl+$code belongs to the shell", KeyShortcuts.global(ctrl(code), t))
        }
        assertNull(KeyShortcuts.global(alt(KeyEvent.KEYCODE_DPAD_UP), t))
        assertNull(KeyShortcuts.unhandled(KeyChord(KeyEvent.KEYCODE_ESCAPE), t))
    }

    @Test
    fun escIsOnlyTakenWhenNothingElseDid() {
        assertEquals(ShortcutAction.INTERRUPT, KeyShortcuts.unhandled(KeyChord(KeyEvent.KEYCODE_ESCAPE), KeyFocus.ELSEWHERE))
        assertNull(KeyShortcuts.unhandled(KeyChord(KeyEvent.KEYCODE_ESCAPE, shift = true), KeyFocus.ELSEWHERE))
        assertNull(KeyShortcuts.unhandled(KeyChord(KeyEvent.KEYCODE_BACK), KeyFocus.ELSEWHERE))
    }

    @Test
    fun composerSendsOnCtrlEnterOnly() {
        assertEquals(ShortcutAction.SEND, KeyShortcuts.composer(ctrl(KeyEvent.KEYCODE_ENTER)))
        assertEquals(ShortcutAction.SEND, KeyShortcuts.composer(ctrl(KeyEvent.KEYCODE_NUMPAD_ENTER)))
        assertEquals(ShortcutAction.SEND, KeyShortcuts.composer(KeyChord(KeyEvent.KEYCODE_ENTER, meta = true)))
        // Enter and Shift+Enter are a new line, as on the soft keyboard.
        assertNull(KeyShortcuts.composer(KeyChord(KeyEvent.KEYCODE_ENTER)))
        assertNull(KeyShortcuts.composer(KeyChord(KeyEvent.KEYCODE_ENTER, shift = true)))
        assertNull(KeyShortcuts.composer(KeyChord(KeyEvent.KEYCODE_ENTER, ctrl = true, shift = true)))
        assertNull(KeyShortcuts.composer(KeyChord(KeyEvent.KEYCODE_ENTER, ctrl = true, alt = true)))
        assertNull(KeyShortcuts.composer(ctrl(KeyEvent.KEYCODE_K)))
    }

    @Test
    fun approvalKeysNeedAnEnabledPlainCardThatOffersTheDecision() {
        val ids = listOf("allow", "allow_session", "deny")
        assertEquals("allow", KeyShortcuts.approval(KeyChord(KeyEvent.KEYCODE_Y), ids, enabled = true, planCard = false))
        assertEquals("deny", KeyShortcuts.approval(KeyChord(KeyEvent.KEYCODE_N), ids, enabled = true, planCard = false))
        assertEquals("allow", KeyShortcuts.approval(ctrl(KeyEvent.KEYCODE_ENTER), ids, enabled = true, planCard = false))
        // Disabled (offline, blocked capability, a decision on its way) → nothing.
        assertNull(KeyShortcuts.approval(KeyChord(KeyEvent.KEYCODE_Y), ids, enabled = false, planCard = false))
        // The plan card's answers have no single "yes".
        assertNull(KeyShortcuts.approval(KeyChord(KeyEvent.KEYCODE_Y), ids, enabled = true, planCard = true))
        assertNull(KeyShortcuts.approval(KeyChord(KeyEvent.KEYCODE_N), ids, enabled = true, planCard = true))
        // Only what the card offers; modifiers are not Y / N.
        assertNull(KeyShortcuts.approval(KeyChord(KeyEvent.KEYCODE_Y), listOf("deny"), enabled = true, planCard = false))
        assertNull(KeyShortcuts.approval(KeyChord(KeyEvent.KEYCODE_Y, shift = true), ids, enabled = true, planCard = false))
        assertNull(KeyShortcuts.approval(ctrl(KeyEvent.KEYCODE_Y), ids, enabled = true, planCard = false))
        assertNull(KeyShortcuts.approval(KeyChord(KeyEvent.KEYCODE_ENTER), ids, enabled = true, planCard = false))
    }

    @Test
    fun sessionShortcutsFollowWhatThePageOffers() {
        val running = SessionKeyState(canInterrupt = true, turnRunning = true, composerEnabled = true, hasSlashCommands = true)
        assertTrue(running.allows(ShortcutAction.INTERRUPT))
        assertTrue(running.allows(ShortcutAction.SLASH_PANEL))
        assertFalse("idle: Esc goes back", running.copy(turnRunning = false).allows(ShortcutAction.INTERRUPT))
        assertFalse("no interrupt capability", running.copy(canInterrupt = false).allows(ShortcutAction.INTERRUPT))
        assertFalse("composer off (offline snapshot, read-only)", running.copy(composerEnabled = false).allows(ShortcutAction.SLASH_PANEL))
        assertFalse("no commands (Codex before its list)", running.copy(hasSlashCommands = false).allows(ShortcutAction.SLASH_PANEL))
        assertFalse("the shell's", running.allows(ShortcutAction.NEW_SESSION))
    }

    @Test
    fun neighbouringSessionsOnlyBesideTheList() {
        val order = listOf("a", "b", "c")
        val two = ShellKeyState(twoPanes = true, sessionOrder = order, selectedSession = "b")
        assertEquals("a", two.neighbour(-1))
        assertEquals("c", two.neighbour(1))
        assertNull(two.copy(selectedSession = "a").neighbour(-1))
        assertNull(two.copy(selectedSession = "c").neighbour(1))
        assertFalse(two.copy(selectedSession = "c").allows(ShortcutAction.NEXT_SESSION))
        assertEquals("a", two.copy(selectedSession = null).neighbour(1))
        assertEquals("c", two.copy(selectedSession = "gone").neighbour(-1))
        val one = two.copy(twoPanes = false)
        assertNull(one.neighbour(1))
        assertFalse(one.allows(ShortcutAction.PREVIOUS_SESSION))
        assertTrue(one.allows(ShortcutAction.SEARCH))
        assertTrue(one.allows(ShortcutAction.TAB_WORKSPACE))
        assertFalse(two.copy(sessionOrder = emptyList()).allows(ShortcutAction.NEXT_SESSION))
        assertFalse("the session page's", two.allows(ShortcutAction.INTERRUPT))
    }

    @Test
    fun theHelperListsWhatIsDispatched() {
        val expected = mapOf(
            KeyShortcuts.HelpLabel.SEARCH to ShortcutAction.SEARCH,
            KeyShortcuts.HelpLabel.NEW_SESSION to ShortcutAction.NEW_SESSION,
            KeyShortcuts.HelpLabel.TAB_HOME to ShortcutAction.TAB_HOME,
            KeyShortcuts.HelpLabel.TAB_SESSIONS to ShortcutAction.TAB_SESSIONS,
            KeyShortcuts.HelpLabel.TAB_WORKSPACE to ShortcutAction.TAB_WORKSPACE,
            KeyShortcuts.HelpLabel.PREVIOUS_SESSION to ShortcutAction.PREVIOUS_SESSION,
            KeyShortcuts.HelpLabel.NEXT_SESSION to ShortcutAction.NEXT_SESSION,
            KeyShortcuts.HelpLabel.SLASH_PANEL to ShortcutAction.SLASH_PANEL,
            KeyShortcuts.HelpLabel.INTERRUPT to ShortcutAction.INTERRUPT,
            KeyShortcuts.HelpLabel.SEND to ShortcutAction.SEND,
        )
        for (h in KeyShortcuts.help) {
            val chord = KeyChord(
                h.keyCode,
                ctrl = h.modifiers and KeyEvent.META_CTRL_ON != 0,
                alt = h.modifiers and KeyEvent.META_ALT_ON != 0,
                shift = h.modifiers and KeyEvent.META_SHIFT_ON != 0,
            )
            val got = KeyShortcuts.global(chord, KeyFocus.ELSEWHERE)
                ?: KeyShortcuts.unhandled(chord, KeyFocus.ELSEWHERE)
                ?: KeyShortcuts.composer(chord)
                ?: KeyShortcuts.approval(chord, listOf("allow", "deny"), enabled = true, planCard = false)?.let { it }
            when (h.label) {
                KeyShortcuts.HelpLabel.APPROVE -> assertEquals("allow", got)
                KeyShortcuts.HelpLabel.DENY -> assertEquals("deny", got)
                else -> assertEquals(h.label.name, expected[h.label], got)
            }
        }
        // Every action has a line in the helper.
        assertEquals(ShortcutAction.entries.toSet(), expected.values.toSet())
    }
}
