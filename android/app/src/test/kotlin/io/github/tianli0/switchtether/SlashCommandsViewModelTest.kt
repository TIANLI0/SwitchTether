package io.github.tianli0.switchtether

import io.github.tianli0.switchtether.connection.ConnectionState
import io.github.tianli0.switchtether.domain.SessionRepository
import io.github.tianli0.switchtether.remote.protocol.SessionDetail
import io.github.tianli0.switchtether.remote.session.SlashCommand
import io.github.tianli0.switchtether.ui.sessions.SlashAction
import io.github.tianli0.switchtether.ui.sessions.SessionDetailViewModel
import io.github.tianli0.switchtether.ui.sessions.builtinSlashCommands
import io.github.tianli0.switchtether.ui.sessions.slashCommands
import io.github.tianli0.switchtether.ui.sessions.slashSuggestions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A desktop that answers `claude/commands` with [answer] (or fails like an older one). */
class SlashRepo(val base: FakeSessionRepository, var answer: () -> List<SlashCommand> = { emptyList() }) : SessionRepository by base {
    var loads = 0
    override suspend fun claudeCommands(id: String): List<SlashCommand> {
        loads++
        return answer()
    }
}

/** A Claude Code session the phone drives (started by SwitchTether, tools offered). */
fun slashSession(agent: String = "claude_code", live: Boolean = true, tools: Boolean = true, send: String = "VERIFIED", control: String = "full") = FakeSessionRepository().apply {
    detail = SessionDetail(
        summary(control = control, status = "idle", agent = agent, live = live).copy(managed = true, origin = "managed"),
        caps(send = send),
        emptyList(),
        claudeTools = tools,
        permissionMode = "default",
    )
}

