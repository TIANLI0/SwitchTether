package io.github.tianli0.switchtether.remote.protocol

import kotlinx.serialization.Serializable

/**
 * `GET /v1/usage/projects` (PROTOCOL.md §7.3): usage of the request logs that carry an agent session
 * id, joined with the session index and grouped by project. Fixture `usage_projects.json`.
 */
@Serializable
data class ProjectUsageView(
    val projects: List<ProjectUsage> = emptyList(),
    /** Projects beyond the first 50 (by cost), summed. */
    val otherProjects: UsageTotals? = null,
    /** Logs without a session id, or whose session is no longer in the index. */
    val unattributed: UsageTotals? = null,
)

@Serializable
data class UsageTotals(
    val requests: Long = 0,
    val costUsd: Double = 0.0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val cacheCreationTokens: Long = 0,
    val lastMs: Long? = null,
) {
    val totalTokens: Long get() = inputTokens + outputTokens + cacheReadTokens + cacheCreationTokens
}

@Serializable
data class ProjectUsage(
    /** Project name as the session list shows it (and filters by). */
    val project: String,
    val cwd: String? = null,
    val sessionCount: Int = 0,
    val requests: Long = 0,
    val costUsd: Double = 0.0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val cacheCreationTokens: Long = 0,
    val lastMs: Long? = null,
    /** Most expensive sessions first (5 per project in the overview, up to 100 for one project). */
    val sessions: List<SessionUsage> = emptyList(),
) {
    val totalTokens: Long get() = inputTokens + outputTokens + cacheReadTokens + cacheCreationTokens
}

@Serializable
data class SessionUsage(
    /** Session list id (`claude:<uuid>`, `codex:<thread>`). */
    val id: String,
    val app: String = "",
    val title: String? = null,
    val cwd: String? = null,
    val requests: Long = 0,
    val costUsd: Double = 0.0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val cacheCreationTokens: Long = 0,
    val lastMs: Long? = null,
) {
    val totalTokens: Long get() = inputTokens + outputTokens + cacheReadTokens + cacheCreationTokens
}
