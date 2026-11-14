package io.github.tianli0.switchtether.ui.common

import io.github.tianli0.switchtether.connection.ConnectionManager
import io.github.tianli0.switchtether.remote.command.CommandOutcome
import io.github.tianli0.switchtether.remote.command.CommandStatus
import io.github.tianli0.switchtether.remote.command.Commands
import io.github.tianli0.switchtether.remote.command.newCommandId
import io.github.tianli0.switchtether.remote.protocol.ErrorCodes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the user must see/decide about a side-effecting command that did not simply succeed. */
data class CommandPrompt(
    val label: String,
    val commandId: String,
    val kind: Kind,
    val detail: String? = null,
    /** Result of `GET /v1/commands/{id}` once checked. */
    val status: CommandStatus? = null,
    val busy: Boolean = false,
) {
    enum class Kind { UNKNOWN, NOT_SENT, IN_PROGRESS, REJECTED, BLOCKED, STEP_UP_REQUIRED }

    /** Command id to retry with, if a retry may be offered right now (see [Commands.retryCommandId]). */
    val retryId: String?
        get() = when {
            kind == Kind.NOT_SENT -> commandId
            status != null -> Commands.retryCommandId(status, commandId)
            else -> null
        }
}

/**
 * Runs side-effecting commands for a ViewModel and turns non-success outcomes into a
 * [CommandPrompt]. It never retries on its own: "Check status" queries the desktop, "Retry" is
 * only offered when [CommandPrompt.retryId] allows it and only runs when the user taps it.
 */
class CommandTracker(
    private val scope: CoroutineScope,
    private val statusQuery: suspend (String) -> CommandStatus,
) {
    private val _prompt = MutableStateFlow<CommandPrompt?>(null)
    val prompt: StateFlow<CommandPrompt?> = _prompt.asStateFlow()

    private val _busy = MutableStateFlow<String?>(null)

    /** Label of the command currently running (for inline progress), or null. */
    val busy: StateFlow<String?> = _busy.asStateFlow()

    private var pending: Pending? = null

    private class Pending(val label: String, val op: suspend (String) -> CommandOutcome, val onSuccess: (CommandOutcome.Succeeded) -> Unit)

    fun run(label: String, onSuccess: (CommandOutcome.Succeeded) -> Unit = {}, op: suspend (commandId: String) -> CommandOutcome) {
        val p = Pending(label, op, onSuccess)
        pending = p
        execute(p, newCommandId())
    }

    private fun execute(p: Pending, commandId: String) {
        scope.launch {
            _busy.value = p.label
            val outcome = try {
                p.op(commandId)
            } finally {
                _busy.value = null
            }
            handle(p, outcome)
        }
    }

    private fun handle(p: Pending, outcome: CommandOutcome) {
        _prompt.value = when (outcome) {
            is CommandOutcome.Succeeded -> {
                p.onSuccess(outcome)
                null
            }
            is CommandOutcome.Rejected -> {
                val e = outcome.error
                val kind = when (e.code) {
                    ErrorCodes.CAPABILITY_UNAVAILABLE -> CommandPrompt.Kind.BLOCKED
                    ErrorCodes.STEP_UP_REQUIRED -> CommandPrompt.Kind.STEP_UP_REQUIRED
                    else -> CommandPrompt.Kind.REJECTED
                }
                val detail = listOfNotNull(e.body.message.ifBlank { null }, e.detailStatus, e.detailReason).distinct().joinToString(" · ")
                CommandPrompt(p.label, outcome.commandId, kind, "${e.code}: $detail")
            }
            is CommandOutcome.NotSent -> CommandPrompt(p.label, outcome.commandId, CommandPrompt.Kind.NOT_SENT, ConnectionManager.describe(outcome.cause))
            is CommandOutcome.Unknown -> CommandPrompt(p.label, outcome.commandId, CommandPrompt.Kind.UNKNOWN, ConnectionManager.describe(outcome.cause))
            is CommandOutcome.InProgress -> CommandPrompt(p.label, outcome.commandId, CommandPrompt.Kind.IN_PROGRESS)
        }
    }

    /** Ask the desktop what happened to the command (§5). */
    fun checkStatus() {
        val cur = _prompt.value ?: return
        _prompt.value = cur.copy(busy = true)
        scope.launch {
            val st = statusQuery(cur.commandId)
            if (st is CommandStatus.Succeeded) {
                // It did run: treat as success, no retry.
                _prompt.value = null
                pending?.onSuccess?.invoke(CommandOutcome.Succeeded(cur.commandId, io.github.tianli0.switchtether.remote.api.CommandResponse(st.record.result ?: kotlinx.serialization.json.JsonNull, true)))
            } else {
                _prompt.value = cur.copy(status = st, busy = false)
            }
        }
    }

    /** User explicitly chose to retry. */
    fun retry() {
        val cur = _prompt.value ?: return
        val p = pending ?: return
        val id = cur.retryId ?: return
        _prompt.value = null
        execute(p, id)
    }

    fun dismiss() {
        _prompt.value = null
    }
}