@OptIn(ExperimentalCoroutinesApi::class)
class SlashCommandsViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun names(vm: SessionDetailViewModel) = vm.state.value.slashCommands.map { it.name }
    private fun suggested(vm: SessionDetailViewModel) = vm.state.value.slashSuggestions.map { it.name }
    private val builtin = builtinSlashCommands("claude_code").map { it.name }

    @Test
    fun anOlderDesktopStillYieldsTheBuiltinList() = runTest(dispatcher) {
        // The repository throws (it should not, but nothing may break if it does) ...
        val throwing = SlashRepo(slashSession()) { throw IllegalStateException("404") }
        val vm = SessionDetailViewModel("s1", FakeConnection(), throwing, FakeDrafts())
        advanceUntilIdle()
        assertEquals(1, throwing.loads)
        assertEquals(builtin, names(vm))
        // ... or returns nothing, which is what an older desktop's 404 / 422 becomes.
        val empty = SlashRepo(slashSession())
        val vm2 = SessionDetailViewModel("s1", FakeConnection(), empty, FakeDrafts())
        advanceUntilIdle()
        assertEquals(builtin, names(vm2))
        // The real repository path: the fake has no Claude tools at all, the default swallows it.
        val vm3 = SessionDetailViewModel("s1", FakeConnection(), slashSession(), FakeDrafts())
        advanceUntilIdle()
        assertEquals(builtin, names(vm3))

        vm.setDraft("/")
        assertEquals(builtin.take(8), suggested(vm))
        vm.setDraft("/co")
        assertEquals(listOf("compact", "context", "cost"), suggested(vm))
        vm.setDraft("/compact ")
        assertTrue(suggested(vm).isEmpty())
        vm.setDraft("compact")
        assertTrue(suggested(vm).isEmpty())
    }

    @Test
    fun theDesktopsListIsMergedAndLoadedOncePerConnection() = runTest(dispatcher) {
        val repo = SlashRepo(slashSession()) { listOf(SlashCommand("compact", "From the agent", "[instructions]"), SlashCommand("deploy", "Ship it", "<env>")) }
        val conn = FakeConnection()
        val vm = SessionDetailViewModel("s1", conn, repo, FakeDrafts())
        advanceUntilIdle()
        assertEquals(1, repo.loads)
        assertEquals(builtin + "deploy", names(vm))
        assertEquals("[instructions]", vm.state.value.slashCommands.first { it.name == "compact" }.argumentHint)
        // More state changes of the same session do not ask again.
        vm.setDraft("/dep")
        vm.load()
        advanceUntilIdle()
        assertEquals(1, repo.loads)
        assertEquals(listOf("deploy"), suggested(vm))

        // Offline: the list stays for drafting; back online it is asked again.
        conn.state.value = ConnectionState.Offline(TEST_DESKTOP, null, null)
        advanceUntilIdle()
        assertEquals(1, repo.loads)
        repo.answer = { listOf(SlashCommand("ship")) }
        conn.state.value = ONLINE
        advanceUntilIdle()
        assertEquals(2, repo.loads)
        assertEquals(builtin + "ship", names(vm))
    }

    @Test
    fun pickingSendsInsertsOrHandsThePhoneSideActionBack() = runTest(dispatcher) {
        val base = slashSession()
        val repo = SlashRepo(base) { listOf(SlashCommand("review", "Review a pull request", "[pr]")) }
        val vm = SessionDetailViewModel("s1", FakeConnection(), repo, FakeDrafts())
        advanceUntilIdle()
        fun entry(name: String) = vm.state.value.slashCommands.first { it.name == name }

        // No arguments: it goes out as an ordinary message.
        vm.setDraft("/comp")
        assertEquals(SlashAction.SEND, vm.pickSlashCommand(entry("compact")))
        advanceUntilIdle()
        assertEquals(listOf("/compact"), base.sent.map { it.second })
        assertEquals("", vm.state.value.draft)

        // Arguments: the draft is ready for them and the panel is gone.
        vm.setDraft("/rev")
        assertEquals(SlashAction.INSERT, vm.pickSlashCommand(entry("review")))
        assertEquals("/review ", vm.state.value.draft)
        assertTrue(suggested(vm).isEmpty())
        assertEquals(1, base.sent.size)

        // The phone's own: nothing is sent, the typed text is cleared, the screen opens it.
        vm.setDraft("/mod")
        assertEquals(SlashAction.OPEN_MODEL, vm.pickSlashCommand(entry("model")))
        assertEquals("", vm.state.value.draft)
        assertEquals(1, base.sent.size)

        // Typed by hand and sent with the send key: a normal message, unchanged.
        vm.setDraft("/compact keep the test names")
        vm.send()
        advanceUntilIdle()
        assertEquals("/compact keep the test names", base.sent.last().second)
    }

    @Test
    fun otherAgentsAndReadOnlySessionsGetNoCommands() = runTest(dispatcher) {
        // Codex: nothing is invented, and the desktop is not even asked.
        val codex = SlashRepo(slashSession(agent = "codex", tools = false)) { listOf(SlashCommand("compact")) }
        val vm = SessionDetailViewModel("s1", FakeConnection(), codex, FakeDrafts())
        advanceUntilIdle()
        vm.setDraft("/")
        assertEquals(0, codex.loads)
        assertTrue(names(vm).isEmpty())
        assertTrue(suggested(vm).isEmpty())

        // Claude Code the phone only observes: the composer cannot send, so no panel.
        val observed = SlashRepo(slashSession(tools = false, send = "BLOCKED", control = "observe"))
        val vm2 = SessionDetailViewModel("s1", FakeConnection(), observed, FakeDrafts())
        advanceUntilIdle()
        vm2.setDraft("/")
        assertEquals(0, observed.loads)
        assertTrue(suggested(vm2).isEmpty())
    }

    @Test
    fun aConversationThatIsNotRunningOffersOnlyWhatAMessageCanDo() = runTest(dispatcher) {
        // Resumed by the next message: no session tools yet, so only commands that are messages.
        val ended = SlashRepo(slashSession(live = false, tools = false))
        val vm = SessionDetailViewModel("s1", FakeConnection(), ended, FakeDrafts())
        advanceUntilIdle()
        assertEquals(0, ended.loads)
        assertEquals(listOf("compact", "clear", "cost", "review", "init", "memory", "help"), names(vm))
    }
}
