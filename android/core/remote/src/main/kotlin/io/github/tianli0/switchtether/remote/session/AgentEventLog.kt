package io.github.tianli0.switchtether.remote.session

import io.github.tianli0.switchtether.remote.protocol.AgentEvent
import io.github.tianli0.switchtether.remote.protocol.EventKinds
import io.github.tianli0.switchtether.remote.protocol.bool
import io.github.tianli0.switchtether.remote.protocol.str

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
        val resultsByCallId = HashMap<String, AgentEvent>()
        val resolutions = HashMap<String, AgentEvent>()
        for (e in events) {
            when (e.kind) {
                EventKinds.TOOL_CALL -> e.data.str("callId")?.let { callsByCallId[it] = e }
                EventKinds.TOOL_RESULT -> e.data.str("callId")?.let { resultsByCallId[it] = e }
                EventKinds.APPROVAL_RESOLVED -> e.data.str("requestId")?.let { resolutions[it] = e }
            }
        }
        val out = ArrayList<RenderItem>(events.size)
        for (e in events) {
            // A tool_result whose call is present is rendered inside the call card.
            if (e.kind == EventKinds.TOOL_RESULT) {
                val callId = e.data.str("callId")
                if (callId != null && callsByCallId.containsKey(callId)) continue
            }
            // Legacy separate approval_resolved events fold into their request.
            if (e.kind == EventKinds.APPROVAL_RESOLVED) {
                val rid = e.data.str("requestId")
                if (rid != null && events.any { it.kind == EventKinds.APPROVAL_REQUEST && it.data.str("requestId") == rid }) continue
            }
            val ancestors = ancestorsOf(e, callsByCallId)
            if (ancestors.any { it in collapsedParents || callsByCallId[it]?.id in collapsedParents }) continue
            out += RenderItem(
                event = e,
                depth = ancestors.size,
                attachedResult = if (e.kind == EventKinds.TOOL_CALL) e.data.str("callId")?.let(resultsByCallId::get) else null,
                resolution = if (e.kind == EventKinds.APPROVAL_REQUEST || e.kind == EventKinds.QUESTION) {
                    e.data.str("requestId")?.let(resolutions::get)
                } else {
                    null
                },
                hasChildren = false,
            )
        }
        val parents = events.mapNotNullTo(HashSet()) { it.parentId }
        return out.map { item ->
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

    private fun ancestorsOf(e: AgentEvent, callsByCallId: Map<String, AgentEvent>): List<String> {
        val chain = ArrayList<String>()
        var p = e.parentId
        while (p != null && p !in chain && chain.size < 8) {
            val parentEvent = byId[p] ?: callsByCallId[p]
            // Each actual parent counts once, even when addressed through its callId alias.
            chain += p
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
