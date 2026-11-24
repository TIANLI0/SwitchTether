package io.github.tianli0.switchtether.ui.sessions

import io.github.tianli0.switchtether.remote.protocol.AgentEvent
import io.github.tianli0.switchtether.remote.protocol.EventKinds
import io.github.tianli0.switchtether.remote.protocol.double
import io.github.tianli0.switchtether.remote.protocol.long
import io.github.tianli0.switchtether.remote.protocol.str

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

/**
 * One turn of a session for the cost sheet: from a user message to the next one. [costUsd] is what
 * the turn added to its run's running total (null when no usage of the turn carried a cost);
 * [prompt] is the first line of the message that started it.
 */
data class TurnUsage(
    val index: Int,
    val ts: String,
    val prompt: String?,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long,
    val cacheWriteTokens: Long,
    val costUsd: Double?,
    val durationMs: Long?,
) {
    val totalTokens: Long get() = inputTokens + outputTokens + cacheReadTokens + cacheWriteTokens
}

/** What the cost sheet shows: totals, the turns that reported usage, and the last request's context. */
data class SessionUsageBreakdown(
    val turns: List<TurnUsage>,
    val costUsd: Double?,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long,
    val cacheWriteTokens: Long,
    /** Prompt size of the latest request (≈ the context the model saw), or null. */
    val contextTokens: Long?,
) {
    val totalTokens: Long get() = inputTokens + outputTokens + cacheReadTokens + cacheWriteTokens

    /** Share of prompt tokens read from the cache (0..1), or null without any prompt tokens. */
    val cacheHitRate: Double?
        get() {
            val prompt = inputTokens + cacheReadTokens + cacheWriteTokens
            return if (prompt > 0 && cacheReadTokens > 0) cacheReadTokens.toDouble() / prompt else null
        }
}

/**
 * Per-turn usage from the loaded `usage` events, with the same rules as [sessionCost]: when any
 * usage event carries a cost (results of a session SwitchTether runs), only those count, a turn's
 * cost is the growth of the running total (a lower total starts a new run), and its tokens are
 * that result's per-turn tokens; otherwise every usage event counts (transcripts: one per finished
 * reply; Codex: the turn's last request). An event id counts once. Turns are split at user
 * messages; turns without usage are left out. Codex reports cached tokens inside `inputTokens`, so
 * for it the cache-read part is subtracted from the input and the context is the input alone.
 */
fun sessionUsageBreakdown(events: List<AgentEvent>, agent: String? = null): SessionUsageBreakdown? {
    val usage = events.filter { it.kind == EventKinds.USAGE }
    if (usage.isEmpty()) return null
    val costed = usage.any { e -> e.data.double("costUsd")?.let { it.isFinite() && it >= 0 } == true }
    val codex = agent == "codex"
    val seen = HashSet<String>()
    val turns = ArrayList<TurnUsage>()
    var prompt: String? = null
    var current: TurnUsage? = null
    var runTotal = 0.0
    var lastContext: Long? = null
    fun close() {
        current?.let { turns += it }
        current = null
    }
    for (e in events) {
        if (e.kind == EventKinds.USER_MESSAGE) {
            close()
            prompt = e.data.str("text")?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }?.take(PROMPT_PREVIEW)
            continue
        }
        if (e.kind != EventKinds.USAGE || !seen.add(e.id)) continue
        val c = e.data.double("costUsd")?.takeIf { it.isFinite() && it >= 0 }
        if (costed && c == null) continue
        var input = (e.data.long("inputTokens") ?: 0).coerceAtLeast(0)
        val cacheRead = (e.data.long("cacheReadTokens") ?: 0).coerceAtLeast(0)
        val cacheWrite = (e.data.long("cacheWriteTokens") ?: 0).coerceAtLeast(0)
        val output = (e.data.long("outputTokens") ?: 0).coerceAtLeast(0)
        if (codex) input = (input - cacheRead).coerceAtLeast(0)
        val context = input + cacheRead + cacheWrite
        if (context > 0) lastContext = context
        var delta: Double? = null
        if (c != null && c > 0.0) {
            delta = if (c < runTotal - COST_EPSILON) c else (c - runTotal).coerceAtLeast(0.0)
            runTotal = c
        }
        val t = current ?: TurnUsage(turns.size + 1, e.ts, prompt, 0, 0, 0, 0, null, null)
        current = t.copy(
            inputTokens = t.inputTokens + input,
            outputTokens = t.outputTokens + output,
            cacheReadTokens = t.cacheReadTokens + cacheRead,
            cacheWriteTokens = t.cacheWriteTokens + cacheWrite,
            costUsd = if (delta == null) t.costUsd else (t.costUsd ?: 0.0) + delta,
            durationMs = e.data.long("durationMs")?.let { (t.durationMs ?: 0) + it } ?: t.durationMs,
            ts = e.ts.ifEmpty { t.ts },
        )
    }
    close()
    if (turns.isEmpty()) return null
    val total = if (costed) sessionCost(events)?.costUsd else null
    return SessionUsageBreakdown(
        turns = turns,
        costUsd = total,
        inputTokens = turns.sumOf { it.inputTokens },
        outputTokens = turns.sumOf { it.outputTokens },
        cacheReadTokens = turns.sumOf { it.cacheReadTokens },
        cacheWriteTokens = turns.sumOf { it.cacheWriteTokens },
        contextTokens = lastContext,
    )
}

private const val PROMPT_PREVIEW = 80
