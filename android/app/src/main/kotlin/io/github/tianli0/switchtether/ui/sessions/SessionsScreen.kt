package io.github.tianli0.switchtether.ui.sessions

import io.github.suqi8.coui.kmp.basic.preciseClickAnchor
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Box
import io.github.tianli0.coui17.patterns.LinkButton
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.suqi8.coui.kmp.basic.ChipTabData
import io.github.suqi8.coui.kmp.basic.EmptyState
import io.github.suqi8.coui.kmp.basic.PullToRefresh
import io.github.suqi8.coui.kmp.basic.Scaffold
import io.github.suqi8.coui.kmp.basic.SearchFilterTopAppBar
import io.github.suqi8.coui.kmp.basic.SmallTitle
import io.github.suqi8.coui.kmp.basic.TabRowWithContour
import io.github.suqi8.coui.kmp.basic.TopAppBarSearchBar
import io.github.suqi8.coui.kmp.basic.cardListPositionOf
import io.github.tianli0.switchtether.R
import io.github.tianli0.switchtether.ui.appContainer
import io.github.tianli0.switchtether.ui.common.ConnectionBanner
import io.github.tianli0.switchtether.ui.common.Formatters
import io.github.tianli0.coui17.patterns.GroupCard
import io.github.tianli0.switchtether.ui.common.Loadable
import io.github.tianli0.switchtether.ui.common.LoadableContent
import io.github.tianli0.coui17.patterns.ToolbarIcon
import io.github.tianli0.coui17.patterns.pageContainerColor
import io.github.tianli0.coui17.patterns.pageListPadding
import io.github.tianli0.coui17.patterns.pageScroll
import io.github.tianli0.coui17.patterns.rememberPageListState
import io.github.tianli0.switchtether.ui.containerViewModel
import io.github.tianli0.switchtether.ui.icons.AppIcons
import io.github.tianli0.switchtether.ui.nav.Route

@Composable
fun SessionsScreen(onOpen: (Route) -> Unit, searchRequested: Boolean = false, onSearchHandled: () -> Unit = {}) {
    val container = appContainer()
    val vm = containerViewModel { SessionsViewModel(it.connection, it.sessions) }
    val state by vm.state.collectAsStateWithLifecycle()
    val content by vm.content.state.collectAsStateWithLifecycle()
    SessionsContent(
        state = state,
        onScope = vm::setScope,
        onAgent = vm::setAgent,
        onProject = vm::setProject,
        onQuery = vm::setQuery,
        onReload = vm::reload,
        onLoadMore = vm::loadMore,
        onRetryConnection = { container.connection.retryNow() },
        onOpen = onOpen,
        contentSearch = content,
        onContentSearch = vm.content::setEnabled,
        searchRequested = searchRequested,
        onSearchHandled = onSearchHandled,
    )
}

