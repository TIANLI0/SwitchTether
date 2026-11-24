package io.github.tianli0.switchtether.remote.protocol

import kotlinx.serialization.Serializable

// ---------------------------------------------------------------------------------------------
// §7.13 Session search
// ---------------------------------------------------------------------------------------------

@Serializable
data class SessionSearchResult(
    val sessionId: String,
    val agent: String = "",
    val title: String? = null,
    val cwd: String? = null,
    val timeMs: Long? = null,
    val snippet: String = "",
    val matchCount: Int = 0,
)

@Serializable
data class SessionSearch(val results: List<SessionSearchResult> = emptyList(), val partial: Boolean = false)
