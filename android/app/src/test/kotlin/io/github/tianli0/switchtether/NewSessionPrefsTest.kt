package io.github.tianli0.switchtether

import io.github.tianli0.switchtether.ui.sessions.NewSessionPrefs
import io.github.tianli0.switchtether.ui.sessions.NewSessionViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** The launcher starts where the last phone-started session did: agent, model per agent, mode, folder. */
class NewSessionPrefsTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun lastChoicesAreTheNextDefaults() {
        val prefs = NewSessionPrefs.Memory()
        val first = NewSessionViewModel(FakeConnection(), FakeSessionRepository(), prefs)
        first.setAgent("codex")
        first.setModel("gpt-x")
        first.select("D:/work/app")
        first.start("Start")
        first.setAgent("claude_code")
        first.setPermissionMode("plan")

        val next = NewSessionViewModel(FakeConnection(), FakeSessionRepository(), prefs).state.value
        assertEquals("claude_code", next.agent)
        assertEquals("plan", next.permissionMode)
        assertEquals("D:/work/app", next.cwd)

        val codex = NewSessionViewModel(FakeConnection(), FakeSessionRepository(), prefs).apply { setAgent("codex") }.state.value
        assertEquals("gpt-x", codex.model)
        assertEquals(listOf("gpt-x"), codex.recentModels)
    }
}
