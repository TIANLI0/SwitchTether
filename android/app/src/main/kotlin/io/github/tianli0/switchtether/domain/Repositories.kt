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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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

    /** Offline snapshot: last session list seen from this desktop, null if none (see SessionCache). */
    suspend fun cachedList(): io.github.tianli0.switchtether.data.ListSnapshot? = null

    /**
     * The last session list this process already holds in memory (another screen loaded or read it),
     * without waiting: what a list screen shows in its first frame. Null when nothing is held yet.
     */
    fun peekList(): io.github.tianli0.switchtether.data.ListSnapshot? = null

    /** Offline snapshot: newest events of a conversation as last seen in this process, null if none. */
    suspend fun cachedEvents(id: String): io.github.tianli0.switchtether.data.EventsSnapshot? = null

    /** Keeps what a conversation screen shows as its offline snapshot (memory only). */
    fun rememberEvents(id: String, events: List<AgentEvent>, hasMoreBefore: Boolean) = Unit

    /** Change model and / or effort of a running session (`""` = the agent's default, null = unchanged). */
    suspend fun setOptions(id: String, model: String?, effort: String?, commandId: String = newCommandId()): CommandOutcome =
        throw UnsupportedOperationException("session options")

    /** Models and effort levels to offer for [agent] (and a running [sessionId]). */
    suspend fun modelOptions(agent: String, sessionId: String? = null): io.github.tianli0.switchtether.remote.protocol.ModelOptions =
        throw UnsupportedOperationException("model options")
    suspend fun detail(id: String): SessionDetail
    suspend fun events(id: String, before: Long? = null, after: Long? = null, limit: Int = 300): EventPage
    suspend fun payload(sessionId: String, eventId: String, offset: Long, limit: Int = 64 * 1024): PayloadChunk

    /** Claude Code sub-agents of a session, with parent links (branch tree). */
    suspend fun subagentTree(id: String): List<io.github.tianli0.switchtether.remote.session.SubagentNode> = emptyList()

    /** The Agent map: sub-agents with live state, background tasks, whether they can be stopped. */
    suspend fun agentMap(id: String): io.github.tianli0.switchtether.remote.session.AgentMap =
        io.github.tianli0.switchtether.remote.session.AgentMap(subagentTree(id))

    /** Stop one sub-agent or background task (sessions SwitchTether started). */
    suspend fun stopSubagent(id: String, taskId: String, commandId: String = newCommandId()): CommandOutcome =
        throw UnsupportedOperationException("stop sub-agent")

    /**
     * One Claude Code session tool (`POST …/claude/{action}`, see
     * [io.github.tianli0.switchtether.remote.session.ClaudeTools]); only sessions whose detail has `claudeTools`.
     */
    suspend fun claudeTool(id: String, action: String, params: JsonObject = JsonObject(emptyMap()), commandId: String = newCommandId()): CommandOutcome =
        throw UnsupportedOperationException("Claude Code session tools")

    /**
     * The slash commands the running Claude Code process offers (`POST …/claude/commands`). Any
     * failure (an older desktop answers 404 / 422, no connection) is an empty list: the phone
     * then offers its built-in ones.
     */
    suspend fun claudeCommands(id: String): List<io.github.tianli0.switchtether.remote.session.SlashCommand> = try {
        when (val out = claudeTool(id, io.github.tianli0.switchtether.remote.session.ClaudeTools.COMMANDS)) {
            is CommandOutcome.Succeeded -> io.github.tianli0.switchtether.remote.session.ClaudeTools.parseCommands(out.response.body)
            else -> emptyList()
        }
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }

    /** One sub-agent's own steps (read-only). */
    suspend fun subagentEvents(id: String, agentId: String, limit: Int = 300): io.github.tianli0.switchtether.remote.session.SubagentPage =
        throw UnsupportedOperationException("sub-agent transcripts")

    suspend fun sendMessage(
        id: String,
        text: String,
        commandId: String = newCommandId(),
        images: List<io.github.tianli0.switchtether.remote.protocol.ImageInput> = emptyList(),
        attachments: List<String> = emptyList(),
    ): CommandOutcome
    /** Files for the next message: announce, then upload in chunks (see docs/PROTOCOL.md). */
    suspend fun beginAttachment(id: String, name: String, size: Long, mediaType: String?): io.github.tianli0.switchtether.remote.protocol.AttachmentView =
        throw UnsupportedOperationException("attachments")
    suspend fun uploadAttachmentChunk(id: String, attachmentId: String, offset: Long, bytes: ByteArray): io.github.tianli0.switchtether.remote.protocol.AttachmentView =
        throw UnsupportedOperationException("attachments")
    suspend fun cancelAttachment(id: String, attachmentId: String) {}
    suspend fun interrupt(id: String, commandId: String = newCommandId()): CommandOutcome

    /** Ends the agent process of a session SwitchTether started; sending again resumes the conversation. */
    suspend fun stop(id: String, commandId: String = newCommandId()): CommandOutcome
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
    /** Session id to continue from (`mode = "continue"`), or to fork (`mode = "fork"`). */
    val resumeFrom: String? = null,
    val mode: String? = null,
    /** Effort / reasoning level; null = the agent's default. */
    val effort: String? = null,
)

