package io.github.tianli0.switchtether.domain

import io.github.tianli0.switchtether.connection.ConnectionController
import io.github.tianli0.switchtether.connection.NotConnectedException
import io.github.tianli0.switchtether.remote.api.RemoteApi
import io.github.tianli0.switchtether.remote.command.CommandOutcome
import io.github.tianli0.switchtether.remote.command.CommandStatus
import io.github.tianli0.switchtether.remote.command.Commands
import io.github.tianli0.switchtether.remote.command.newCommandId
import io.github.tianli0.switchtether.remote.protocol.AgentEvent
import io.github.tianli0.switchtether.remote.protocol.AppToggleItem
import io.github.tianli0.switchtether.remote.protocol.CreateSessionBody
import io.github.tianli0.switchtether.remote.protocol.FsEntry
import io.github.tianli0.switchtether.remote.protocol.FsListing
import io.github.tianli0.switchtether.remote.protocol.DailyUsage
import io.github.tianli0.switchtether.remote.protocol.EventPage
import io.github.tianli0.switchtether.remote.protocol.OperationResult
import io.github.tianli0.switchtether.remote.protocol.PayloadChunk
import io.github.tianli0.switchtether.remote.protocol.PromptView
import io.github.tianli0.switchtether.remote.protocol.ProtocolJson
import io.github.tianli0.switchtether.remote.protocol.ProvidersView
import io.github.tianli0.switchtether.remote.protocol.QuotaView
import io.github.tianli0.switchtether.remote.protocol.SessionDetail
import io.github.tianli0.switchtether.remote.protocol.SessionPage
import io.github.tianli0.switchtether.remote.protocol.SessionSummary
import io.github.tianli0.switchtether.remote.protocol.UsageBreakdownRow
import io.github.tianli0.switchtether.remote.protocol.UsageSummary
import io.github.tianli0.switchtether.remote.protocol.parseAppTypes
import io.github.tianli0.switchtether.remote.protocol.long
import io.github.tianli0.switchtether.remote.protocol.str
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.JsonObject

/** Live changes relevant to sessions, derived from the event stream. */
sealed interface SessionChange {
    data class Upserted(val session: SessionSummary) : SessionChange
    data class Removed(val id: String) : SessionChange
    data class Event(val event: AgentEvent) : SessionChange
    /** Something about approvals changed; refresh counts. */
    data object ApprovalsChanged : SessionChange
}

data class SessionQuery(val agent: String? = null, val project: String? = null, val text: String? = null)

interface SessionRepository {
    /** Live session changes (empty when offline). */
    val changes: Flow<SessionChange>

    /** Emits when cached state must be refetched (reconnect / resync). */
    val refetch: Flow<String>

    suspend fun list(query: SessionQuery, cursor: String? = null, limit: Int = 50): SessionPage
    suspend fun detail(id: String): SessionDetail
    suspend fun events(id: String, before: Long? = null, after: Long? = null, limit: Int = 300): EventPage
    suspend fun payload(sessionId: String, eventId: String, offset: Long, limit: Int = 64 * 1024): PayloadChunk

    suspend fun sendMessage(
        id: String,
        text: String,
        commandId: String = newCommandId(),
        images: List<io.github.tianli0.switchtether.remote.protocol.ImageInput> = emptyList(),
    ): CommandOutcome
    suspend fun interrupt(id: String, commandId: String = newCommandId()): CommandOutcome
    suspend fun resolveApproval(id: String, requestId: String, decision: String, commandId: String = newCommandId()): CommandOutcome
    suspend fun answer(
        id: String,
        requestId: String,
        answers: JsonObject?,
        commandId: String = newCommandId(),
        action: String? = null,
    ): CommandOutcome
    suspend fun downloadFile(path: String): ByteArray? = null
    suspend fun commandStatus(commandId: String): CommandStatus

