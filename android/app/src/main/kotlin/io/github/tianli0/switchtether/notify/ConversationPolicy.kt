package io.github.tianli0.switchtether.notify

import io.github.tianli0.switchtether.remote.command.CommandOutcome
import io.github.tianli0.switchtether.remote.notify.NotifyType
import io.github.tianli0.switchtether.remote.protocol.CapStatus
import io.github.tianli0.switchtether.remote.protocol.ControlLevel
import io.github.tianli0.switchtether.remote.protocol.ErrorCodes
import io.github.tianli0.switchtether.remote.protocol.SessionSummary

/**
 * Pure decisions behind conversation notifications (docs/ANDROID_UI.md §34): which session
 * shortcuts exist, what a session notification shows, when it offers an inline reply, and how it
 * coexists with a pinned task's Live Update. No Android types, so every rule has a JVM test
 * (`ConversationPolicyTest`).
 */
object ConversationPolicy {
    /** Prefix of the long-lived sharing shortcut of a session (one per session). */
    const val ID_PREFIX = "session:"

    /** Never more session shortcuts than this, whatever the launcher allows. */
    const val MAX_SESSION_SHORTCUTS = 6

    /** Slots left to other shortcuts of the app (the system limit is per activity). */
    const val RESERVED_SLOTS = 1

    /** A session that ended keeps its shortcut (and its place under "Conversations") this long. */
    const val ENDED_TTL_MS = 3L * 24 * 60 * 60 * 1000

    /** A shortcut nothing was posted for this long is removed even if the session never ended. */
    const val UNUSED_TTL_MS = 14L * 24 * 60 * 60 * 1000

    fun shortcutId(sessionId: String): String = ID_PREFIX + sessionId

    /** The session of a shortcut id, null for shortcuts that are not session conversations. */
    fun sessionIdOf(shortcutId: String): String? =
        shortcutId.takeIf { it.startsWith(ID_PREFIX) && it.length > ID_PREFIX.length }?.substring(ID_PREFIX.length)

    /** How many session shortcuts to keep for a launcher limit of [systemMax] per activity. */
    fun limit(systemMax: Int): Int = (systemMax - RESERVED_SLOTS).coerceIn(1, MAX_SESSION_SHORTCUTS)

    /** What the app remembers about a session shortcut. */
    data class Entry(val sessionId: String, val lastUsedMs: Long, val endedAtMs: Long? = null)

    /**
     * Session shortcuts to remove: ended more than [ENDED_TTL_MS] ago, unused for [UNUSED_TTL_MS],
     * and beyond [limit] the least recently used. [keep] (the session being posted now) is never
     * evicted, so a notification never references a shortcut removed in the same step.
     */
    fun evictions(entries: Collection<Entry>, nowMs: Long, limit: Int, keep: String? = null): Set<String> {
        val expired = entries.filter { e ->
            e.sessionId != keep && (
                (e.endedAtMs != null && nowMs - e.endedAtMs > ENDED_TTL_MS) || nowMs - e.lastUsedMs > UNUSED_TTL_MS
                )
        }.map { it.sessionId }.toSet()
        val rest = entries.filter { it.sessionId !in expired }
            .sortedWith(compareByDescending<Entry> { it.sessionId == keep }.thenByDescending { it.lastUsedMs })
        val overflow = rest.drop(limit.coerceAtLeast(1)).map { it.sessionId }.filter { it != keep }
        return expired + overflow
    }

    /** `sid \t lastUsed \t endedAt` per line; unreadable lines are dropped. */
    fun encode(entries: Collection<Entry>): String =
        entries.joinToString("\n") { "${it.sessionId}\t${it.lastUsedMs}\t${it.endedAtMs ?: ""}" }

    fun decode(raw: String?): List<Entry> = raw.orEmpty().lineSequence().mapNotNull { line ->
        val p = line.split('\t')
        val sid = p.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val used = p.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
        Entry(sid, used, p.getOrNull(2)?.toLongOrNull())
    }.toList()

    // ---- inline reply -------------------------------------------------------------------------

    /**
     * Whether a session notification may offer 回复: the engine said the phone controls the live
     * session ([engineReplyable]: live and control `full`) **and** the desktop's `sendMessage`
     * capability is usable — the same capability the in-app composer is enabled by. An unknown
     * capability (detail not loaded) does not offer it.
     */
    fun replyAllowed(engineReplyable: Boolean, type: NotifyType, summary: SessionSummary?, sendCapability: CapStatus?): Boolean =
        engineReplyable && type != NotifyType.WAITING_APPROVAL &&
            summary != null && summary.isLive && summary.controlLevel == ControlLevel.FULL &&
            sendCapability?.usable == true

    /** What an inline reply came to, for the notification that replaces it. */
    sealed interface ReplyResult {
        /** The desktop accepted the message. */
        data object Sent : ReplyResult

        /** The desktop wants a fresh step-up: never done from a notification; the app is opened. */
        data object NeedsStepUp : ReplyResult

        /** Definitively refused (the message is not in the session). */
        data class Refused(val reason: String) : ReplyResult

