package io.github.tianli0.switchtether.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.tianli0.switchtether.connection.ConnectionController
import io.github.tianli0.switchtether.connection.ConnectionState
import io.github.tianli0.switchtether.domain.ManagementRepository
import io.github.tianli0.switchtether.domain.SessionChange
import io.github.tianli0.switchtether.domain.SessionQuery
import io.github.tianli0.switchtether.domain.SessionRepository
import io.github.tianli0.switchtether.remote.protocol.SessionSummary
import io.github.tianli0.switchtether.remote.protocol.UsageSummary
import io.github.tianli0.switchtether.ui.common.Loadable
import io.github.tianli0.switchtether.ui.common.loadable
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeState(
    val connection: ConnectionState = ConnectionState.Unpaired,
    val sessions: Loadable<List<SessionSummary>> = Loadable.Loading,
    val usageToday: Loadable<UsageSummary> = Loadable.Loading,
    val usageMonth: Loadable<UsageSummary> = Loadable.Loading,
    /** Subscription quotas the desktop could read (Claude, Codex); unavailable ones are left out. */
    val quotas: List<io.github.tianli0.switchtether.remote.protocol.QuotaView> = emptyList(),
    /** A pull-to-refresh is in flight (the list stays visible). */
    val refreshing: Boolean = false,
    /** What runs inside each live session (sub-agents, background tasks), by session id. */
    val activity: Map<String, SessionActivity> = emptyMap(),
    /** Set while [sessions] is an offline snapshot (see SessionsState.snapshotAtMs). */
    val snapshotAtMs: Long? = null,
) {
    val live: List<SessionSummary> get() = sessions.valueOrNull.orEmpty().filter { it.isLive }.sortedWith(RECENT_FIRST)
    /** Live sessions that wait for the user (approval / question / input): shown first on Home. */
    val attention: List<SessionSummary> get() = live.filter { it.status == "waiting" || it.waitingFor != null }
    val waitingCount: Int get() = attention.size
    private val notWaiting: List<SessionSummary> get() = live.filter { it.status != "waiting" && it.waitingFor == null }
    /** Live sessions that are actually working (not waiting, not idle). */
    val running: List<SessionSummary> get() = notWaiting.filter { !it.isIdle }
    /** Live sessions sitting idle (process open, turn finished): listed apart so "running" means running. */
    val idle: List<SessionSummary> get() = notWaiting.filter { it.isIdle }
    val recent: List<SessionSummary> get() = sessions.valueOrNull.orEmpty().filter { !it.isLive }.sortedWith(RECENT_FIRST).take(MAX_RECENT_ROWS)
    /** Ended sessions in the list: more than [recent] shows → a link to the Sessions tab. */
    val historyCount: Int get() = sessions.valueOrNull.orEmpty().count { !it.isLive }
}

/**
 * The sub-agents and background shell tasks still running in one session, from its Agent map
 * (`GET /v1/sessions/{id}/subagents`): what Home shows under the session's row, so a dev server
 * left running after the turn ended, or agents working in parallel, are visible without opening it.
 */
data class SessionActivity(
    val agents: List<io.github.tianli0.switchtether.remote.session.SubagentNode> = emptyList(),
    val tasks: List<io.github.tianli0.switchtether.remote.session.BackgroundTaskNode> = emptyList(),
) {
    val isEmpty: Boolean get() = agents.isEmpty() && tasks.isEmpty()
    val waiting: Boolean get() = agents.any { it.waiting == true }

    companion object {
        fun of(map: io.github.tianli0.switchtether.remote.session.AgentMap): SessionActivity = SessionActivity(
            // Waiting agents first: they need the user.
            agents = map.agents.filter { it.isRunning() }.sortedByDescending { it.waiting == true },
            tasks = map.backgroundTasks.filter { it.status == "running" || it.status == "pending" },
        )
    }
}

private fun io.github.tianli0.switchtether.remote.session.SubagentNode.isRunning(): Boolean =
    status == "running" || (status == null && finished == false)

/** Most recently active first; the id breaks ties so a live update never reshuffles equal rows. */
private val RECENT_FIRST: Comparator<SessionSummary> = compareByDescending<SessionSummary> { it.lastActiveAtMs ?: 0L }.thenBy { it.id }

