package io.github.tianli0.switchtether.remote.session

import io.github.tianli0.switchtether.remote.protocol.AgentEvent
import io.github.tianli0.switchtether.remote.protocol.EventKinds
import io.github.tianli0.switchtether.remote.protocol.bool
import io.github.tianli0.switchtether.remote.protocol.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * In-memory, immutable log of one session's [AgentEvent]s (§10.2).
 *
 * - Events are keyed by `id` and ordered by `seq` (tie-break by id).
 * - Events may be updated in place (same id, new data): [upsert] replaces by id.
 * - Pages loaded with `before`/`after` merge through the same path, so duplicates are harmless.
 *
 * Transcripts are never written to disk (only this in-memory structure exists).
 */
class AgentEventLog private constructor(
    private val byId: Map<String, AgentEvent>,
    val events: List<AgentEvent>,
) {
    val size: Int get() = events.size
    val minSeq: Long? get() = events.firstOrNull()?.seq
    val maxSeq: Long? get() = events.lastOrNull()?.seq

    operator fun get(id: String): AgentEvent? = byId[id]

    fun upsert(incoming: Collection<AgentEvent>): AgentEventLog {
        if (incoming.isEmpty()) return this
        val map = LinkedHashMap(byId)
        var changed = false
        for (e in incoming) {
            val old = map[e.id]
            if (old != e) {
                map[e.id] = e
                changed = true
            }
        }
        if (!changed) return this
        return AgentEventLog(map, map.values.sortedWith(ORDER))
    }

    fun upsert(event: AgentEvent): AgentEventLog = upsert(listOf(event))

    /** Builds the flat render list (see [RenderItem]). Cheap enough to run per update. */
    fun renderItems(collapsedParents: Set<String> = emptySet()): List<RenderItem> {
        val callsByCallId = HashMap<String, AgentEvent>()
        val callCount = HashMap<String, Int>()
        val resolutions = HashMap<String, AgentEvent>()
        for (e in events) {
            when (e.kind) {
                EventKinds.TOOL_CALL -> e.data.str("callId")?.let {
                    callsByCallId[it] = e
                    callCount[it] = (callCount[it] ?: 0) + 1
                }
                EventKinds.APPROVAL_RESOLVED -> e.data.str("requestId")?.let { resolutions[it] = e }
            }
        }
        // A callId is unique within one agent, not across sub-agents: when several calls share it, a
        // result belongs to the call under the same parent (the parent may be named by its event id
        // or by its callId alias). A callId used once keeps matching whatever the parent says.
        fun parentOf(e: AgentEvent): String? = e.parentId?.let { p -> (byId[p] ?: callsByCallId[p])?.id ?: p }
        fun slot(e: AgentEvent, callId: String): Pair<String, String?> = callId to (if ((callCount[callId] ?: 0) > 1) parentOf(e) else null)
        val callSlots = HashSet<Pair<String, String?>>()
        val resultsBySlot = HashMap<Pair<String, String?>, AgentEvent>()
        for (e in events) {
            val callId = e.data.str("callId") ?: continue
            when (e.kind) {
                EventKinds.TOOL_CALL -> callSlots += slot(e, callId)
                EventKinds.TOOL_RESULT -> resultsBySlot[slot(e, callId)] = e
            }
        }
        val supersededToolIds = findSupersededToolIds(callsByCallId, resultsBySlot, ::slot)
        val out = ArrayList<RenderItem>(events.size)
        for (e in events) {
            // A tool_call that already has a dedicated card (subagent, file_change, question, plan)
            // is redundant unless it failed / was denied / was interrupted.
            if (e.kind == EventKinds.TOOL_CALL && e.id in supersededToolIds) continue
            // A tool_result whose call is present is rendered inside the call card.
            if (e.kind == EventKinds.TOOL_RESULT) {
                val callId = e.data.str("callId")
                if (callId != null && slot(e, callId) in callSlots) continue
            }
            // Legacy separate approval_resolved events fold into their request.
            if (e.kind == EventKinds.APPROVAL_RESOLVED) {
                val rid = e.data.str("requestId")
                if (rid != null && events.any { it.kind == EventKinds.APPROVAL_REQUEST && it.data.str("requestId") == rid }) continue
            }
            val ancestors = ancestorsOf(e, callsByCallId, supersededToolIds)
            if (ancestors.any { it in collapsedParents || callsByCallId[it]?.id in collapsedParents }) continue
            out += RenderItem(
                event = e,
                depth = ancestors.size,
                attachedResult = if (e.kind == EventKinds.TOOL_CALL) e.data.str("callId")?.let { resultsBySlot[slot(e, it)] } else null,
                resolution = if (e.kind == EventKinds.APPROVAL_REQUEST || e.kind == EventKinds.QUESTION) {
                    e.data.str("requestId")?.let(resolutions::get)
                } else {
                    null
                },
                hasChildren = false,
            )
        }
        val parents = out.mapNotNullTo(HashSet()) { it.event.parentId }
        // A foreground call that never got its result but was followed by the turn ending at the same
        // level (an error such as a rate limit, an interrupt, "turn stopped") is over. Without this its
        // spinner kept turning after the session went idle. Agent text alone is not an end: some
        // agents speak while a command still runs.
        val settledAfter = HashMap<String?, Int>()
        events.forEachIndexed { i, e ->
            val ends = e.kind == EventKinds.ERROR ||
                (e.kind == EventKinds.STATUS && e.data.str("state") == "interrupted") ||
                (e.kind == EventKinds.SYSTEM && e.data.str("subtype") == "turn_stopped")
            if (ends) settledAfter[e.parentId] = i
        }
        val indexOf = HashMap<String, Int>(events.size).also { m -> events.forEachIndexed { i, e -> m[e.id] = i } }
        return out.map { item ->
            val e = item.event
            val st = e.data.str("status")
            val stale = e.kind == EventKinds.TOOL_CALL && (st == "running" || st == "pending") &&
                item.attachedResult == null && e.data.bool("background") != true &&
                (settledAfter[e.parentId] ?: -1) > (indexOf[e.id] ?: Int.MAX_VALUE)
            if (stale) item.copy(event = e.copy(data = JsonObject(e.data + ("status" to JsonPrimitive("interrupted")))))
            else item
        }.map { item ->
            // The callId alias addresses the tool call itself; a subagent row also carries the
            // callId of the Agent call it hangs under, which must not make it its own parent.
            val callId = item.event.data.str("callId").takeIf { item.event.kind == EventKinds.TOOL_CALL }
            if (item.event.id in parents || (callId != null && callId in parents)) {
                item.copy(hasChildren = true)
            } else {
                item
            }
        }
    }

    /**
     * Tool calls whose work is already represented by a dedicated card (`subagent`, `file_change`,
     * `question`, `plan`). Suppressing the raw wrapper avoids showing both e.g. an `edit_file` /
     * `invoke_subagent` tool card and its diff / sub-agent card back-to-back. Failed, denied or
     * interrupted calls stay visible (except when a `subagent` card already carries the failure),
     * and wrapper calls that hold inline non-subagent child events stay visible as their group header.
     */
    private fun findSupersededToolIds(
        callsByCallId: Map<String, AgentEvent>,
        resultsBySlot: Map<Pair<String, String?>, AgentEvent>,
        slot: (AgentEvent, String) -> Pair<String, String?>,
    ): Set<String> {
        val subagentToolIds = HashSet<String>()
        val fileChangeCallIds = HashSet<String>()
        val questionRequestIds = HashSet<String>()
        val planIds = HashSet<String>()
        var hasPlan = false
        val nonSubagentChildParents = HashSet<String>()
        for (e in events) {
            when (e.kind) {
                EventKinds.SUBAGENT -> {
                    e.data.str("callId")?.let { callsByCallId[it]?.id }?.let(subagentToolIds::add)
                    e.parentId?.let { (byId[it] ?: callsByCallId[it])?.id }?.let(subagentToolIds::add)
                }
                EventKinds.FILE_CHANGE -> e.data.str("callId")?.let(fileChangeCallIds::add)
                EventKinds.QUESTION -> e.data.str("requestId")?.let(questionRequestIds::add)
                EventKinds.PLAN -> {
                    hasPlan = true
                    planIds += e.id
                }
            }
            if (e.kind != EventKinds.SUBAGENT && e.kind != EventKinds.TOOL_RESULT) {
                e.parentId?.let { (byId[it] ?: callsByCallId[it])?.id }?.let(nonSubagentChildParents::add)
            }
        }
        val superseded = HashSet<String>()
        for (e in events) {
            if (e.kind != EventKinds.TOOL_CALL) continue
            if (e.id in nonSubagentChildParents) continue
            if (e.id in subagentToolIds) {
                superseded += e.id
                continue
            }
            val callId = e.data.str("callId")
            val st = e.data.str("status")
            val res = callId?.let { resultsBySlot[slot(e, it)] }
            val failedOrStopped = st == "failed" || st == "denied" || st == "interrupted" || res?.data?.bool("isError") == true
            if (failedOrStopped) continue
            val isFileChange = callId != null && callId in fileChangeCallIds
            val isQuestion = callId != null && callId in questionRequestIds
            val isPlan = hasPlan && (
                (callId != null && ("plan:$callId" in planIds || "cx-plan-$callId" in planIds)) ||
                    e.data.str("category") in PLAN_CATEGORIES
                )
            if (isFileChange || isQuestion || isPlan) {
                superseded += e.id
            }
        }
        return superseded
    }

    private fun ancestorsOf(
        e: AgentEvent,
        callsByCallId: Map<String, AgentEvent>,
        supersededToolIds: Set<String>,
    ): List<String> {
        val chain = ArrayList<String>()
        val seen = HashSet<String>()
        var p = e.parentId
        while (p != null && seen.add(p) && chain.size < 8) {
            val parentEvent = byId[p] ?: callsByCallId[p]
            // Each actual visible parent counts once; a suppressed wrapper tool_call is transparent
            // so its dedicated child (e.g. a `subagent` row) sits at the wrapper's own depth.
            if (parentEvent == null || parentEvent.id !in supersededToolIds) {
                chain += p
            }
            p = parentEvent?.parentId
        }
        return chain
    }

    /** Open approvals/questions (not resolved in place and not resolved by a separate event). */
    fun pendingRequests(): List<AgentEvent> {
        val resolvedIds = events.filter { it.kind == EventKinds.APPROVAL_RESOLVED }.mapNotNull { it.data.str("requestId") }.toSet()
        return events.filter {
            (it.kind == EventKinds.APPROVAL_REQUEST || it.kind == EventKinds.QUESTION) &&
                it.data.bool("resolved") != true &&
                it.data.str("requestId") !in resolvedIds
        }
    }

    companion object {
        private val ORDER = compareBy<AgentEvent>({ it.seq }, { it.id })
        private val PLAN_CATEGORIES = setOf("plan", "todo")
        val EMPTY = AgentEventLog(emptyMap(), emptyList())

        fun of(events: Collection<AgentEvent>): AgentEventLog = EMPTY.upsert(events)
    }
}

/**
 * One row of the session detail list. [key] is the event id: stable across in-place updates, so a
 * LazyColumn only recomposes the row whose event changed.
 */
data class RenderItem(
    val event: AgentEvent,
    /** Sub-agent nesting depth (number of parentId ancestors). */
    val depth: Int,
    /** For tool_call: its tool_result (matched by callId), rendered inside the call card. */
    val attachedResult: AgentEvent?,
    /** For approval_request/question: a separate approval_resolved event, if any. */
    val resolution: AgentEvent?,
    /** True when other events reference this one as parentId (sub-agent group header). */
    val hasChildren: Boolean,
) {
    val key: String get() = event.id
}
