package io.github.tianli0.switchtether

import io.github.tianli0.switchtether.domain.QUOTA_TOOLS
import io.github.tianli0.switchtether.ui.common.Formatters
import io.github.tianli0.switchtether.ui.manage.DEFAULT_APPS
import io.github.tianli0.switchtether.ui.manage.ToggleListKind
import io.github.tianli0.switchtether.ui.manage.appChoices
import io.github.tianli0.switchtether.ui.sessions.fallbackEfforts
import io.github.tianli0.switchtether.ui.sessions.launcherAgents
import io.github.tianli0.switchtether.ui.sessions.supportsEffort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Antigravity ("agy") is offered wherever the desktop lists it, and named properly. */
class AgyAppsTest {
    private val desktop = listOf("claude", "codex", "gemini", "claude-desktop", "openclaw", "agy")

    @Test
    fun theDesktopsAppListDrivesTheTabs() {
        assertEquals(listOf("claude", "codex", "gemini", "claude-desktop", "openclaw", "agy"), appChoices(desktop, emptySet()))
        assertEquals(listOf("claude", "codex", "gemini", "agy"), appChoices(desktop, emptySet(), ToggleListKind.MCP))
        assertEquals(listOf("claude", "codex", "gemini", "agy"), appChoices(desktop, emptySet(), ToggleListKind.SKILLS))
        assertEquals(listOf("claude", "codex", "gemini", "openclaw", "agy"), appChoices(desktop, emptySet(), ToggleListKind.PROMPTS))
        // Switched off on the desktop (`visibleApps`).
        assertEquals(listOf("claude", "codex", "gemini"), appChoices(desktop, setOf("agy"), ToggleListKind.MCP))
    }

    @Test
    fun offlineFallsBackToTheDefaults() {
        assertEquals(DEFAULT_APPS, appChoices(null, emptySet()))
        assertEquals(DEFAULT_APPS, appChoices(emptyList(), emptySet(), ToggleListKind.PROMPTS))
        assertEquals(listOf("claude", "gemini"), appChoices(null, setOf("codex")))
    }

    @Test
    fun agyHasALabelAndAQuota() {
        assertEquals("Antigravity", Formatters.agentLabel("agy"))
        assertTrue("agy" in QUOTA_TOOLS)
    }

    @Test
    fun launcherOffersAgyOnlyWhenTheDesktopCanStartIt() {
        assertEquals(listOf("claude_code", "codex", "dsh"), launcherAgents(setOf("managed_sessions", "dsh_web")))
        assertEquals(listOf("claude_code", "codex", "dsh", "agy"), launcherAgents(setOf("managed_sessions", "managed_agy")))
        // Thinking levels before the desktop's list arrives.
        assertEquals(listOf("low", "medium", "high", "xhigh"), fallbackEfforts("agy"))
        assertTrue(supportsEffort("agy"))
    }
}
