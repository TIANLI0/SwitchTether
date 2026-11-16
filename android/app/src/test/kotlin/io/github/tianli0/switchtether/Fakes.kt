package io.github.tianli0.switchtether

import io.github.tianli0.switchtether.connection.ConnectionController
import io.github.tianli0.switchtether.connection.ConnectionState
import io.github.tianli0.switchtether.data.DraftStore
import io.github.tianli0.switchtether.data.PairedDesktop
import io.github.tianli0.switchtether.domain.NewSessionRequest
import io.github.tianli0.switchtether.domain.SessionChange
import io.github.tianli0.switchtether.domain.SessionQuery
import io.github.tianli0.switchtether.domain.SessionRepository
import io.github.tianli0.switchtether.remote.api.Endpoint
import io.github.tianli0.switchtether.remote.api.RemoteApi
import io.github.tianli0.switchtether.remote.command.CommandOutcome
import io.github.tianli0.switchtether.remote.command.CommandStatus
import io.github.tianli0.switchtether.remote.events.StreamFrame
import io.github.tianli0.switchtether.remote.protocol.AgentEvent
import io.github.tianli0.switchtether.remote.protocol.EventPage
import io.github.tianli0.switchtether.remote.protocol.FsEntry
import io.github.tianli0.switchtether.remote.protocol.FsListing
import io.github.tianli0.switchtether.remote.protocol.PayloadChunk
import io.github.tianli0.switchtether.remote.protocol.ProtocolJson
import io.github.tianli0.switchtether.remote.protocol.SessionCapabilities
import io.github.tianli0.switchtether.remote.protocol.SessionDetail
import io.github.tianli0.switchtether.remote.protocol.SessionPage
import io.github.tianli0.switchtether.remote.protocol.SessionSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

val TEST_DESKTOP = PairedDesktop(
    serverId = "srv", name = "DESKTOP-01", fingerprint = "ab".repeat(32), hosts = listOf("10.0.2.2"),
    lastGoodHost = "10.0.2.2", port = 47615, deviceId = "dev-1", identityAlias = "st-id-x", stepUpAlias = null,
    stepUpEnabled = false, pairedAtMs = 0,
)

val ONLINE = ConnectionState.Online(TEST_DESKTOP, Endpoint("10.0.2.2", 47615), null)

fun event(id: String, seq: Long, kind: String, data: String = "{}", parent: String? = null, session: String = "s1") = AgentEvent(
    id = id, seq = seq, sessionId = session, kind = kind, parentId = parent, ts = "2026-11-14T03:00:00Z",
    data = ProtocolJson.parseToJsonElement(data).jsonObject,
)

class FakeConnection(initial: ConnectionState = ONLINE) : ConnectionController {
    override val state = MutableStateFlow(initial)
    val eventFlow = MutableSharedFlow<StreamFrame.Event>(extraBufferCapacity = 64)
    override val events: SharedFlow<StreamFrame.Event> = eventFlow
    val resyncFlow = MutableSharedFlow<String>(extraBufferCapacity = 8)
    override val resyncs: SharedFlow<String> = resyncFlow
    override fun apiOrNull(): RemoteApi? = null
    var retries = 0
    override fun retryNow() {
        retries++
    }
}

class FakeSessionRepository : SessionRepository {
    val changeFlow = MutableSharedFlow<SessionChange>(extraBufferCapacity = 64)
    override val changes: Flow<SessionChange> = changeFlow
    val refetchFlow = MutableSharedFlow<String>(extraBufferCapacity = 8)
    override val refetch: Flow<String> = refetchFlow

    var sessions = listOf<SessionSummary>()
    var detail: SessionDetail? = null
    var events = listOf<AgentEvent>()
    var hasMore = false
    var nextOutcome: (String) -> CommandOutcome = { CommandOutcome.Succeeded(it, io.github.tianli0.switchtether.remote.api.CommandResponse(JsonObject(emptyMap()), false)) }
    var nextStatus: CommandStatus = CommandStatus.NotFound
    val sent = mutableListOf<Pair<String, String>>() // commandId to text
    val statusQueries = mutableListOf<String>()
    var listCalls = 0

    override suspend fun list(query: SessionQuery, cursor: String?, limit: Int): SessionPage {
        listCalls++
        return SessionPage(sessions.filter { query.agent == null || it.agent == query.agent })
    }

    override suspend fun detail(id: String) = detail ?: throw IllegalStateException("no detail")
    override suspend fun events(id: String, before: Long?, after: Long?, limit: Int) =
        EventPage(events.filter { before == null || it.seq < before }.takeLast(limit), hasMore && before == null)

    override suspend fun payload(sessionId: String, eventId: String, offset: Long, limit: Int) = PayloadChunk("full output", offset, 11, true)

    override suspend fun sendMessage(
        id: String,
        text: String,
        commandId: String,
        images: List<io.github.tianli0.switchtether.remote.protocol.ImageInput>,
    ): CommandOutcome {
        sent += commandId to text
        return nextOutcome(commandId)
    }

    override suspend fun interrupt(id: String, commandId: String) = nextOutcome(commandId)
    override suspend fun resolveApproval(id: String, requestId: String, decision: String, commandId: String) = nextOutcome(commandId)
    override suspend fun answer(id: String, requestId: String, answers: JsonObject?, commandId: String, action: String?) = nextOutcome(commandId)
    override suspend fun commandStatus(commandId: String): CommandStatus {
        statusQueries += commandId
        return nextStatus
    }

    var lastCreate: NewSessionRequest? = null
    override suspend fun createSession(request: NewSessionRequest, commandId: String): CommandOutcome {
        lastCreate = request
        return nextOutcome(commandId)
    }
    override suspend fun fsRoots() = listOf(FsEntry(name = "app", path = "D:\\proj\\app", kind = "dir"))
    override suspend fun fsList(path: String) = FsListing(path = path, parent = null, entries = listOf(FsEntry("src", "$path\\src", "dir")))
}

class FakeDrafts : DraftStore {
    val map = MutableStateFlow(mapOf<String, String>())
    override fun draft(sessionId: String): Flow<String> = map.map { it[sessionId].orEmpty() }
    override suspend fun setDraft(sessionId: String, text: String) {
        map.value = map.value + (sessionId to text)
    }

    override suspend fun clearAll() {
        map.value = emptyMap()
    }
}

fun summary(id: String = "s1", live: Boolean = true, control: String = "observe", agent: String = "claude_code", status: String = "idle") = SessionSummary(
    id = id, agent = agent, origin = "vscode", nativeId = id, title = "Fix login bug", project = "app",
    liveness = if (live) "live" else "ended", status = status, control = control, lastActiveAtMs = 1_731_503_600_000,
)

fun caps(send: String = "BLOCKED", approve: String = "BLOCKED", interrupt: String = "BLOCKED", continueSession: String? = null) =
    SessionCapabilities(sendMessage = send, approve = approve, interrupt = interrupt, answerQuestion = approve, readHistory = "VERIFIED", liveEvents = "PARTIAL", continueSession = continueSession, reason = "external interactive session")
