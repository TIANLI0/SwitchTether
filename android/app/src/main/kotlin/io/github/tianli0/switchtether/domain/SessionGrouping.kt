package io.github.tianli0.switchtether.domain

import io.github.tianli0.switchtether.remote.protocol.SessionSummary

/**
 * One conversation as the user sees it. The desktop lists every agent session id separately, so a
 * resumed / continued / forked conversation (same agent, same folder, same title) or a session that
 * is both a live process and a history entry shows up several times. The list collapses those into
 * one row ([primary], the live or newest one) and keeps the rest reachable ([others]).
 */
data class SessionGroup(val primary: SessionSummary, val others: List<SessionSummary>) {
    val size: Int get() = 1 + others.size
}

object SessionGrouping {
    private val GENERIC = setOf("", "untitled", "new session", "新会话", "未命名")

    private fun titleKey(s: SessionSummary): String? {
        val t = s.title?.trim()?.lowercase().orEmpty()
        if (t in GENERIC || t.length < 4) return null
        // A title that is only the folder name does not identify a conversation.
        if (t == s.project?.lowercase()) return null
        return "t:${s.agent}|${s.cwd?.trimEnd('/', '\\')?.lowercase().orEmpty()}|$t"
    }

    private fun nativeKey(s: SessionSummary): String? = s.nativeId.takeIf { it.isNotBlank() }?.let { "n:${s.agent}|$it" }

    fun group(sessions: List<SessionSummary>): List<SessionGroup> {
        // Union-find over "same native id" and "same agent + folder + title".
        val parent = IntArray(sessions.size) { it }
        fun find(x: Int): Int { var a = x; while (parent[a] != a) { parent[a] = parent[parent[a]]; a = parent[a] }; return a }
        val seen = HashMap<String, Int>()
        sessions.forEachIndexed { i, s ->
            listOfNotNull(nativeKey(s), titleKey(s)).forEach { k ->
                val j = seen.putIfAbsent(k, i)
                if (j != null) parent[find(i)] = find(j)
            }
        }
        val order = compareByDescending<SessionSummary> { it.isLive }.thenByDescending { it.lastActiveAtMs ?: 0L }
        return sessions.indices.groupBy { find(it) }.values
            .map { idx -> idx.map { sessions[it] }.sortedWith(order) }
            .map { SessionGroup(it.first(), it.drop(1)) }
            .sortedWith(compareBy<SessionGroup> { 0 }.thenByDescending { it.primary.isLive }.thenByDescending { it.primary.lastActiveAtMs ?: 0L })
    }
}
