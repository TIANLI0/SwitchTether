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
 * event stream.
 *
 * Slots: the newest [MAX_PINS] running tasks are pinned automatically. A newer task takes the slot of
 * the oldest automatic pin (they rotate); a task that is waiting for a free slot gets it when one
 * ends. A manual pin outranks every automatic one (it can displace one, and is never displaced by
 * one); when all slots are manual a further pin is refused. A pin is void once its task has finished:
 * after the final card has been shown, the slot is released and the next running task moves in. Updates are coalesced (at most one post per session per [THROTTLE_MS]); nothing
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
    private val startedAtMs = clock()
    private val prefs = context.getSharedPreferences("live_pins", Context.MODE_PRIVATE)
    private val states = ConcurrentHashMap<String, PinnedSessionState>()
    private val posted = ConcurrentHashMap<String, Int>()
    private val postedPhase = ConcurrentHashMap<String, LivePhase>()
    private val pending = ConcurrentHashMap<String, Job>()
    private val lastPost = ConcurrentHashMap<String, Long>()
    private val _pins = MutableStateFlow<Set<String>>(emptySet())
    val pins: StateFlow<Set<String>> = _pins.asStateFlow()
    private val text = ResourceLiveText(context)
    private val unpinned = ConcurrentHashMap.newKeySet<String>()
    private val manualPins = ConcurrentHashMap.newKeySet<String>()
    private val restoredPins = ConcurrentHashMap.newKeySet<String>()
    private val pinnedAt = ConcurrentHashMap<String, Long>()

    /** Running tasks that currently have no slot, newest activity first when a slot frees. */
    private val liveSeen = ConcurrentHashMap<String, SessionSummary>()
    private val knownLive = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var baselineMs: Long = 0L
    @Volatile private var currentServerId: String = ""

    /** Auto-pin newly started live tasks to the phone (respects manual unpin and ignores history). */
    var autoPinPhoneSessions: Boolean
        get() = prefs.getBoolean("auto_pin_new", true)
        set(v) = prefs.edit().putBoolean("auto_pin_new", v).apply()

    fun canPromote(): Boolean = channel.canPromote()

    fun isPinned(sessionId: String) = sessionId in _pins.value

    fun start() {
        scope.launch {
            val serverId = pairingStore.current()?.serverId ?: ""
            loadServerState(serverId)
            // Restore after a restart (only ids are stored): start as an old, idle card until the
            // first summary arrives, so a restart does not announce a turn that finished long ago.
            _pins.value.forEach { id -> states.putIfAbsent(id, PinnedSessionState(id, id.substringAfter(':').take(8), "unknown", phase = LivePhase.DONE, phaseSinceMs = 0)) }
        }
        scope.launch {
            pairingStore.desktop.collect { d ->
                val sid = d?.serverId ?: ""
                if (sid != currentServerId) loadServerState(sid)
            }
        }
        scope.launch {
            conn.events.collect { ev ->
                val data = ev.data as? JsonObject ?: return@collect
                when (ev.topic) {
                    "session.upserted" -> runCatching { ProtocolJson.decodeFromJsonElement(SessionSummary.serializer(), (data["session"] as? JsonObject) ?: data) }.getOrNull()
                        ?.let(::onUpsert)
                    "session.event" -> runCatching { ProtocolJson.decodeFromJsonElement(AgentEvent.serializer(), (data["event"] as? JsonObject) ?: data) }.getOrNull()
                        ?.let { e ->
                            val at = runCatching { java.time.Instant.parse(e.ts).toEpochMilli() }.getOrNull()
                            if (at == null || at >= startedAtMs) mutate(e.sessionId) { LiveReducer.onEvent(it, e, clock()) }
                        }
                    "session.removed" -> (data["sessionId"] ?: data["id"])?.toString()?.trim('"')?.let { unpin(it, userInitiated = false) }
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

    private fun loadServerState(serverId: String) {
        currentServerId = serverId
        _pins.value = prefs.getStringSet(key(serverId), emptySet()).orEmpty().toSet()
        restoredPins.clear()
        restoredPins.addAll(_pins.value)
        unpinned.clear()
        unpinned.addAll(prefs.getStringSet(unpinnedKey(serverId), emptySet()).orEmpty())
        manualPins.clear()
        manualPins.addAll(prefs.getStringSet(manualKey(serverId), emptySet()).orEmpty().filter { it in _pins.value })
        knownLive.clear()
        knownLive.addAll(prefs.getStringSet(knownKey(serverId), emptySet()).orEmpty())
        baselineMs = prefs.getLong(baselineKey(serverId), 0L)
    }

    private fun onUpsert(s: SessionSummary) {
        // A persisted pin is a subscription, not evidence of a turn completing now.
        if (restoredPins.remove(s.id) && (!s.isLive || s.status !in setOf("busy", "waiting") && s.waitingFor == null)) {
            unpin(s.id, userInitiated = false, refill = false)
            return
        }
        if (s.id !in _pins.value) {
            if (s.isLive) liveSeen[s.id] = s else liveSeen.remove(s.id)
            maybeAutoPin(s)
            return
        }
        mutate(s.id) { LiveReducer.onUpsert(it, s, clock()) }
        if (!s.isLive) scope.launch {
            // Session ended / process exited: the final card stays briefly, then the pin is removed.
            delay(LiveUpdateRenderer.DONE_PROMOTED_MS)
            unpin(s.id, keepFinal = true, userInitiated = false)
        }
    }

    private fun maybeAutoPin(s: SessionSummary) {
        if (!autoPinPhoneSessions) return
        if (!s.isLive) return
        if (s.id in unpinned) return
        val isNew = knownLive.add(s.id)
        if (isNew) persistSet(knownKey(currentServerId), knownLive)
        if (!isNew) return
        val base = baselineMs
        if (base == 0L) return
        val created = s.createdAtMs
        if (created != null && created < base - 5_000L) return
        // The newest task takes the slot of the oldest automatic pin; with only manual pins left it waits.
        pin(s, manual = false)
    }

    /** Oldest automatic pin, finished ones first; null when every pin is manual. */
    private fun oldestAuto(): String? = _pins.value.filter { it !in manualPins }
        .minWithOrNull(compareBy<String> { states[it]?.phase.let { p -> if (p == LivePhase.DONE || p == LivePhase.FAILED || p == LivePhase.INTERRUPTED || p == LivePhase.ENDED) 0 else 1 } }.thenBy { pinnedAt[it] ?: 0L })

    /** A slot is free: pin the newest running tasks that are waiting for one (not ones the user unpinned). */
    private fun fillSlots() {
        if (!autoPinPhoneSessions) return
        liveSeen.values
            .filter { it.isLive && it.id !in _pins.value && it.id !in unpinned && (it.status == "busy" || it.status == "waiting" || it.waitingFor != null) }
            .sortedByDescending { it.lastActiveAtMs ?: it.createdAtMs ?: 0L }
            .forEach { if (_pins.value.size < MAX_PINS) pin(it, manual = false) }
    }


    private suspend fun refreshSummaries() {
        _pins.value.toList().forEach { id -> refresh(id) }
        val api = conn.apiOrNull() ?: return
        val live = runCatching { api.sessions(limit = 50).items.filter { it.isLive } }.getOrNull() ?: return
        if (baselineMs == 0L) {
            // Initial baseline: record existing live tasks without mis-pinning historical/old ones.
            live.forEach { knownLive.add(it.id) }
            baselineMs = clock()
            prefs.edit()
                .putStringSet(knownKey(currentServerId), knownLive.toSet())
                .putLong(baselineKey(currentServerId), baselineMs)
                .apply()
            return
        }
        live.forEach { s -> maybeAutoPin(s) }
    }

    /**
     * Reads the session (summary + still-open requests) so a pin made mid-turn, or after a
     * reconnect, shows the request that is already waiting instead of the last streamed step.
     */
    private suspend fun refresh(id: String) {
        val api = conn.apiOrNull() ?: return
        val detail = runCatching { api.session(id) }.getOrNull() ?: return
        if (!detail.summary.isLive) {
            unpin(id, userInitiated = false, refill = false)
            return
        }
        onUpsert(detail.summary)
        detail.pendingRequests.forEach { e -> mutate(id) { LiveReducer.onEvent(it, e, clock()) } }
    }

    /** Pins a session; false when [MAX_PINS] are already pinned. */
    fun pin(summary: SessionSummary, manual: Boolean = true): Boolean {
        if (manual && unpinned.remove(summary.id)) {
            persistSet(unpinnedKey(currentServerId), unpinned)
        }
        knownLive.add(summary.id)
        persistSet(knownKey(currentServerId), knownLive)
        if (summary.id in _pins.value) {
            if (manual && manualPins.add(summary.id)) persistSet(manualKey(currentServerId), manualPins)
            return true
        }
        if (_pins.value.size >= MAX_PINS) {
            // Rotate: the newest task displaces the oldest automatic pin; all-manual means no room.
            val victim = oldestAuto() ?: return false
            unpin(victim, userInitiated = false, refill = false)
        }
        if (manual) {
            manualPins.add(summary.id)
            persistSet(manualKey(currentServerId), manualPins)
        }
        pinnedAt[summary.id] = clock()
        setPins(_pins.value + summary.id)
        states[summary.id] = LiveReducer.onUpsert(
            PinnedSessionState(summary.id, io.github.tianli0.switchtether.domain.SessionNaming.title(summary) ?: io.github.tianli0.switchtether.domain.SessionNaming.projectName(summary) ?: summary.id, summary.agent, phase = LivePhase.DONE, phaseSinceMs = 0),
            summary,
            clock(),
        ).let { if (it.phase == LivePhase.DONE && summary.status != "busy") it.copy(phaseSinceMs = 0) else it }
        schedule(summary.id, immediate = true)
        scope.launch { refresh(summary.id) }
        return true
    }

    fun unpin(sessionId: String, keepFinal: Boolean = false, userInitiated: Boolean = true, refill: Boolean = true) {
        if (manualPins.remove(sessionId)) persistSet(manualKey(currentServerId), manualPins)
        pinnedAt.remove(sessionId)
        if (userInitiated && unpinned.add(sessionId)) {
            persistSet(unpinnedKey(currentServerId), unpinned)
        }
        setPins(_pins.value - sessionId)
        pending.remove(sessionId)?.cancel()
        states.remove(sessionId)
        posted.remove(sessionId)
        postedPhase.remove(sessionId)
        channel.end(sessionId) // keepFinal: the final card was already shown for a minute
        if (refill) fillSlots()
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
                if (states[sessionId]?.version == s.version) {
                    // The task ended and its final card has been shown: the pin is void (a pin that
                    // never saw a turn run, e.g. just made on an idle session, is kept).
                    if (s.turnStartedMs > 0) unpin(sessionId, userInitiated = false) else post(sessionId, force = true)
                }
            }
        }
    }

    private fun post(sessionId: String, force: Boolean = false) {
        val s = states[sessionId] ?: return
        if (!enabled()) return channel.end(sessionId)
        if (s.dismissed) return
        if (!force && posted[sessionId] == s.version) return
        val spec = LiveUpdateRenderer.render(s, clock(), text, postedPhase[sessionId])
        val subtitle = listOfNotNull(io.github.tianli0.switchtether.ui.common.Formatters.agentLabel(s.agent).takeIf { s.agent != "unknown" }, s.project, spec.progressNote.takeIf { it.isNotEmpty() && s.phase == LivePhase.RUNNING }).joinToString(" · ").ifEmpty { null }
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

    private fun persistSet(prefKey: String, set: Set<String>) {
        // Bound stored history sets so prefs never grow unboundedly.
        val trimmed = if (set.size > 200) set.toList().takeLast(200).toSet() else set.toSet()
        prefs.edit().putStringSet(prefKey, trimmed).apply()
    }

    private fun key(serverId: String) = "pins:$serverId"
    private fun unpinnedKey(serverId: String) = "unpinned:$serverId"
    private fun manualKey(serverId: String) = "manual:$serverId"
    private fun knownKey(serverId: String) = "known:$serverId"
    private fun baselineKey(serverId: String) = "baseline_ms:$serverId"

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
    override fun chipStep(kind: io.github.tianli0.switchtether.remote.live.StepKind?) = s(
        when (kind) {
            null -> R.string.live_chip_think
            io.github.tianli0.switchtether.remote.live.StepKind.COMMAND -> R.string.live_chip_cmd
            io.github.tianli0.switchtether.remote.live.StepKind.EDIT -> R.string.live_chip_edit
            io.github.tianli0.switchtether.remote.live.StepKind.READ -> R.string.live_chip_read
            io.github.tianli0.switchtether.remote.live.StepKind.SEARCH -> R.string.live_chip_find
            io.github.tianli0.switchtether.remote.live.StepKind.WEB_FETCH, io.github.tianli0.switchtether.remote.live.StepKind.WEB_SEARCH -> R.string.live_chip_web
            io.github.tianli0.switchtether.remote.live.StepKind.SUBAGENT -> R.string.live_chip_agent
            io.github.tianli0.switchtether.remote.live.StepKind.PLAN -> R.string.live_chip_plan
            io.github.tianli0.switchtether.remote.live.StepKind.MESSAGE -> R.string.live_chip_reply
            io.github.tianli0.switchtether.remote.live.StepKind.OTHER -> R.string.live_chip_running
        },
    )
    override fun stepsDone(n: Int) = if (n == 1) s(R.string.live_steps_one) else s(R.string.live_steps_many, n)
    override fun plan(done: Int, total: Int, current: String?) = if (current != null) s(R.string.live_plan_now, done, total, current) else s(R.string.live_plan, done, total)
    override fun filesChanged(n: Int) = s(R.string.live_files_changed, n)
    override fun tokensUsed(tokens: String) = s(R.string.live_tokens_used, tokens)
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