/** How often Home refreshes running agents / tasks while something runs. */
private const val ACTIVITY_POLL_MS = 10_000L

/** Shortest gap between two Agent map fetches of one session. */
private const val ACTIVITY_MIN_GAP_MS = 4_000L

/** Live sessions whose activity Home follows (the most recently active). */
private const val MAX_ACTIVITY_SESSIONS = 8

/** Ended sessions listed on Home under "Recent". */
const val MAX_RECENT_ROWS = 5

private val QUOTA_TOOLS = listOf("claude", "codex")

private val SessionSummary.isIdle: Boolean get() = status == "idle" || status == "completed"

class HomeViewModel(
    private val conn: ConnectionController,
    private val sessions: SessionRepository,
    private val management: ManagementRepository,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val today: () -> LocalDate = { LocalDate.now(zone) },
    private val nowSec: () -> Long = { System.currentTimeMillis() / 1000 },
) : ViewModel() {
    private val _state = MutableStateFlow(HomeState(connection = conn.state.value))
    val state: StateFlow<HomeState> = _state.asStateFlow()

    /**
     * Last Agent map fetch per session (monotonic ms): at most one per [ACTIVITY_MIN_GAP_MS].
     * Declared before `init`, which can already refresh (an unconfined dispatcher runs it at once).
     */
    private val activityFetchedAt = HashMap<String, Long>()

    /** Live changes that arrive while a list request is in flight (one map per request). */
    private val changedDuringFetch = ArrayList<MutableMap<String, SessionSummary?>>()

    init {
        // Last list seen from this desktop, at once (replaced by the first successful load).
        viewModelScope.launch {
            val cached = runCatching { sessions.cachedList() }.getOrNull()
            if (cached != null && cached.items.isNotEmpty()) {
                _state.update { if (it.sessions is Loadable.Ready) it else it.copy(sessions = Loadable.Ready(cached.items), snapshotAtMs = io.github.tianli0.switchtether.ui.common.snapshotLabelFor(it.connection, cached.savedAtMs)) }
            }
        }
        viewModelScope.launch {
            conn.state.distinctUntilChangedBy { it::class }.collect { s ->
                // Online again: the label goes at once (the refresh below replaces the data; if it
                // fails, the list is the last one seen online, not an offline snapshot).
                _state.update { it.copy(connection = s).let { st -> if (s is ConnectionState.Online) st.copy(snapshotAtMs = null) else st.markedOffline() } }
                if (s is ConnectionState.Online) refresh()
            }
        }
        viewModelScope.launch { conn.state.collect { s -> _state.update { it.copy(connection = s) } } }
        viewModelScope.launch { sessions.refetch.collect { refresh() } }
        viewModelScope.launch {
            sessions.changes.collect { ch ->
                when (ch) {
                    is SessionChange.Upserted -> {
                        val before = _state.value.sessions.valueOrNull?.firstOrNull { it.id == ch.session.id }
                        changedDuringFetch.forEach { it[ch.session.id] = ch.session }
                        _state.update { st ->
                            val list = st.sessions.valueOrNull ?: return@update st
                            st.copy(sessions = Loadable.Ready(listOf(ch.session) + list.filter { it.id != ch.session.id }))
                        }
                        // A turn started or ended (agents and tasks start and finish with turns).
                        if (ch.session.isLive && (before == null || before.status != ch.session.status)) {
                            activityFetchedAt.remove(ch.session.id)
                            refreshActivity(only = ch.session.id)
                        }
                    }
                    is SessionChange.Removed -> {
                        changedDuringFetch.forEach { it[ch.id] = null }
                        _state.update { st ->
                            val list = st.sessions.valueOrNull ?: return@update st
                            st.copy(sessions = Loadable.Ready(list.filter { it.id != ch.id }))
                        }
                    }
                    SessionChange.ApprovalsChanged -> refreshSessions()
                    is SessionChange.Event -> Unit
                }
            }
        }
        viewModelScope.launch { management.changes.collect { refreshUsage() } }
    }

    /**
     * What runs inside live sessions, kept current while Home is on screen: called from the screen
     * (cancelled when it leaves), never from init — a ViewModel-owned forever-loop also kept test
     * schedulers busy forever. Refreshes while something runs (a dev server's age, an agent's
     * progress), not at all when nothing does; a status change refetches on its own.
     */
    suspend fun followActivity() {
        while (true) {
            kotlinx.coroutines.delay(ACTIVITY_POLL_MS)
            val st = _state.value
            if (st.connection is ConnectionState.Online &&
                (st.activity.values.any { !it.isEmpty } || st.live.any { it.status == "busy" })
            ) refreshActivity()
        }
    }


    /** Refetches the Agent map of live sessions ([only] = just that one, after its status changed). */
    private fun refreshActivity(only: String? = null) {
        val live = _state.value.live.take(MAX_ACTIVITY_SESSIONS)
        val ids = live.map { it.id }.toSet()
        // Ended sessions drop their lines.
        _state.update { st -> if (st.activity.keys.all { it in ids }) st else st.copy(activity = st.activity.filterKeys { it in ids }) }
        val now = System.nanoTime() / 1_000_000
        for (s in live) {
            if (only != null && s.id != only) continue
            if (now - (activityFetchedAt[s.id] ?: 0L) < ACTIVITY_MIN_GAP_MS) continue
            activityFetchedAt[s.id] = now
            viewModelScope.launch {
                val a = try {
                    SessionActivity.of(sessions.agentMap(s.id))
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // An agent without an Agent map (or an older desktop): nothing to show.
                    return@launch
                }
                _state.update { st -> if (st.activity[s.id] == a) st else st.copy(activity = st.activity + (s.id to a)) }
            }
        }
    }

    /** The connection dropped: the list stays, labelled as a snapshot of this moment. */
    private fun HomeState.markedOffline(): HomeState =
        if (io.github.tianli0.switchtether.ui.common.isAway(connection) && sessions is Loadable.Ready && snapshotAtMs == null) {
            copy(snapshotAtMs = System.currentTimeMillis())
        } else {
            this
        }

    fun refresh() {
        refreshSessions()
        refreshUsage()
    }

    private fun refreshSessions() = viewModelScope.launch {
        val changed = LinkedHashMap<String, SessionSummary?>()
        changedDuringFetch += changed
        val fetched = try {
            loadable { sessions.list(SessionQuery(), limit = 50).items }
        } finally {
            changedDuringFetch.remove(changed)
        }
        // The answer was read before these live changes: they stay on top of it.
        val r = if (fetched is Loadable.Ready && changed.isNotEmpty()) {
            Loadable.Ready(changed.values.filterNotNull() + fetched.value.filter { it.id !in changed })
        } else {
            fetched
        }
        // A failed refresh keeps the list that is already shown.
        _state.update {
            when {
                r is Loadable.Failed && it.sessions is Loadable.Ready -> it
                r is Loadable.Ready -> it.copy(sessions = r, snapshotAtMs = null).markedOffline()
                else -> it.copy(sessions = r)
            }
        }
        if (r is Loadable.Ready) refreshActivity()
    }

    /** Pull-to-refresh: reload sessions and usage, showing the indicator until the list is back. */
    fun pullRefresh() {
        if (_state.value.refreshing) return
        _state.update { it.copy(refreshing = true) }
        refreshUsage()
        viewModelScope.launch {
            try {
                refreshSessions().join()
            } finally {
                _state.update { it.copy(refreshing = false) }
            }
        }
    }

    private fun refreshUsage() {
        viewModelScope.launch {
            val d = today()
            val startToday = d.atStartOfDay(zone).toEpochSecond()
            val startMonth = d.withDayOfMonth(1).atStartOfDay(zone).toEpochSecond()
            val now = nowSec()
            val t = loadable { management.usageSummary(startToday, now) }
            val m = loadable { management.usageSummary(startMonth, now) }
            _state.update { it.copy(usageToday = t, usageMonth = m) }
        }
        viewModelScope.launch {
            val q = QUOTA_TOOLS.mapNotNull { tool ->
                try {
                    management.quota(tool).takeIf { it.available && it.tiers.isNotEmpty() }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            }
            _state.update { it.copy(quotas = q) }
        }
    }

    fun retryConnection() = conn.retryNow()
}
