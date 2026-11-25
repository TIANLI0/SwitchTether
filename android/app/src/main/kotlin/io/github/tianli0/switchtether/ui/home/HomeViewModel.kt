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
    val recent: List<SessionSummary> get() = sessions.valueOrNull.orEmpty().filter { !it.isLive }.sortedWith(RECENT_FIRST).take(5)
}

/** Most recently active first; the id breaks ties so a live update never reshuffles equal rows. */
private val RECENT_FIRST: Comparator<SessionSummary> = compareByDescending<SessionSummary> { it.lastActiveAtMs ?: 0L }.thenBy { it.id }

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

    /** Live changes that arrive while a list request is in flight (one map per request). */
    private val changedDuringFetch = ArrayList<MutableMap<String, SessionSummary?>>()

    init {
        // Last list seen from this desktop, at once (replaced by the first successful load).
        viewModelScope.launch {
            val cached = runCatching { sessions.cachedList() }.getOrNull().orEmpty()
            if (cached.isNotEmpty()) _state.update { if (it.sessions is Loadable.Ready) it else it.copy(sessions = Loadable.Ready(cached)) }
        }
        viewModelScope.launch {
            conn.state.distinctUntilChangedBy { it::class }.collect { s ->
                _state.update { it.copy(connection = s) }
                if (s is ConnectionState.Online) refresh()
            }
        }
        viewModelScope.launch { conn.state.collect { s -> _state.update { it.copy(connection = s) } } }
        viewModelScope.launch { sessions.refetch.collect { refresh() } }
        viewModelScope.launch {
            sessions.changes.collect { ch ->
                when (ch) {
                    is SessionChange.Upserted -> {
                        changedDuringFetch.forEach { it[ch.session.id] = ch.session }
                        _state.update { st ->
                            val list = st.sessions.valueOrNull ?: return@update st
                            st.copy(sessions = Loadable.Ready(listOf(ch.session) + list.filter { it.id != ch.session.id }))
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
        _state.update { if (r is Loadable.Failed && it.sessions is Loadable.Ready) it else it.copy(sessions = r) }
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