/** Tools whose subscription quota Home and the Usage page show (`GET /v1/usage/quota/{tool}`). */
val QUOTA_TOOLS = listOf("claude", "codex")

/** One usage total: Home's "today" and the Usage page's "today" (all apps) share a key. */
data class UsageSummaryKey(val fromSec: Long, val app: String?)

private val NO_QUOTAS: StateFlow<Map<String, QuotaView>> = MutableStateFlow(emptyMap())
private val NO_SUMMARIES: StateFlow<Map<UsageSummaryKey, UsageSummary>> = MutableStateFlow(emptyMap())

interface ManagementRepository {
    val changes: Flow<String>

    /**
     * Last quota read per tool by any screen: Home and the Usage page show the same numbers, and
     * a page opened after the other starts from them instead of a spinner.
     */
    val quotas: StateFlow<Map<String, QuotaView>> get() = NO_QUOTAS

    /** Last [usageSummary] answer per range start and app (same sharing as [quotas]). */
    val summaries: StateFlow<Map<UsageSummaryKey, UsageSummary>> get() = NO_SUMMARIES
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
    /** Usage per project (`GET /v1/usage/projects`); [project] narrows it to one. 404 on older desktops. */
    suspend fun usageProjects(fromSec: Long?, toSec: Long?, app: String?, project: String? = null): io.github.tianli0.switchtether.remote.protocol.ProjectUsageView =
        throw UnsupportedOperationException()
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

class RemoteSessionRepository(
    private val conn: ConnectionController,
    private val cache: io.github.tianli0.switchtether.data.SessionCache? = null,
    /** Server id of the paired desktop (cache key). */
    private val serverId: suspend () -> String? = { null },
) : SessionRepository {
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

    override suspend fun list(query: SessionQuery, cursor: String?, limit: Int): SessionPage {
        val page = conn.withApi { it.sessions(query.agent, query.project, query.text, cursor, limit) }
        if (cache != null && cursor == null && query == SessionQuery()) {
            serverId()?.let { sid ->
                lastServerId = sid
                listServerId = sid
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { cache.saveList(sid, page.items) }
            }
        }
        return page
    }

    override suspend fun cachedList(): io.github.tianli0.switchtether.data.ListSnapshot? {
        val c = cache ?: return null
        val sid = serverId() ?: return null
        listServerId = sid
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { c.loadList(sid) }
    }

    /** Server id of the last list saved or read ([peekList] is not suspending). */
    @Volatile private var listServerId: String? = null

    override fun peekList(): io.github.tianli0.switchtether.data.ListSnapshot? {
        val sid = listServerId ?: return null
        return cache?.peekList(sid)
    }

    override suspend fun cachedEvents(id: String): io.github.tianli0.switchtether.data.EventsSnapshot? {
        val c = cache ?: return null
        val sid = serverId() ?: return null
        return c.loadEvents(sid, id)
    }

    /** Server id of the last [list] / [events] answer: [rememberEvents] is not suspending. */
    @Volatile private var lastServerId: String? = null

    override fun rememberEvents(id: String, events: List<AgentEvent>, hasMoreBefore: Boolean) {
        val c = cache ?: return
        val sid = lastServerId ?: return
        c.saveEvents(sid, id, events, hasMoreBefore)
    }

    override suspend fun detail(id: String) = conn.withApi { it.session(id) }

    override suspend fun events(id: String, before: Long?, after: Long?, limit: Int): EventPage {
        val page = conn.withApi { it.sessionEvents(id, before, after, limit) }
        if (cache != null && before == null && after == null) {
            serverId()?.let { sid ->
                lastServerId = sid
                cache.saveEvents(sid, id, page.events, page.hasMoreBefore) // memory only
            }
        }
        return page
    }

    override suspend fun setOptions(id: String, model: String?, effort: String?, commandId: String) =
        conn.command(commandId) { api, c -> api.setSessionOptions(id, c, model, effort) }

    override suspend fun modelOptions(agent: String, sessionId: String?) =
        conn.withApi { it.modelOptions(agent, sessionId) }

    override suspend fun payload(sessionId: String, eventId: String, offset: Long, limit: Int) =
        conn.withApi { it.eventPayload(sessionId, eventId, offset, limit) }

    override suspend fun subagentTree(id: String) = conn.withApi { it.subagentTree(id) }

    override suspend fun agentMap(id: String) = conn.withApi { it.agentMap(id) }

    override suspend fun stopSubagent(id: String, taskId: String, commandId: String) =
        conn.command(commandId) { api, c -> api.stopSubagent(id, taskId, c) }

    override suspend fun claudeTool(id: String, action: String, params: JsonObject, commandId: String) =
        conn.command(commandId) { api, c -> api.claudeTool(id, action, c, params) }

    override suspend fun claudeCommands(id: String): List<io.github.tianli0.switchtether.remote.session.SlashCommand> = try {
        conn.withApi { it.claudeCommands(id) }
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList() // older desktop (404 / 422) or no connection: the built-in list is used
    }

    override suspend fun subagentEvents(id: String, agentId: String, limit: Int) =
        conn.withApi { it.subagentEvents(id, agentId, limit) }

    override suspend fun sendMessage(
        id: String,
        text: String,
        commandId: String,
        images: List<io.github.tianli0.switchtether.remote.protocol.ImageInput>,
        attachments: List<String>,
    ) = conn.command(commandId) { api, c -> api.sendMessage(id, c, text, images, attachments) }

    override suspend fun beginAttachment(id: String, name: String, size: Long, mediaType: String?) =
        conn.withApi { it.beginAttachment(id, io.github.tianli0.switchtether.remote.protocol.BeginAttachmentBody(name, size, mediaType)) }

    override suspend fun uploadAttachmentChunk(id: String, attachmentId: String, offset: Long, bytes: ByteArray) =
        conn.withApi { it.uploadAttachmentChunk(id, attachmentId, offset, bytes) }

    override suspend fun cancelAttachment(id: String, attachmentId: String) {
        runCatching { conn.withApi { it.cancelAttachment(id, attachmentId) } }
    }

    override suspend fun interrupt(id: String, commandId: String) =
        conn.command(commandId) { api, c -> api.interrupt(id, c) }

    override suspend fun stop(id: String, commandId: String) =
        conn.command(commandId) { api, c -> api.stop(id, c) }

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
                    effort = request.effort?.takeIf { it.isNotBlank() },
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
    private val _quotas = MutableStateFlow<Map<String, QuotaView>>(emptyMap())
    override val quotas: StateFlow<Map<String, QuotaView>> = _quotas.asStateFlow()
    private val _summaries = MutableStateFlow<Map<UsageSummaryKey, UsageSummary>>(emptyMap())
    override val summaries: StateFlow<Map<UsageSummaryKey, UsageSummary>> = _summaries.asStateFlow()

    /** Range end of each kept summary: an answer for an earlier "now" never replaces a later one. */
    private val summaryTo = HashMap<UsageSummaryKey, Long>()

    override suspend fun usageSummary(fromSec: Long, toSec: Long, app: String?): UsageSummary {
        val summary = conn.withApi { UsageSummary.parse(it.usage("summary", fromSec, toSec, app)) }
        val key = UsageSummaryKey(fromSec, app)
        synchronized(summaryTo) {
            if ((summaryTo[key] ?: Long.MIN_VALUE) <= toSec) {
                summaryTo[key] = toSec
                _summaries.update { old ->
                    // A handful of keys (ranges × apps); keep the newest few so it cannot grow.
                    val kept = if (old.size >= MAX_SHARED_SUMMARIES && key !in old) old.entries.drop(1).associate { it.key to it.value } else old
                    summaryTo.keys.retainAll(kept.keys + key)
                    (kept - key) + (key to summary)
                }
            }
        }
        return summary
    }
    override suspend fun usageTrend(fromSec: Long, toSec: Long, app: String?) = conn.withApi { DailyUsage.parseList(it.usage("trends", fromSec, toSec, app)) }
    override suspend fun usageModels(fromSec: Long, toSec: Long, app: String?) = conn.withApi { UsageBreakdownRow.parseModels(it.usage("models", fromSec, toSec, app)) }
    override suspend fun usageProviders(fromSec: Long, toSec: Long, app: String?) = conn.withApi { UsageBreakdownRow.parseProviders(it.usage("providers", fromSec, toSec, app)) }
    override suspend fun quota(tool: String): QuotaView {
        val q = conn.withApi { QuotaView.parse(it.quota(tool), tool) }
        _quotas.update { it + (tool to q) }
        return q
    }
    override suspend fun usageLogs(fromSec: Long, toSec: Long, app: String?, page: Int, pageSize: Int) =
        conn.withApi { io.github.tianli0.switchtether.remote.protocol.RequestLogPage.parse(it.usageLogs(fromSec, toSec, app, page, pageSize)) }
    override suspend fun usageLog(requestId: String) =
        conn.withApi { io.github.tianli0.switchtether.remote.protocol.RequestLog.parse(it.usageLog(requestId) as kotlinx.serialization.json.JsonObject) }
    override suspend fun usageProjects(fromSec: Long?, toSec: Long?, app: String?, project: String?) =
        conn.withApi { it.usageProjects(fromSec, toSec, app, project) }
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

private const val MAX_SHARED_SUMMARIES = 16
