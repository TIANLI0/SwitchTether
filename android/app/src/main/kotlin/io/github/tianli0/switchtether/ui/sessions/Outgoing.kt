package io.github.tianli0.switchtether.ui.sessions

import io.github.tianli0.switchtether.remote.protocol.EventKinds
import io.github.tianli0.switchtether.remote.protocol.str
import io.github.tianli0.switchtether.remote.session.AgentEventLog
import io.github.tianli0.switchtether.remote.session.RenderItem

/**
 * A message the user sent from this phone that the desktop has not echoed back yet (optimistic UI).
 * [commandId] is the idempotency key of `POST …/messages`; a retry reuses it, so the desktop runs
 * the message at most once. Kept in memory only (never written to disk).
 */
data class OutgoingMessage(
    val commandId: String,
    val text: String,
    val status: OutgoingStatus,
    /** Highest event seq known when it was sent: the fallback match only looks at newer events. */
    val afterSeq: Long,
    val error: String? = null,
    /** Names of the files sent with it (shown on the bubble until the desktop echoes it). */
    val attachmentNames: List<String> = emptyList(),
    /**
     * Claude Code's id for the message when it was queued behind a busy turn (`messageUuid` of the
     * send response): while it is not echoed it can be withdrawn (`cancel_message`).
     */
    val messageUuid: String? = null,
    /** A withdraw request is in flight. */
    val withdrawing: Boolean = false,
    /**
     * The user's retry goes out under a new id: the desktop answers the same [commandId] with what it
     * stored — a failure (rejected / `failed`) is replayed, and a command it lost in a restart
     * (status `unknown`, §5; may or may not have arrived) is refused with 409 for good.
     */
    val retryAsNew: Boolean = false,
)

const val MAX_ATTACHMENTS = 10
const val MAX_ATTACHMENT_BYTES = 64L * 1024 * 1024
const val ATTACHMENT_CHUNK = 2 * 1024 * 1024

enum class AttachmentFailure { TOO_LARGE, EMPTY, OLD_DESKTOP, FAILED }

/** A file picked for the next message and its upload to the computer. */
data class DraftAttachment(
    val localId: String,
    val name: String,
    val size: Long,
    val mediaType: String?,
    /** Bytes the computer has confirmed. */
    val sent: Long = 0,
    val attachmentId: String? = null,
    val ready: Boolean = false,
    val failure: AttachmentFailure? = null,
    val detail: String? = null,
    /** The picked file on this phone (content URI), for the composer's photo preview. */
    val localUri: String? = null,
) {
    val uploading: Boolean get() = !ready && failure == null
    val isImage: Boolean get() = mediaType?.startsWith("image/") == true
}

enum class OutgoingStatus {
    /** POST in flight ("发送中"). */
    SENDING,

    /** Accepted by the desktop, waiting for the agent's `user_message` echo ("已送达"). */
    DELIVERED,

    /** Sent while offline: goes out (same commandId) as soon as the connection is back ("等待连接"). */
    WAITING_CONNECTION,

    /** Rejected or not confirmed ("发送失败"): the user may retry (same commandId unless [OutgoingMessage.retryAsNew]) or delete it. */
    FAILED,
}

/** Whitespace-insensitive comparison for the text fallback. */
fun normalizeMessage(text: String): String = text.trim().replace(Regex("\\s+"), " ")

/**
 * Drops outgoing messages whose real `user_message` arrived. Exact match first: the desktop echoes
 * the commandId as `data.clientId`. Older desktops: same normalized text, sent from the phone (or
 * unknown source), newer than the send; each event replaces at most one bubble, oldest first.
 *
 * With the same text sent more than once, an echo goes to a bubble the desktop accepted before one
 * that failed, never to one that was not sent yet; the bubbles that stay only look at events after
 * it from then on (otherwise the next call would hand the same echo to the second bubble).
 */
fun reconcileOutgoing(outgoing: List<OutgoingMessage>, log: AgentEventLog): List<OutgoingMessage> {
    if (outgoing.isEmpty()) return outgoing
    val users = log.events.filter { it.kind == EventKinds.USER_MESSAGE }
    if (users.isEmpty()) return outgoing
    val byClientId = users.mapNotNull { e -> e.data.str("clientId")?.let { it to e.id } }.toMap()
    val used = HashSet<String>()
    val echoed = HashSet<String>()
    /** Normalized text → seq of the newest event the fallback consumed for it. */
    val consumed = HashMap<String, Long>()
    for (m in outgoing) if (byClientId[m.commandId] != null) echoed += m.commandId
    val candidates = outgoing.filter { it.commandId !in echoed && it.status != OutgoingStatus.WAITING_CONNECTION }
    for (m in candidates.filter { it.status != OutgoingStatus.FAILED } + candidates.filter { it.status == OutgoingStatus.FAILED }) {
        val norm = normalizeMessage(m.text)
        val fallback = users.firstOrNull { e ->
            e.id !in used && e.data.str("clientId") == null && e.seq > m.afterSeq &&
                (e.data.str("source") ?: "unknown") in setOf("phone", "unknown") &&
                normalizeMessage(e.data.str("text").orEmpty()) == norm
        } ?: continue
        used += fallback.id
        echoed += m.commandId
        consumed[norm] = maxOf(consumed[norm] ?: 0L, fallback.seq)
    }
    if (echoed.isEmpty()) return outgoing
    return outgoing.filter { it.commandId !in echoed }.map { m ->
        val seq = consumed[normalizeMessage(m.text)]
        if (seq != null && seq > m.afterSeq) m.copy(afterSeq = seq) else m
    }
}

/**
 * "正在回复…": the user's latest message (echoed, or delivered and not echoed yet) has no agent
 * output after it while the session is working on it.
 */
fun showTypingRow(items: List<RenderItem>, outgoing: List<OutgoingMessage>, sessionBusy: Boolean): Boolean {
    if (outgoing.any { it.status == OutgoingStatus.SENDING || it.status == OutgoingStatus.WAITING_CONNECTION || it.status == OutgoingStatus.FAILED }) return false
    if (outgoing.any { it.status == OutgoingStatus.DELIVERED }) return true
    if (!sessionBusy || items.none { it.event.kind == EventKinds.USER_MESSAGE }) return false
    return !agentRepliedAfterLastUser(items)
}

/** Whether anything from the agent follows the latest user message. */
fun agentRepliedAfterLastUser(items: List<RenderItem>): Boolean {
    val lastUser = items.indexOfLast { it.event.kind == EventKinds.USER_MESSAGE }
    return lastUser >= 0 && items.drop(lastUser + 1).any { it.event.kind in AGENT_OUTPUT }
}

private val AGENT_OUTPUT = setOf(
    EventKinds.ASSISTANT_MESSAGE, EventKinds.REASONING, EventKinds.TOOL_CALL, EventKinds.SUBAGENT,
    EventKinds.APPROVAL_REQUEST, EventKinds.QUESTION, EventKinds.ERROR, EventKinds.FILE_CHANGE, EventKinds.PLAN,
)
