package io.github.tianli0.switchtether.remote.api

import io.github.tianli0.switchtether.remote.protocol.AgentEvent
import io.github.tianli0.switchtether.remote.protocol.AnswersBody
import io.github.tianli0.switchtether.remote.protocol.ApiErrorBody
import io.github.tianli0.switchtether.remote.protocol.ApiErrorEnvelope
import io.github.tianli0.switchtether.remote.protocol.ApprovalBody
import io.github.tianli0.switchtether.remote.protocol.CommandBody
import io.github.tianli0.switchtether.remote.protocol.CommandRecord
import io.github.tianli0.switchtether.remote.protocol.CreateSessionBody
import io.github.tianli0.switchtether.remote.protocol.FsListing
import io.github.tianli0.switchtether.remote.protocol.FsRoots
import io.github.tianli0.switchtether.remote.protocol.DeviceView
import io.github.tianli0.switchtether.remote.protocol.EventPage
import io.github.tianli0.switchtether.remote.protocol.Hello
import io.github.tianli0.switchtether.remote.protocol.PairRequest
import io.github.tianli0.switchtether.remote.protocol.PairResponse
import io.github.tianli0.switchtether.remote.protocol.PayloadChunk
import io.github.tianli0.switchtether.remote.protocol.ProtocolJson
import io.github.tianli0.switchtether.remote.protocol.BeginUploadBody
import io.github.tianli0.switchtether.remote.protocol.CreateTerminalBody
import io.github.tianli0.switchtether.remote.protocol.FileContent
import io.github.tianli0.switchtether.remote.protocol.GitDiff
import io.github.tianli0.switchtether.remote.protocol.GitLog
import io.github.tianli0.switchtether.remote.protocol.GitShow
import io.github.tianli0.switchtether.remote.protocol.PathBody
import io.github.tianli0.switchtether.remote.protocol.RenameBody
import io.github.tianli0.switchtether.remote.protocol.RepoStatus
import io.github.tianli0.switchtether.remote.protocol.ScrollbackPage
import io.github.tianli0.switchtether.remote.protocol.TerminalList
import io.github.tianli0.switchtether.remote.protocol.UploadView
import io.github.tianli0.switchtether.remote.protocol.WriteBody
import io.github.tianli0.switchtether.remote.protocol.SendMessageBody
import io.github.tianli0.switchtether.remote.protocol.SessionCapabilities
import io.github.tianli0.switchtether.remote.protocol.SessionDetail
import io.github.tianli0.switchtether.remote.protocol.SessionPage
import io.github.tianli0.switchtether.remote.protocol.SessionSummary
import io.github.tianli0.switchtether.remote.protocol.StatusView
import io.github.tianli0.switchtether.remote.protocol.StepUpChallenge
import io.github.tianli0.switchtether.remote.protocol.StepUpChallengeRequest
import io.github.tianli0.switchtether.remote.protocol.StepUpGrant
import io.github.tianli0.switchtether.remote.protocol.StepUpVerifyRequest
import io.github.tianli0.switchtether.remote.protocol.ToggleBody
import io.github.tianli0.switchtether.remote.protocol.bool
import io.github.tianli0.switchtether.remote.protocol.long
import io.github.tianli0.switchtether.remote.protocol.obj
import io.github.tianli0.switchtether.remote.protocol.str
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import okhttp3.Call
import okhttp3.Callback
import okhttp3.EventListener
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** Where the desktop is reachable. IPv6 literals are passed without brackets. */
data class Endpoint(val host: String, val port: Int) {
    fun url(): HttpUrl = HttpUrl.Builder().scheme("https").host(host).port(port).build()
    override fun toString(): String = if (host.contains(':')) "[$host]:$port" else "$host:$port"
}

/** Result of a successful side-effecting call. */
data class CommandResponse(val body: JsonElement, val replayed: Boolean)

/**
 * Thin typed client for the SwitchTether Remote API v1 (docs/PROTOCOL.md). One instance talks to one
 * desktop endpoint over the pinned mTLS [OkHttpClient] from
 * [io.github.tianli0.switchtether.remote.tls.TlsClientFactory].
 *
 * Every side-effecting method takes an explicit `commandId` (§5). Transport failures of those
 * calls surface as [CommandTransportException] so callers can tell "never sent" from "outcome
 * unknown"; nothing is retried automatically.
 */
