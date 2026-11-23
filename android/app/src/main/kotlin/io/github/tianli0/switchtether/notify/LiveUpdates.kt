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
import io.github.tianli0.switchtether.remote.live.LiveUpdateSpec
import io.github.tianli0.switchtether.remote.live.PinnedSessionState
import io.github.tianli0.switchtether.remote.protocol.AgentEvent
import io.github.tianli0.switchtether.remote.protocol.EventKinds
import io.github.tianli0.switchtether.remote.protocol.bool
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
 * one); when all slots are manual a further pin is refused.
 *
 * Posting (every re-post makes Fluid Cloud / the status chip animate, so it must mean something): a
 * card is re-posted only when what it shows changed — a new phase at once, the collapsed line /
 * capsule at most every [Timing.headlineMs], expanded-only details (counters, the agent's latest
 * words) at most every [Timing.detailMs]. A connection drop shorter than [Timing.offlineGraceMs] is
 * never shown, and only a turn in flight shows one at all. A dismissed card is not reposted until
 * its phase changes.
 *
 * End of a task (docs/ANDROID_UI.md, "Live Updates lifecycle"):
 * - The turn finishes → the capsule shows the outcome for [Timing.settleMs] (sounding once if the
 *   user's "task finished / failed / interrupted" notification is on), then the card **settles**: the
 *   same notification becomes an ordinary, swipeable result ("本轮已完成 · 12 步"), the pin slot is
 *   released, and the session's next turn is followed again on the same card.
 * - Background work still running after the turn (`run_in_background` shells, background agents):
 *   the card says so ("本轮已完成 · 后台 2 个任务运行中") and is demoted after [Timing.settleMs] but
 *   keeps its pin, so the agent waking up for a task's result continues on the same card. It settles
 *   [Timing.bgSettleGraceMs] after the last background task ended without a new turn, at the latest
 *   [Timing.bgFollowMaxMs] after the turn (a dev server never ends). Its slot is the first given up.
 * - The process exits → settles at once ("会话已结束", or the finished turn's outcome).
 * - Opening the session in the app clears its result notification (it has been seen).
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
    /** Whether a finished turn (DONE / FAILED / INTERRUPTED) should sound, per the user's notification settings. */
    private val alertOnFinish: (PinnedSessionState) -> Boolean = { false },
    private val timing: Timing = Timing(),
) {
    data class Timing(
        val headlineMs: Long = 3_000L,
        val detailMs: Long = 15_000L,
        val offlineGraceMs: Long = 20_000L,
        val settleMs: Long = LiveUpdateRenderer.DONE_PROMOTED_MS,
        val bgSettleGraceMs: Long = 10_000L,
        val bgFollowMaxMs: Long = 30 * 60_000L,
    )

    private val startedAtMs = clock()
    private val prefs = context.getSharedPreferences("live_pins", Context.MODE_PRIVATE)
    private val states = ConcurrentHashMap<String, PinnedSessionState>()
    /** What each card shows now (spec + subtitle): a post that would show the same is skipped. */
    private val postedSpec = ConcurrentHashMap<String, Pair<LiveUpdateSpec, String?>>()
    private val postedPhase = ConcurrentHashMap<String, LivePhase>()
    private val pending = ConcurrentHashMap<String, Job>()
    private val pendingDue = ConcurrentHashMap<String, Long>()
    /** Demote / settle timers of a finished turn, and what they were armed for. */
    private val endJobs = ConcurrentHashMap<String, Job>()
    private val endArmedFor = ConcurrentHashMap<String, List<Any>>()
    /** When the last background task of a finished turn ended (phone clock). */
    private val bgClearedAt = ConcurrentHashMap<String, Long>()
    /** Turn (start time) whose outcome has been announced, so a re-post never sounds twice. */
    private val announcedTurn = ConcurrentHashMap<String, Long>()
    @Volatile private var offlineJob: Job? = null
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
    /** Last seen run state per session (this app run), to notice a task starting again. */
    private val runState = ConcurrentHashMap<String, AutoFollow.Run>()
    @Volatile private var baselineMs: Long = 0L
    @Volatile private var currentServerId: String = ""

    /**
     * All state changes and posts run under this lock. The app scope is multi-threaded: the event
     * collector, the reconnect refresh, the pin made from the UI and the throttled post jobs ran in
     * parallel, so a read-modify-write could drop a newer change (finished → running again) and an
     * older snapshot could be posted after a newer one.
     */
    private val lock = Any()

    /** Per session, a counter bumped by every stream input: a REST read that raced with the stream is stale. */
    private val streamTick = java.util.concurrent.atomic.AtomicLong()
    private val streamMark = ConcurrentHashMap<String, Long>()

    /** Requests the stream reported resolved (bounded): a REST read made before must not reopen them. */
    private val resolvedRequests: MutableSet<String> = java.util.Collections.synchronizedSet(
        java.util.Collections.newSetFromMap(object : java.util.LinkedHashMap<String, Boolean>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > 500
        }),
    )

    private fun requestKey(sessionId: String, e: AgentEvent): String =
        "$sessionId|${(e.data["requestId"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: e.id}"

    /** Auto-pin newly started live tasks to the phone (respects manual unpin and ignores history). */
    var autoPinPhoneSessions: Boolean
        get() = prefs.getBoolean("auto_pin_new", true)
        set(v) = prefs.edit().putBoolean("auto_pin_new", v).apply()

    fun canPromote(): Boolean = channel.canPromote()

    fun isPinned(sessionId: String) = sessionId in _pins.value

    /**
     * Whether a Live Update card is actually showing this session's state, so ordinary notifications
     * may stay quiet. Not when Live Updates are switched off (the card is withdrawn) or the user
     * swiped the card away: then approvals and questions must still be notified.
     */
    fun covers(sessionId: String): Boolean =
        sessionId in _pins.value && sessionId !in restoredPins && enabled() && states[sessionId]?.dismissed != true

    fun start() {
        scope.launch {
            val serverId = pairingStore.current()?.serverId ?: ""
            loadServerState(serverId)
            // Restore after a restart (only ids are stored): start as an old, idle card until the
            // first summary arrives, so a restart does not announce a turn that finished long ago.
            // Nothing is posted for it before then ([post]): only the id is known, and a card titled
            // with a fragment of it ("3fa9c2d1 · 运行中") that opens an unknown session is noise.
            _pins.value.forEach { id -> states.putIfAbsent(id, PinnedSessionState(id, context.getString(R.string.app_name), "unknown", phase = LivePhase.DONE, phaseSinceMs = 0)) }
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
                        ?.let { onStreamUpsert(it) }
                    "session.event" -> runCatching { ProtocolJson.decodeFromJsonElement(AgentEvent.serializer(), (data["event"] as? JsonObject) ?: data) }.getOrNull()
                        ?.let { onStreamEvent(it) }
                    "session.removed" -> (data["sessionId"] ?: data["id"])?.toString()?.trim('"')?.let {
                        // The process exited; the same conversation resumed later is a new run to follow.
                        runState[it] = AutoFollow.Run.ENDED
                        ended(it)
                    }
                }
            }
        }
        scope.launch {
            conn.state.collect { s ->
                // Only a real drop counts as "离线"; the first connect after start is not one.
                if (s !is ConnectionState.Online && s !is ConnectionState.Offline) return@collect
                if (s is ConnectionState.Online) {
                    offlineJob?.cancel()
                    offlineJob = null
                    refreshSummaries()
                    states.keys.forEach { id -> mutate(id) { LiveReducer.onConnection(it, true, clock()) } }
                } else if (offlineJob?.isActive != true) {
                    // A blip (network handover, relay reconnect) is not news: only a lasting drop is shown.
                    offlineJob = scope.launch {
                        delay(timing.offlineGraceMs)
                        if (conn.state.value is ConnectionState.Online) return@launch
                        states.keys.forEach { id -> mutate(id) { LiveReducer.onConnection(it, false, clock()) } }
                    }
                }
            }
        }
    }

    private fun loadServerState(serverId: String): Unit = synchronized(lock) {
        // Another computer: the previous one's cards are not about it (and open sessions it does not have).
        if (serverId != currentServerId) {
            (_pins.value + states.keys).forEach { id ->
                pending.remove(id)?.cancel()
                pendingDue.remove(id)
                endJobs.remove(id)?.cancel()
                endArmedFor.remove(id)
                postedSpec.remove(id)
                postedPhase.remove(id)
                channel.end(id)
            }
            states.clear()
            pinnedAt.clear()
            liveSeen.clear()
            runState.clear()
        }
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

    /** `session.upserted` from the event stream (newer than any REST read still in flight). */
    internal fun onStreamUpsert(s: SessionSummary): Unit = synchronized(lock) {
        streamMark[s.id] = streamTick.incrementAndGet()
        onUpsert(s, fromRest = false)
    }

    /** `session.event` from the event stream. */
    internal fun onStreamEvent(e: AgentEvent): Unit = synchronized(lock) {
        streamMark[e.sessionId] = streamTick.incrementAndGet()
        val resolved = when (e.kind) {
            EventKinds.APPROVAL_RESOLVED -> true
            EventKinds.APPROVAL_REQUEST, EventKinds.QUESTION -> e.data.bool("resolved") == true
            else -> false
        }
        if (resolved) resolvedRequests.add(requestKey(e.sessionId, e))
        val at = runCatching { java.time.Instant.parse(e.ts).toEpochMilli() }.getOrNull()
        if (at == null || at >= startedAtMs) mutate(e.sessionId) { LiveReducer.onEvent(it, e, clock()) }
    }

    private fun onUpsert(raw: SessionSummary, fromRest: Boolean): Unit = synchronized(lock) {
        val s = forCard(raw)
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
        runState[s.id] = AutoFollow.runOf(s)
        if (!s.isLive) return ended(s.id)
        mutate(s.id) { LiveReducer.onUpsert(it, s, clock(), fromRest) }
    }

    /** The session's process exited: the card settles at once (its outcome, or "会话已结束"). */
    private fun ended(sessionId: String): Unit = synchronized(lock) {
        if (sessionId !in _pins.value) return
        mutate(sessionId) { LiveReducer.onEnded(it, clock()) }
        settle(sessionId)
    }

    private fun maybeAutoPin(s: SessionSummary) {
        val prev = runState.put(s.id, AutoFollow.runOf(s))
        if (!autoPinPhoneSessions) return
        // Live Updates switched off: nothing would show, so do not take a pin slot either.
        if (!enabled()) return
        if (!s.isLive) return
        if (s.id in unpinned) return
        val isNew = knownLive.add(s.id)
        if (isNew) persistSet(knownKey(currentServerId), knownLive)
        if (!AutoFollow.shouldFollow(s, prev, isNew, baselineMs)) return
        // The newest task takes the slot of the oldest automatic pin; with only manual pins left it waits.
        pin(s, manual = false)
    }

    /** Oldest automatic pin, finished ones (also those with only background work left) first; null when every pin is manual. */
    private fun oldestAuto(): String? = _pins.value.filter { it !in manualPins }
        .minWithOrNull(compareBy<String> { states[it]?.phase.let { p -> if (p == null || LiveReducer.isFinished(p) || p == LivePhase.ENDED) 0 else 1 } }.thenBy { pinnedAt[it] ?: 0L })

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
        val marks = HashMap(streamMark)
        val listed = runCatching { api.sessions(limit = 50).items.filter { it.isLive } }.getOrNull() ?: return
        // A session the stream reported on while the list was read: the list is older, skip it.
        val live = listed.filter { streamMark[it.id] == marks[it.id] }
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
        synchronized(lock) { live.forEach { s -> maybeAutoPin(s) } }
    }

    /**
     * Reads the session (summary + still-open requests) so a pin made mid-turn, or after a
     * reconnect, shows the request that is already waiting instead of the last streamed step.
     */
    private suspend fun refresh(id: String) {
        val api = conn.apiOrNull() ?: return
        val mark = streamMark[id]
        val result = runCatching { api.session(id) }
        if (result.exceptionOrNull()?.let { e -> generateSequence(e) { it.cause }.any { (it as? io.github.tianli0.switchtether.remote.api.ApiException)?.httpStatus == 404 } } == true) {
            // The computer no longer knows this session (removed, or the pin is from an older run):
            // a card for it would open an empty page.
            unpin(id, userInitiated = false)
            return
        }
        val detail = result.getOrNull() ?: return
        applyRefresh(id, detail.summary, detail.pendingRequests, streamMovedSince = streamMark[id] != mark)
    }

    /**
     * Applies a REST read of a pinned session. When the stream delivered something for it while the
     * read was in flight, the summary is older than what the card shows and is dropped (it turned a
     * just-finished card back to running); still-open requests are applied unless the stream resolved
     * them meanwhile (and the reducer drops requests older than the turn's outcome).
     */
    internal fun applyRefresh(id: String, summary: SessionSummary, pendingRequests: List<AgentEvent>, streamMovedSince: Boolean): Unit = synchronized(lock) {
        if (!streamMovedSince) {
            onUpsert(summary, fromRest = true)
        } else if (id in restoredPins) {
            // The summary is older than the stream, but a restored pin still needs its name, and a
            // restored pin of a task that is no longer running is void.
            if (!summary.isLive || summary.status !in setOf("busy", "waiting") && summary.waitingFor == null) {
                unpin(id, userInitiated = false, refill = false)
                return
            }
            restoredPins.remove(id)
            val named = forCard(summary)
            states.computeIfPresent(id) { _, st -> st.copy(title = named.title ?: st.title, agent = named.agent, project = named.project ?: st.project, version = st.version + 1) }
            schedule(id, phaseChanged = true)
        }
        pendingRequests
            .filter { e -> requestKey(id, e) !in resolvedRequests }
            .forEach { e -> mutate(id) { LiveReducer.onEvent(it, e, clock()) } }
    }

    /** Pins a session; false when [MAX_PINS] are already pinned. */
    fun pin(raw: SessionSummary, manual: Boolean = true): Boolean = synchronized(lock) {
        val summary = forCard(raw)
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
            PinnedSessionState(summary.id, summary.title ?: context.getString(R.string.app_name), summary.agent, phase = LivePhase.DONE, phaseSinceMs = 0),
            summary,
            clock(),
        ).let { if (it.phase == LivePhase.DONE && summary.status != "busy") it.copy(phaseSinceMs = 0) else it }
        // A new pin always shows (the session's result notification, if any, becomes live again).
        postedSpec.remove(summary.id)
        schedule(summary.id, phaseChanged = true)
        scope.launch { refresh(summary.id) }
        return true
    }

    /** @param keepCard leave the notification up (a settled result); otherwise it is withdrawn. */
    fun unpin(sessionId: String, keepCard: Boolean = false, userInitiated: Boolean = true, refill: Boolean = true): Unit = synchronized(lock) {
        if (manualPins.remove(sessionId)) persistSet(manualKey(currentServerId), manualPins)
        pinnedAt.remove(sessionId)
        if (userInitiated && unpinned.add(sessionId)) {
            persistSet(unpinnedKey(currentServerId), unpinned)
        }
        setPins(_pins.value - sessionId)
        pending.remove(sessionId)?.cancel()
        pendingDue.remove(sessionId)
        endJobs.remove(sessionId)?.cancel()
        endArmedFor.remove(sessionId)
        bgClearedAt.remove(sessionId)
        states.remove(sessionId)
        postedSpec.remove(sessionId)
        postedPhase.remove(sessionId)
        if (keepCard) channel.release(sessionId) else channel.end(sessionId)
        if (refill) fillSlots()
    }

    /** The user opened the session in the app: its result notification has been seen. */
    fun onSessionOpened(sessionId: String): Unit = synchronized(lock) {
        if (sessionId !in _pins.value) channel.end(sessionId)
    }

    /**
     * The finished card's outcome has been shown: the same notification becomes an ordinary result
     * (swipeable, not promoted, no unpin action) and the slot is released.
     */
    private fun settle(sessionId: String): Unit = synchronized(lock) {
        if (sessionId !in _pins.value) return
        val s = states[sessionId] ?: return
        states[sessionId] = LiveReducer.onSettled(s)
        post(sessionId)
        unpin(sessionId, keepCard = true, userInitiated = false)
    }

    fun onDismissed(sessionId: String): Unit = synchronized(lock) {
        postedSpec.remove(sessionId)
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

    private fun mutate(sessionId: String, f: (PinnedSessionState) -> PinnedSessionState): Unit = synchronized(lock) {
        if (sessionId !in _pins.value) return
        val old = states[sessionId] ?: return
        val new = f(old)
        if (new === old) return
        states[sessionId] = new
        if (LiveReducer.isFinished(new.phase) && old.background > 0 && new.background == 0) bgClearedAt[sessionId] = clock()
        if (!LiveReducer.isFinished(new.phase)) bgClearedAt.remove(sessionId)
        schedule(sessionId, phaseChanged = new.phase != old.phase)
        armEnd(sessionId)
    }

    private fun render(sessionId: String, s: PinnedSessionState): Pair<LiveUpdateSpec, String?> {
        val spec = LiveUpdateRenderer.render(s, clock(), text, postedPhase[sessionId], timing.settleMs)
        val subtitle = listOfNotNull(io.github.tianli0.switchtether.ui.common.Formatters.agentLabel(s.agent).takeIf { s.agent != "unknown" }, s.project, spec.progressNote.takeIf { it.isNotEmpty() && s.phase == LivePhase.RUNNING }).joinToString(" · ").ifEmpty { null }
        return spec to subtitle
    }

    /**
     * Coalesces updates by what changed on screen: a new phase goes out at once, the collapsed line /
     * capsule at most every [Timing.headlineMs], expanded-only details at most every [Timing.detailMs];
     * nothing visible changed → nothing is posted.
     */
    private fun schedule(sessionId: String, phaseChanged: Boolean) {
        val s = states[sessionId] ?: return
        val now = clock()
        val last = postedSpec[sessionId]
        val wait = if (phaseChanged || last == null) 0L else {
            val (spec, sub) = render(sessionId, s)
            when {
                spec.headline() != last.first.headline() -> timing.headlineMs
                spec.copy(alert = false) != last.first.copy(alert = false) || sub != last.second -> timing.detailMs
                else -> return
            }
        }
        val due = if (wait == 0L) now else maxOf(now, (lastPost[sessionId] ?: 0L) + wait)
        // A post already due sooner carries this change too (it posts the state of its time).
        if (pending[sessionId]?.isActive == true && (pendingDue[sessionId] ?: Long.MAX_VALUE) <= due) return
        pending.remove(sessionId)?.cancel()
        pendingDue[sessionId] = due
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            if (due > now) delay(due - now)
            synchronized(lock) {
                if (pendingDue[sessionId] == due) {
                    pending.remove(sessionId)
                    pendingDue.remove(sessionId)
                }
                post(sessionId)
            }
        }
        pending[sessionId] = job
        job.start()
    }

    /**
     * Timers of a finished turn: demote the capsule after [Timing.settleMs], then settle — at the same
     * time, or, with background work still running, [Timing.bgSettleGraceMs] after it ended (at the
     * latest [Timing.bgFollowMaxMs] after the turn). A pin that never saw a turn run (made on an idle
     * session) is kept as it is.
     */
    private fun armEnd(sessionId: String) {
        val s = states[sessionId]
        if (s == null || !LiveReducer.isFinished(s.phase) || s.turnStartedMs == 0L || s.settled) {
            endJobs.remove(sessionId)?.cancel()
            endArmedFor.remove(sessionId)
            return
        }
        val key = listOf(s.phase, s.phaseSinceMs, s.background > 0, bgClearedAt[sessionId] ?: 0L)
        if (endArmedFor[sessionId] == key && endJobs[sessionId]?.isActive == true) return
        endJobs.remove(sessionId)?.cancel()
        endArmedFor[sessionId] = key
        val demoteAt = s.phaseSinceMs + timing.settleMs
        val settleAt = if (s.background > 0) s.phaseSinceMs + timing.bgFollowMaxMs
        else maxOf(demoteAt, (bgClearedAt[sessionId] ?: 0L) + timing.bgSettleGraceMs)
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            val toDemote = demoteAt - clock()
            if (settleAt > demoteAt) {
                delay(toDemote.coerceAtLeast(0) + 50) // just past the window, so the render is demoted
                synchronized(lock) { if (endArmedFor[sessionId] == key) post(sessionId) } // promoted → quiet, same words
            }
            val toSettle = settleAt - clock()
            if (toSettle > 0) delay(toSettle)
            synchronized(lock) {
                if (endArmedFor[sessionId] != key) return@synchronized
                endArmedFor.remove(sessionId)
                endJobs.remove(sessionId)
                val cur = states[sessionId] ?: return@synchronized
                if (cur.background == 0 || clock() - cur.phaseSinceMs >= timing.bgFollowMaxMs) settle(sessionId)
            }
        }
        endJobs[sessionId] = job
        job.start()
    }

    private fun post(sessionId: String, force: Boolean = false): Unit = synchronized(lock) {
        val s = states[sessionId] ?: return
        if (!enabled()) {
            postedSpec.remove(sessionId)
            return channel.end(sessionId)
        }
        // A pin restored after a restart is shown only once a summary has named it.
        if (sessionId in restoredPins) return
        if (s.dismissed) return
        var (spec, subtitle) = render(sessionId, s)
        val last = postedSpec[sessionId]
        if (!force && last != null && spec.copy(alert = false) == last.first.copy(alert = false) && subtitle == last.second) return
        // The whole job is over (no background work left): announce it once, per the user's settings.
        if (LiveReducer.isFinished(s.phase) && s.background == 0 && s.turnStartedMs > 0 && announcedTurn[sessionId] != s.turnStartedMs) {
            announcedTurn[sessionId] = s.turnStartedMs
            if (alertOnFinish(s)) spec = spec.copy(alert = true)
        }
        channel.update(spec, s.agent, subtitle)
        postedSpec[sessionId] = spec to subtitle
        postedPhase[sessionId] = s.phase
        lastPost[sessionId] = clock()
    }

    /**
     * The summary as the card names it: the same cleaned title as the session list (never the raw
     * one, which can be injected markup or an auto-generated "<project>-<hex>" slug), else the
     * project, else the agent; never the session id.
     */
    private fun forCard(s: SessionSummary): SessionSummary {
        val title = io.github.tianli0.switchtether.domain.SessionNaming.title(s)
            ?: io.github.tianli0.switchtether.domain.SessionNaming.projectName(s)
            ?: io.github.tianli0.switchtether.ui.common.Formatters.agentLabel(s.agent).takeIf { s.agent != "unknown" }
            ?: context.getString(R.string.app_name)
        val project = io.github.tianli0.switchtether.domain.SessionNaming.projectName(s)
        return s.copy(title = title, project = project ?: s.project)
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
    }
}

