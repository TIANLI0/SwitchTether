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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SessionScope { LIVE, HISTORY, ALL }

data class SessionsState(
    // Everything by default (running sessions first): history must be visible on a fresh start.
    val scope: SessionScope = SessionScope.ALL,
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
    /**
     * Set while the list is an offline snapshot ("离线快照 · 10:42"): read from the phone on a fresh
     * start, or kept from the moment the connection dropped. Null once a live load replaced it.
     */
    val snapshotAtMs: Long? = null,
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
            // Live first; among live sessions the ones waiting for the user lead (like Home).
            .sortedWith(io.github.tianli0.switchtether.domain.SESSION_ORDER)
}

class SessionsViewModel(private val conn: ConnectionController, private val repo: SessionRepository) : ViewModel() {
    /**
     * The first frame already shows the list Home loaded a moment ago (held in memory by the
     * repository): starting from Loading put a "正在加载…" frame between Home and the list on the
     * first switch to this tab. The disk snapshot below still fills in when nothing is held yet.
     */
    private val _state = MutableStateFlow(initialState(conn.state.value, repo.peekList()))
    val state: StateFlow<SessionsState> = _state.asStateFlow()
    private var browseBeforeSearch: SessionsState? = null
    private var queryJob: Job? = null
    private var loadJob: Job? = null
    private var moreJob: Job? = null

    /** Live changes that arrive while [reload] waits for the list (re-applied over the answer). */
    private var changedDuringReload: MutableMap<String, SessionSummary?>? = null

    /** "Search conversation content": full-text search on the computer (ANDROID_UI.md §28.2). */
    val content = ContentSearch(viewModelScope, { q, a ->
        (conn.apiOrNull() ?: throw io.github.tianli0.switchtether.connection.NotConnectedException()).searchSessions(q, a)
    })

