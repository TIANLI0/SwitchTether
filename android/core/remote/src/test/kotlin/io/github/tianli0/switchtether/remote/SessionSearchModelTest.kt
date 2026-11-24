package io.github.tianli0.switchtether.remote

import io.github.tianli0.switchtether.remote.protocol.ProtocolJson
import io.github.tianli0.switchtether.remote.protocol.SessionSearch
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Session search (PROTOCOL §7.13) model against the shared fixture. */
class SessionSearchModelTest {
    private fun fixture(name: String): String {
        val dir = System.getProperty("switchtether.fixtures") ?: "../../../protocol/fixtures"
        return File(dir, name).readText()
    }

    @Test
    fun sessionSearchFixture() {
        val s = ProtocolJson.decodeFromString(SessionSearch.serializer(), fixture("session_search.json"))
        assertFalse(s.partial)
        assertEquals(2, s.results.size)
        val r = s.results[0]
        assertEquals("claude:2b1c9f8e-0d1a-4c55-9a77-5e6f7a8b9c0d", r.sessionId)
        assertEquals("claude_code", r.agent)
        assertEquals("修复登录页面", r.title)
        assertEquals("D:\\proj\\app", r.cwd)
        assertEquals(1763452800000L, r.timeMs)
        assertEquals(2, r.matchCount)
        assertTrue(r.snippet.contains("login"))
        assertNull(s.results[1].title)
        assertNull(s.results[1].timeMs)
    }
}
