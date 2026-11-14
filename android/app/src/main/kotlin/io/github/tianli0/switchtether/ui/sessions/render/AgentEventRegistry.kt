package io.github.tianli0.switchtether.ui.sessions.render

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import io.github.tianli0.switchtether.remote.protocol.CapStatus
import io.github.tianli0.switchtether.remote.protocol.EventKinds
import io.github.tianli0.switchtether.remote.session.RenderItem
import io.github.tianli0.switchtether.ui.sessions.PayloadState
import kotlinx.serialization.json.JsonObject

/** Callbacks and session-level facts shared by all renderers (stable: does not change per event). */
@Immutable
data class RenderContext(
    val approve: CapStatus,
    val answer: CapStatus,
    val capabilityReason: String?,
    val online: Boolean,
    val onApprove: (requestId: String, decision: String, label: String) -> Unit,
    val onAnswer: (requestId: String, answers: JsonObject) -> Unit,
    val onLoadPayload: (eventId: String) -> Unit,
    val payloadOf: (eventId: String) -> PayloadState?,
    val onToggleCollapse: (eventId: String) -> Unit,
    val isCollapsed: (eventId: String) -> Boolean,
    val onLink: (String) -> Unit,
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
        EventKinds.FILE_CHANGE to { i, _ -> FileChange(i) },
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
