package io.github.tianli0.switchtether.domain

import androidx.fragment.app.FragmentActivity
import io.github.tianli0.switchtether.remote.protocol.StepUpGrant
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** No screen picked the step-up request up in time, so the user was never asked. */
class StepUpUnavailableException : Exception("no screen could show the step-up prompt")

/** The user dismissed the prompt, or the screen showing it went away. */
class StepUpCancelledException(cause: Throwable? = null) : Exception("step-up cancelled", cause)

/**
 * Bridges ViewModels (no Activity) and BiometricPrompt (needs a FragmentActivity) for step-up
 * (docs/PROTOCOL.md §4). `ensure(scope)` returns immediately while a grant is cached, otherwise it
 * publishes a request that the activity-level composable performs (challenge → biometric signature →
 * verify) and suspends until the user finished or cancelled. Grants are cached in memory only, with
 * the server's expiry; a server `step_up_required` calls [invalidate].
 *
 * Guarantees (each one has a test in StepUpGateTest):
 * - the pending request is held in a StateFlow, so a host that subscribes after `ensure` was called
 *   (screen recreated, app brought to the front) still receives it;
 * - if no host claims it within [claimTimeoutMs], `ensure` fails with [StepUpUnavailableException]
 *   and a host that shows up later ignores it — the gate never waits forever;
 * - a host cancelled mid-prompt (activity destroyed) resolves the request as cancelled;
 * - concurrent `ensure` calls for one scope share a single prompt and its outcome;
 * - prompts are shown one at a time; the lock is always released (`withLock`, bounded waits).
 */
class StepUpGate(
    private val perform: suspend (FragmentActivity, String) -> StepUpGrant,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val claimTimeoutMs: Long = CLAIM_TIMEOUT_MS,
) {
    class Request(val scope: String, val result: CompletableDeferred<Result<StepUpGrant>>) {
        private val state = AtomicInteger(WAITING)

        /** The host takes this request; false when it was already taken or given up on. */
        internal fun claim(): Boolean = state.compareAndSet(WAITING, CLAIMED)

        /** `ensure` gives up waiting for a host; false when a host already took it. */
        internal fun abandon(): Boolean = state.compareAndSet(WAITING, ABANDONED)

        private companion object {
            const val WAITING = 0
            const val CLAIMED = 1
            const val ABANDONED = 2
        }
    }

    /** The leader of a shared ensure() went away before an outcome; followers ask again. */
    private class AskerGone : Exception()

    private val pending = MutableStateFlow<Request?>(null)

    /** Requests for the activity host; the current one is replayed to a host that subscribes late. */
    val requests: Flow<Request> = pending.filterNotNull()
    private val grants = ConcurrentHashMap<String, Long>()
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<Result<Unit>>>()
    private val lock = Mutex()

    fun hasGrant(scope: String): Boolean = (grants[scope] ?: 0L) > nowMs() + 5_000

    fun invalidate(scope: String) {
        grants.remove(scope)
    }

    fun clear() = grants.clear()

    /** Tests only: pretend a grant was obtained. */
    fun grantForTest(scope: String, untilMs: Long) {
        grants[scope] = untilMs
    }

    /**
     * Ensures a valid grant for [scope]; failure = user cancelled / no step-up key / desktop refused /
     * no screen could ask ([StepUpUnavailableException]).
     */
    suspend fun ensure(scope: String): Result<Unit> {
        while (true) {
            if (hasGrant(scope)) return Result.success(Unit)
            val mine = CompletableDeferred<Result<Unit>>()
            val shared = inFlight.putIfAbsent(scope, mine)
            if (shared != null) {
                val r = shared.await()
                if (r.exceptionOrNull() is AskerGone) continue
                return r
            }
            try {
                val r = lock.withLock { if (hasGrant(scope)) Result.success(Unit) else ask(scope) }
                mine.complete(r)
                return r
            } finally {
                inFlight.remove(scope, mine)
                mine.complete(Result.failure(AskerGone())) // no-op unless this caller was cancelled
            }
        }
    }

    private suspend fun ask(scope: String): Result<Unit> {
        val req = Request(scope, CompletableDeferred())
        pending.value = req
        try {
            val r = withTimeoutOrNull(claimTimeoutMs) { req.result.await() }
                ?: if (req.abandon()) Result.failure(StepUpUnavailableException()) else req.result.await()
            return r.map { }
        } finally {
            // Cancelled caller: a host that appears later must not show a prompt nobody waits for.
            req.abandon()
            pending.compareAndSet(req, null)
        }
    }

    /** Called by the activity-level host for each request. */
    suspend fun handle(activity: FragmentActivity, req: Request) = handleWith(req) { scope -> perform(activity, scope) }

    /** [handle] without an Activity: [prompt] stands in for challenge → biometric → verify. */
    internal suspend fun handleWith(req: Request, prompt: suspend (String) -> StepUpGrant) {
        if (req.result.isCompleted || !req.claim()) return
        // Granted meanwhile (a previous prompt for this scope finished while this one was queued).
        if (hasGrant(req.scope)) {
            req.result.complete(Result.success(StepUpGrant(req.scope)))
            return
        }
        try {
            val g = prompt(req.scope)
            val exp = runCatching { Instant.parse(g.expiresAt).toEpochMilli() }.getOrDefault(nowMs() + 10 * 60_000)
            grants[req.scope] = exp
            req.result.complete(Result.success(g))
        } catch (e: CancellationException) {
            req.result.complete(Result.failure(StepUpCancelledException(e)))
            throw e
        } catch (e: Exception) {
            req.result.complete(Result.failure(e))
        } finally {
            req.result.complete(Result.failure(StepUpCancelledException())) // no-op unless an Error escaped
        }
    }

    companion object {
        /** How long a request waits for a screen to pick it up before ensure() fails. */
        const val CLAIM_TIMEOUT_MS = 30_000L
    }
}
