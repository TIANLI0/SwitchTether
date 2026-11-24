package io.github.tianli0.switchtether

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import io.github.tianli0.switchtether.remote.api.ApiException
import io.github.tianli0.switchtether.remote.protocol.ApiErrorBody
import io.github.tianli0.switchtether.remote.protocol.SessionSearch
import io.github.tianli0.switchtether.remote.protocol.SessionSearchResult
import io.github.tianli0.switchtether.ui.sessions.ContentSearch
import io.github.tianli0.switchtether.ui.sessions.highlightMatches
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Search conversation content" on the Sessions page (ANDROID_UI.md §25.2). */
@OptIn(ExperimentalCoroutinesApi::class)
class ContentSearchTest {
    private fun hit(id: String) = SessionSearchResult(sessionId = id, agent = "claude_code", snippet = "… fix the Login page …", matchCount = 1)

    @Test
    fun debouncedAndOnlyTheLatestQueryCounts() = runTest {
        val calls = mutableListOf<Pair<String, String?>>()
        val cs = ContentSearch(CoroutineScope(StandardTestDispatcher(testScheduler)), { q, a ->
            calls += q to a
            // The first query answers slowly: its answer must never replace the newer one.
            if (q == "log") kotlinx.coroutines.delay(5_000)
            SessionSearch(listOf(hit("s-$q")))
        }, debounceMs = 400)
        // Off: typing does not search.
        cs.setQuery("lo", null)
        advanceUntilIdle()
        assertTrue(calls.isEmpty())
        cs.setEnabled(true)
        cs.setQuery("log", null)
        advanceTimeBy(200)
        cs.setQuery("login", "codex")
        advanceTimeBy(399)
        runCurrent()
        assertTrue(cs.state.value.searching)
        // "lo" (debounced away on enable) and "log" (replaced while debouncing) were never sent.
        assertTrue(calls.isEmpty())
        advanceUntilIdle()
        assertEquals(listOf("login" to "codex"), calls)
        assertEquals("s-login", cs.state.value.results!!.single().sessionId)
        assertFalse(cs.state.value.searching)

        // A slow answer for an older query is cancelled by a newer one.
        cs.setQuery("log", "codex")
        advanceTimeBy(1_000) // "log" request in flight
        cs.setQuery("logi", "codex")
        advanceUntilIdle()
        assertEquals("s-logi", cs.state.value.results!!.single().sessionId)

        // Clearing the box clears the results without a request.
        val before = calls.size
        cs.setQuery("  ", "codex")
        advanceUntilIdle()
        assertNull(cs.state.value.results)
        assertEquals(before, calls.size)
    }

    @Test
    fun olderComputerAndErrorsAreReported() = runTest {
        var fail: Exception = ApiException(404, ApiErrorBody("not_found", "unknown session"), null)
        val cs = ContentSearch(CoroutineScope(StandardTestDispatcher(testScheduler)), { _, _ -> throw fail }, debounceMs = 10)
        cs.setEnabled(true)
        cs.setQuery("x", null)
        advanceUntilIdle()
        assertTrue(cs.state.value.unsupported)
        assertNull(cs.state.value.error)
        fail = ApiException(400, ApiErrorBody("validation", "q must be 1..200 characters"), null)
        cs.setQuery("y", null)
        advanceUntilIdle()
        assertFalse(cs.state.value.unsupported)
        assertEquals("q must be 1..200 characters", cs.state.value.error)
        // Turning the mode off stops a pending search.
        cs.setQuery("z", null)
        cs.setEnabled(false)
        advanceUntilIdle()
        assertFalse(cs.state.value.searching)
    }

    @Test
    fun matchesAreHighlightedCaseInsensitively() {
        val style = SpanStyle(fontWeight = FontWeight.Bold)
        val s = highlightMatches("修复 Login，再测 login 页", "LOGIN", style)
        assertEquals("修复 Login，再测 login 页", s.text)
        assertEquals(listOf(3 to 8, 12 to 17), s.spanStyles.map { it.start to it.end })
        assertTrue(highlightMatches("abc", " ", style).spanStyles.isEmpty())
        assertEquals(1, highlightMatches("中文搜索测试", "搜索", style).spanStyles.size)
    }
}