@Composable
fun SessionsContent(
    state: SessionsState,
    onScope: (SessionScope) -> Unit,
    onAgent: (String?) -> Unit,
    onProject: (String?) -> Unit,
    onQuery: (String) -> Unit,
    onReload: () -> Unit,
    onLoadMore: () -> Unit,
    onRetryConnection: () -> Unit,
    onOpen: (Route) -> Unit,
    contentSearch: ContentSearchUi = ContentSearchUi(),
    onContentSearch: (Boolean) -> Unit = {},
    searchRequested: Boolean = false,
    onSearchHandled: () -> Unit = {},
) {
    val refreshing = state.items is Loadable.Ready && state.items.refreshing
    val pins by appContainer().liveUpdates.pins.collectAsStateWithLifecycle()
    var menuFor by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<io.github.tianli0.switchtether.remote.protocol.SessionSummary?>(null) }
    var expanded by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var searchExpanded by rememberSaveable { mutableStateOf(state.query.isNotEmpty()) }
    // The bar's search key (Home / Sessions) opens the field once, then the request is handled.
    androidx.compose.runtime.LaunchedEffect(searchRequested) {
        if (searchRequested) {
            searchExpanded = true
            onSearchHandled()
        }
    }
    val containerColor = pageContainerColor()
    val title = stringResource(R.string.tab_sessions)
    val agents = if (state.knownAgents.size > 1) listOf<String?>(null) + state.knownAgents else null
    val agentTabs = agents?.map { ChipTabData(if (it == null) stringResource(R.string.sessions_all_agents) else Formatters.agentLabel(it)) }
    val touch = io.github.suqi8.coui.kmp.basic.rememberPreciseClickState()
    PinMenu(menuFor, menuFor?.id in pins, touch) { menuFor = null }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = containerColor,
        topBar = {
            SearchFilterTopAppBar(
                filters = agentTabs,
                selectedFilter = agents?.indexOf(state.agent)?.coerceAtLeast(0) ?: 0,
                onFilterSelected = { idx -> agents?.getOrNull(idx)?.let(onAgent) ?: onAgent(null) },
                modifier = Modifier.fillMaxWidth(),
                containerColor = containerColor,
                searchRow = {
                    TopAppBarSearchBar(
                        query = state.query,
                        onQueryChange = onQuery,
                        expanded = searchExpanded || state.query.isNotEmpty(),
                        onExpandedChange = { searchExpanded = it },
                        modifier = Modifier.fillMaxWidth().testTag("session-search"),
                        title = title,
                        placeholder = stringResource(R.string.sessions_search),
                        cancelText = stringResource(R.string.action_cancel),
                        searchButtonContentDescription = stringResource(R.string.sessions_search),
                        onCancel = {
                            onQuery("")
                            searchExpanded = false
                        },
                        gradientColor = Color.Transparent,
                        extraActions = { io.github.tianli0.switchtether.ui.common.RefreshToolbarIcon(onReload, refreshing = refreshing) },
                    )
                },
            )
        },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(top = inner.calculateTopPadding())) {
            ConnectionBanner(state.connection, onRetryConnection)
            io.github.tianli0.switchtether.ui.common.OfflineSnapshotNotice(state.snapshotAtMs)
            TabRowWithContour(
                tabs = listOf(stringResource(R.string.sessions_tab_live), stringResource(R.string.sessions_history), stringResource(R.string.sessions_all)),
                selectedTabIndex = state.scope.ordinal,
                onTabSelected = { onScope(SessionScope.entries[it]) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            )
            // Project filter: only once sessions of more than one project are known.
            io.github.tianli0.switchtether.ui.common.NoticeVisibility(state.knownProjects.size > 1) {
                val projects = listOf<String?>(null) + state.knownProjects
                io.github.suqi8.coui.kmp.basic.ChipTabGroup(
                    tabs = projects.map { ChipTabData(it ?: stringResource(R.string.sessions_all_projects)) },
                    selectedIndex = projects.indexOf(state.project).coerceAtLeast(0),
                    onSelectedChange = { onProject(projects[it]) },
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    modifier = Modifier.padding(bottom = 4.dp).testTag("project-filter"),
                )
            }
            // While searching: titles (the list below) or what was said in the sessions (§28.2).
            val searching = searchExpanded || state.query.isNotEmpty()
            // Alt+↑ / Alt+↓ beside the conversation follow the rows as shown here (§35).
            val shortcuts = io.github.tianli0.switchtether.ui.keys.LocalShortcuts.current
            val keyOrder = androidx.compose.runtime.remember(state.visible, expanded, state.scope, searching && contentSearch.enabled) {
                if (searching && contentSearch.enabled) emptyList() else sessionRowOrder(state.visible, state.scope, expanded)
            }
            androidx.compose.runtime.DisposableEffect(shortcuts, keyOrder) {
                shortcuts?.sessionOrder = keyOrder
                onDispose { if (shortcuts?.sessionOrder === keyOrder) shortcuts.sessionOrder = emptyList() }
            }
            io.github.tianli0.switchtether.ui.common.NoticeVisibility(searching) {
                io.github.suqi8.coui.kmp.basic.ChipTabGroup(
                    tabs = listOf(ChipTabData(stringResource(R.string.tab_sessions)), ChipTabData(stringResource(R.string.search_content_mode))),
                    selectedIndex = if (contentSearch.enabled) 1 else 0,
                    onSelectedChange = { onContentSearch(it == 1) },
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    modifier = Modifier.padding(bottom = 4.dp).testTag("search-mode"),
                )
            }
            if (searching && contentSearch.enabled) {
                LazyColumn(state = rememberPageListState(), modifier = Modifier.fillMaxSize().pageScroll(), contentPadding = pageListPadding(top = 8.dp)) {
                    item(key = "content") {
                        ContentSearchResults(contentSearch, onOpen = { r -> onOpen(Route.SessionDetail(r.sessionId, r.title)) })
                    }
                }
                return@Column
            }
            LoadableContent(state.items, onRetry = onReload) { _ ->
                val visible = state.visible
                PullToRefresh(isRefreshing = refreshing, onRefresh = onReload, topAppBarScrollBehavior = null) {
                    LazyColumn(state = rememberPageListState(), modifier = Modifier.fillMaxSize().pageScroll().preciseClickAnchor(touch).testTag("session-list"), contentPadding = pageListPadding(top = 8.dp)) {
                        if (visible.isEmpty()) {
                            item {
                                EmptyState(
                                    title = when (state.scope) {
                                        SessionScope.LIVE -> stringResource(R.string.sessions_empty_live)
                                        SessionScope.HISTORY -> stringResource(R.string.sessions_empty_history)
                                        SessionScope.ALL -> stringResource(R.string.sessions_empty)
                                    },
                                    // History alone said only "none found": say where it comes from.
                                    subtitle = if (state.scope == SessionScope.LIVE) null else stringResource(R.string.sessions_empty_history_hint),
                                    illustration = {
                                        io.github.suqi8.coui.kmp.basic.Icon(
                                            if (state.scope == SessionScope.LIVE) io.github.tianli0.switchtether.ui.icons.AppIcons.Terminal else io.github.tianli0.switchtether.ui.icons.AppIcons.Clock,
                                            contentDescription = null,
                                            tint = io.github.suqi8.coui.kmp.theme.COUITheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.5f),
                                            modifier = Modifier.size(56.dp),
                                        )
                                    },
                                    modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
                                )
                            }
                        }
                        // Live sessions split like Home: "运行中" only for those working or waiting,
                        // "空闲" for live ones with nothing to do. Under one "运行中" header an idle
                        // session read as busy here while Home called it idle.
                        val (live, past) = visible.partition { it.isLive }
                        val (idle, working) = live.partition { it.status == "idle" || it.status == "completed" }
                        val groups: List<Pair<String, List<io.github.tianli0.switchtether.remote.protocol.SessionSummary>>> = when (state.scope) {
                            SessionScope.HISTORY -> listOf("history" to past)
                            else -> listOf("running" to working, "idle" to idle) + if (state.scope == SessionScope.ALL) listOf("history" to past) else emptyList()
                        }
                        val showHeaders = groups.count { it.second.isNotEmpty() } > 1 || state.scope != SessionScope.HISTORY
                        groups.forEach { (groupKey, list) ->
                            if (list.isEmpty()) return@forEach
                            // A group (its title, its card) is one item: it fades in and out with the
                            // scope and glides when the group above it grows or empties.
                            if (showHeaders) item(key = "h-$groupKey") {
                                val groupTitle = when (groupKey) {
                                    "running" -> stringResource(R.string.home_running, list.size)
                                    "idle" -> stringResource(R.string.home_idle, list.size)
                                    else -> stringResource(R.string.sessions_history)
                                }
                                SmallTitle(groupTitle, Modifier.animateItem())
                            }
                            item(key = "g-$groupKey") {
                                // One row per conversation: resumed / continued sessions fold under the newest one.
                                val grouped = androidx.compose.runtime.remember(list) { io.github.tianli0.switchtether.domain.SessionGrouping.group(list) }
                                GroupCard(Modifier.animateItem()) {
                                    val rows = buildList<Pair<io.github.tianli0.switchtether.remote.protocol.SessionSummary?, io.github.tianli0.switchtether.domain.SessionGroup>> {
                                        grouped.forEach { g ->
                                            add(g.primary to g)
                                            if (g.others.isNotEmpty()) add(null to g) // fold toggle
                                            if (g.primary.id in expanded) g.others.forEach { add(it to g) }
                                        }
                                    }
                                    rows.forEachIndexed { i, (s, g) ->
                                        val pos = cardListPositionOf(i, rows.size)
                                        if (s == null) {
                                            val open = g.primary.id in expanded
                                            io.github.suqi8.coui.kmp.basic.BasicComponent(
                                                title = stringResource(if (open) R.string.sessions_related_hide else R.string.sessions_related, g.others.size),
                                                cardListPosition = pos,
                                                onClick = { expanded = if (open) expanded - g.primary.id else expanded + g.primary.id },
                                            )
                                        } else {
                                            SessionRow(s, onClick = { onOpen(Route.SessionDetail(s.id, s.title)) }, position = pos, pinned = s.id in pins, onLongClick = { menuFor = s })
                                        }
                                    }
                                }
                            }
                        }
                        if (state.nextCursor != null) {
                            item(key = "more") {
                                // The list's footer: a quiet link, as ColorOS lists end ("加载更多").
                                Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
                                    LinkButton(
                                        text = if (state.loadingMore) stringResource(R.string.state_loading) else stringResource(R.string.action_load_more),
                                        onClick = onLoadMore,
                                        enabled = !state.loadingMore,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The session rows in the order the list shows them (running, idle, then history for the scope;
 * one row per conversation, with the folded ones of an [expanded] conversation under it), as
 * (id, title) for the keyboard's previous / next session.
 */
fun sessionRowOrder(
    visible: List<io.github.tianli0.switchtether.remote.protocol.SessionSummary>,
    scope: SessionScope,
    expanded: Set<String>,
): List<Pair<String, String?>> {
    val (live, past) = visible.partition { it.isLive }
    val (idle, working) = live.partition { it.status == "idle" || it.status == "completed" }
    val groups = when (scope) {
        SessionScope.HISTORY -> listOf(past)
        SessionScope.LIVE -> listOf(working, idle)
        SessionScope.ALL -> listOf(working, idle, past)
    }
    return groups.flatMap { list ->
        io.github.tianli0.switchtether.domain.SessionGrouping.group(list).flatMap { g ->
            listOf(g.primary) + if (g.primary.id in expanded) g.others else emptyList()
        }
    }.map { it.id to it.title }
}
