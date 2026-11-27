package io.github.tianli0.switchtether

import io.github.tianli0.switchtether.domain.ManagementRepository
import io.github.tianli0.switchtether.domain.SESSION_ORDER
import io.github.tianli0.switchtether.domain.SessionChange
import io.github.tianli0.switchtether.domain.SessionGrouping
import io.github.tianli0.switchtether.remote.protocol.SessionPage
import io.github.tianli0.switchtether.remote.protocol.SessionSummary
import io.github.tianli0.switchtether.ui.home.HomeViewModel
import io.github.tianli0.switchtether.ui.sessions.SessionsViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** Order, filtering, search and paging of the session lists on Home and Sessions. */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionListOrderTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun s(id: String, live: Boolean = false, at: Long? = 1_000, status: String = "idle", waitingFor: String? = null, title: String? = "Task $id") =
        summary(id, live = live, status = status).copy(lastActiveAtMs = at, waitingFor = waitingFor, title = title, nativeId = id)

    private val noManagement = java.lang.reflect.Proxy.newProxyInstance(
        ManagementRepository::class.java.classLoader, arrayOf(ManagementRepository::class.java),
    ) { _, m, _ ->
        when (m.name) { "getChanges" -> kotlinx.coroutines.flow.emptyFlow<String>(); "getQuotas", "getSummaries" -> kotlinx.coroutines.flow.MutableStateFlow(emptyMap<Any, Any>()); else -> throw IllegalStateException("backend unavailable") }
    } as ManagementRepository

    @Test
    fun theOrderIsTotalWaitingFirstThenNewestThenId() {
        val all = listOf(
            s("h2", at = 5), s("h1", at = 5), s("hn2", at = null), s("hn1", at = null),
            s("busy", live = true, at = 900, status = "busy"), s("ask", live = true, at = 100, waitingFor = "approval"),
            s("wait", live = true, at = 50, status = "waiting"), s("idle", live = true, at = 900),
        )
        val expected = listOf("ask", "wait", "busy", "idle", "h1", "h2", "hn1", "hn2")
        // Whatever order the rows arrive in (first page, a later page, a live update on top).
        for (input in listOf(all, all.reversed(), all.shuffled(java.util.Random(7)))) {
            assertEquals(expected, input.sortedWith(SESSION_ORDER).map { it.id })
        }
        // "Waiting" only means something for a running session.
        assertEquals(listOf("new", "old"), listOf(s("old", at = 1, status = "waiting"), s("new", at = 2)).sortedWith(SESSION_ORDER).map { it.id })
    }

    @Test
    fun aLiveUpdateDoesNotReshuffleRowsWithTheSameOrNoTimestamp() = runTest(dispatcher) {
        val repo = FakeSessionRepository().apply { sessions = listOf(s("a", at = 5), s("b", at = 5), s("m", at = null), s("n", at = null)) }
        val vm = SessionsViewModel(FakeConnection(), repo)
        val home = HomeViewModel(FakeConnection(), repo, noManagement)
        advanceUntilIdle()
        assertEquals(listOf("a", "b", "m", "n"), vm.state.value.visible.map { it.id })
        // The same sessions again, only renamed: each update moves its session to the front of the stored list.
        repo.changeFlow.emit(SessionChange.Upserted(s("n", at = null, title = "renamed")))
        repo.changeFlow.emit(SessionChange.Upserted(s("b", at = 5, title = "renamed")))
        advanceUntilIdle()
        assertEquals(listOf("a", "b", "m", "n"), vm.state.value.visible.map { it.id })
        assertEquals(listOf("a", "b", "m", "n"), home.state.value.recent.map { it.id })
    }

    @Test
    fun theSessionsPageKeepsWaitingSessionsFirstAfterGrouping() {
        val visible = listOf(
            s("busy", live = true, at = 900, status = "busy"), s("ask", live = true, at = 100, waitingFor = "approval"),
        ).sortedWith(SESSION_ORDER)
        assertEquals(listOf("ask", "busy"), visible.map { it.id })
        // The page renders SessionGrouping.group(visible): the rows must come out in the same order.
        assertEquals(listOf("ask", "busy"), SessionGrouping.group(visible).map { it.primary.id })
        // Equal rows keep one order whatever the input order was.
        val same = listOf(s("y", at = 3), s("x", at = 3), s("z", at = null))
        assertEquals(listOf("x", "y", "z"), SessionGrouping.group(same).map { it.primary.id })
        assertEquals(listOf("x", "y", "z"), SessionGrouping.group(same.reversed()).map { it.primary.id })
    }

    @Test
    fun homeShowsTheFiveMostRecentEndedSessionsAndWaitingOnesApart() = runTest(dispatcher) {
        val repo = FakeSessionRepository().apply {
            sessions = (1..8).map { s("h$it", at = it.toLong()) } + s("none", at = null) +
                listOf(s("ask", live = true, at = 1, waitingFor = "question"), s("run", live = true, at = 9, status = "busy"), s("rest", live = true, at = 5))
        }
        val home = HomeViewModel(FakeConnection(), repo, noManagement)
        advanceUntilIdle()
        val st = home.state.value
        assertEquals(listOf("h8", "h7", "h6", "h5", "h4"), st.recent.map { it.id })
        assertEquals(listOf("ask"), st.attention.map { it.id })
        assertEquals(listOf("run"), st.running.map { it.id })
        assertEquals(listOf("rest"), st.idle.map { it.id })
    }

    @Test
    fun searchSendsTheLastKeystrokeAndKeepsUnrelatedLiveUpdatesOut() = runTest(dispatcher) {
        val login = s("login", title = "Fix login bug")
        val repo = FakeSessionRepository().apply {
            listHandler = { q, _ -> SessionPage(if (q.text == null) listOf(login, s("other", title = "Refactor build")) else listOf(login)) }
        }
        val vm = SessionsViewModel(FakeConnection(), repo)
        advanceUntilIdle()
        val before = repo.listQueries.size
        vm.setQuery("l")
        advanceTimeBy(100)
        vm.setQuery("lo")
        advanceTimeBy(299)
        vm.setQuery("log")
        advanceUntilIdle()
        assertEquals("one request, for the text as it was last typed", listOf("log"), repo.listQueries.drop(before).map { it.text })
        assertEquals(listOf("login"), vm.state.value.visible.map { it.id })

        // A session that is not in the search result changes on the computer.
        repo.changeFlow.emit(SessionChange.Upserted(s("other", live = true, title = "Refactor build")))
        repo.changeFlow.emit(SessionChange.Upserted(login.copy(title = "Fix login bug (done)")))
        advanceUntilIdle()
        assertEquals(listOf("login"), vm.state.value.visible.map { it.id })
        assertEquals("Fix login bug (done)", vm.state.value.visible.single().title)

        // Without a search every session belongs to the list again.
        vm.setQuery("")
        advanceUntilIdle()
        repo.changeFlow.emit(SessionChange.Upserted(s("brand-new", live = true)))
        advanceUntilIdle()
        assertEquals(listOf("brand-new", "login", "other"), vm.state.value.visible.map { it.id })
    }

    @Test
    fun aNextPageOfTheReplacedListIsDropped() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val repo = FakeSessionRepository().apply {
            listHandler = { q, cursor ->
                when {
                    q.agent == "codex" -> SessionPage(listOf(s("x").copy(agent = "codex")))
                    cursor == null -> SessionPage(listOf(s("a", at = 9), s("b", at = 8)), nextCursor = "c1")
                    else -> {
                        gate.await()
                        SessionPage(listOf(s("z", at = 1)), nextCursor = "c2")
                    }
                }
            }
        }
        val vm = SessionsViewModel(FakeConnection(), repo)
        advanceUntilIdle()
        vm.loadMore() // page 2 of the unfiltered list, in flight
        vm.setAgent("codex")
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()
        val st = vm.state.value
        assertEquals(listOf("x"), st.items.valueOrNull!!.map { it.id })
        assertNull("no cursor of the other list", st.nextCursor)
        assertFalse(st.loadingMore)
    }

    @Test
    fun aSessionThatMovedToTheNextPageIsNotListedTwice() = runTest(dispatcher) {
        val repo = FakeSessionRepository().apply {
            listHandler = { _, cursor ->
                // "b" was on page 1; by the time page 2 is read it has moved down into it.
                if (cursor == null) SessionPage(listOf(s("a", at = 9), s("b", at = 8)), nextCursor = "c1")
                else SessionPage(listOf(s("b", at = 8), s("c", at = 7)))
            }
        }
        val vm = SessionsViewModel(FakeConnection(), repo)
        advanceUntilIdle()
        vm.loadMore()
        advanceUntilIdle()
        assertEquals(listOf("a", "b", "c"), vm.state.value.items.valueOrNull!!.map { it.id })
        assertEquals(listOf("a", "b", "c"), vm.state.value.visible.map { it.id })
    }

    @Test
    fun aLiveUpdateDuringAListRequestIsNotOverwrittenByTheOlderAnswer() = runTest(dispatcher) {
        var gate: CompletableDeferred<Unit>? = null
        val stale = listOf(s("a", live = true, at = 5, status = "busy"), s("gone", live = true, at = 4), s("b", at = 3))
        val repo = FakeSessionRepository().apply {
            listHandler = { _, _ ->
                val answer = SessionPage(stale) // read on the computer now, delivered later
                gate?.await()
                answer
            }
        }
        val vm = SessionsViewModel(FakeConnection(), repo)
        val home = HomeViewModel(FakeConnection(), repo, noManagement)
        advanceUntilIdle()
        gate = CompletableDeferred()
        repo.refetchFlow.tryEmit("resync") // both lists refetch
        advanceUntilIdle()
        repo.changeFlow.emit(SessionChange.Upserted(s("a", live = true, at = 6, waitingFor = "approval")))
        repo.changeFlow.emit(SessionChange.Removed("gone"))
        advanceUntilIdle()
        gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("a", "b"), vm.state.value.visible.map { it.id })
        assertEquals("approval", vm.state.value.visible.first().waitingFor)
        assertEquals(listOf("a"), home.state.value.attention.map { it.id })
        assertEquals(listOf("a"), home.state.value.live.map { it.id })
    }
}
