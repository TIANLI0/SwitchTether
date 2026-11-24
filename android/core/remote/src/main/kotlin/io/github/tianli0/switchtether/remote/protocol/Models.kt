package io.github.tianli0.switchtether.remote.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/*
 * Kotlin mirror of the wire types in docs/PROTOCOL.md (source of truth on the desktop side:
 * src-tauri/crates/switchtether-core/src/protocol.rs and agents/model.rs).
 *
 * Rules: every field has a default so additive server changes never break parsing; enum-like
 * values stay Strings on the wire and are interpreted through the helpers below so unknown values
 * degrade gracefully instead of throwing.
 */

// ---------------------------------------------------------------------------------------------
// §3 Pairing
// ---------------------------------------------------------------------------------------------

@Serializable
data class Hello(
    val protocolVersion: Int,
    val minProtocolVersion: Int = 1,
    val serverId: String = "",
    val serverName: String = "",
    val serverVersion: String = "",
    val paired: Boolean = false,
    val deviceId: String? = null,
    val epoch: String = "",
) {
    /** §9: the app refuses to talk to a desktop whose supported range does not include ours. */
    fun compatibility(clientVersion: Int = CLIENT_PROTOCOL_VERSION): Compatibility = when {
        clientVersion < minProtocolVersion -> Compatibility.APP_TOO_OLD
        clientVersion > protocolVersion -> Compatibility.DESKTOP_TOO_OLD
        else -> Compatibility.OK
    }

    enum class Compatibility { OK, APP_TOO_OLD, DESKTOP_TOO_OLD }
}

@Serializable
data class PairRequest(
    /** QR / link pairing: session id + one-time secret (null when pairing by [code]). */
    val pairingId: String? = null,
    val secret: String? = null,
    val deviceName: String,
    val platform: String = "android",
    val osVersion: String? = null,
    val appVersion: String? = null,
    val protocolVersion: Int = CLIENT_PROTOCOL_VERSION,
    /** Base64 (standard) SubjectPublicKeyInfo DER of the Keystore step-up key, or null. */
    val stepUpPublicKey: String? = null,
    /** Code pairing: the 6-digit code shown on the desktop. */
    val code: String? = null,
)

@Serializable
data class PairResponse(
    val deviceId: String,
    val serverId: String = "",
    val serverName: String = "",
    val serverFingerprint: String = "",
    val stepUpEnabled: Boolean = false,
    val verificationCode: String? = null,
)

// ---------------------------------------------------------------------------------------------
// §2 Errors
// ---------------------------------------------------------------------------------------------

@Serializable
data class ApiErrorEnvelope(val error: ApiErrorBody)

@Serializable
data class ApiErrorBody(
    val code: String = "internal",
    val message: String = "",
    val requestId: String? = null,
    val retryable: Boolean = false,
    val details: JsonObject? = null,
)

object ErrorCodes {
    const val BAD_REQUEST = "bad_request"
    const val VALIDATION_FAILED = "validation_failed"
    const val UNPAIRED = "unpaired"
    const val DEVICE_REVOKED = "device_revoked"
    const val STEP_UP_REQUIRED = "step_up_required"
    const val FORBIDDEN = "forbidden"
    const val NOT_FOUND = "not_found"
    const val CONFLICT = "conflict"
    const val COMMAND_IN_PROGRESS = "command_in_progress"
    const val PAIRING_EXPIRED = "pairing_expired"
    const val PAYLOAD_TOO_LARGE = "payload_too_large"
    const val CAPABILITY_UNAVAILABLE = "capability_unavailable"
    const val RATE_LIMITED = "rate_limited"
    const val INTERNAL = "internal"
    const val BACKEND_UNAVAILABLE = "backend_unavailable"
}

// ---------------------------------------------------------------------------------------------
// §4 Step-up
// ---------------------------------------------------------------------------------------------

@Serializable
data class StepUpChallengeRequest(val scope: String)

@Serializable
data class StepUpChallenge(val challengeId: String, val nonce: String, val expiresAt: String = "")

@Serializable
data class StepUpVerifyRequest(val challengeId: String, val signature: String)

@Serializable
data class StepUpGrant(val scope: String, val expiresAt: String = "")

@Serializable
data class StepUpGrants(val grants: List<StepUpGrant> = emptyList())