        /** Sent but not confirmed; the desktop's command record did not settle it either. */
        data object Unsure : ReplyResult

        /** No connection to the computer in time (nothing was sent). */
        data object Offline : ReplyResult
    }

    /**
     * Maps a send outcome like the composer does: `409 in progress` / unknown outcomes are settled
     * by the desktop's command record ([recordSays]: true = it has the message, false = it failed
     * or was lost, null = could not ask), never by sending again.
     */
    fun replyResult(outcome: CommandOutcome?, recordSays: Boolean? = null): ReplyResult = when (outcome) {
        null -> ReplyResult.Offline
        is CommandOutcome.Succeeded -> ReplyResult.Sent
        is CommandOutcome.Rejected ->
            if (outcome.error.code == ErrorCodes.STEP_UP_REQUIRED || (outcome.error.httpStatus == 403 && outcome.error.detailReason == "no_step_up_key")) ReplyResult.NeedsStepUp
            else ReplyResult.Refused(outcome.error.body.message.ifBlank { outcome.error.code })
        is CommandOutcome.NotSent -> ReplyResult.Offline
        is CommandOutcome.InProgress, is CommandOutcome.Unknown -> when (recordSays) {
            true -> ReplyResult.Sent
            false -> ReplyResult.Refused("")
            null -> if (outcome is CommandOutcome.InProgress) ReplyResult.Sent else ReplyResult.Unsure
        }
    }

    // ---- Live Update coexistence ----------------------------------------------------------------

    enum class LiveDecision {
        /** Post as usual. */
        POST,

        /** Post into the conversation without sound or vibration (the card already alerted). */
        POST_SILENTLY,

        /** Do not post: the pinned task's Live Update card shows exactly this. */
        SKIP,
    }

    /** What the Live Update card shows for a pinned session: its state, waits and outcomes. */
    private val SHOWN_BY_CARD = setOf(
        NotifyType.WAITING_APPROVAL, NotifyType.WAITING_INPUT, NotifyType.TASK_STARTED, NotifyType.TASK_COMPLETED,
        NotifyType.TASK_FAILED, NotifyType.SESSION_INTERRUPTED, NotifyType.SESSION_EXITED, NotifyType.LONG_RUNNING, NotifyType.TOOL_CALL,
    )

    /**
     * The de-dup rule between a pinned task's Live Update and the session's conversation
     * notification. While a card really shows the session ([covered], see `LiveUpdates.covers`):
     * - everything the card shows (state, approvals, questions, outcomes, tool steps) is **not**
     *   posted as a conversation message: the card is the one place for it, with its own
     *   allow / deny buttons;
     * - what the card cannot show — the agent's reply text — still goes into the conversation,
     *   but **silently** (the card is the session's alerting surface).
     * Not covered (no pin, Live Updates off, card swiped away): everything posts as usual.
     */
    fun liveDecision(type: NotifyType, covered: Boolean): LiveDecision = when {
        !covered -> LiveDecision.POST
        type in SHOWN_BY_CARD -> LiveDecision.SKIP
        else -> LiveDecision.POST_SILENTLY
    }

    // ---- content -------------------------------------------------------------------------------

    /** How a session notification is built. */
    data class Content(
        /** MessagingStyle conversation with the session's shortcut (false: a plain notification). */
        val conversation: Boolean,
        /** The sender: "Claude Code · fix login" (agent label and session name). */
        val sender: String,
        /** The message added to the conversation for this notification. */
        val message: String,
        val offerReply: Boolean,
        val silent: Boolean,
    )

    const val MAX_NAME = 40

    fun senderName(agentLabel: String, sessionName: String?): String {
        val name = sessionName?.trim()?.takeIf { it.isNotEmpty() }?.let { if (it.length > MAX_NAME) it.take(MAX_NAME - 1) + "…" else it }
        return if (name == null) agentLabel else "$agentLabel · $name"
    }

    /**
     * The message for a notification: an agent reply is the reply text itself; anything else says
     * what happened (title) followed by its detail.
     */
    fun messageText(type: NotifyType, title: String, body: String): String = when {
        type == NotifyType.AGENT_REPLY && body.isNotBlank() -> body
        body.isBlank() -> title
        else -> "$title\n$body"
    }

    /** Types that never become a conversation (no session, or not about the agent's work). */
    private val NOT_CONVERSATION = setOf(NotifyType.CONNECTION, NotifyType.TERMINAL_EXITED, NotifyType.LIVE_UPDATE)

    fun content(
        type: NotifyType,
        sessionId: String?,
        title: String,
        body: String,
        agentLabel: String,
        sessionName: String?,
        replyAllowed: Boolean,
        silent: Boolean,
        live: LiveDecision = LiveDecision.POST,
    ): Content {
        val conversation = sessionId != null && type !in NOT_CONVERSATION
        return Content(
            conversation = conversation,
            sender = senderName(agentLabel, sessionName),
            message = messageText(type, title, body),
            offerReply = replyAllowed && sessionId != null,
            silent = silent || live == LiveDecision.POST_SILENTLY,
        )
    }
}
