package io.github.tianli0.switchtether.ui.sessions.render

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import io.github.tianli0.switchtether.remote.protocol.CapStatus
import io.github.tianli0.switchtether.remote.protocol.EventKinds
import io.github.tianli0.switchtether.remote.session.RenderItem
import io.github.tianli0.switchtether.ui.sessions.PayloadState
import kotlinx.serialization.json.JsonObject

data class ImagePreview(
    val alt: String? = null,
    val bytes: ByteArray? = null,
    val url: String? = null,
    val path: String? = null,
    /** The other pictures of the same message (this one included), swiped through in the lightbox. */
    val gallery: List<ImagePreview> = emptyList(),
    val index: Int = 0,
)

/** Callbacks and session-level facts shared by all renderers (stable: does not change per event). */
@Immutable
data class RenderContext(
    /** Agent id of the session (`claude_code`, `codex`, …) and its display name (avatars). */
    val agent: String,
    val agentLabel: String,
    val approve: CapStatus,
    val answer: CapStatus,
    val capabilityReason: String?,
    val online: Boolean,
    val onApprove: (requestId: String, decision: String, label: String) -> Unit,
    val onAnswer: (requestId: String, answers: JsonObject) -> Unit,
    val onAnswerAction: (requestId: String, answers: JsonObject?, action: String?) -> Unit = { id, ans, _ -> if (ans != null) onAnswer(id, ans) },
    val onLoadPayload: (eventId: String) -> Unit,
    val payloadOf: (eventId: String) -> PayloadState?,
    val onToggleCollapse: (eventId: String) -> Unit,
    val isCollapsed: (eventId: String) -> Boolean,
    val onLink: (String) -> Unit,
    /** Opens a file on the computer (the one a Read / Edit / Write step is about) in the viewer; null = not offered. */
    val onOpenFile: ((path: String) -> Unit)? = null,
    val onLoadImageBytes: (suspend (path: String) -> ByteArray?)? = null,
    val onViewImage: ((ImagePreview) -> Unit)? = null,
    val requestBusy: Boolean = false,
    /** Opens a Claude Code sub-agent's own steps ("查看过程"); null where that is unavailable. */
    val onOpenSubagent: ((agentId: String, title: String?) -> Unit)? = null,
    /** Latest steps of a sub-agent for the branch lines under its row (null until loaded). */
    val subagentPreview: (agentId: String) -> SubagentPreview? = { null },
    /** Asks for (or refreshes) [subagentPreview]; throttled by the owner. */
    val onWantSubagentPreview: (agentId: String) -> Unit = {},
    /** Claude Code session tools: move a running foreground Bash / Agent call to the background (null = not offered). */
    val onRunInBackground: ((toolUseId: String) -> Unit)? = null,
    /** Whether a run-in-background request for that tool use id is in flight. */
    val isBackgrounding: (toolUseId: String) -> Boolean = { false },
    /** Claude Code session tools: "rewind files to before this message" for a user message id (null = not offered). */
    val onRewindFiles: ((userMessageId: String) -> Unit)? = null,
    /**
     * Leaving plan mode like Claude Code: switch the session to [mode] (`acceptEdits` or `default`)
     * and then approve the `ExitPlanMode` request. Null = not offered (no Claude Code session
     * tools, or offline): the card keeps the plain allow / deny.
     */
    val onApprovePlan: ((requestId: String, mode: String, label: String) -> Unit)? = null,
)

typealias EventRenderer = @Composable (item: RenderItem, ctx: RenderContext) -> Unit

/**
 * Component registry keyed by `AgentEvent.kind` (docs/PROTOCOL.md §10.2). Unknown kinds fall back to
 * [GenericEventCard]; the `ui` kind dispatches again on `data.component` through [UiComponentRegistry].
 */
object AgentEventRegistry {
    val renderers: Map<String, EventRenderer> = mapOf(
        EventKinds.USER_MESSAGE to { i, c -> UserMessage(i, c) },
        EventKinds.ASSISTANT_MESSAGE to { i, c -> AssistantMessage(i, c) },
        EventKinds.REASONING to { i, _ -> Reasoning(i) },
        EventKinds.TOOL_CALL to { i, c -> ToolCall(i, c) },
        EventKinds.TOOL_RESULT to { i, c -> ToolResultStandalone(i, c) },
        EventKinds.FILE_CHANGE to { i, c -> FileChange(i, c) },
        EventKinds.PLAN to { i, _ -> Plan(i) },
        EventKinds.APPROVAL_REQUEST to { i, c -> ApprovalRequest(i, c) },
        EventKinds.APPROVAL_RESOLVED to { i, _ -> ApprovalResolved(i) },
        EventKinds.QUESTION to { i, c -> Question(i, c) },
        EventKinds.SUBAGENT to { i, c -> Subagent(i, c) },
        EventKinds.STATUS to { i, _ -> StatusRow(i) },
        EventKinds.ERROR to { i, _ -> ErrorRow(i) },
        EventKinds.SYSTEM to { i, _ -> SystemRow(i) },
        EventKinds.USAGE to { i, _ -> UsageRow(i) },
        EventKinds.UI to { i, c -> UiComponent(i, c) },
    )

    fun rendererFor(kind: String): EventRenderer = renderers[kind] ?: { i, _ -> GenericEventCard(i) }

    /** For docs/tests: kinds with a dedicated renderer. */
    val supportedKinds: Set<String> get() = renderers.keys
}

/** `ui` events: `data.component` = "<namespace>.<name>" → Composable; fallback = structured JSON card. */
object UiComponentRegistry {
    val components: Map<String, @Composable (props: JsonObject, version: Int) -> Unit> = emptyMap()
}
