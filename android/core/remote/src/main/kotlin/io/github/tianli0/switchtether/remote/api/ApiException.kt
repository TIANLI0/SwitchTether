package io.github.tianli0.switchtether.remote.api

import io.github.tianli0.switchtether.remote.protocol.ApiErrorBody
import io.github.tianli0.switchtether.remote.protocol.ErrorCodes
import io.github.tianli0.switchtether.remote.protocol.str
import java.io.IOException

/** A structured error from the desktop (§2 envelope). */
class ApiException(
    val httpStatus: Int,
    val body: ApiErrorBody,
    val requestId: String?,
) : Exception("${body.code}: ${body.message} (HTTP $httpStatus)") {
    val code: String get() = body.code
    val isAuthFailure: Boolean get() = code == ErrorCodes.UNPAIRED || code == ErrorCodes.DEVICE_REVOKED
    val detailReason: String? get() = body.details?.str("reason")
    val detailStatus: String? get() = body.details?.str("status")
}

/**
 * A side-effecting request failed at the transport level. [sent] tells whether the request
 * headers were already written to the socket: if so the desktop may have executed it and the
 * outcome is UNKNOWN (§5) — the client must query `GET /v1/commands/{id}` and let the user decide.
 */
class CommandTransportException(
    val commandId: String,
    val sent: Boolean,
    cause: IOException,
) : IOException("command $commandId failed at transport level (sent=$sent): ${cause.message}", cause)
