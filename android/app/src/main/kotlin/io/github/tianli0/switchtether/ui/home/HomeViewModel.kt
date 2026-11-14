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
) {
    val live: List<SessionSummary> get() = sessions.valueOrNull.orEmpty().filter { it.isLive }.sortedByDescending { it.lastActiveAtMs ?: 0L }
    /** Live sessions that wait for the user (approval / question / input): shown first on Home. */
    val attention: List<SessionSummary> get() = live.filter { it.status == "waiting" || it.waitingFor != null }
    val waitingCount: Int get() = attention.size
    /** Live sessions not already listed under [attention]. */
    val running: List<SessionSummary> get() = live.filter { it.status != "waiting" && it.waitingFor == null }
    val recent: List<SessionSummary> get() = sessions.valueOrNull.orEmpty().filter { !it.isLive }.sortedByDescending { it.lastActiveAtMs ?: 0L }.take(5)
}

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

    init {
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
                    is SessionChange.Upserted -> _state.update { st ->
                        val list = st.sessions.valueOrNull ?: return@update st
                        st.copy(sessions = Loadable.Ready(listOf(ch.session) + list.filter { it.id != ch.session.id }))
                    }
                    is SessionChange.Removed -> _state.update { st ->
                        val list = st.sessions.valueOrNull ?: return@update st
                        st.copy(sessions = Loadable.Ready(list.filter { it.id != ch.id }))
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

    private fun refreshSessions() {
        viewModelScope.launch {
            val r = loadable { sessions.list(SessionQuery(), limit = 50).items }
            _state.update { it.copy(sessions = r) }
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
    }

    fun retryConnection() = conn.retryNow()
}
