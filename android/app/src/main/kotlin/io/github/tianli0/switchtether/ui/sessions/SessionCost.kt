package io.github.tianli0.switchtether.ui.sessions

import io.github.tianli0.switchtether.remote.protocol.AgentEvent
import io.github.tianli0.switchtether.remote.protocol.EventKinds
import io.github.tianli0.switchtether.remote.protocol.double
import io.github.tianli0.switchtether.remote.protocol.long

/** What the loaded turns of a session cost, with the tokens of those same turns. */
data class SessionCost(val costUsd: Double, val inputTokens: Long, val outputTokens: Long)

/**
 * The session's cost from the `usage` events that carry one (`data.costUsd`, one per `result` of a
 * session SwitchTether runs). The desktop fills `costUsd` from Claude Code's `total_cost_usd`, which
 * is a **running total**, not a turn's cost: "cumulative across turns in streaming-input sessions —
 * each result carries the running total so far, so read the latest result rather than summing
 * across results" (Claude Code 2.1.331, SDK schema of `total_cost_usd`). A resumed session continues
 * from the total its transcript saved; a `/clear`, or a process that starts without a saved total,
 * starts again from zero, and crash / startup-error results carry `0`.
 *
 * So the cost is the latest total of each run, summed over runs: a total lower than the one before
 * starts a new run. Zero totals are skipped (they would look like a new run) unless nothing else
 * carries a cost. The tokens are different: a `result`'s `usage` is per turn (main loop only), so
 * they are summed. Usage events without a cost are ignored, also for the tokens: they are the
 * per-message counts of the same turns. An event id counts once. Null when no event carries a cost.
 */
fun sessionCost(events: List<AgentEvent>): SessionCost? {
    val seen = HashSet<String>()
    var finishedRuns = 0.0
    var runTotal = 0.0
    var input = 0L
    var output = 0L
    var any = false
    for (e in events) {
        if (e.kind != EventKinds.USAGE) continue
        val c = e.data.double("costUsd")?.takeIf { it.isFinite() && it >= 0 } ?: continue
        if (!seen.add(e.id)) continue
        any = true
        input += e.data.long("inputTokens") ?: 0
        output += e.data.long("outputTokens") ?: 0
        if (c == 0.0) continue
        if (c < runTotal - COST_EPSILON) finishedRuns += runTotal
        runTotal = c
    }
    return if (any) SessionCost(finishedRuns + runTotal, input, output) else null
}

/** Totals less than a millionth of a dollar apart count as the same (floating point noise). */
private const val COST_EPSILON = 1e-6