    /** Starts a SwitchTether-managed session (§7.5); the success body is its SessionSummary. */
    suspend fun createSession(request: NewSessionRequest, commandId: String = newCommandId()): CommandOutcome
    suspend fun fsRoots(): List<FsEntry>
    suspend fun fsList(path: String): FsListing
}

data class NewSessionRequest(
    val agent: String,
    val cwd: String,
    val prompt: String?,
    val model: String?,
    val permissionMode: String?,
    /** Session id to continue from (`mode = "continue"`): forks an external session into a managed one. */
    val resumeFrom: String? = null,
    val mode: String? = null,
)

interface ManagementRepository {
    val changes: Flow<String>
    suspend fun apps(): List<String>
    suspend fun providers(app: String): ProvidersView
    suspend fun switchProvider(app: String, id: String, commandId: String = newCommandId()): CommandOutcome
    suspend fun testProvider(app: String, id: String, commandId: String = newCommandId()): CommandOutcome
    suspend fun setCredential(app: String, id: String, field: String, value: String, commandId: String = newCommandId()): CommandOutcome
    suspend fun usageSummary(fromSec: Long, toSec: Long, app: String? = null): UsageSummary
    suspend fun usageTrend(fromSec: Long, toSec: Long, app: String? = null): List<DailyUsage>
    suspend fun usageModels(fromSec: Long, toSec: Long, app: String? = null): List<UsageBreakdownRow>
    suspend fun usageProviders(fromSec: Long, toSec: Long, app: String? = null): List<UsageBreakdownRow>
    suspend fun quota(tool: String): QuotaView

    // Newer desktops (PROTOCOL.md §7.3); older ones answer 404 and the UI hides the feature.
    suspend fun usageLogs(fromSec: Long, toSec: Long, app: String?, page: Int, pageSize: Int = 20): io.github.tianli0.switchtether.remote.protocol.RequestLogPage =
        throw UnsupportedOperationException()
    suspend fun usageLog(requestId: String): io.github.tianli0.switchtether.remote.protocol.RequestLog = throw UnsupportedOperationException()
    /** First usage day (desktop-local midnight, epoch ms), null without data. */
    suspend fun usageFirstDateMs(app: String?): Long? = null
    /** Last completed session-log scan (epoch ms), null if none since the desktop started. */
    suspend fun usageLastSyncMs(): Long? = null
    /** `visibleApps` from the desktop settings: apps switched off there are hidden here too. */
    suspend fun visibleApps(): Map<String, Boolean> = emptyMap()
    suspend fun mcpServers(): List<AppToggleItem>
    suspend fun toggleMcp(id: String, app: String, enabled: Boolean, commandId: String = newCommandId()): CommandOutcome
    suspend fun skills(): List<AppToggleItem>
    suspend fun toggleSkill(id: String, app: String, enabled: Boolean, commandId: String = newCommandId()): CommandOutcome
    suspend fun prompts(app: String): List<PromptView>
    suspend fun enablePrompt(app: String, id: String, commandId: String = newCommandId()): CommandOutcome
    suspend fun commandStatus(commandId: String): CommandStatus
}

/** Parses an operation result returned by a successful command (provider switch/test). */
fun CommandOutcome.Succeeded.operationResult(): OperationResult = OperationResult.parse(response.body)

private suspend fun <T> ConnectionController.withApi(block: suspend (RemoteApi) -> T): T =
    block(apiOrNull() ?: throw NotConnectedException())

private suspend fun ConnectionController.command(commandId: String, block: suspend (RemoteApi, String) -> io.github.tianli0.switchtether.remote.api.CommandResponse): CommandOutcome {
    val api = apiOrNull() ?: return CommandOutcome.NotSent(commandId, NotConnectedException())
    return Commands.run(commandId) { id -> block(api, id) }
}

