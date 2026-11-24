package io.github.tianli0.switchtether

import io.github.tianli0.switchtether.ui.sessions.shortModelName
import org.junit.Assert.assertEquals
import org.junit.Test

class ShortModelNameTest {
    @Test
    fun claudeIdsBecomeFamilyAndVersion() {
        assertEquals("Opus 5.5", shortModelName("claude-opus-5-5"))
        assertEquals("Sonnet 4.5", shortModelName("claude-sonnet-4-5-20250929"))
        assertEquals("Opus 4.8 1M", shortModelName("claude-opus-4-8[1m]"))
        assertEquals("Haiku 4", shortModelName("claude-haiku-4"))
        assertEquals("Opus", shortModelName("opus"))
    }

    @Test
    fun otherModelsAreKept() {
        assertEquals("gpt-5.1-codex", shortModelName("gpt-5.1-codex"))
        assertEquals("deepseek-v4", shortModelName("deepseek-v4"))
    }
}
