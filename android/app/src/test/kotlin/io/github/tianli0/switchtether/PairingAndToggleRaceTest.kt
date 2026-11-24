package io.github.tianli0.switchtether

import io.github.tianli0.switchtether.domain.ManagementRepository
import io.github.tianli0.switchtether.domain.PairingException
import io.github.tianli0.switchtether.domain.PairingRepository
import io.github.tianli0.switchtether.domain.PairingResult
import io.github.tianli0.switchtether.remote.pairing.PairingTarget
import io.github.tianli0.switchtether.remote.protocol.AppToggleItem
import io.github.tianli0.switchtether.remote.protocol.PromptView
import io.github.tianli0.switchtether.ui.common.Loadable
import io.github.tianli0.switchtether.ui.manage.ToggleListKind
import io.github.tianli0.switchtether.ui.manage.ToggleListViewModel
import io.github.tianli0.switchtether.ui.onboarding.PairingUiState
import io.github.tianli0.switchtether.ui.onboarding.PairingViewModel
import java.lang.reflect.Proxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PairingAndToggleRaceTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val link = "switchtether://pair?v=1&h=10.0.2.2&p=47615&fp=${"ab".repeat(32)}&id=p1&s=${"A".repeat(43)}&n=PC"
    private val otherLink = "switchtether://pair?v=1&h=10.0.2.9&p=47615&fp=${"cd".repeat(32)}&id=p2&s=${"B".repeat(43)}&n=Other"

    /** A pairing request that stays in flight until the test completes it. */
    private class SlowPairing : PairingRepository {
        val targets = mutableListOf<PairingTarget>()
        val answer = CompletableDeferred<PairingResult>()
        override suspend fun pair(target: PairingTarget, deviceName: String, osVersion: String, appVersion: String): PairingResult {
            targets += target
            return answer.await()
        }
    }

    private fun pairingVm(repo: PairingRepository) = PairingViewModel(repo, { true }, "Pixel", "15", "0.1.0") {}

    @Test
    fun scanIsTakenOnlyWhileScanning() = runTest(dispatcher) {
        val repo = SlowPairing()
        val vm = pairingVm(repo)
        vm.onScanned(link) // no scanner on screen
        assertEquals(PairingUiState.Intro, vm.state.value)

        vm.startScan()
        vm.onScanned(link)
        assertEquals("10.0.2.2", (vm.state.value as PairingUiState.Confirm).link.hosts.single())
        vm.onScanned(otherLink) // the camera delivered one more frame
        assertEquals("10.0.2.2", (vm.state.value as PairingUiState.Confirm).link.hosts.single())

        vm.pair()
        assertTrue(vm.state.value is PairingUiState.Pairing)
        vm.onScanned(otherLink) // ... and another while pairing runs
        assertTrue(vm.state.value is PairingUiState.Pairing)

        repo.answer.complete(PairingResult(TEST_DESKTOP, "123456", "123456", stepUpEnabled = false))
        assertTrue(vm.state.value is PairingUiState.Done)
        assertEquals(1, repo.targets.size)
        vm.onScanned(otherLink)
        assertTrue(vm.state.value is PairingUiState.Done)
    }

    @Test
    fun scanOfSomethingElseIsReported() = runTest(dispatcher) {
        val vm = pairingVm(SlowPairing())
        vm.startScan()
        vm.onScanned("https://example.com/")
        assertTrue(vm.state.value is PairingUiState.LinkInvalid)
    }

    @Test
    fun nearbyDesktopWithoutAHostFailsVisibly() = runTest(dispatcher) {
        val repo = SlowPairing()
        val vm = pairingVm(repo)
        // Not reachable through pickNearby today (the advertisement's host is non-null); the state
        // itself allows it, so it is put there directly.
        val field = PairingViewModel::class.java.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (field.get(vm) as MutableStateFlow<PairingUiState>).value =
            PairingUiState.EnterCode("PC", null, 47615, "cd".repeat(32), manual = false)

        vm.submitCode("", "123456")

        val failed = vm.state.value as PairingUiState.Failed
        assertEquals(PairingException.Reason.UNREACHABLE, failed.reason)
        assertTrue(failed.target is PairingTarget.Code)
        assertTrue(repo.targets.isEmpty())
        // "Re-enter" leads to a screen where the address can be typed.
        vm.pair()
        val entry = vm.state.value as PairingUiState.EnterCode
        assertTrue(entry.manual)
        assertEquals("PC", entry.desktopName)
    }

    // ---- MCP / skills / prompts ------------------------------------------------------------------------

    /** Every list request stays in flight until the test answers it, in any order. */
    private class SlowLists : ManagementRepository by stub {
        val mcp = mutableListOf<CompletableDeferred<List<AppToggleItem>>>()
        val skills = mutableListOf<CompletableDeferred<List<AppToggleItem>>>()
        val prompts = mutableListOf<Pair<String, CompletableDeferred<List<PromptView>>>>()
        override suspend fun mcpServers() = CompletableDeferred<List<AppToggleItem>>().also { mcp += it }.await()
        override suspend fun skills() = CompletableDeferred<List<AppToggleItem>>().also { skills += it }.await()
        override suspend fun prompts(app: String) = CompletableDeferred<List<PromptView>>().also { prompts += app to it }.await()

        private companion object {
            val stub = Proxy.newProxyInstance(ManagementRepository::class.java.classLoader, arrayOf(ManagementRepository::class.java)) { _, m, _ ->
                throw UnsupportedOperationException(m.name)
            } as ManagementRepository
        }
    }

    private fun item(id: String, on: Boolean) = AppToggleItem(id, id, null, mapOf("claude" to on))

    @Test
    fun overlappingMcpLoadsKeepTheLatest() = runTest(dispatcher) {
        val repo = SlowLists()
        val vm = ToggleListViewModel(ToggleListKind.MCP, FakeConnection(), repo)
        vm.load() // after a second quick toggle
        assertEquals(2, repo.mcp.size)

        repo.mcp[1].complete(listOf(item("fs", on = true)))
        repo.mcp[0].complete(listOf(item("fs", on = false))) // the older answer arrives last

        assertEquals(listOf(item("fs", on = true)), (vm.state.value.items as Loadable.Ready).value)
    }

    @Test
    fun overlappingSkillLoadsKeepTheLatest() = runTest(dispatcher) {
        val repo = SlowLists()
        val vm = ToggleListViewModel(ToggleListKind.SKILLS, FakeConnection(), repo)
        vm.load()
        repo.skills[1].complete(listOf(item("pdf", on = true)))
        repo.skills[0].completeExceptionally(java.io.IOException("timeout")) // a stale failure is dropped too
        assertEquals(listOf(item("pdf", on = true)), (vm.state.value.items as Loadable.Ready).value)
    }

    @Test
    fun overlappingPromptLoadsKeepTheLatest() = runTest(dispatcher) {
        val repo = SlowLists()
        val vm = ToggleListViewModel(ToggleListKind.PROMPTS, FakeConnection(), repo)
        vm.load()
        val app = vm.state.value.app
        assertEquals(listOf(app, app), repo.prompts.map { it.first })
        repo.prompts[1].second.complete(listOf(PromptView("p", "p", null, enabled = true)))
        repo.prompts[0].second.complete(listOf(PromptView("p", "p", null, enabled = false)))
        assertEquals(listOf(PromptView("p", "p", null, enabled = true)), (vm.state.value.prompts as Loadable.Ready).value)
    }
}
