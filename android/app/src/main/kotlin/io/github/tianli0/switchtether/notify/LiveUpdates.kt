package io.github.tianli0.switchtether.notify

import android.content.Context
import io.github.tianli0.switchtether.R
import io.github.tianli0.switchtether.connection.ConnectionController
import io.github.tianli0.switchtether.connection.ConnectionState
import io.github.tianli0.switchtether.data.PairingStore
import io.github.tianli0.switchtether.remote.command.CommandOutcome
import io.github.tianli0.switchtether.remote.command.newCommandId
import io.github.tianli0.switchtether.remote.live.LivePhase
import io.github.tianli0.switchtether.remote.live.LiveReducer
import io.github.tianli0.switchtether.remote.live.LiveText
import io.github.tianli0.switchtether.remote.live.LiveUpdateRenderer
import io.github.tianli0.switchtether.remote.live.PinnedSessionState
import io.github.tianli0.switchtether.remote.protocol.AgentEvent
import io.github.tianli0.switchtether.remote.protocol.ProtocolJson
import io.github.tianli0.switchtether.remote.protocol.SessionSummary
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * "钉住任务 → 流体云跟进": keeps one Live Update per pinned session (max [MAX_PINS]) in sync with the
 * event stream. Updates are coalesced (at most one post per session per [THROTTLE_MS]); nothing
 * is posted when nothing visible changed; a dismissed card is not reposted until its phase
 * changes; a finished turn stays promoted for a minute and is then demoted (the pin remains).
 */