class RemoteApi(
    baseClient: OkHttpClient,
    val endpoint: Endpoint,
    /** Invoked for `unpaired` / `device_revoked` errors so the connection layer can react. */
    private val onAuthFailure: (ApiException) -> Unit = {},
) {
    private val json = ProtocolJson
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    /** Records whether request headers reached the socket (see [CommandTransportException.sent]). */
    class SendTracker {
        @Volatile var sent: Boolean = false
    }

    val client: OkHttpClient = baseClient.newBuilder()
        .eventListenerFactory {
            object : EventListener() {
                override fun requestHeadersStart(call: Call) {
                    call.request().tag(SendTracker::class.java)?.sent = true
                }
            }
        }
        .build()

    /**
     * Same pool/TLS config, but OkHttp may transparently retry on a stale pooled connection.
     * Used ONLY for idempotent GETs (and the WebSocket upgrade); side-effecting requests use
     * [client] with `retryOnConnectionFailure(false)` so they are never replayed silently (§5).
     */
    private val readClient: OkHttpClient = client.newBuilder().retryOnConnectionFailure(true).build()

    // ---- §3 pairing / status -------------------------------------------------------------------

    suspend fun hello(): Hello = get("v1/hello")

    suspend fun pair(request: PairRequest): PairResponse = post("v1/pair", request)

    suspend fun status(): StatusView = get("v1/status")

    suspend fun capabilities(): JsonElement = getJson("v1/capabilities")

    suspend fun device(): DeviceView = get("v1/device")

    /** `DELETE /v1/device`: the phone unpairs itself (the desktop revokes this certificate). */
    suspend fun unpairSelf(): JsonElement = executeJson(request("v1/device").delete().build())

    suspend fun health(): JsonElement = getJson("v1/health")

    // ---- §4 step-up ---------------------------------------------------------------------------------

    suspend fun stepUpChallenge(scope: String): StepUpChallenge =
        post("v1/auth/step-up/challenge", StepUpChallengeRequest(scope))

    suspend fun stepUpVerify(challengeId: String, signatureBase64: String): StepUpGrant =
        post("v1/auth/step-up/verify", StepUpVerifyRequest(challengeId, signatureBase64))

    // ---- §5 commands ------------------------------------------------------------------------------------

    suspend fun command(commandId: String): CommandRecord = get(url("v1/commands", commandId))

    // ---- §7.5 sessions ---------------------------------------------------------------------------------

    suspend fun sessions(
        agent: String? = null,
        project: String? = null,
        query: String? = null,
        cursor: String? = null,
        limit: Int? = null,
    ): SessionPage {
        val u = url("v1/sessions").newBuilder().apply {
            agent?.let { addQueryParameter("agent", it) }
            project?.let { addQueryParameter("project", it) }
            query?.takeIf { it.isNotBlank() }?.let { addQueryParameter("q", it) }
            cursor?.let { addQueryParameter("cursor", it) }
            limit?.let { addQueryParameter("limit", it.toString()) }
        }.build()
        return parseSessionPage(executeJson(Request.Builder().url(u).get().tagged().build()))
    }

    suspend fun session(sessionId: String): SessionDetail = parseSessionDetail(getJson(url("v1/sessions", sessionId)))

    suspend fun sessionEvents(sessionId: String, before: Long? = null, after: Long? = null, limit: Int = 200): EventPage {
        val u = url("v1/sessions", sessionId, "events").newBuilder().apply {
            before?.let { addQueryParameter("before", it.toString()) }
            after?.let { addQueryParameter("after", it.toString()) }
            addQueryParameter("limit", limit.toString())
        }.build()
        val e = executeJson(Request.Builder().url(u).get().tagged().build())
        return withContext(Dispatchers.Default) { parseEventPage(e, limit, after == null) }
    }

    /** Claude Code sub-agents of a session as a flat list with parent links (empty when none). */
    suspend fun subagentTree(sessionId: String): List<io.github.tianli0.switchtether.remote.session.SubagentNode> {
        val e = getJson(url("v1/sessions", sessionId, "subagents"))
        val arr = ((e as? JsonObject)?.get("agents") as? JsonArray) ?: return emptyList()
        return arr.mapNotNull {
            runCatching { ProtocolJson.decodeFromJsonElement(io.github.tianli0.switchtether.remote.session.SubagentNode.serializer(), it) }.getOrNull()
        }.filter { it.agentId.isNotEmpty() }
    }

    /** The Agent map: sub-agents with live state, background tasks, and whether they can be stopped. */
    suspend fun agentMap(sessionId: String): io.github.tianli0.switchtether.remote.session.AgentMap {
        val o = getJson(url("v1/sessions", sessionId, "subagents")) as? JsonObject
            ?: return io.github.tianli0.switchtether.remote.session.AgentMap(emptyList())
        val agents = (o["agents"] as? JsonArray).orEmpty().mapNotNull {
            runCatching { ProtocolJson.decodeFromJsonElement(io.github.tianli0.switchtether.remote.session.SubagentNode.serializer(), it) }.getOrNull()
        }.filter { it.agentId.isNotEmpty() }
        val tasks = (o["backgroundTasks"] as? JsonArray).orEmpty().mapNotNull {
            runCatching { ProtocolJson.decodeFromJsonElement(io.github.tianli0.switchtether.remote.session.BackgroundTaskNode.serializer(), it) }.getOrNull()
        }
        val stoppable = (o["stoppable"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
        return io.github.tianli0.switchtether.remote.session.AgentMap(agents, tasks, stoppable)
    }

    /** `POST /v1/sessions/{id}/subagents/{taskId}/stop`: stop a sub-agent or background task. */
    suspend fun stopSubagent(sessionId: String, taskId: String, commandId: String): CommandResponse =
        command(commandId, url("v1/sessions", sessionId, "subagents", taskId, "stop"), CommandBody(commandId))

    /** One sub-agent's own steps, newest page (read-only; the desktop tails its transcript). */
    suspend fun subagentEvents(sessionId: String, agentId: String, limit: Int = 300): io.github.tianli0.switchtether.remote.session.SubagentPage {
        val u = url("v1/sessions", sessionId, "subagents", agentId, "events").newBuilder()
            .addQueryParameter("limit", limit.toString())
            .build()
        val e = executeJson(Request.Builder().url(u).get().tagged().build())
        val page = withContext(Dispatchers.Default) { parseEventPage(e, limit, true) }
        val meta = ((e as? JsonObject)?.get("meta") as? JsonObject)?.let {
            runCatching { ProtocolJson.decodeFromJsonElement(io.github.tianli0.switchtether.remote.session.SubagentNode.serializer(), it) }.getOrNull()
        }?.copy(agentId = agentId)
        return io.github.tianli0.switchtether.remote.session.SubagentPage(page.events, meta, page.hasMoreBefore)
    }

    suspend fun eventPayload(sessionId: String, eventId: String, offset: Long, limit: Int): PayloadChunk {
        val u = url("v1/sessions", sessionId, "events", eventId, "payload").newBuilder()
            .addQueryParameter("offset", offset.toString())
            .addQueryParameter("limit", limit.toString())
            .build()
        val (text, contentType) = executeRaw(Request.Builder().url(u).get().tagged().build())
        return parsePayload(text, contentType, offset)
    }

    /** Starts a SwitchTether-managed session; the response body is its SessionSummary. */
    suspend fun createSession(body: CreateSessionBody): CommandResponse =
        command(body.commandId, url("v1/sessions"), body)

    suspend fun fsRoots(): FsRoots = get("v1/fs/roots")

    suspend fun fsList(path: String, showHidden: Boolean = false): FsListing = get(
        url("v1/fs/list").newBuilder()
            .addQueryParameter("path", path)
            .addQueryParameter("showHidden", showHidden.toString())
            .build(),
    )

    suspend fun sendMessage(
        sessionId: String,
        commandId: String,
        text: String,
        images: List<io.github.tianli0.switchtether.remote.protocol.ImageInput> = emptyList(),
        attachments: List<String> = emptyList(),
    ): CommandResponse =
        command(commandId, url("v1/sessions", sessionId, "messages"), SendMessageBody(commandId, text, images, attachments))

    /** Starts uploading a file for the next message of [sessionId] (desktop answers 404 when too old). */
    suspend fun beginAttachment(sessionId: String, body: io.github.tianli0.switchtether.remote.protocol.BeginAttachmentBody): io.github.tianli0.switchtether.remote.protocol.AttachmentView =
        decode(
            io.github.tianli0.switchtether.remote.protocol.AttachmentView.serializer(),
            executeJson(Request.Builder().url(url("v1/sessions", sessionId, "attachments")).post(encode(body)).tagged().build()),
        )

    /** One raw chunk (≤ 8 MiB) at [offset]; the last one completes the attachment. */
    suspend fun uploadAttachmentChunk(sessionId: String, attachmentId: String, offset: Long, bytes: ByteArray): io.github.tianli0.switchtether.remote.protocol.AttachmentView {
        val u = url("v1/sessions", sessionId, "attachments", attachmentId).newBuilder().addQueryParameter("offset", offset.toString()).build()
        val req = Request.Builder().url(u).put(bytes.toRequestBody("application/octet-stream".toMediaType())).tagged().build()
        val r = fetch(req)
        if (!r.ok) throw toApiException(r)
        return decode(io.github.tianli0.switchtether.remote.protocol.AttachmentView.serializer(), parseJson(r.text))
    }

    suspend fun cancelAttachment(sessionId: String, attachmentId: String): JsonElement =
        executeJson(Request.Builder().url(url("v1/sessions", sessionId, "attachments", attachmentId)).delete().tagged().build())

    suspend fun interrupt(sessionId: String, commandId: String): CommandResponse =
        command(commandId, url("v1/sessions", sessionId, "interrupt"), CommandBody(commandId))

    /** `POST /v1/sessions/{id}/stop`: ends the agent process of a session SwitchTether started (the conversation can be resumed). */
    suspend fun stop(sessionId: String, commandId: String): CommandResponse =
        command(commandId, url("v1/sessions", sessionId, "stop"), CommandBody(commandId))

    /**
     * `POST /v1/sessions/{id}/claude/{action}`: one Claude Code session tool ([io.github.tianli0.switchtether.remote.session.ClaudeTools]).
     * [params] are the action's fields; the commandId is added. A side question can take minutes,
     * so this uses a longer read timeout than other calls.
     */
    suspend fun claudeTool(sessionId: String, action: String, commandId: String, params: JsonObject = JsonObject(emptyMap())): CommandResponse {
        val body = JsonObject(params + ("commandId" to kotlinx.serialization.json.JsonPrimitive(commandId)))
        val tracker = SendTracker()
        val req = Request.Builder().url(url("v1/sessions", sessionId, "claude", action))
            .post(body.toString().toRequestBody(jsonMedia))
            .header("Idempotency-Key", commandId)
            .tagged()
            .tag(SendTracker::class.java, tracker)
            .build()
        val r = try {
            fetch(req, slowClient)
        } catch (e: BodyReadException) {
            throw CommandTransportException(commandId, true, e.cause as? IOException ?: e)
        } catch (e: IOException) {
            throw CommandTransportException(commandId, tracker.sent, e)
        }
        if (!r.ok) throw toApiException(r)
        return CommandResponse(parseJson(r.text), r.headers["X-Command-Replayed"] == "true")
    }

    /**
     * `POST /v1/sessions/{id}/claude/commands`: the slash commands the running Claude Code process
     * offers. A desktop without the action answers 404 / 422, which throws like any other call.
     */
    suspend fun claudeCommands(sessionId: String, commandId: String = io.github.tianli0.switchtether.remote.command.newCommandId()): List<io.github.tianli0.switchtether.remote.session.SlashCommand> =
        io.github.tianli0.switchtether.remote.session.ClaudeTools.parseCommands(
            claudeTool(sessionId, io.github.tianli0.switchtether.remote.session.ClaudeTools.COMMANDS, commandId).body,
        )

    /** Side-effecting client for calls the desktop may take minutes to answer (never retried). */
    private val slowClient: OkHttpClient by lazy {
        client.newBuilder().readTimeout(200, java.util.concurrent.TimeUnit.SECONDS).build()
    }

    /** `POST /v1/sessions/{id}/options`: model / effort of a running session (`""` = default). */
    suspend fun setSessionOptions(sessionId: String, commandId: String, model: String?, effort: String?): CommandResponse =
        command(commandId, url("v1/sessions", sessionId, "options"), io.github.tianli0.switchtether.remote.protocol.SessionOptionsBody(commandId, model, effort))

    /** `GET /v1/agents/{agent}/models?session=` (404 on older desktops). */
    suspend fun modelOptions(agent: String, sessionId: String? = null): io.github.tianli0.switchtether.remote.protocol.ModelOptions =
        get(url("v1/agents", agent, "models").newBuilder().apply { sessionId?.let { addQueryParameter("session", it) } }.build())

    suspend fun resolveApproval(sessionId: String, requestId: String, commandId: String, decision: String, message: String? = null): CommandResponse =
        command(commandId, url("v1/sessions", sessionId, "approvals", requestId), ApprovalBody(commandId, decision, message))

    suspend fun answerQuestion(
        sessionId: String,
        requestId: String,
        commandId: String,
        answers: JsonObject?,
        action: String? = null,
    ): CommandResponse =
        command(commandId, url("v1/sessions", sessionId, "answers", requestId), AnswersBody(commandId, answers, action))

    /** Reads a small file (e.g. an image referenced by an agent event) over the pinned mTLS channel. */
    suspend fun fsDownloadBytes(path: String, maxBytes: Int = 8 * 1024 * 1024): ByteArray =
        executeBytes(Request.Builder().url(url("v1/fs/download").newBuilder().addQueryParameter("path", path).build()).get().tagged().build(), maxBytes)

    // ---- §7.2 providers ------------------------------------------------------------------------------

    suspend fun providerApps(): JsonElement = getJson("v1/providers/apps")

    suspend fun providers(app: String): JsonElement =
        getJson(url("v1/providers").newBuilder().addQueryParameter("app", app).build())

    suspend fun switchProvider(app: String, providerId: String, commandId: String): CommandResponse =
        command(commandId, url("v1/providers", app, providerId, "switch"), CommandBody(commandId))

    suspend fun testProvider(app: String, providerId: String, commandId: String): CommandResponse =
        command(commandId, url("v1/providers", app, providerId, "test"), CommandBody(commandId))

    /** Write-only credential update (step-up `config_write`); the value is never read back. */
    suspend fun setProviderCredential(app: String, providerId: String, field: String, value: String, commandId: String): CommandResponse {
        val body = kotlinx.serialization.json.buildJsonObject {
            put("commandId", kotlinx.serialization.json.JsonPrimitive(commandId))
            put("field", kotlinx.serialization.json.JsonPrimitive(field))
            put("value", kotlinx.serialization.json.JsonPrimitive(value))
        }
        val tracker = SendTracker()
        val req = Request.Builder().url(url("v1/providers", app, providerId, "credential"))
            .put(body.toString().toRequestBody(jsonMedia)).header("Idempotency-Key", commandId).tagged()
            .tag(SendTracker::class.java, tracker).build()
        val r = try {
            fetch(req)
        } catch (e: IOException) {
            throw CommandTransportException(commandId, tracker.sent, e)
        }
        if (!r.ok) throw toApiException(r)
        return CommandResponse(parseJson(r.text), r.headers["X-Command-Replayed"] == "true")
    }

    // ---- §7.3 usage -----------------------------------------------------------------------------------

    suspend fun usage(kind: String, fromEpochSec: Long?, toEpochSec: Long?, app: String? = null): JsonElement {
        val u = url("v1/usage", kind).newBuilder().apply {
            fromEpochSec?.let { addQueryParameter("from", it.toString()) }
            toEpochSec?.let { addQueryParameter("to", it.toString()) }
            app?.let { addQueryParameter("app", it) }
        }.build()
        return getJson(u)
    }

    suspend fun quota(tool: String): JsonElement = getJson(url("v1/usage/quota", tool))

    /** `GET /v1/usage/logs` (page 0-based, pageSize ≤ 100). 404 on older desktops. */
    suspend fun usageLogs(fromEpochSec: Long?, toEpochSec: Long?, app: String?, page: Int, pageSize: Int): JsonElement {
        val u = url("v1/usage/logs").newBuilder().apply {
            fromEpochSec?.let { addQueryParameter("from", it.toString()) }
            toEpochSec?.let { addQueryParameter("to", it.toString()) }
            app?.let { addQueryParameter("app", it) }
            addQueryParameter("page", page.toString())
            addQueryParameter("pageSize", pageSize.toString())
        }.build()
        return getJson(u)
    }

    /** `GET /v1/usage/logs/{requestId}`. */
    suspend fun usageLog(requestId: String): JsonElement = getJson(url("v1/usage/logs", requestId))

    /** `GET /v1/usage/first-date?app` → `{ firstDateMs, firstDate }`. */
    suspend fun usageFirstDate(app: String?): JsonElement =
        getJson(url("v1/usage/first-date").newBuilder().apply { app?.let { addQueryParameter("app", it) } }.build())

    /** `GET /v1/usage/projects?from&to&app&project` — usage per project. 404 on older desktops. */
    suspend fun usageProjects(fromEpochSec: Long?, toEpochSec: Long?, app: String?, project: String? = null): io.github.tianli0.switchtether.remote.protocol.ProjectUsageView {
        val u = url("v1/usage/projects").newBuilder().apply {
            fromEpochSec?.let { addQueryParameter("from", it.toString()) }
            toEpochSec?.let { addQueryParameter("to", it.toString()) }
            app?.let { addQueryParameter("app", it) }
            project?.let { addQueryParameter("project", it) }
        }.build()
        return get(u)
    }

    /** `GET /v1/usage/last-sync` → `{ lastSyncMs }`. */
    suspend fun usageLastSync(): JsonElement = getJson(url("v1/usage/last-sync"))

    /** `GET /v1/relay` (RELAY.md §5): the desktop's relay settings incl. token and key. 404 on older desktops. */
    suspend fun relay(): RelaySettings = get("v1/relay")

    /** `GET /v1/settings` (whitelisted subset). */
    suspend fun settings(): JsonElement = getJson("v1/settings")

    // ---- §7.4 MCP / skills / prompts ----------------------------------------------------------------

    suspend fun mcpServers(): JsonElement = getJson("v1/mcp/servers")

    suspend fun mcpToggle(serverId: String, app: String, enabled: Boolean, commandId: String): CommandResponse =
        command(commandId, url("v1/mcp/servers", serverId, "toggle"), ToggleBody(commandId, app, enabled))

    suspend fun skills(): JsonElement = getJson("v1/skills")

    suspend fun skillToggle(skillId: String, app: String, enabled: Boolean, commandId: String): CommandResponse =
        command(commandId, url("v1/skills", skillId, "toggle"), ToggleBody(commandId, app, enabled))

    suspend fun prompts(app: String): JsonElement =
        getJson(url("v1/prompts").newBuilder().addQueryParameter("app", app).build())

    suspend fun enablePrompt(app: String, promptId: String, commandId: String): CommandResponse =
        command(commandId, url("v1/prompts", app, promptId, "enable"), CommandBody(commandId))

    // ---- §7.6 terminals (step-up `terminal`) ---------------------------------------------------------

    suspend fun terminals(): TerminalList = get("v1/terminals")

    suspend fun createTerminal(body: CreateTerminalBody): CommandResponse = command(body.commandId, url("v1/terminals"), body)

    suspend fun killTerminal(id: String): JsonElement = executeJson(Request.Builder().url(url("v1/terminals", id)).delete().tagged().build())

    suspend fun terminalScrollback(id: String, offset: Int, limit: Int): ScrollbackPage = get(
        url("v1/terminals", id, "scrollback").newBuilder()
            .addQueryParameter("offset", offset.toString()).addQueryParameter("limit", limit.toString()).build(),
    )

    /** `GET /v1/terminals/{id}/stream` WebSocket (snapshot/patch frames, §7.6). */
    fun openTerminal(id: String, listener: WebSocketListener): WebSocket =
        readClient.newWebSocket(Request.Builder().url(url("v1/terminals", id, "stream")).tagged().build(), listener)

    // §7.11 Port forwarding ---------------------------------------------------------------------

    suspend fun forwardPorts(): io.github.tianli0.switchtether.remote.protocol.ListeningPorts = get("v1/forwards/ports")

    suspend fun forwards(): io.github.tianli0.switchtether.remote.protocol.ForwardList = get("v1/forwards")

    /** Step-up `port_forward`; renews the device's lease when this port is already forwarded. */
    suspend fun createForward(port: Int, ttlMinutes: Int? = null): io.github.tianli0.switchtether.remote.protocol.ForwardView =
        post("v1/forwards", io.github.tianli0.switchtether.remote.protocol.CreateForwardBody(port, ttlMinutes))

    suspend fun stopForward(id: String): JsonElement =
        executeJson(Request.Builder().url(url("v1/forwards", id)).delete().tagged().build())

    /** Pings keep the relay (10 min idle limit) and NAT mappings alive, and detect dead paths. */
    private val tunnelClient: OkHttpClient by lazy { readClient.newBuilder().pingInterval(20, java.util.concurrent.TimeUnit.SECONDS).build() }

    /** The lease's multiplexed tunnel (binary frames, see [io.github.tianli0.switchtether.remote.forward.ForwardFrames]). */
    fun openForwardTunnel(
        id: String,
        events: io.github.tianli0.switchtether.remote.forward.TunnelEvents,
    ): io.github.tianli0.switchtether.remote.forward.TunnelLink {
        val ws = tunnelClient.newWebSocket(
            Request.Builder().url(url("v1/forwards", id, "tunnel")).tagged().build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = events.onOpen()

                override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) = events.onFrame(bytes.toByteArray())

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                    events.onClosed(code, reason)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                    events.onClosed(response?.code ?: -1, t.message)
            },
        )
        return object : io.github.tianli0.switchtether.remote.forward.TunnelLink {
            override fun send(frame: ByteArray): Boolean = ws.send(okio.ByteString.of(*frame))
            override fun close() {
                ws.close(1000, null)
            }
        }
    }

    // ---- §7.7 files & git (writes need step-up `files_write`) --------------------------------------------

    suspend fun fsRead(path: String, allowSensitive: Boolean = false, maxBytes: Long = 2L * 1024 * 1024): FileContent = get(
        url("v1/fs/read").newBuilder().addQueryParameter("path", path)
            .addQueryParameter("allowSensitive", allowSensitive.toString())
            .addQueryParameter("maxBytes", maxBytes.toString()).build(),
    )

    suspend fun fsSearch(root: String, q: String, content: Boolean, limit: Int = 200): JsonElement = getJson(
        url("v1/fs/search").newBuilder().addQueryParameter("root", root).addQueryParameter("q", q)
            .addQueryParameter("content", content.toString()).addQueryParameter("limit", limit.toString()).build(),
    )

    suspend fun fsWrite(body: WriteBody): CommandResponse {
        val tracker = SendTracker()
        val req = Request.Builder().url(url("v1/fs/write")).put(encode(body))
            .header("Idempotency-Key", body.commandId).tagged().tag(SendTracker::class.java, tracker).build()
        val r = try {
            fetch(req)
        } catch (e: IOException) {
            throw CommandTransportException(body.commandId, tracker.sent, e)
        }
        if (!r.ok) throw toApiException(r)
        return CommandResponse(parseJson(r.text), r.headers["X-Command-Replayed"] == "true")
    }

    suspend fun fsMkdir(body: PathBody): CommandResponse = command(body.commandId, url("v1/fs/mkdir"), body)

    suspend fun fsDelete(body: PathBody): CommandResponse = command(body.commandId, url("v1/fs/delete"), body)

    suspend fun fsRename(body: RenameBody): CommandResponse = command(body.commandId, url("v1/fs/rename"), body)

    suspend fun beginUpload(body: BeginUploadBody): UploadView =
        decode(UploadView.serializer(), command(body.commandId, url("v1/fs/upload"), body).body)

    suspend fun uploadChunk(uploadId: String, offset: Long, bytes: ByteArray): UploadView {
        val u = url("v1/fs/upload", uploadId).newBuilder().addQueryParameter("offset", offset.toString()).build()
        val req = Request.Builder().url(u).put(bytes.toRequestBody("application/octet-stream".toMediaType())).tagged().build()
        val r = fetch(req)
        if (!r.ok) throw toApiException(r)
        return decode(UploadView.serializer(), parseJson(r.text))
    }

    suspend fun cancelUpload(uploadId: String): JsonElement =
        executeJson(Request.Builder().url(url("v1/fs/upload", uploadId)).delete().tagged().build())

    /** Streams `GET /v1/fs/download` into [sink]; returns the number of bytes written. Runs on IO. */
    suspend fun download(path: String, sink: java.io.OutputStream, onProgress: (Long, Long?) -> Unit = { _, _ -> }): Long =
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url(url("v1/fs/download").newBuilder().addQueryParameter("path", path).build()).get().tagged().build()
            readClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw toApiException(Fetched(resp.code, resp.headers, resp.body.string()))
                val total = resp.body.contentLength().takeIf { it >= 0 }
                var n = 0L
                resp.body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val r = input.read(buf)
                        if (r < 0) break
                        sink.write(buf, 0, r)
                        n += r
                        onProgress(n, total)
                    }
                }
                n
            }
        }

    suspend fun gitStatus(repo: String): RepoStatus = get(url("v1/git/status").newBuilder().addQueryParameter("repo", repo).build())

    suspend fun gitDiff(repo: String, path: String?, staged: Boolean = false): GitDiff = get(
        url("v1/git/diff").newBuilder().addQueryParameter("repo", repo).apply { path?.let { addQueryParameter("path", it) } }
            .addQueryParameter("staged", staged.toString()).build(),
    )

    suspend fun gitLog(repo: String, path: String? = null, limit: Int = 50): GitLog = get(
        url("v1/git/log").newBuilder().addQueryParameter("repo", repo).apply { path?.let { addQueryParameter("path", it) } }
            .addQueryParameter("limit", limit.toString()).build(),
    )

    suspend fun gitShow(repo: String, rev: String, path: String): GitShow = get(
        url("v1/git/show").newBuilder().addQueryParameter("repo", repo).addQueryParameter("rev", rev).addQueryParameter("path", path).build(),
    )

    suspend fun gitBranches(repo: String): io.github.tianli0.switchtether.remote.protocol.GitBranches =
        get(url("v1/git/branches").newBuilder().addQueryParameter("repo", repo).build())

    /** `POST /v1/git/{op}` ([io.github.tianli0.switchtether.remote.protocol.GitOps]); step-up `git_write`. */
    suspend fun gitWrite(op: String, body: io.github.tianli0.switchtether.remote.protocol.GitWriteBody): CommandResponse =
        command(body.commandId, url("v1/git", op), body)

    /** The body of a [gitWrite] answer. */
    fun gitWriteResult(r: CommandResponse): io.github.tianli0.switchtether.remote.protocol.GitWriteResult =
        decode(io.github.tianli0.switchtether.remote.protocol.GitWriteResult.serializer(), r.body)

    /** `features` of `GET /v1/capabilities` (e.g. `git_write`); empty when the desktop does not say. */
    suspend fun features(): Set<String> =
        ((capabilities() as? kotlinx.serialization.json.JsonObject)?.get("features") as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { p -> p.isString }?.content }?.toSet().orEmpty()

    // ---- §6 event stream ---------------------------------------------------------------------------

    /** Opens `GET /v1/events?since=&epoch=` as a WebSocket on the same pinned client. */
    fun openEvents(since: Long, epoch: String?, listener: WebSocketListener): WebSocket {
        val u = url("v1/events").newBuilder().apply {
            addQueryParameter("since", since.toString())
            epoch?.takeIf { it.isNotEmpty() }?.let { addQueryParameter("epoch", it) }
        }.build()
        return readClient.newWebSocket(Request.Builder().url(u).header("X-Request-Id", UUID.randomUUID().toString()).build(), listener)
    }

    // =================================================================================================

    /** [path] is a literal route ("v1/sessions"); [segments] are appended one by one, percent-encoded. */
    fun url(path: String, vararg segments: String): HttpUrl {
        val b = endpoint.url().newBuilder().addPathSegments(path)
        for (s in segments) b.addPathSegment(s)
        return b.build()
    }

    private fun request(path: String): Request.Builder = Request.Builder().url(url(path)).tagged()

    private fun Request.Builder.tagged(): Request.Builder = header("X-Request-Id", UUID.randomUUID().toString())

    private suspend inline fun <reified T> get(path: String): T = get(url(path))

    private suspend inline fun <reified T> get(u: HttpUrl): T =
        decode(serializer(), executeJson(Request.Builder().url(u).get().tagged().build()))

    private suspend fun getJson(path: String): JsonElement = getJson(url(path))

    private suspend fun getJson(u: HttpUrl): JsonElement = executeJson(Request.Builder().url(u).get().tagged().build())

    private suspend inline fun <reified B, reified T> post(path: String, body: B): T {
        val req = request(path).post(encode(body)).build()
        return decode(serializer(), executeJson(req))
    }

    private inline fun <reified B> encode(body: B): RequestBody = json.encodeToString(serializer<B>(), body).toRequestBody(jsonMedia)

    private fun <T> decode(s: KSerializer<T>, e: JsonElement): T = json.decodeFromJsonElement(s, e)

    private suspend inline fun <reified B> command(commandId: String, u: HttpUrl, body: B): CommandResponse {
        val tracker = SendTracker()
        val req = Request.Builder().url(u)
            .post(encode(body))
            .header("Idempotency-Key", commandId)
            .tagged()
            .tag(SendTracker::class.java, tracker)
            .build()
        val r = try {
            fetch(req)
        } catch (e: BodyReadException) {
            throw CommandTransportException(commandId, true, e.cause as? IOException ?: e)
        } catch (e: IOException) {
            throw CommandTransportException(commandId, tracker.sent, e)
        }
        if (!r.ok) throw toApiException(r)
        return CommandResponse(parseJson(r.text), r.headers["X-Command-Replayed"] == "true")
    }

    private suspend fun executeJson(request: Request): JsonElement {
        val (text, _) = executeRaw(request)
        // Large pages (hundreds of events) are parsed off the caller's (often main) thread.
        return if (text.length > 16 * 1024) withContext(Dispatchers.Default) { parseJson(text) } else parseJson(text)
    }

    private suspend fun executeRaw(request: Request): Pair<String, String?> {
        val r = fetch(request)
        if (!r.ok) throw toApiException(r)
        return r.text to r.headers["Content-Type"]
    }

    private suspend fun executeBytes(request: Request, maxBytes: Int): ByteArray = suspendCancellableCoroutine { cont ->
        val call = readClient.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use { r ->
                        if (!r.isSuccessful) {
                            cont.resumeWithException(toApiException(Fetched(r.code, r.headers, r.body.string())))
                            return
                        }
                        val bytes = r.body.bytes()
                        if (bytes.size > maxBytes) {
                            cont.resumeWithException(ApiException(413, ApiErrorBody(io.github.tianli0.switchtether.remote.protocol.ErrorCodes.PAYLOAD_TOO_LARGE, "image too large"), r.headers["X-Request-Id"]))
                            return
                        }
                        cont.resume(bytes) {}
                    }
                } catch (e: IOException) {
                    cont.resumeWithException(BodyReadException(e))
                }
            }
        })
    }

    private fun parseJson(text: String): JsonElement =
        if (text.isBlank()) JsonNull else json.parseToJsonElement(text)

    private fun toApiException(r: Fetched): ApiException {
        val body = runCatching { json.decodeFromString(ApiErrorEnvelope.serializer(), r.text).error }
            .getOrElse { ApiErrorBody(code = "http_${r.code}", message = r.text.take(200)) }
        val ex = ApiException(r.code, body, r.headers["X-Request-Id"])
        if (ex.isAuthFailure) onAuthFailure(ex)
        return ex
    }

    /** A fully buffered response (status, headers, body text). */
    private class Fetched(val code: Int, val headers: Headers, val text: String) {
        val ok: Boolean get() = code in 200..299
    }

    /** The response started but its body could not be read (the request was certainly sent). */
    private class BodyReadException(cause: IOException) : IOException(cause)

    /**
     * Executes asynchronously and reads the body on OkHttp's dispatcher thread, so callers on the
     * Android main thread never perform network I/O (NetworkOnMainThreadException).
     */
    private suspend fun fetch(request: Request, via: OkHttpClient? = null): Fetched = suspendCancellableCoroutine { cont ->
        val call = (via ?: if (request.method == "GET") readClient else client).newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                val fetched = try {
                    response.use { Fetched(it.code, it.headers, it.body.string()) }
                } catch (e: IOException) {
                    cont.resumeWithException(BodyReadException(e))
                    return
                }
                cont.resume(fetched) { _, _, _ -> }
            }
        })
    }

    companion object {
        fun parseSessionPage(e: JsonElement): SessionPage = when (e) {
            is JsonArray -> SessionPage(e.mapNotNull { runCatching { ProtocolJson.decodeFromJsonElement(SessionSummary.serializer(), it) }.getOrNull() })
            is JsonObject -> SessionPage(
                items = ((e["items"] ?: e["sessions"]) as? JsonArray).orEmpty()
                    .mapNotNull { runCatching { ProtocolJson.decodeFromJsonElement(SessionSummary.serializer(), it) }.getOrNull() },
                nextCursor = e.str("nextCursor", "cursor"),
            )
            else -> SessionPage()
        }

        fun parseSessionDetail(e: JsonElement): SessionDetail {
            val root = e.obj() ?: throw IOException("session detail is not an object")
            val summaryObj = root["session"].obj() ?: root
            val summary = ProtocolJson.decodeFromJsonElement(SessionSummary.serializer(), summaryObj)
            val caps = (root["capabilities"] ?: summaryObj["capabilities"])?.let {
                runCatching { ProtocolJson.decodeFromJsonElement(SessionCapabilities.serializer(), it) }.getOrNull()
            } ?: SessionCapabilities()
            val pending = ((root["pendingRequests"] ?: summaryObj["pendingRequests"]) as? JsonArray).orEmpty()
                .mapNotNull { runCatching { ProtocolJson.decodeFromJsonElement(AgentEvent.serializer(), it) }.getOrNull() }
            return SessionDetail(
                summary,
                caps,
                pending,
                claudeTools = (root.bool("claudeTools") ?: summaryObj.bool("claudeTools")) == true,
                permissionMode = root.str("permissionMode") ?: summaryObj.str("permissionMode")
                    ?: (root["nextStart"] as? JsonObject)?.str("permissionMode"),
                claudeReadTools = root.bool("claudeReadTools") == true,
                resumable = root.bool("resumable") == true,
            )
        }

        fun parseEventPage(e: JsonElement, limit: Int, olderPossible: Boolean): EventPage {
            val arr: JsonArray
            var hasMore: Boolean? = null
            when (e) {
                is JsonArray -> arr = e
                is JsonObject -> {
                    arr = ((e["items"] ?: e["events"]) as? JsonArray) ?: JsonArray(emptyList())
                    hasMore = e.bool("hasMore", "hasMoreBefore") ?: e.str("nextBefore", "prevCursor")?.let { true }
                }
                else -> arr = JsonArray(emptyList())
            }
            val events = arr.mapNotNull { runCatching { ProtocolJson.decodeFromJsonElement(AgentEvent.serializer(), it) }.getOrNull() }
                .sortedBy { it.seq }
            return EventPage(events, hasMore ?: (olderPossible && events.size >= limit))
        }

        fun parsePayload(text: String, contentType: String?, requestedOffset: Long): PayloadChunk {
            if (contentType?.contains("json") == true || text.trimStart().startsWith("{")) {
                val o = runCatching { ProtocolJson.parseToJsonElement(text) }.getOrNull().obj()
                if (o != null) {
                    val chunk = o.str("text", "data", "chunk") ?: ""
                    val offset = o.long("offset") ?: requestedOffset
                    val total = o.long("totalBytes", "total", "bytes", "size")
                    val eof = o.bool("eof", "done") ?: (total != null && offset + chunk.toByteArray().size >= total)
                    return PayloadChunk(chunk, offset, total, eof)
                }
            }
            return PayloadChunk(text, requestedOffset, null, text.isEmpty())
        }
    }
}

/** `GET /v1/relay`. [token] and [key] are secrets (never logged; [toString] redacts them). */
@kotlinx.serialization.Serializable
data class RelaySettings(
    val enabled: Boolean = false,
    val url: String? = null,
    val token: String? = null,
    val key: String? = null,
) {
    override fun toString(): String = "RelaySettings(enabled=$enabled, url=$url, token=<redacted>, key=<redacted>)"
}
