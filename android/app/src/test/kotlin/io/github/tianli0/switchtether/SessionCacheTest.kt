package io.github.tianli0.switchtether

import io.github.tianli0.switchtether.connection.ConnectionState
import io.github.tianli0.switchtether.data.SessionCache
import io.github.tianli0.switchtether.remote.api.CommandResponse
import io.github.tianli0.switchtether.remote.command.CommandOutcome
import io.github.tianli0.switchtether.remote.protocol.EventPage
import io.github.tianli0.switchtether.remote.protocol.ProtocolJson
import io.github.tianli0.switchtether.remote.protocol.SessionDetail
import io.github.tianli0.switchtether.ui.common.Loadable
import io.github.tianli0.switchtether.ui.sessions.SessionDetailViewModel
import io.github.tianli0.switchtether.ui.sessions.SessionsViewModel
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionCacheTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val offline = ConnectionState.Offline(TEST_DESKTOP, "no route", null)

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun cacheRoundTripsListsAndConversationsPerDesktop() {
        val dir = Files.createTempDirectory("st-cache").toFile()
        val cache = SessionCache(dir, testCipher(), maxConversations = 2)
        assertNull(cache.loadList("srv"))
        cache.saveList("srv", listOf(summary("claude:a"), summary("codex:b", live = false)))
        assertEquals(listOf("claude:a", "codex:b"), cache.loadList("srv")!!.items.map { it.id })
        assertNull(cache.loadList("other-desktop"))

        val events = (1L..(SessionCache.MAX_EVENTS + 20)).map { event("e$it", it, "assistant_message", """{"text":"t$it"}""", session = "claude:a") }
        cache.saveEvents("srv", "claude:a", events, hasMoreBefore = false)
        val back = cache.loadEvents("srv", "claude:a")!!
        assertEquals(SessionCache.MAX_EVENTS, back.events.size)
        assertEquals(events.last().id, back.events.last().id)
        assertTrue("older events were dropped, so older ones exist", back.hasMoreBefore)

        // Only the most recently used conversations are kept.
        cache.saveEvents("srv", "claude:b", events.take(3), false)
        cache.saveEvents("srv", "claude:c", events.take(3), false)
        val kept = listOf("claude:a", "claude:b", "claude:c").filter { cache.loadEvents("srv", it) != null }
        assertEquals(listOf("claude:b", "claude:c"), kept)

        cache.clear()
        assertNull(cache.loadList("srv"))
        assertNull(cache.loadEvents("srv", "claude:b"))
        assertFalse(dir.exists())
    }

    @Test
    fun ids_with_colons_map_to_distinct_files() {
        assertTrue(SessionCache.safe("claude:a") != SessionCache.safe("claude_a"))
        assertFalse(SessionCache.safe("dsh:../../x").contains('/'))
    }

    @Test
    fun aFreshStartShowsTheLastListWhileOffline() = runTest(dispatcher) {
        val repo = FakeSessionRepository().apply { cached = listOf(summary("claude:a"), summary("claude:old", live = false)) }
        val sessions = SessionsViewModel(FakeConnection(offline), repo)
        advanceUntilIdle()
        assertEquals(listOf("claude:a", "claude:old"), sessions.state.value.visible.map { it.id })
    }

    @Test
    fun aConversationShowsWhereItStoodWhileOffline() = runTest(dispatcher) {
        val repo = FakeSessionRepository().apply {
            cached = listOf(summary("s1", live = false))
            cachedPage = EventPage(listOf(event("u1", 1, "user_message", """{"text":"hello"}"""), event("a1", 2, "assistant_message", """{"text":"hi"}""")), hasMoreBefore = true)
        }
        val vm = SessionDetailViewModel("s1", FakeConnection(offline), repo, FakeDrafts())
        advanceUntilIdle()
        val st = vm.state.value
        assertTrue(st.events is Loadable.Ready)
        assertEquals(2, st.log.size)
        assertTrue(st.hasMoreBefore)
        assertEquals("s1", st.detail.valueOrNull?.summary?.id)
    }

    @Test
    fun switchingModelUpdatesTheSessionFromTheDesktopsAnswer() = runTest(dispatcher) {
        val repo = FakeSessionRepository().apply {
            detail = SessionDetail(
                summary("s1", control = "full").copy(managed = true, model = "claude-haiku-5-5"),
                caps(send = "VERIFIED", approve = "VERIFIED", interrupt = "VERIFIED").copy(changeModel = "PARTIAL"),
                emptyList(),
            )
            val updated = summary("s1", control = "full").copy(managed = true, model = "sonnet", effort = "high")
            nextOutcome = { CommandOutcome.Succeeded(it, CommandResponse(ProtocolJson.encodeToJsonElement(io.github.tianli0.switchtether.remote.protocol.SessionSummary.serializer(), updated) as JsonObject, false)) }
        }
        val vm = SessionDetailViewModel("s1", FakeConnection(), repo, FakeDrafts())
        advanceUntilIdle()
        assertTrue(vm.state.value.canChangeModel)
        vm.setModel("sonnet", "switch")
        advanceUntilIdle()
        vm.setEffort("high", "switch")
        advanceUntilIdle()
        assertEquals(listOf<Pair<String?, String?>>("sonnet" to null, null to "high"), repo.optionCalls)
        val s = vm.state.value.detail.valueOrNull!!.summary
        assertEquals("sonnet", s.model)
        assertEquals("high", s.effort)
    }

    @Test
    fun historyThatResumesOnSendKeepsTheComposer() = runTest(dispatcher) {
        val repo = FakeSessionRepository().apply {
            detail = SessionDetail(summary("claude:h", live = false, control = "history"), caps(send = "PARTIAL", continueSession = "PARTIAL"), emptyList())
        }
        val vm = SessionDetailViewModel("claude:h", FakeConnection(), repo, FakeDrafts())
        advanceUntilIdle()
        val st = vm.state.value
        assertTrue(st.canSend)
        assertTrue(st.resumesOnSend)
        assertFalse(st.canChangeModel)
        assertTrue("a Claude history can still be forked", st.canFork)
    }
}