object StepUpScopes {
    const val TERMINAL = "terminal"
    const val FILES_WRITE = "files_write"
    const val CONFIG_WRITE = "config_write"
    const val PORT_FORWARD = "port_forward"
    /** Stage, discard, commit, push, pull, fetch, checkout (PROTOCOL.md §7.7). */
    const val GIT_WRITE = "git_write"
}

// ---------------------------------------------------------------------------------------------
// §5 Commands
// ---------------------------------------------------------------------------------------------

@Serializable
data class CommandRecord(
    val commandId: String,
    val kind: String? = null,
    /** pending | succeeded | failed */
    val status: String = "pending",
    val result: JsonElement? = null,
    val error: JsonElement? = null,
    val createdAtMs: Long? = null,
    val completedAtMs: Long? = null,
    val createdAt: String? = null,
    val completedAt: String? = null,
)

// ---------------------------------------------------------------------------------------------
// §7.1 Status / §8 capabilities
// ---------------------------------------------------------------------------------------------

@Serializable
data class StatusView(
    val serverId: String = "",
    val serverName: String = "",
    val serverVersion: String = "",
    val protocolVersion: Int = 1,
    val epoch: String = "",
    val headSeq: Long = 0,
    val networkMode: String? = null,
    /** The desktop's IANA time zone (usage "today" / buckets), null when unknown or older desktops. */
    val timeZone: String? = null,
    val utcOffsetMinutes: Int? = null,
    val backendAvailable: Boolean = true,
    val connections: Int = 0,
    val terminals: Int = 0,
    val platform: String? = null,
    val deviceId: String? = null,
    val stepUpGrants: List<StepUpGrant> = emptyList(),
    /** The desktop's current address candidates (merged into the stored hosts on connect). */
    val hosts: List<String> = emptyList(),
    /** Wake-on-LAN targets of the desktop's LAN interfaces. */
    val wake: List<io.github.tianli0.switchtether.remote.wake.WakeTarget> = emptyList(),
)

@Serializable
data class DeviceView(
    val id: String = "",
    val name: String = "",
    val platform: String = "",
    val certFingerprint: String = "",
    val stepUpEnabled: Boolean = false,
    val createdAtMs: Long? = null,
    val lastSeenAtMs: Long? = null,
    val lastIp: String? = null,
)

/** Capability status values (docs/AGENT_COMPATIBILITY.md). */
enum class CapStatus {
    VERIFIED, PARTIAL, BLOCKED, UNTESTED, UNKNOWN;

    /**
     * Whether the desktop offers the action at all. VERIFIED, PARTIAL (works with stated
     * limitations) and UNTESTED (implemented, not exercised live) are enabled; the status is
     * still shown to the user. BLOCKED / UNKNOWN are disabled.
     */
    val usable: Boolean get() = this == VERIFIED || this == PARTIAL || this == UNTESTED

    companion object {
        fun parse(value: String?): CapStatus =
            entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) } ?: UNKNOWN
    }
}

// ---------------------------------------------------------------------------------------------
// §10 Sessions and agent events
// ---------------------------------------------------------------------------------------------

@Serializable
data class SessionSummary(
    val id: String,
    val agent: String = "unknown",
    val origin: String = "unknown",
    val nativeId: String = "",
    val title: String? = null,
    val cwd: String? = null,
    val project: String? = null,
    val liveness: String = "unknown",
    val status: String = "unknown",
    val waitingFor: String? = null,
    val control: String = "history",
    val createdAtMs: Long? = null,
    val lastActiveAtMs: Long? = null,
    val model: String? = null,
    /** Effort / reasoning level chosen for a session SwitchTether runs (`low` … `max`). */
    val effort: String? = null,
    val gitBranch: String? = null,
    val pid: Long? = null,
    val managed: Boolean = false,
    /** Set when the session is mirrored from a SwitchTether terminal (`GET /v1/terminals/{id}`). */
    val terminalId: String? = null,
) {
    val isLive: Boolean get() = liveness == "live"

    /**
     * The user's own process (VS Code, desktop app, CLI) that SwitchTether is attached to through the
     * opt-in wrapper: full control without being a SwitchTether-managed session.
     */
    val isAttached: Boolean get() = controlLevel == ControlLevel.FULL && !managed && origin != "managed"
    val controlLevel: ControlLevel get() = ControlLevel.parse(control)
}

