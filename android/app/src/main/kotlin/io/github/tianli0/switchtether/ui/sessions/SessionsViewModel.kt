package io.github.tianli0.switchtether.ui.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.tianli0.switchtether.connection.ConnectionController
import io.github.tianli0.switchtether.connection.ConnectionState
import io.github.tianli0.switchtether.domain.SessionChange
import io.github.tianli0.switchtether.domain.SessionQuery
import io.github.tianli0.switchtether.domain.SessionRepository
import io.github.tianli0.switchtether.remote.protocol.SessionSummary
import io.github.tianli0.switchtether.ui.common.Loadable
import io.github.tianli0.switchtether.ui.common.UiError
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SessionScope { LIVE, HISTORY, ALL }

data class SessionsState(
    val scope: SessionScope = SessionScope.LIVE,
    val agent: String? = null,
    val project: String? = null,
    val query: String = "",
    val items: Loadable<List<SessionSummary>> = Loadable.Loading,
    val nextCursor: String? = null,
    val loadingMore: Boolean = false,
    val connection: ConnectionState = ConnectionState.Unpaired,
    /** Agents/projects seen so far (filter options). */
    val knownAgents: List<String> = emptyList(),
    val knownProjects: List<String> = emptyList(),
) {
    val visible: List<SessionSummary>
        get() = items.valueOrNull.orEmpty()
            .filter { s ->
                when (scope) {
                    SessionScope.LIVE -> s.isLive
                    SessionScope.HISTORY -> !s.isLive
                    SessionScope.ALL -> true
                }
            }
            .filter { agent == null || it.agent == agent }
            .filter { project == null || it.project == project }
            .sortedWith(compareByDescending<SessionSummary> { it.isLive }.thenByDescending { it.lastActiveAtMs ?: 0L })
}

@OptIn(FlowPreview::class)
class SessionsViewModel(private val conn: ConnectionController, private val repo: SessionRepository) : ViewModel() {
    private val _state = MutableStateFlow(SessionsState(connection = conn.state.value))
    val state: StateFlow<SessionsState> = _state.asStateFlow()
    private val queryFlow = MutableStateFlow("")
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            conn.state.distinctUntilChangedBy { it::class }.collect { s ->
                _state.update { it.copy(connection = s) }
                if (s is ConnectionState.Online) reload()
            }
        }
        viewModelScope.launch { repo.refetch.collect { reload() } }
        viewModelScope.launch { queryFlow.drop(1).debounce(300).distinctUntilChanged().collect { reload() } }
        viewModelScope.launch {
            repo.changes.collect { ch ->
                when (ch) {
                    is SessionChange.Upserted -> _state.update { st ->
                        val list = st.items.valueOrNull ?: return@update st
                        st.copy(items = Loadable.Ready(listOf(ch.session) + list.filter { it.id != ch.session.id })).withKnown()
                    }
                    is SessionChange.Removed -> _state.update { st ->
                        val list = st.items.valueOrNull ?: return@update st
                        st.copy(items = Loadable.Ready(list.filter { it.id != ch.id }))
                    }
                    else -> Unit
                }
            }
        }
    }

    fun setScope(scope: SessionScope) = _state.update { it.copy(scope = scope) }

    fun setAgent(agent: String?) {
        _state.update { it.copy(agent = agent) }
        reload()
    }

    fun setProject(project: String?) {
        _state.update { it.copy(project = project) }
        reload()
    }

    fun setQuery(q: String) {
        _state.update { it.copy(query = q) }
        queryFlow.value = q
    }

    fun reload() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            if (_state.value.items !is Loadable.Ready) _state.update { it.copy(items = Loadable.Loading) }
            val s = _state.value
            try {
                val page = repo.list(SessionQuery(s.agent, s.project, s.query.ifBlank { null }), limit = 100)
                _state.update { it.copy(items = Loadable.Ready(page.items), nextCursor = page.nextCursor).withKnown() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(items = Loadable.Failed(UiError.from(e))) }
            }
        }
    }

    fun loadMore() {
        val s = _state.value
        val cursor = s.nextCursor ?: return
        if (s.loadingMore) return
        _state.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            try {
                val page = repo.list(SessionQuery(s.agent, s.project, s.query.ifBlank { null }), cursor = cursor, limit = 100)
                _state.update { st ->
                    val existing = st.items.valueOrNull.orEmpty()
                    val ids = existing.map { it.id }.toHashSet()
                    st.copy(items = Loadable.Ready(existing + page.items.filter { it.id !in ids }), nextCursor = page.nextCursor, loadingMore = false).withKnown()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loadingMore = false) }
            }
        }
    }

    private fun SessionsState.withKnown(): SessionsState {
        val list = items.valueOrNull.orEmpty()
        return copy(
            knownAgents = (knownAgents + list.map { it.agent }).distinct().sorted(),
            knownProjects = (knownProjects + list.mapNotNull { it.project }).distinct().sorted(),
        )
    }
}
