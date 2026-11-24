package io.github.tianli0.switchtether.domain

import io.github.tianli0.switchtether.remote.protocol.StepUpGrant
import io.github.tianli0.switchtether.remote.protocol.StepUpScopes
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StepUpGateTest {
    private fun TestScope.gate(claimTimeoutMs: Long = 30_000) =
        StepUpGate({ _, _ -> error("activity not used") }, nowMs = { currentTime }, claimTimeoutMs = claimTimeoutMs)

    /** A host that answers every request with [answer] (counting prompts). */
    private fun TestScope.host(gate: StepUpGate, prompts: MutableList<String>, answer: suspend (String) -> StepUpGrant) =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            gate.requests.collect { req -> gate.handleWith(req) { scope -> prompts += scope; answer(scope) } }
        }

    private fun TestScope.grantFor(scope: String, forMs: Long) = StepUpGrant(scope, Instant.ofEpochMilli(currentTime + forMs).toString())

    @Test
    fun noHostFailsAfterABoundedWaitAndReleasesTheLock() = runTest {
        val gate = gate()
        // Before the fix this never returned: the request was emitted to nobody and the mutex stayed held.
        val first = withTimeoutOrNull(10 * 60_000) { gate.ensure(StepUpScopes.TERMINAL) }
        assertNotNull("ensure() hung with no host", first)
        assertTrue(first!!.exceptionOrNull() is StepUpUnavailableException)
        assertEquals(30_000, currentTime)

        // The lock is free: a later action of another scope is asked normally.
        val prompts = mutableListOf<String>()
        host(gate, prompts) { grantFor(it, 600_000) }
        assertTrue(gate.ensure(StepUpScopes.FILES_WRITE).isSuccess)
        assertEquals(listOf(StepUpScopes.FILES_WRITE), prompts)
    }

    @Test
    fun aHostThatSubscribesLateStillGetsTheRequest() = runTest {
        val gate = gate()
        val prompts = mutableListOf<String>()
        val result = async { gate.ensure(StepUpScopes.TERMINAL) }
        advanceTimeBy(5_000) // e.g. the activity is being recreated
        runCurrent()
        assertFalse(result.isCompleted)
        host(gate, prompts) { grantFor(it, 600_000) }
        assertTrue(result.await().isSuccess)
        assertEquals(listOf(StepUpScopes.TERMINAL), prompts)
        assertTrue(gate.hasGrant(StepUpScopes.TERMINAL))
    }

    @Test
    fun aHostAppearingAfterTheTimeoutDoesNotShowAStalePrompt() = runTest {
        val gate = gate()
        assertTrue(gate.ensure(StepUpScopes.TERMINAL).exceptionOrNull() is StepUpUnavailableException)
        val prompts = mutableListOf<String>()
        host(gate, prompts) { grantFor(it, 600_000) }
        runCurrent()
        assertEquals(emptyList<String>(), prompts)
    }

    @Test
    fun claimedPromptIsNotCutOffByTheClaimTimeout() = runTest {
        val gate = gate(claimTimeoutMs = 1_000)
        val prompts = mutableListOf<String>()
        host(gate, prompts) { scope ->
            kotlinx.coroutines.delay(20_000) // the user takes a while at the biometric prompt
            grantFor(scope, 600_000)
        }
        assertTrue(gate.ensure(StepUpScopes.TERMINAL).isSuccess)
    }

    @Test
    fun userCancelResolvesAsDenied() = runTest {
        val gate = gate()
        host(gate, mutableListOf()) { throw Exception("user cancelled") }
        val r = gate.ensure(StepUpScopes.TERMINAL)
        assertTrue(r.isFailure)
        assertFalse(gate.hasGrant(StepUpScopes.TERMINAL))
    }

    @Test
    fun hostGoingAwayMidPromptResolvesAsDeniedAndFreesTheGate() = runTest {
        val gate = gate()
        val shown = CompletableDeferred<Unit>()
        val activityHost = host(gate, mutableListOf()) { shown.complete(Unit); awaitCancellation() }
        val result = async { gate.ensure(StepUpScopes.TERMINAL) }
        shown.await()
        activityHost.cancel() // activity destroyed while the biometric prompt is up
        assertTrue(result.await().exceptionOrNull() is StepUpCancelledException)

        val prompts = mutableListOf<String>()
        host(gate, prompts) { grantFor(it, 600_000) }
        assertTrue(gate.ensure(StepUpScopes.TERMINAL).isSuccess)
        assertEquals(listOf(StepUpScopes.TERMINAL), prompts)
    }

    @Test
    fun concurrentEnsureForOneScopeSharesOnePrompt() = runTest {
        val gate = gate()
        val prompts = mutableListOf<String>()
        val answer = CompletableDeferred<StepUpGrant>()
        host(gate, prompts) { answer.await() }
        val a = async { gate.ensure(StepUpScopes.FILES_WRITE) }
        val b = async { gate.ensure(StepUpScopes.FILES_WRITE) }
        runCurrent()
        answer.complete(grantFor(StepUpScopes.FILES_WRITE, 600_000))
        assertTrue(a.await().isSuccess)
        assertTrue(b.await().isSuccess)
        assertEquals(1, prompts.size)
    }

    @Test
    fun concurrentEnsureSharesADenialToo() = runTest {
        val gate = gate()
        val prompts = mutableListOf<String>()
        val answer = CompletableDeferred<StepUpGrant>()
        host(gate, prompts) { answer.await() }
        val a = async { gate.ensure(StepUpScopes.FILES_WRITE) }
        val b = async { gate.ensure(StepUpScopes.FILES_WRITE) }
        runCurrent()
        answer.completeExceptionally(Exception("cancelled"))
        assertTrue(a.await().isFailure)
        assertTrue(b.await().isFailure)
        assertEquals(1, prompts.size) // the second caller is not asked again right after a "no"
    }

    @Test
    fun cancelledFirstCallerHandsTheRequestToTheSecond() = runTest {
        val gate = gate()
        val a = async { gate.ensure(StepUpScopes.TERMINAL) }
        val b = async { gate.ensure(StepUpScopes.TERMINAL) }
        runCurrent()
        a.cancel() // e.g. its screen closed before any host picked the request up
        runCurrent()
        val prompts = mutableListOf<String>()
        host(gate, prompts) { grantFor(it, 600_000) }
        assertTrue(b.await().isSuccess)
        assertEquals(1, prompts.size)
    }

    @Test
    fun grantIsCachedOnlyForItsScopeAndUntilItsExpiry() = runTest {
        val gate = gate()
        val prompts = mutableListOf<String>()
        host(gate, prompts) { grantFor(it, 60_000) }
        assertTrue(gate.ensure(StepUpScopes.TERMINAL).isSuccess)
        assertTrue(gate.ensure(StepUpScopes.TERMINAL).isSuccess)
        assertEquals(1, prompts.size) // cached

        assertTrue(gate.ensure(StepUpScopes.FILES_WRITE).isSuccess)
        assertEquals(listOf(StepUpScopes.TERMINAL, StepUpScopes.FILES_WRITE), prompts) // other scope asks

        advanceTimeBy(56_000) // within 5 s of expiry the grant no longer counts
        assertFalse(gate.hasGrant(StepUpScopes.TERMINAL))
        assertTrue(gate.ensure(StepUpScopes.TERMINAL).isSuccess)
        assertEquals(3, prompts.size)

        assertTrue(gate.hasGrant(StepUpScopes.TERMINAL))
        gate.invalidate(StepUpScopes.TERMINAL) // server said step_up_required
        assertFalse(gate.hasGrant(StepUpScopes.TERMINAL))
    }

    @Test
    fun promptsOfDifferentScopesAreShownOneAtATime() = runTest {
        val gate = gate()
        val prompts = mutableListOf<String>()
        val answers = ArrayDeque<CompletableDeferred<StepUpGrant>>()
        host(gate, prompts) { CompletableDeferred<StepUpGrant>().also { answers.addLast(it) }.await() }
        val a = async { gate.ensure(StepUpScopes.TERMINAL) }
        val b = async { gate.ensure(StepUpScopes.FILES_WRITE) }
        runCurrent()
        assertEquals(listOf(StepUpScopes.TERMINAL), prompts)
        answers.removeFirst().complete(grantFor(StepUpScopes.TERMINAL, 600_000))
        runCurrent()
        assertEquals(listOf(StepUpScopes.TERMINAL, StepUpScopes.FILES_WRITE), prompts)
        answers.removeFirst().complete(grantFor(StepUpScopes.FILES_WRITE, 600_000))
        assertTrue(a.await().isSuccess)
        assertTrue(b.await().isSuccess)
    }
}