enum class ControlLevel {
    FULL, OBSERVE, HISTORY;

    companion object {
        fun parse(v: String?): ControlLevel = when (v?.lowercase()) {
            "full" -> FULL
            "observe" -> OBSERVE
            else -> HISTORY
        }
    }
}

@Serializable
data class SessionPage(
    val items: List<SessionSummary> = emptyList(),
    val nextCursor: String? = null,
)

@Serializable
data class SessionCapabilities(
    val readHistory: String? = null,
    val liveEvents: String? = null,
    val sendMessage: String? = null,
    val approve: String? = null,
    val answerQuestion: String? = null,
    val interrupt: String? = null,
    /** Present (e.g. `PARTIAL`) when the session can be continued as a managed one on the phone. */
    val continueSession: String? = null,
    /** Present when model / effort of the running session can be changed (`POST …/options`). */
    val changeModel: String? = null,
    val reason: String? = null,
) {
    fun status(name: Capability): CapStatus = CapStatus.parse(
        when (name) {
            Capability.READ_HISTORY -> readHistory
            Capability.LIVE_EVENTS -> liveEvents
            Capability.SEND_MESSAGE -> sendMessage
            Capability.APPROVE -> approve
            Capability.ANSWER_QUESTION -> answerQuestion
            Capability.INTERRUPT -> interrupt
            Capability.CONTINUE_SESSION -> continueSession
            Capability.CHANGE_MODEL -> changeModel
        },
    )
}

enum class Capability { READ_HISTORY, LIVE_EVENTS, SEND_MESSAGE, APPROVE, ANSWER_QUESTION, INTERRUPT, CONTINUE_SESSION, CHANGE_MODEL }

/** `GET /v1/sessions/{id}`: the summary fields plus capabilities and open requests. */
data class SessionDetail(
    val summary: SessionSummary,
    val capabilities: SessionCapabilities,
    val pendingRequests: List<AgentEvent>,
    /** Claude Code session tools are offered (`POST …/claude/{action}`): a Claude process SwitchTether started. */
    val claudeTools: Boolean = false,
    /** Current Claude Code permission mode when [claudeTools] (`default`, `acceptEdits`, `plan`, …). */
    val permissionMode: String? = null,
    /** A Claude Code session started on the computer (observe-only): only the read-only tools are
     *  offered (plan, export, background task output). */
    val claudeReadTools: Boolean = false,
    /** Not running anywhere: a message resumes it under SwitchTether, with the model, effort and
     *  permission mode chosen beforehand ([permissionMode] then is that pending choice). */
    val resumable: Boolean = false,
)

@Serializable
data class AgentEvent(
    val id: String,
    val seq: Long = 0,
    val sessionId: String = "",
    val turnId: String? = null,
    val ts: String = "",
    val kind: String = "unknown",
    val parentId: String? = null,
    val streaming: Boolean = false,
    val data: JsonObject = JsonObject(emptyMap()),
)

/** Known [AgentEvent.kind] values (§10.2). Unknown kinds are rendered by the generic fallback. */
object EventKinds {
    const val USER_MESSAGE = "user_message"
    const val ASSISTANT_MESSAGE = "assistant_message"
    const val REASONING = "reasoning"
    const val TOOL_CALL = "tool_call"
    const val TOOL_RESULT = "tool_result"
    const val FILE_CHANGE = "file_change"
    const val PLAN = "plan"
    const val APPROVAL_REQUEST = "approval_request"
    const val APPROVAL_RESOLVED = "approval_resolved"
    const val QUESTION = "question"
    const val SUBAGENT = "subagent"
    const val STATUS = "status"
    const val ERROR = "error"
    const val SYSTEM = "system"
    const val USAGE = "usage"
    const val UI = "ui"

    val ALL: Set<String> = setOf(
        USER_MESSAGE, ASSISTANT_MESSAGE, REASONING, TOOL_CALL, TOOL_RESULT, FILE_CHANGE, PLAN,
        APPROVAL_REQUEST, APPROVAL_RESOLVED, QUESTION, SUBAGENT, STATUS, ERROR, SYSTEM, USAGE, UI,
    )
}

/** A page of events from `GET /v1/sessions/{id}/events`. */
data class EventPage(
    val events: List<AgentEvent>,
    /** True when the server indicated (or we infer) older events exist before the first one. */
    val hasMoreBefore: Boolean,
)