/**
 * When a session that is not followed should be followed automatically. A follow ends with its task
 * (final card shown, process exited), so every new run is followed again: a new session, the next
 * turn of a known one (idle → busy / waiting), or a conversation resumed after its process ended.
 */
internal object AutoFollow {
    enum class Run { ENDED, IDLE, ACTIVE }

    fun runOf(s: SessionSummary): Run = when {
        !s.isLive -> Run.ENDED
        s.status == "busy" || s.status == "waiting" || s.waitingFor != null -> Run.ACTIVE
        else -> Run.IDLE
    }

    /**
     * @param prev run state last seen in this app run (null: first sighting since the app started)
     * @param isNew never seen live on this phone before
     * @param baselineMs when auto-follow started recording (0: not yet; nothing is followed)
     */
    fun shouldFollow(s: SessionSummary, prev: Run?, isNew: Boolean, baselineMs: Long): Boolean {
        if (!s.isLive || baselineMs == 0L) return false
        if (isNew) {
            // A session that existed before auto-follow started is history, not a new task.
            val created = s.createdAtMs
            return created == null || created >= baselineMs - 5_000L
        }
        return when (prev) {
            // Resumed after its process ended (`claude --resume`, a reopened IDE tab).
            Run.ENDED -> true
            // Started working again (the next turn), or found working after the app restarted.
            Run.IDLE, null -> runOf(s) == Run.ACTIVE
            Run.ACTIVE -> false
        }
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
    override fun chipBackground() = s(R.string.live_chip_background)
    override fun backgroundRunning(n: Int) = c.resources.getQuantityString(R.plurals.live_background_running, n, n)
    override fun resolvedElsewhere() = s(R.string.live_resolved_elsewhere)
    override fun publicText() = s(R.string.app_name)
}