class RemoteSessionRepository(private val conn: ConnectionController) : SessionRepository {
    override val changes: Flow<SessionChange> = conn.events.mapNotNull { ev ->
        val data = ev.data as? JsonObject
        when (ev.topic) {
            "session.upserted" -> data?.let { d ->
                val obj = (d["session"] as? JsonObject) ?: d
                runCatching { ProtocolJson.decodeFromJsonElement(SessionSummary.serializer(), obj) }.getOrNull()
            }?.let { SessionChange.Upserted(it) }
            "session.removed" -> data?.str("id", "sessionId")?.let { SessionChange.Removed(it) }
            "session.event" -> data?.let { d ->
                val obj = (d["event"] as? JsonObject) ?: d
                runCatching { ProtocolJson.decodeFromJsonElement(AgentEvent.serializer(), obj) }.getOrNull()
            }?.let { SessionChange.Event(it) }
            "approval.requested", "approval.resolved" -> SessionChange.ApprovalsChanged
            else -> null
        }
    }

    override val refetch: Flow<String> = conn.resyncs

    override suspend fun list(query: SessionQuery, cursor: String?, limit: Int) =
        conn.withApi { it.sessions(query.agent, query.project, query.text, cursor, limit) }

    override suspend fun detail(id: String) = conn.withApi { it.session(id) }

    override suspend fun events(id: String, before: Long?, after: Long?, limit: Int) =
        conn.withApi { it.sessionEvents(id, before, after, limit) }

    override suspend fun payload(sessionId: String, eventId: String, offset: Long, limit: Int) =
        conn.withApi { it.eventPayload(sessionId, eventId, offset, limit) }

    override suspend fun sendMessage(
        id: String,
        text: String,
        commandId: String,
        images: List<io.github.tianli0.switchtether.remote.protocol.ImageInput>,
    ) = conn.command(commandId) { api, c -> api.sendMessage(id, c, text, images) }

    override suspend fun interrupt(id: String, commandId: String) =
        conn.command(commandId) { api, c -> api.interrupt(id, c) }

    override suspend fun resolveApproval(id: String, requestId: String, decision: String, commandId: String) =
        conn.command(commandId) { api, c -> api.resolveApproval(id, requestId, c, decision) }

    override suspend fun answer(id: String, requestId: String, answers: JsonObject?, commandId: String, action: String?) =
        conn.command(commandId) { api, c -> api.answerQuestion(id, requestId, c, answers, action) }

    override suspend fun downloadFile(path: String): ByteArray? =
        conn.apiOrNull()?.let { api -> runCatching { api.fsDownloadBytes(path) }.getOrNull() }

    override suspend fun commandStatus(commandId: String): CommandStatus {
        val api = conn.apiOrNull() ?: return CommandStatus.Unreachable(NotConnectedException())
        return Commands.status(commandId) { api.command(it) }
    }

    override suspend fun createSession(request: NewSessionRequest, commandId: String) =
        conn.command(commandId) { api, c ->
            api.createSession(
                CreateSessionBody(
                    commandId = c,
                    agent = request.agent,
                    cwd = request.cwd.takeIf { it.isNotBlank() },
                    prompt = request.prompt?.takeIf { it.isNotBlank() },
                    model = request.model?.takeIf { it.isNotBlank() },
                    permissionMode = request.permissionMode?.takeIf { it.isNotBlank() },
                    resumeFrom = request.resumeFrom,
                    mode = request.mode,
                ),
            )
        }

    override suspend fun fsRoots(): List<FsEntry> = conn.withApi { it.fsRoots().roots }

    override suspend fun fsList(path: String): FsListing = conn.withApi { it.fsList(path) }
}

/** Parses the SessionSummary returned by `POST /v1/sessions`. */
fun CommandOutcome.Succeeded.createdSession(): SessionSummary? =
    runCatching { ProtocolJson.decodeFromJsonElement(SessionSummary.serializer(), response.body) }.getOrNull()

class RemoteManagementRepository(private val conn: ConnectionController) : ManagementRepository {
    override val changes: Flow<String> = conn.events
        .filter { it.topic == "provider.switched" || it.topic == "usage.updated" }
        .map { it.topic }