class LiveUpdates(
    private val context: Context,
    private val scope: CoroutineScope,
    private val conn: ConnectionController,
    private val pairingStore: PairingStore,
    private val channel: LiveActivityChannel,
    /** Approve/deny through the same API + commandId idempotency as the in-app buttons. */
    private val resolveApproval: suspend (sessionId: String, requestId: String, decision: String, commandId: String) -> CommandOutcome,
    private val enabled: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val prefs = context.getSharedPreferences("live_pins", Context.MODE_PRIVATE)
    private val states = ConcurrentHashMap<String, PinnedSessionState>()
    private val posted = ConcurrentHashMap<String, Int>()
    private val postedPhase = ConcurrentHashMap<String, LivePhase>()
    private val pending = ConcurrentHashMap<String, Job>()
    private val lastPost = ConcurrentHashMap<String, Long>()
    private val _pins = MutableStateFlow<Set<String>>(emptySet())
    val pins: StateFlow<Set<String>> = _pins.asStateFlow()
    private val text = ResourceLiveText(context)

    /** "从手机发起的任务自动钉住" (still user-initiated: the phone started it). */
    var autoPinPhoneSessions: Boolean
        get() = prefs.getBoolean("auto_pin", false)
        set(v) = prefs.edit().putBoolean("auto_pin", v).apply()

    fun canPromote(): Boolean = channel.canPromote()

    fun isPinned(sessionId: String) = sessionId in _pins.value

    fun start() {
        scope.launch {
            val serverId = pairingStore.current()?.serverId ?: ""
            _pins.value = prefs.getStringSet(key(serverId), emptySet()).orEmpty().toSet()
            // Restore after a restart (only ids are stored): start as an old, idle card until the
            // first summary arrives, so a restart does not announce a turn that finished long ago.
            _pins.value.forEach { id -> states.putIfAbsent(id, PinnedSessionState(id, id.substringAfter(':').take(8), "unknown", phase = LivePhase.DONE, phaseSinceMs = 0)) }
        }
        scope.launch {
            conn.events.collect { ev ->
                val data = ev.data as? JsonObject ?: return@collect
                when (ev.topic) {
                    "session.upserted" -> runCatching { ProtocolJson.decodeFromJsonElement(SessionSummary.serializer(), (data["session"] as? JsonObject) ?: data) }.getOrNull()
                        ?.let(::onUpsert)
                    "session.event" -> runCatching { ProtocolJson.decodeFromJsonElement(AgentEvent.serializer(), (data["event"] as? JsonObject) ?: data) }.getOrNull()
                        ?.let { e -> mutate(e.sessionId) { LiveReducer.onEvent(it, e, clock()) } }
                    "session.removed" -> (data["sessionId"] ?: data["id"])?.toString()?.trim('"')?.let { unpin(it) }
                }
            }
        }
        scope.launch {
            conn.state.collect { s ->
                // Only a real drop counts as "离线"; the first connect after start is not one.
                if (s !is ConnectionState.Online && s !is ConnectionState.Offline) return@collect
                val online = s is ConnectionState.Online
                if (online) refreshSummaries()
                states.keys.forEach { id -> mutate(id) { LiveReducer.onConnection(it, online, clock()) } }
            }
        }
    }

    private fun onUpsert(s: SessionSummary) {
        if (s.id !in _pins.value) return
        mutate(s.id) { LiveReducer.onUpsert(it, s, clock()) }
        if (!s.isLive) scope.launch {
            // Session ended / process exited: the final card stays briefly, then the pin is removed.
            delay(LiveUpdateRenderer.DONE_PROMOTED_MS)
            unpin(s.id, keepFinal = true)
        }
    }

    private suspend fun refreshSummaries() {
        _pins.value.forEach { id -> refresh(id) }
    }

    /**
     * Reads the session (summary + still-open requests) so a pin made mid-turn, or after a
     * reconnect, shows the request that is already waiting instead of the last streamed step.
     */
    private suspend fun refresh(id: String) {
        val api = conn.apiOrNull() ?: return
        val detail = runCatching { api.session(id) }.getOrNull() ?: return
        detail.pendingRequests.forEach { e -> mutate(id) { LiveReducer.onEvent(it, e, clock()) } }
        onUpsert(detail.summary)
    }

    /** Pins a session; false when [MAX_PINS] are already pinned. */
    fun pin(summary: SessionSummary): Boolean {
        if (summary.id in _pins.value) return true
        if (_pins.value.size >= MAX_PINS) return false
        setPins(_pins.value + summary.id)
        states[summary.id] = LiveReducer.onUpsert(
            PinnedSessionState(summary.id, summary.title ?: summary.project ?: summary.id, summary.agent, phase = LivePhase.DONE, phaseSinceMs = 0),
            summary,
            clock(),
        ).let { if (it.phase == LivePhase.DONE && summary.status != "busy") it.copy(phaseSinceMs = 0) else it }
        schedule(summary.id, immediate = true)
        scope.launch { refresh(summary.id) }
        return true
    }

    fun unpin(sessionId: String, keepFinal: Boolean = false) {
        setPins(_pins.value - sessionId)
        pending.remove(sessionId)?.cancel()
        states.remove(sessionId)
        posted.remove(sessionId)
        postedPhase.remove(sessionId)
        channel.end(sessionId) // keepFinal: the final card was already shown for a minute
    }

    fun onDismissed(sessionId: String) {
        states.computeIfPresent(sessionId) { _, s -> LiveReducer.onDismissed(s) }
    }

    /** Approve / deny from the Live Update (the phone is unlocked: setAuthenticationRequired). */
    fun answer(sessionId: String, requestId: String, decision: String) {
        scope.launch {
            when (val out = resolveApproval(sessionId, requestId, decision, newCommandId())) {
                is CommandOutcome.Succeeded -> Unit // the resolved event moves the card on
                is CommandOutcome.Rejected -> if (out.error.httpStatus == 409) {
                    mutate(sessionId) { LiveReducer.onResolvedElsewhere(it, clock()) }
                } else {
                    mutate(sessionId) { LiveReducer.onNotice(it, context.getString(R.string.live_not_sent_detail, out.error.body.message.ifBlank { out.error.code })) }
                }
                // Never retried silently (§5): the user decides in the app.
                else -> mutate(sessionId) { LiveReducer.onNotice(it, context.getString(R.string.live_not_sent)) }
            }
        }
    }

    private fun mutate(sessionId: String, f: (PinnedSessionState) -> PinnedSessionState) {
        if (sessionId !in _pins.value) return
        val old = states[sessionId] ?: return
        val new = f(old)
        if (new === old) return
        states[sessionId] = new
        schedule(sessionId, immediate = new.phase != old.phase)
    }

    /** Coalesces updates: at most one post per session per [THROTTLE_MS]; phase changes go out at once. */
    private fun schedule(sessionId: String, immediate: Boolean) {
        val since = clock() - (lastPost[sessionId] ?: 0)
        val wait = if (immediate || since >= THROTTLE_MS) 0 else THROTTLE_MS - since
        pending[sessionId]?.cancel()
        pending[sessionId] = scope.launch {
            if (wait > 0) delay(wait)
            post(sessionId)
            // Demote a finished turn after it has been shown for a minute (the pin stays).
            val s = states[sessionId] ?: return@launch
            if (s.phase == LivePhase.DONE || s.phase == LivePhase.FAILED || s.phase == LivePhase.INTERRUPTED) {
                delay(LiveUpdateRenderer.DONE_PROMOTED_MS + 500)
                if (states[sessionId]?.version == s.version) post(sessionId, force = true)
            }
        }
    }

    private fun post(sessionId: String, force: Boolean = false) {
        val s = states[sessionId] ?: return
        if (!enabled()) return channel.end(sessionId)
        if (s.dismissed) return
        if (!force && posted[sessionId] == s.version) return
        val spec = LiveUpdateRenderer.render(s, clock(), text, postedPhase[sessionId])
        val subtitle = listOfNotNull(io.github.tianli0.switchtether.ui.common.Formatters.agentLabel(s.agent).takeIf { s.agent != "unknown" }, s.project).joinToString(" · ").ifEmpty { null }
        channel.update(spec, s.agent, subtitle)
        posted[sessionId] = s.version
        postedPhase[sessionId] = s.phase
        lastPost[sessionId] = clock()
    }

    private fun setPins(p: Set<String>) {
        _pins.value = p
        scope.launch {
            val serverId = pairingStore.current()?.serverId ?: ""
            prefs.edit().putStringSet(key(serverId), p).apply()
        }
    }

    private fun key(serverId: String) = "pins:$serverId"

    companion object {
        const val MAX_PINS = 3
        const val THROTTLE_MS = 1_000L
    }
}

