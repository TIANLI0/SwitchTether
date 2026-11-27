package io.github.tianli0.switchtether

import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.tianli0.switchtether.ui.keys.KeyFocus
import io.github.tianli0.switchtether.ui.keys.ShortcutAction
import io.github.tianli0.switchtether.ui.keys.ShortcutRegistry
import io.github.tianli0.switchtether.ui.keys.keyboardShortcutGroups
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The activity's key routing: global chords before the views, Esc after, the terminal untouched. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en")
class ShortcutRegistryTest {
    private fun down(code: Int, meta: Int = 0, repeat: Int = 0) = KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, code, repeat, meta)
    private fun up(code: Int, meta: Int = 0) = KeyEvent(0L, 0L, KeyEvent.ACTION_UP, code, 0, meta)
    private val ctrl = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON

    private class Views(var takes: Boolean = false) {
        val seen = mutableListOf<Int>()
        fun dispatch(e: KeyEvent): Boolean {
            seen += e.keyCode
            return takes
        }
    }

    @Test
    fun newestHandlerAnswersFirstAndGlobalChordsSkipTheViews() {
        val r = ShortcutRegistry()
        val shell = mutableListOf<ShortcutAction>()
        val page = mutableListOf<ShortcutAction>()
        val shellToken = r.register { shell += it; it != ShortcutAction.INTERRUPT }
        r.register { if (it == ShortcutAction.INTERRUPT) { page += it; true } else false }
        val views = Views()
        assertTrue(r.onKeyEvent(down(KeyEvent.KEYCODE_N, ctrl), views::dispatch))
        assertTrue("its key up is swallowed too", r.onKeyEvent(up(KeyEvent.KEYCODE_N, ctrl), views::dispatch))
        assertEquals(listOf(ShortcutAction.NEW_SESSION), shell)
        assertTrue("the views never saw Ctrl+N", views.seen.isEmpty())

        // Esc: the views first; nobody took it, the page interrupts.
        assertTrue(r.onKeyEvent(down(KeyEvent.KEYCODE_ESCAPE), views::dispatch))
        assertEquals(listOf(KeyEvent.KEYCODE_ESCAPE), views.seen)
        assertEquals(listOf(ShortcutAction.INTERRUPT), page)

        // A repeat does not fire again.
        assertFalse(r.onKeyEvent(down(KeyEvent.KEYCODE_N, ctrl, repeat = 1), views::dispatch))
        assertEquals(1, shell.count { it == ShortcutAction.NEW_SESSION })

        r.unregister(shellToken)
        assertFalse("nobody handles it any more", r.onKeyEvent(down(KeyEvent.KEYCODE_K, ctrl), views::dispatch))
    }

    @Test
    fun escThatNoPageWantsStaysUnhandled() {
        val r = ShortcutRegistry()
        r.register { false }
        // Unhandled → Android's fallback turns it into Back, as before.
        assertFalse(r.onKeyEvent(down(KeyEvent.KEYCODE_ESCAPE), Views()::dispatch))
    }

    @Test
    fun aViewThatTakesEscKeepsIt() {
        val r = ShortcutRegistry()
        var asked = false
        r.register { asked = true; true }
        assertTrue(r.onKeyEvent(down(KeyEvent.KEYCODE_ESCAPE), Views(takes = true)::dispatch))
        assertFalse(asked)
    }

    @Test
    fun theTerminalGetsEveryKey() {
        val r = ShortcutRegistry()
        var asked = false
        r.register { asked = true; true }
        r.focus = KeyFocus.TERMINAL
        val views = Views()
        r.onKeyEvent(down(KeyEvent.KEYCODE_K, ctrl), views::dispatch)
        r.onKeyEvent(down(KeyEvent.KEYCODE_ESCAPE), views::dispatch)
        r.onKeyEvent(down(KeyEvent.KEYCODE_C, ctrl), views::dispatch)
        assertFalse(asked)
        assertEquals(listOf(KeyEvent.KEYCODE_K, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_C), views.seen)
    }

    @Test
    fun plainTypingGoesToTheViews() {
        val r = ShortcutRegistry()
        var asked = false
        r.register { asked = true; true }
        val views = Views(takes = true)
        r.onKeyEvent(down(KeyEvent.KEYCODE_K), views::dispatch)
        r.onKeyEvent(down(KeyEvent.KEYCODE_ENTER, ctrl), views::dispatch)
        r.onKeyEvent(down(KeyEvent.KEYCODE_V, ctrl), views::dispatch)
        assertFalse(asked)
        assertEquals(3, views.seen.size)
    }

    @Test
    fun helperGroupsAreLocalized() {
        val groups = keyboardShortcutGroups(ApplicationProvider.getApplicationContext())
        assertEquals(listOf("SwitchTether", "Session"), groups.map { it.label.toString() })
        val labels = groups.flatMap { g -> g.items.map { it.label.toString() } }
        assertTrue(labels.contains("Search sessions"))
        assertTrue(labels.contains("Interrupt the running turn"))
        assertTrue(labels.contains("Send message"))
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun helperGroupsInChinese() {
        val groups = keyboardShortcutGroups(ApplicationProvider.getApplicationContext())
        assertEquals("会话", groups[1].label.toString())
        assertTrue(groups.flatMap { g -> g.items.map { it.label.toString() } }.contains("新建会话"))
    }
}