/** A chunk of a large tool output from `…/events/{eventId}/payload`. */
data class PayloadChunk(
    val text: String,
    val offset: Long,
    val totalBytes: Long?,
    val eof: Boolean,
)

// ---------------------------------------------------------------------------------------------
// Request bodies for side-effecting calls (§5: every one carries a commandId)
// ---------------------------------------------------------------------------------------------

@Serializable
data class CommandBody(val commandId: String)

@Serializable
data class ImageInput(
    val mediaType: String,
    val dataBase64: String,
    val name: String? = null,
)

@Serializable
data class SendMessageBody(
    val commandId: String,
    val text: String,
    val images: List<ImageInput> = emptyList(),
    /** Ids of files uploaded with `POST /v1/sessions/{id}/attachments` (newer desktops). */
    val attachments: List<String> = emptyList(),
)

/** `POST /v1/sessions/{id}/attachments`: announce a file attached to the next message. */
@Serializable
data class BeginAttachmentBody(val name: String, val size: Long, val mediaType: String? = null)

/** Upload state of an attachment; `complete` once every byte arrived. */
@Serializable
data class AttachmentView(
    val attachmentId: String = "",
    val name: String = "",
    val size: Long = 0,
    val received: Long = 0,
    val complete: Boolean = false,
    val mediaType: String = "",
)

@Serializable
data class ApprovalBody(val commandId: String, val decision: String, val message: String? = null)

@Serializable
data class AnswersBody(
    val commandId: String,
    val answers: JsonObject? = null,
    val action: String? = null,
)

@Serializable
data class ToggleBody(val commandId: String, val app: String, val enabled: Boolean)

/** `POST /v1/sessions`: starts a SwitchTether-managed session (origin "managed", control "full"). */
@Serializable
data class CreateSessionBody(
    val commandId: String,
    val agent: String,
    /** Required for new sessions; optional with [resumeFrom] (defaults to the original's). */
    val cwd: String?,
    val prompt: String? = null,
    val model: String? = null,
    val permissionMode: String? = null,
    /** Continue an existing (externally started) session as a managed one. */
    val resumeFrom: String? = null,
    /** `"continue"` with [resumeFrom]. */
    val mode: String? = null,
    /** Effort / reasoning level (Claude Code: low … max; Codex: minimal … xhigh). */
    val effort: String? = null,
)

/** `POST /v1/sessions/{id}/options`: absent = unchanged, `""` = the agent's default. */
@kotlinx.serialization.Serializable
data class SessionOptionsBody(
    val commandId: String,
    val model: String? = null,
    val effort: String? = null,
)

/** One model offered by `GET /v1/agents/{agent}/models`. */
@kotlinx.serialization.Serializable
data class ModelOption(
    val id: String,
    val label: String = id,
    val description: String? = null,
    /** Effort levels this model supports, when the agent says so. */
    val efforts: List<String>? = null,
)

/** `GET /v1/agents/{agent}/models?session=`: models and effort levels to offer. */
@kotlinx.serialization.Serializable
data class ModelOptions(
    val agent: String = "",
    val models: List<ModelOption> = emptyList(),
    val efforts: List<String> = emptyList(),
    /** `agent` (reported by the running agent) or `builtin` (aliases every install accepts). */
    val source: String = "builtin",
)

// ---------------------------------------------------------------------------------------------
// §7.7 Files (read-only use in Phase A: working-directory picker)
// ---------------------------------------------------------------------------------------------

@Serializable
data class FsEntry(
    val name: String = "",
    val path: String = "",
    /** dir | file | symlink | other */
    val kind: String = "file",
    val size: Long = 0,
    val modifiedMs: Long? = null,
    val hidden: Boolean = false,
    val sensitive: Boolean = false,
    val symlink: Boolean = false,
    val readonly: Boolean = false,
) {
    val isDir: Boolean get() = kind == "dir"
}

@Serializable
data class FsRoots(val roots: List<FsEntry> = emptyList())

@Serializable
data class FsListing(
    val path: String = "",
    val parent: String? = null,
    val entries: List<FsEntry> = emptyList(),
    val truncated: Boolean = false,
    /** Entries hidden because the desktop's policy blocks them. */
    val blocked: Int = 0,
)
