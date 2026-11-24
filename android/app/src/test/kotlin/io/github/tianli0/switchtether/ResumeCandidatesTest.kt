package io.github.tianli0.switchtether

import io.github.tianli0.switchtether.remote.protocol.SessionSummary
import io.github.tianli0.switchtether.ui.sessions.NewSessionViewModel
import io.github.tianli0.switchtether.ui.sessions.resumeCandidates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** "Continue a conversation" in the launcher: which sessions are offered, and the request that continues one. */
class ResumeCandidatesTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun session(
        id: String,
        agent: String = "claude_code",
        cwd: String? = "D:/work/app",
        at: Long = 1,
        liveness: String = "ended",
        status: String = "ended",
        waitingFor: String? = null,
    ) = SessionSummary(id = id, agent = agent, cwd = cwd, lastActiveAtMs = at, liveness = liveness, status = status, waitingFor = waitingFor, title = "t-$id")

    @Test
    fun liveBusyAndWaitingSessionsAreNotOffered() {
        val got = resumeCandidates(
            listOf(
                session("ended"),
                session("live", liveness = "live", status = "idle"),
                session("busy", status = "busy"),
                session("waiting", status = "waiting"),
                session("asking", waitingFor = "approval"),
                session("nowhere", cwd = null),
            ),
            "claude_code",
            "D:/work/app",
        )
        assertEquals(listOf("ended"), got.map { it.id })
    }

    @Test
    fun onlyTheSelectedAgentsSessions() {
        val all = listOf(session("c1"), session("c2", agent = "claude"), session("x1", agent = "codex"), session("d1", agent = "dsh"))
        assertEquals(setOf("c1", "c2"), resumeCandidates(all, "claude_code", null).map { it.id }.toSet())
        assertEquals(listOf("x1"), resumeCandidates(all, "codex", null).map { it.id })
    }

    @Test
    fun sameFolderFirstThenNewestAtMostFive() {
        val all = (1..8).map { session("other$it", cwd = "D:/work/other", at = 100L + it) } +
            listOf(session("here-old", cwd = "D:\\work\\app\\", at = 1).copy(cwd = "D:/work/app/"), session("here-new", at = 2))
        val got = resumeCandidates(all, "claude_code", "D:/work/app").map { it.id }
        assertEquals(listOf("here-new", "here-old", "other8", "other7", "other6"), got)
        assertEquals(listOf("other8", "other7", "other6", "other5", "other4"), resumeCandidates(all, "claude_code", null).map { it.id })
    }

    @Test
    fun aSelectedConversationIsContinuedInItsOwnFolder() {
        val repo = FakeSessionRepository().apply {
            sessions = listOf(session("old", cwd = "D:/work/lib", at = 5), session("running", liveness = "live", status = "busy", at = 9))
        }
        val vm = NewSessionViewModel(FakeConnection(), repo)
        vm.select("D:/work/app")
        vm.start("Start")
        assertNull(repo.lastCreate!!.resumeFrom)
        assertNull(repo.lastCreate!!.mode)

        // A live session cannot be chosen.
        vm.setResume(repo.sessions[1])
        assertNull(vm.state.value.resume)

        vm.setResume(repo.sessions[0])
        assertEquals("D:/work/lib", vm.state.value.effectiveCwd)
        vm.start("Start")
        val req = repo.lastCreate!!
        assertEquals("old", req.resumeFrom)
        assertEquals("continue", req.mode)
        assertEquals("D:/work/lib", req.cwd)
        assertEquals("claude_code", req.agent)

        // Another agent is a new conversation again.
        vm.setResume(repo.sessions[0])
        vm.setAgent("codex")
        assertNull(vm.state.value.resume)
    }
}
