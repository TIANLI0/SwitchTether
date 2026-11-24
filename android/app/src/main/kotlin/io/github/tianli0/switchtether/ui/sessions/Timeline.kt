package io.github.tianli0.switchtether.ui.sessions

import io.github.tianli0.switchtether.remote.protocol.EventKinds
import io.github.tianli0.switchtether.remote.protocol.bool
import io.github.tianli0.switchtether.remote.protocol.str
import io.github.tianli0.switchtether.remote.session.RenderItem
import io.github.tianli0.coui17.patterns.SegmentPosition

/**
 * One row of the session timeline. Consecutive agent steps (tool calls, sub-agents, thinking
 * between them) of a turn fold into a collapsible "N steps" block: a [Steps] header followed —
 * only when expanded — by its member [Event] rows drawn as segments of one card. Every row is its
 * own lazy item with a stable key, so streaming updates recompose only the rows that changed.
 */
sealed interface TimelineRow {
    val key: String

    data class Event(val item: RenderItem, val segment: SegmentPosition = SegmentPosition.None) : TimelineRow {
        override val key: String get() = item.key
    }

    /** Optimistic bubble of a message sent from this phone, until the desktop echoes it. */
    data class Outgoing(val message: OutgoingMessage) : TimelineRow {
        override val key: String get() = "out:${message.commandId}"
    }

    /** "正在回复…" placeholder until the agent's first output arrives. */
    data object Typing : TimelineRow {
        override val key: String get() = "typing"
    }

    data class Steps(
        val groupId: String,
        val steps: Int,
        val running: Boolean,
        val failed: Int,
        val expanded: Boolean,
        val latestTitle: String?,
    ) : TimelineRow {
        override val key: String get() = "steps:$groupId"
    }
}

/** Kinds that are part of an agent's "work" between messages. */
private val STEP_KINDS = setOf(EventKinds.TOOL_CALL, EventKinds.TOOL_RESULT, EventKinds.SUBAGENT, EventKinds.REASONING)

/** Kinds that must never be hidden inside a collapsed block (the user has to see/act on them). */
private val PROMINENT_KINDS = setOf(EventKinds.APPROVAL_REQUEST, EventKinds.QUESTION, EventKinds.ERROR, EventKinds.FILE_CHANGE, EventKinds.PLAN)

internal fun isStep(item: RenderItem): Boolean {
    val kind = item.event.kind
    if (kind in PROMINENT_KINDS) return false
    return kind in STEP_KINDS || item.depth > 0
}

private fun isCountedStep(item: RenderItem) = item.event.kind == EventKinds.TOOL_CALL || item.event.kind == EventKinds.SUBAGENT

/** Minimum number of tool calls / sub-agents in a run before it is folded into a block. */
const val MIN_GROUP_STEPS = 2

fun buildTimeline(items: List<RenderItem>, expandedGroups: Set<String>): List<TimelineRow> {
    val out = ArrayList<TimelineRow>(items.size)
    var i = 0
    while (i < items.size) {
        if (!isStep(items[i])) {
            out += TimelineRow.Event(items[i], SegmentPosition.None)
            i++
            continue
        }
        var j = i
        while (j < items.size && isStep(items[j])) j++
        val run = items.subList(i, j)
        val counted = run.count(::isCountedStep)
        if (counted < MIN_GROUP_STEPS) {
            run.forEach { out += TimelineRow.Event(it, if (isCountedStep(it) || it.event.kind == EventKinds.TOOL_RESULT) SegmentPosition.Single else SegmentPosition.None) }
        } else {
            val id = run.first().key
            val expanded = id in expandedGroups
            out += TimelineRow.Steps(
                groupId = id,
                steps = counted,
                running = run.any { isRunning(it) },
                failed = run.count { it.event.kind == EventKinds.TOOL_CALL && (it.event.data.str("status") == "failed" || it.attachedResult?.data?.bool("isError") == true) },
                expanded = expanded,
                latestTitle = run.lastOrNull(::isCountedStep)?.let(::stepTitle),
            )
            if (expanded) {
                run.forEachIndexed { k, it -> out += TimelineRow.Event(it, if (k == run.lastIndex) SegmentPosition.Last else SegmentPosition.Middle) }
            }
        }
        i = j
    }
    return out
}

private fun isRunning(item: RenderItem): Boolean {
    val st = item.event.data.str("status")
    return when (item.event.kind) {
        // A background shell has its "started" result already and keeps running until its notification.
        EventKinds.TOOL_CALL -> (st == "running" || st == "pending") && (item.attachedResult == null || item.event.data.bool("background") == true)
        EventKinds.SUBAGENT -> st == "running"
        else -> false
    }
}

fun stepTitle(item: RenderItem): String {
    val d = item.event.data
    if (d.str("category") == "shell") io.github.tianli0.switchtether.remote.session.ToolCommands.raw(d)?.let {
        val preview = io.github.tianli0.switchtether.remote.session.ToolCommands.preview(it)
        val title = d.str("title")
        if (preview != it.trim() || title.isNullOrBlank() || it.startsWith(title.trimEnd('…'))) return preview
    }
    return d.str("title")?.takeIf { it.isNotBlank() } ?: d.str("description")?.takeIf { it.isNotBlank() } ?: d.str("name").orEmpty()
}

/** Approval requests / questions still waiting for an answer, oldest first (keys of their rows). */
fun pendingRequestKeys(items: List<RenderItem>): List<String> = items.filter {
    (it.event.kind == EventKinds.APPROVAL_REQUEST || it.event.kind == EventKinds.QUESTION) &&
        it.event.data.bool("resolved") != true && it.resolution == null
}.map { it.key }
