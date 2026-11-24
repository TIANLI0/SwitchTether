package io.github.tianli0.switchtether

import io.github.tianli0.switchtether.ui.workspace.ImeEdit
import io.github.tianli0.switchtether.ui.workspace.SENTINEL
import io.github.tianli0.switchtether.ui.workspace.imeEdit
import org.junit.Assert.assertEquals
import org.junit.Test

/** The hidden terminal input: field edit → Backspace × n + typed text. */
class TerminalImeTest {
    private val s = SENTINEL
    private val grin = "😀" // 😀 U+1F600
    private val beam = "😁" // 😁 U+1F601, same high surrogate

    @Test
    fun plainAppend() {
        assertEquals(ImeEdit(0, "a"), imeEdit(s, s + "a"))
        assertEquals(ImeEdit(0, "c"), imeEdit(s + "ab", s + "abc"))
        assertEquals(ImeEdit(0, "hello"), imeEdit(s, s + "hello")) // swipe / suggestion commits a word
        assertEquals(ImeEdit(0, ""), imeEdit(s + "ab", s + "ab")) // selection / composing-span change only
    }

    @Test
    fun deletion() {
        assertEquals(ImeEdit(1, ""), imeEdit(s + "abc", s + "ab"))
        assertEquals(ImeEdit(3, ""), imeEdit(s + "abc", s)) // word delete
        // Backspace on an empty line eats one sentinel character: still one Backspace.
        assertEquals(ImeEdit(1, ""), imeEdit(s, s.dropLast(1)))
    }

    @Test
    fun replacementOfAComposingRegion() {
        // Autocorrect / suggestion pick: the differing tail is erased, then retyped.
        assertEquals(ImeEdit(1, "lo"), imeEdit(s + "helo", s + "hello"))
        assertEquals(ImeEdit(2, "he "), imeEdit(s + "teh", s + "the "))
        // An edit in the middle retypes everything after it.
        assertEquals(ImeEdit(5, "lo wor"), imeEdit(s + "helo wor", s + "hello wor"))
    }

    @Test
    fun zeroWidthSentinelCharactersAreNeverTyped() {
        // The field was reset by the IME to something that no longer starts with the sentinel.
        assertEquals(ImeEdit(2, "x"), imeEdit(s, "x"))
        assertEquals(ImeEdit(0, "b"), imeEdit(s + "a", s + "a​b"))
    }

    @Test
    fun emojiIsOneKey() {
        assertEquals(ImeEdit(0, grin), imeEdit(s, s + grin))
        // One emoji removed = one Backspace: the shell deletes a character, not a UTF-16 unit.
        assertEquals(ImeEdit(1, ""), imeEdit(s + grin, s))
        assertEquals(ImeEdit(1, ""), imeEdit(s + "a" + grin, s + "a"))
        assertEquals(ImeEdit(2, ""), imeEdit(s + grin + beam, s))
        assertEquals(ImeEdit(2, ""), imeEdit(s + "a" + grin, s))
    }

    @Test
    fun emojiReplacedByOneSharingItsHighSurrogateIsNotSplit() {
        assertEquals(ImeEdit(1, beam), imeEdit(s + grin, s + beam))
        assertEquals(ImeEdit(2, beam + "!"), imeEdit(s + grin + "x", s + beam + "!"))
    }

    @Test
    fun cjkCompositionCommit() {
        // Pinyin typed as a composing region, then committed as one character.
        assertEquals(ImeEdit(0, "n"), imeEdit(s, s + "n"))
        assertEquals(ImeEdit(0, "i"), imeEdit(s + "n", s + "ni"))
        assertEquals(ImeEdit(2, "你"), imeEdit(s + "ni", s + "你"))
        assertEquals(ImeEdit(0, "好"), imeEdit(s + "你", s + "你好"))
        // Candidate changed after the commit, and a committed character deleted.
        assertEquals(ImeEdit(1, "号"), imeEdit(s + "你好", s + "你号"))
        assertEquals(ImeEdit(1, ""), imeEdit(s + "你好", s + "你"))
        // A character outside the BMP (U+20BB7 𠮷) is one Backspace as well.
        assertEquals(ImeEdit(1, ""), imeEdit(s + "𠮷", s))
    }
}
