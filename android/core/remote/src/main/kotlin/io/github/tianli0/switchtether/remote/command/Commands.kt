package io.github.tianli0.switchtether.remote.command

import io.github.tianli0.switchtether.remote.api.ApiException
import io.github.tianli0.switchtether.remote.api.CommandResponse
import io.github.tianli0.switchtether.remote.api.CommandTransportException
import io.github.tianli0.switchtether.remote.protocol.CommandRecord
import io.github.tianli0.switchtether.remote.protocol.ErrorCodes
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/** Generates the client-side command id (UUID) for one user intent (§5). */
fun newCommandId(): String = UUID.randomUUID().toString()

/**
 * Outcome of one side-effecting request. Nothing here is ever retried automatically: on
 * [NotSent] / [Unknown] / [InProgress] the UI asks the user, and a retry reuses the same
 * [commandId] so the desktop can de-duplicate.
 */
sealed interface CommandOutcome {
    val commandId: String

    data class Succeeded(override val commandId: String, val response: CommandResponse) : CommandOutcome

    /** The desktop answered with an error (definitive; e.g. 422 capability_unavailable). */
    data class Rejected(override val commandId: String, val error: ApiException) : CommandOutcome

    /** Connection failed before the request was written: it was not executed. */
    data class NotSent(override val commandId: String, val cause: Throwable) : CommandOutcome

    /** Request was written but no response arrived: the desktop may or may not have executed it. */
    data class Unknown(override val commandId: String, val cause: Throwable) : CommandOutcome

    /** 409 command_in_progress: same commandId still executing. */
    data class InProgress(override val commandId: String) : CommandOutcome
}

/** Result of `GET /v1/commands/{id}` after an [CommandOutcome.Unknown]. */
sealed interface CommandStatus {
    data class Succeeded(val record: CommandRecord) : CommandStatus
    data class Failed(val record: CommandRecord) : CommandStatus
    data class Pending(val record: CommandRecord) : CommandStatus

    /** The desktop has no record: it never received the command (or the 24 h record expired). */
    data object NotFound : CommandStatus

    /** Still could not reach the desktop. */
    data class Unreachable(val cause: Throwable) : CommandStatus
}

object Commands {
    /** Runs [block] with [commandId] and classifies the outcome. Never retries. */
    suspend fun run(commandId: String = newCommandId(), block: suspend (String) -> CommandResponse): CommandOutcome = try {
        CommandOutcome.Succeeded(commandId, block(commandId))
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiException) {
        if (e.code == ErrorCodes.COMMAND_IN_PROGRESS) CommandOutcome.InProgress(commandId) else CommandOutcome.Rejected(commandId, e)
    } catch (e: CommandTransportException) {
        if (e.sent) CommandOutcome.Unknown(commandId, e.cause ?: e) else CommandOutcome.NotSent(commandId, e.cause ?: e)
    } catch (e: Exception) {
        // Anything else (an answer that cannot be read, …): the desktop may have executed it.
        CommandOutcome.Unknown(commandId, e)
    }

    /** Queries the command record; used before offering the user a retry. */
    suspend fun status(commandId: String, query: suspend (String) -> CommandRecord): CommandStatus = try {
        val rec = query(commandId)
        when (rec.status.lowercase()) {
            "succeeded", "success", "completed", "done" -> CommandStatus.Succeeded(rec)
            "failed", "error" -> CommandStatus.Failed(rec)
            else -> CommandStatus.Pending(rec)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiException) {
        if (e.code == ErrorCodes.NOT_FOUND) CommandStatus.NotFound else CommandStatus.Unreachable(e)
    } catch (e: Exception) {
        CommandStatus.Unreachable(e)
    }

    /**
     * The command id to use if the user explicitly chooses to retry, or null when a retry must not
     * be offered (already succeeded / still pending / unreachable).
     *
     * - NotFound: the desktop never saw it → reuse the SAME id (if the original lands late, the
     *   desktop de-duplicates instead of executing twice).
     * - Failed: the stored failure would just be replayed for the same id → a retry is a new
     *   user intent with a NEW id.
     */
    fun retryCommandId(status: CommandStatus, originalId: String): String? = when (status) {
        is CommandStatus.NotFound -> originalId
        is CommandStatus.Failed -> newCommandId()
        else -> null
    }
}