private class ResourceLiveText(private val c: Context) : LiveText {
    private fun s(id: Int, vararg a: Any) = c.getString(id, *a)
    override fun chipRunning() = s(R.string.live_chip_running)
    override fun chipWaiting() = s(R.string.live_chip_waiting)
    override fun chipDone() = s(R.string.live_chip_done)
    override fun chipFailed() = s(R.string.live_chip_failed)
    override fun chipInterrupted() = s(R.string.live_chip_interrupted)
    override fun chipOffline() = s(R.string.live_chip_offline)
    override fun chipEnded() = s(R.string.live_chip_ended)
    override fun thinking() = s(R.string.live_thinking)
    override fun step(step: io.github.tianli0.switchtether.remote.live.Step): String {
        val d = step.detail
        fun pick(withDetail: Int, bare: Int) = if (d != null) s(withDetail, d) else s(bare)
        return when (step.kind) {
            io.github.tianli0.switchtether.remote.live.StepKind.COMMAND -> pick(R.string.live_step_command, R.string.live_step_command_bare)
            io.github.tianli0.switchtether.remote.live.StepKind.EDIT -> pick(R.string.live_step_edit, R.string.live_step_edit_bare)
            io.github.tianli0.switchtether.remote.live.StepKind.READ -> pick(R.string.live_step_read, R.string.live_step_read_bare)
            io.github.tianli0.switchtether.remote.live.StepKind.SEARCH -> s(R.string.live_step_search)
            io.github.tianli0.switchtether.remote.live.StepKind.WEB_FETCH -> pick(R.string.live_step_web, R.string.live_step_web_bare)
            io.github.tianli0.switchtether.remote.live.StepKind.WEB_SEARCH -> s(R.string.live_step_web_search)
            io.github.tianli0.switchtether.remote.live.StepKind.SUBAGENT -> pick(R.string.live_step_subagent, R.string.live_step_subagent_bare)
            io.github.tianli0.switchtether.remote.live.StepKind.PLAN -> s(R.string.live_step_plan)
            io.github.tianli0.switchtether.remote.live.StepKind.MESSAGE -> s(R.string.live_step_message)
            io.github.tianli0.switchtether.remote.live.StepKind.OTHER -> s(R.string.live_step_other)
        }
    }
    override fun waitingApproval() = s(R.string.live_waiting_approval)
    override fun waitingInput() = s(R.string.live_waiting_input)
    override fun done() = s(R.string.live_done)
    override fun failed() = s(R.string.live_failed)
    override fun interrupted() = s(R.string.live_interrupted)
    override fun offline() = s(R.string.live_offline)
    override fun ended() = s(R.string.live_ended)
    override fun resolvedElsewhere() = s(R.string.live_resolved_elsewhere)
    override fun publicText() = s(R.string.app_name)
}
