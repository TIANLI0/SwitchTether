package io.github.tianli0.switchtether.ui.sessions

import io.github.tianli0.switchtether.remote.protocol.AgentEvent
import io.github.tianli0.switchtether.remote.protocol.EventKinds
import io.github.tianli0.switchtether.remote.protocol.double
import io.github.tianli0.switchtether.remote.protocol.long

/** What the loaded turns of a session cost, with the tokens of those same turns. */
data class SessionCost(val costUsd: Double, val inputTokens: Long, val outputTokens: Long)

/**
 * Sum over the `usage` events that carry a cost (`data.costUsd`, one per finished turn of a session
 * SwitchTether runs). Usage events without a cost are ignored, also for the tokens: they are the
 * per-message counts of the same turns and would be counted twice. An event id counts once.
 * Null when no event carries a cost.
 */
fun sessionCost(events: List<AgentEvent>): SessionCost? {
    val seen = HashSet<String>()
    var cost = 0.0
    var input = 0L
    var output = 0L
    var any = false
    for (e in events) {
        if (e.kind != EventKinds.USAGE) continue
        val c = e.data.double("costUsd")?.takeIf { it.isFinite() } ?: continue
        if (!seen.add(e.id)) continue
        any = true
        cost += c
        input += e.data.long("inputTokens") ?: 0
        output += e.data.long("outputTokens") ?: 0
    }
    return if (any) SessionCost(cost, input, output) else null
}