    init {
        // Last list seen from this desktop, at once (replaced by the first successful load).
        viewModelScope.launch {
            val cached = runCatching { repo.cachedList() }.getOrNull()
            if (cached != null && cached.items.isNotEmpty()) {
                _state.update { if (it.items is Loadable.Ready) it else it.copy(items = Loadable.Ready(cached.items), snapshotAtMs = io.github.tianli0.switchtether.ui.common.snapshotLabelFor(it.connection, cached.savedAtMs)).withKnown() }
            }
        }
        viewModelScope.launch {
            conn.state.distinctUntilChangedBy { it::class }.collect { s ->
                _state.update { it.copy(connection = s).let { st -> if (s is ConnectionState.Online) st.copy(snapshotAtMs = null) else st.markedOffline() } }
                if (s is ConnectionState.Online) reload()
            }
        }
        viewModelScope.launch { repo.refetch.collect { reload() } }
        viewModelScope.launch {
            repo.changes.collect { ch ->
                when (ch) {
                    is SessionChange.Upserted -> {
                        changedDuringReload?.put(ch.session.id, ch.session)
                        _state.update { st ->
                            val list = st.items.valueOrNull ?: return@update st
                            st.copy(items = Loadable.Ready(upserted(list, ch.session, st.query))).withKnown()
                        }
                    }
                    is SessionChange.Removed -> {
                        changedDuringReload?.put(ch.id, null)
                        _state.update { st ->
                            val list = st.items.valueOrNull ?: return@update st
                            st.copy(items = Loadable.Ready(list.filter { it.id != ch.id }))
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    fun setScope(scope: SessionScope) = _state.update { it.copy(scope = scope) }

    fun setAgent(agent: String?) {
        if (agent == _state.value.agent) return
        rememberBrowse()
        _state.update { it.copy(agent = agent, nextCursor = null) }
        content.setQuery(_state.value.query, agent)
        reload()
    }

    fun setProject(project: String?) {
        _state.update { it.copy(project = project) }
        reload()
    }

    fun setQuery(q: String) {
        if (q == _state.value.query) return
        rememberBrowse()
        _state.update { it.copy(query = q, nextCursor = null, loadingMore = false) }
        content.setQuery(q, _state.value.agent)
        // A result for the old text must not land while the new text is being debounced.
        loadJob?.cancel()
        moreJob?.cancel()
        queryJob?.cancel()
        queryJob = viewModelScope.launch {
            delay(300)
            queryJob = null
            reload()
        }
    }

    private fun rememberBrowse() {
        val state = _state.value
        if (browseBeforeSearch == null && state.query.isEmpty() && state.agent == null && state.items is Loadable.Ready) {
            browseBeforeSearch = state
        }
    }

    fun closeSearch() {
        val browse = browseBeforeSearch
        browseBeforeSearch = null
        val current = _state.value
        if (current.query.isEmpty() && current.agent == null && browse == null) return
        _state.update {
            val restored = it.copy(query = "", agent = null, loadingMore = false, nextCursor = null)
            if (browse != null && browse.project == it.project) {
                restored.copy(items = browse.items, nextCursor = browse.nextCursor, snapshotAtMs = browse.snapshotAtMs).markedOffline()
            } else {
                restored
            }
        }
        content.setQuery("", null)
        reload()
    }

    /**
     * A live update of one session. While a search is active the desktop decides what matches, so a
     * session that is not in the result is not added to it (it is only refreshed when it is there).
     */
    private fun upserted(list: List<SessionSummary>, session: SessionSummary, query: String): List<SessionSummary> = when {
        query.isBlank() -> listOf(session) + list.filter { it.id != session.id }
        else -> list.map { if (it.id == session.id) session else it }
    }

    fun reload() {
        queryJob?.cancel()
        queryJob = null
        loadJob?.cancel()
        // A next page still in flight belongs to the list (and the filters) being replaced.
        moreJob?.cancel()
        if (_state.value.loadingMore) _state.update { it.copy(loadingMore = false) }
        loadJob = viewModelScope.launch {
            if (_state.value.items !is Loadable.Ready) _state.update { it.copy(items = Loadable.Loading) }
            val s = _state.value
            val changed = LinkedHashMap<String, SessionSummary?>()
            try {
                changedDuringReload = changed
                val page = repo.list(SessionQuery(s.agent, s.project, s.query.ifBlank { null }), limit = 100)
                // The answer was read before these live changes: they stay on top of it.
                var items = page.items.filter { it.id !in changed || changed[it.id] != null }
                changed.values.filterNotNull().forEach { items = upserted(items, it, s.query) }
                _state.update { it.copy(items = Loadable.Ready(items), nextCursor = page.nextCursor, snapshotAtMs = null).withKnown().markedOffline() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keep showing the last known list; only an empty screen turns into an error.
                _state.update { if (it.items is Loadable.Ready) it else it.copy(items = Loadable.Failed(UiError.from(e))) }
            } finally {
                if (changedDuringReload === changed) changedDuringReload = null
            }
        }
    }

    fun loadMore() {
        val s = _state.value
        val cursor = s.nextCursor ?: return
        if (s.loadingMore) return
        _state.update { it.copy(loadingMore = true) }
        moreJob = viewModelScope.launch {
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

    private fun initialState(connection: ConnectionState, held: io.github.tianli0.switchtether.data.ListSnapshot?): SessionsState {
        val st = SessionsState(connection = connection)
        if (held == null || held.items.isEmpty()) return st
        return st.copy(items = Loadable.Ready(held.items), snapshotAtMs = io.github.tianli0.switchtether.ui.common.snapshotLabelFor(connection, held.savedAtMs)).withKnown()
    }

    /** The connection dropped: what is shown stays, labelled as a snapshot of this moment. */
    private fun SessionsState.markedOffline(): SessionsState =
        if (io.github.tianli0.switchtether.ui.common.isAway(connection) && items is Loadable.Ready && snapshotAtMs == null) copy(snapshotAtMs = System.currentTimeMillis()) else this

    private fun SessionsState.withKnown(): SessionsState {
        val list = items.valueOrNull.orEmpty()
        return copy(
            knownAgents = (knownAgents + list.map { it.agent }).distinct().sorted(),
            knownProjects = (knownProjects + list.mapNotNull { it.project }).distinct().sorted(),
        )
    }
}
