package io.github.tianli0.switchtether.remote.pairing

import io.github.tianli0.switchtether.remote.api.Endpoint
import io.github.tianli0.switchtether.remote.tls.PinMismatchException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** All candidates failed; [failures] keeps the per-host cause for diagnostics. */
class NoReachableHostException(val failures: List<Pair<Endpoint, Throwable>>) :
    Exception("no desktop host candidate was reachable: " + failures.joinToString { "${it.first}: ${it.second.javaClass.simpleName}" }) {
    /** At least one host answered with a certificate that does not match the pinned fingerprint. */
    val pinMismatch: Boolean get() = failures.any { (_, t) -> t.causeChain().any { it is PinMismatchException } }
}

/**
 * This throwable, its causes and their suppressed exceptions (OkHttp's fast fallback reports the
 * failure of the other address family as a suppressed exception, e.g. a pin mismatch on IPv4
 * hidden behind a ConnectException on IPv6).
 */
fun Throwable.causeChain(): Sequence<Throwable> = sequence {
    val seen = HashSet<Throwable>()
    val queue = ArrayDeque<Throwable>().apply { add(this@causeChain) }
    while (queue.isNotEmpty() && seen.size < 32) {
        val t = queue.removeFirst()
        if (!seen.add(t)) continue
        yield(t)
        t.cause?.let(queue::add)
        t.suppressed.forEach(queue::add)
    }
}

object HostRace {
    /**
     * Probes every host candidate in parallel; the first [probe] that succeeds wins and the others
     * are cancelled (§3: "Phone connects to each host candidate, TLS pinned to fp").
     */
    suspend fun <T> first(
        endpoints: List<Endpoint>,
        perHostTimeoutMs: Long = 8_000,
        probe: suspend (Endpoint) -> T,
    ): Pair<Endpoint, T> = coroutineScope {
        require(endpoints.isNotEmpty()) { "no host candidates" }
        val results = Channel<Result<Pair<Endpoint, T>>>(endpoints.size)
        val jobs = endpoints.map { ep ->
            launch {
                val r = try {
                    Result.success(ep to withTimeout(perHostTimeoutMs) { probe(ep) })
                } catch (e: CancellationException) {
                    // A timeout surfaces as TimeoutCancellationException (a CancellationException).
                    if (e is kotlinx.coroutines.TimeoutCancellationException) Result.failure(HostTimeout(ep, e)) else throw e
                } catch (e: Exception) {
                    Result.failure(HostFailure(ep, e))
                }
                results.send(r)
            }
        }
        val failures = ArrayList<Pair<Endpoint, Throwable>>()
        repeat(endpoints.size) {
            val r = results.receive()
            r.onSuccess { win ->
                jobs.forEach { it.cancel() }
                return@coroutineScope win
            }
            r.onFailure { f ->
                val hf = f as HostFailure
                failures += hf.endpoint to (hf.cause ?: hf)
            }
        }
        throw NoReachableHostException(failures)
    }

    private open class HostFailure(val endpoint: Endpoint, cause: Throwable) : Exception(cause)
    private class HostTimeout(endpoint: Endpoint, cause: Throwable) : HostFailure(endpoint, cause)
}
