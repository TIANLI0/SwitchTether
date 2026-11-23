package io.github.tianli0.switchtether.remote.session

import io.github.tianli0.switchtether.remote.protocol.AgentEvent
import kotlinx.serialization.Serializable

/**
 * One Claude Code sub-agent of a session (`GET /v1/sessions/{id}/subagents`), from the sidecar
 * metadata Claude Code writes next to each sub-agent transcript. [parentAgentId] is null for a
 * sub-agent the session started itself; nested ones (an agent that started agents) point to
 * their parent, which is what the branch views are built from.
 */
@Serializable
data class SubagentNode(
    val agentId: String = "",
    val description: String? = null,
    val agentType: String? = null,
    val toolUseId: String? = null,
    val parentAgentId: String? = null,
    val spawnDepth: Int? = null,
    val finished: Boolean? = null,
    val updatedAtMs: Long? = null,
    // Live state from the session log (Agent map; absent on older desktops and for nested agents
    // whose rows are only in their parent's transcript).
    /** `running` | `completed` | `failed` | `interrupted`. */
    val status: String? = null,
    /** A permission request or question raised inside this agent is unanswered. */
    val waiting: Boolean? = null,
    val background: Boolean? = null,
    val model: String? = null,
    val tokens: Long? = null,
    val toolUses: Long? = null,
    val durationMs: Long? = null,
    /** What it is doing now ("Running …"), while it runs. */
    val activity: String? = null,
    val summary: String? = null,
    val result: String? = null,
    val prompt: String? = null,
    val startedAt: String? = null,
    val finishedAt: String? = null,
)

/** A background shell task of the session (`Bash` with `run_in_background`). */
@Serializable
data class BackgroundTaskNode(
    val taskId: String? = null,
    val callId: String? = null,
    val title: String? = null,
    val status: String? = null,
    val summary: String? = null,
    val exitCode: Long? = null,
    val startedAt: String? = null,
    val finishedAt: String? = null,
)

/** `GET /v1/sessions/{id}/subagents` as the Agent map: agents, background tasks, and whether
 *  the phone may stop them (a session SwitchTether started). */
data class AgentMap(
    val agents: List<SubagentNode>,
    val backgroundTasks: List<BackgroundTaskNode> = emptyList(),
    val stoppable: Boolean = false,
)

/** `GET /v1/sessions/{id}/subagents/{agentId}/events`: the sub-agent's own steps (read-only). */
data class SubagentPage(
    val events: List<AgentEvent>,
    val meta: SubagentNode?,
    val hasMoreBefore: Boolean,
)

/** A row of the branch tree: the node, its depth, and whether it is the last child of its parent. */
data class SubagentBranch(
    val node: SubagentNode,
    val depth: Int,
    val last: Boolean,
    /** For each ancestor level: true when that ancestor still has later siblings (draw "│"). */
    val rails: List<Boolean>,
)

object SubagentTree {
    /**
     * Depth-first order (children in start order, oldest first), the way Claude Code lists agents
     * under the task that started them. Nodes whose parent is unknown (metadata of an older Claude
     * Code without `parentAgentId`, or a parent file that is gone) hang off the root.
     */
    fun flatten(nodes: List<SubagentNode>): List<SubagentBranch> {
        val ids = nodes.map { it.agentId }.toSet()
        val children = nodes
            .groupBy { n -> n.parentAgentId?.takeIf { it in ids && it != n.agentId } }
            .mapValues { (_, v) -> v.sortedBy { it.updatedAtMs ?: 0L } }
        val out = ArrayList<SubagentBranch>()
        val seen = HashSet<String>()
        fun walk(parent: String?, depth: Int, rails: List<Boolean>) {
            val list = children[parent].orEmpty()
            list.forEachIndexed { i, n ->
                if (!seen.add(n.agentId)) return@forEachIndexed // cycle guard
                val last = i == list.lastIndex
                out += SubagentBranch(n, depth, last, rails)
                walk(n.agentId, depth + 1, rails + !last)
            }
        }
        walk(null, 0, emptyList())
        return out
    }

    /** Ancestors of [agentId], outermost first (for the breadcrumb). */
    fun trail(nodes: List<SubagentNode>, agentId: String): List<SubagentNode> {
        val byId = nodes.associateBy { it.agentId }
        val out = ArrayList<SubagentNode>()
        var cur = byId[agentId]?.parentAgentId
        while (cur != null && out.size < 16) {
            val n = byId[cur] ?: break
            out.add(0, n)
            cur = n.parentAgentId
        }
        return out
    }

    /** Connector glyphs for a branch row, e.g. "│  ├─ " (monospace, like Claude Code's tree). */
    fun prefix(b: SubagentBranch): String = buildString {
        b.rails.forEach { append(if (it) "│  " else "   ") }
        append(if (b.last) "└─ " else "├─ ")
    }
}
