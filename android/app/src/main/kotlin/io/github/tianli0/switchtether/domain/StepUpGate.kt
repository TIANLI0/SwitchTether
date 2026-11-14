package io.github.tianli0.switchtether.domain

import androidx.fragment.app.FragmentActivity
import io.github.tianli0.switchtether.remote.protocol.StepUpGrant
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Bridges ViewModels (no Activity) and BiometricPrompt (needs a FragmentActivity) for step-up
 * (docs/PROTOCOL.md §4). `ensure(scope)` returns immediately while a grant is cached, otherwise it
 * emits a request that the activity-level composable performs (challenge → biometric signature →
 * verify) and suspends until the user finished or cancelled. Grants are cached in memory only, with
 * the server's expiry; a server `step_up_required` calls [invalidate].
 */
class StepUpGate(
    private val perform: suspend (FragmentActivity, String) -> StepUpGrant,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    class Request(val scope: String, val result: CompletableDeferred<Result<StepUpGrant>>)

    private val _requests = MutableSharedFlow<Request>(extraBufferCapacity = 4)
    val requests: SharedFlow<Request> = _requests.asSharedFlow()
    private val grants = ConcurrentHashMap<String, Long>()
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

    /** Ensures a valid grant for [scope]; failure = user cancelled / no step-up key / desktop refused. */
    suspend fun ensure(scope: String): Result<Unit> = lock.withLock {
        if (hasGrant(scope)) return@withLock Result.success(Unit)
        val req = Request(scope, CompletableDeferred())
        _requests.emit(req)
        req.result.await().map { }
    }

    /** Called by the activity-level host for each request. */
    suspend fun handle(activity: FragmentActivity, req: Request) {
        val r = try {
            val g = perform(activity, req.scope)
            val exp = runCatching { Instant.parse(g.expiresAt).toEpochMilli() }.getOrDefault(nowMs() + 10 * 60_000)
            grants[req.scope] = exp
            Result.success(g)
        } catch (e: CancellationException) {
            req.result.complete(Result.failure(e))
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
        req.result.complete(r)
    }
}