    override suspend fun apps(): List<String> = conn.withApi { parseAppTypes(it.providerApps()) }
    override suspend fun providers(app: String) = conn.withApi { ProvidersView.parse(it.providers(app), app) }
    override suspend fun switchProvider(app: String, id: String, commandId: String) = conn.command(commandId) { api, c -> api.switchProvider(app, id, c) }
    override suspend fun testProvider(app: String, id: String, commandId: String) = conn.command(commandId) { api, c -> api.testProvider(app, id, c) }
    override suspend fun setCredential(app: String, id: String, field: String, value: String, commandId: String) =
        conn.command(commandId) { api, c -> api.setProviderCredential(app, id, field, value, c) }
    override suspend fun usageSummary(fromSec: Long, toSec: Long, app: String?) = conn.withApi { UsageSummary.parse(it.usage("summary", fromSec, toSec, app)) }
    override suspend fun usageTrend(fromSec: Long, toSec: Long, app: String?) = conn.withApi { DailyUsage.parseList(it.usage("trends", fromSec, toSec, app)) }
    override suspend fun usageModels(fromSec: Long, toSec: Long, app: String?) = conn.withApi { UsageBreakdownRow.parseModels(it.usage("models", fromSec, toSec, app)) }
    override suspend fun usageProviders(fromSec: Long, toSec: Long, app: String?) = conn.withApi { UsageBreakdownRow.parseProviders(it.usage("providers", fromSec, toSec, app)) }
    override suspend fun quota(tool: String) = conn.withApi { QuotaView.parse(it.quota(tool), tool) }
    override suspend fun usageLogs(fromSec: Long, toSec: Long, app: String?, page: Int, pageSize: Int) =
        conn.withApi { io.github.tianli0.switchtether.remote.protocol.RequestLogPage.parse(it.usageLogs(fromSec, toSec, app, page, pageSize)) }
    override suspend fun usageLog(requestId: String) =
        conn.withApi { io.github.tianli0.switchtether.remote.protocol.RequestLog.parse(it.usageLog(requestId) as kotlinx.serialization.json.JsonObject) }
    override suspend fun usageFirstDateMs(app: String?) = conn.withApi {
        (it.usageFirstDate(app) as? kotlinx.serialization.json.JsonObject)?.let { o -> o.long("firstDateMs") }
    }
    override suspend fun usageLastSyncMs() = conn.withApi {
        (it.usageLastSync() as? kotlinx.serialization.json.JsonObject)?.let { o -> o.long("lastSyncMs") }
    }
    override suspend fun visibleApps() = conn.withApi {
        ((it.settings() as? kotlinx.serialization.json.JsonObject)?.get("visibleApps") as? kotlinx.serialization.json.JsonObject)
            ?.mapNotNull { (k, v) -> (v as? kotlinx.serialization.json.JsonPrimitive)?.let { p -> p.content.toBooleanStrictOrNull()?.let { b -> k to b } } }
            ?.toMap().orEmpty()
    }
    override suspend fun mcpServers() = conn.withApi { AppToggleItem.parseList(it.mcpServers(), "servers") }
    override suspend fun toggleMcp(id: String, app: String, enabled: Boolean, commandId: String) = conn.command(commandId) { api, c -> api.mcpToggle(id, app, enabled, c) }
    override suspend fun skills() = conn.withApi { AppToggleItem.parseList(it.skills(), "skills") }
    override suspend fun toggleSkill(id: String, app: String, enabled: Boolean, commandId: String) = conn.command(commandId) { api, c -> api.skillToggle(id, app, enabled, c) }
    override suspend fun prompts(app: String) = conn.withApi { PromptView.parseList(it.prompts(app)) }
    override suspend fun enablePrompt(app: String, id: String, commandId: String) = conn.command(commandId) { api, c -> api.enablePrompt(app, id, c) }
    override suspend fun commandStatus(commandId: String): CommandStatus {
        val api = conn.apiOrNull() ?: return CommandStatus.Unreachable(NotConnectedException())
        return Commands.status(commandId) { api.command(it) }
    }
}
