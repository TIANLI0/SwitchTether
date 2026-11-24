package io.github.tianli0.switchtether.remote.protocol

import kotlinx.serialization.Serializable

// ---------------------------------------------------------------------------------------------
// §7.12 Push wake
// ---------------------------------------------------------------------------------------------

/** `GET /v1/push`: whether this phone has a subscription, and the desktop's VAPID key (87 chars). */
@Serializable
data class PushInfo(val registered: Boolean = false, val vapidPublicKey: String? = null)

/** `PUT /v1/push`: a UnifiedPush endpoint and the RFC 8291 receiver keys (base64url). */
@Serializable
data class PushRegistration(val endpoint: String, val p256dh: String, val auth: String)

/** `POST /v1/push/test`: the push server's HTTP status. */
@Serializable
data class PushTestResult(val status: Int = 0, val delivered: Boolean = false)

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
