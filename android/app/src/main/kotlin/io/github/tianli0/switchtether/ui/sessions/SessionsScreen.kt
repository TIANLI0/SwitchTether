package io.github.tianli0.switchtether.ui.sessions

import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Box
import io.github.tianli0.switchtether.ui.components.LinkButton
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import io.github.tianli0.switchtether.ui.common.GroupCard
import io.github.tianli0.switchtether.ui.common.Loadable
import io.github.tianli0.switchtether.ui.common.LoadableContent
import io.github.tianli0.switchtether.ui.common.ToolbarIcon
import io.github.tianli0.switchtether.ui.common.pageContainerColor
import io.github.tianli0.switchtether.ui.common.pageListPadding
import io.github.tianli0.switchtether.ui.common.pageScroll
import io.github.tianli0.switchtether.ui.common.rememberPageListState
import io.github.tianli0.switchtether.ui.containerViewModel
import io.github.tianli0.switchtether.ui.icons.AppIcons
import io.github.tianli0.switchtether.ui.nav.Route

@Composable
fun SessionsScreen(onOpen: (Route) -> Unit) {
    val container = appContainer()
    val vm = containerViewModel { SessionsViewModel(it.connection, it.sessions) }
    val state by vm.state.collectAsStateWithLifecycle()
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
) {
    val refreshing = state.items is Loadable.Ready && state.items.refreshing
    val pins by appContainer().liveUpdates.pins.collectAsStateWithLifecycle()
    var menuFor by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<io.github.tianli0.switchtether.remote.protocol.SessionSummary?>(null) }
    var expanded by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var searchExpanded by rememberSaveable { mutableStateOf(state.query.isNotEmpty()) }
    val containerColor = pageContainerColor()
    val title = stringResource(R.string.tab_sessions)
    val agents = if (state.knownAgents.size > 1) listOf<String?>(null) + state.knownAgents else null
    val agentTabs = agents?.map { ChipTabData(if (it == null) stringResource(R.string.sessions_all_agents) else Formatters.agentLabel(it)) }
    PinMenuDialog(menuFor, menuFor?.id in pins) { menuFor = null }
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
                        extraActions = { ToolbarIcon(AppIcons.Refresh, stringResource(R.string.action_refresh), onReload) },
                    )
                },
            )
        },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(top = inner.calculateTopPadding())) {
            ConnectionBanner(state.connection, onRetryConnection)
            TabRowWithContour(
                tabs = listOf(stringResource(R.string.sessions_live), stringResource(R.string.sessions_history), stringResource(R.string.sessions_all)),
                selectedTabIndex = state.scope.ordinal,
                onTabSelected = { onScope(SessionScope.entries[it]) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            )
            // Project filter: only once sessions of more than one project are known.
            if (state.knownProjects.size > 1) {
                val projects = listOf<String?>(null) + state.knownProjects
                io.github.suqi8.coui.kmp.basic.ChipTabGroup(
                    tabs = projects.map { ChipTabData(it ?: stringResource(R.string.sessions_all_projects)) },
                    selectedIndex = projects.indexOf(state.project).coerceAtLeast(0),
                    onSelectedChange = { onProject(projects[it]) },
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    modifier = Modifier.padding(bottom = 4.dp).testTag("project-filter"),
                )
            }
            LoadableContent(state.items, onRetry = onReload) { _ ->
                val visible = state.visible
                PullToRefresh(isRefreshing = refreshing, onRefresh = onReload, topAppBarScrollBehavior = null) {
                    LazyColumn(state = rememberPageListState(), modifier = Modifier.fillMaxSize().pageScroll().testTag("session-list"), contentPadding = pageListPadding(top = 8.dp)) {
                        if (visible.isEmpty()) {
                            item {
                                EmptyState(
                                    title = when (state.scope) {
                                        SessionScope.LIVE -> stringResource(R.string.sessions_empty_live)
                                        SessionScope.HISTORY -> stringResource(R.string.sessions_empty_history)
                                        SessionScope.ALL -> stringResource(R.string.sessions_empty)
                                    },
                                    modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
                                )
                            }
                        }
                        val groups = if (state.scope == SessionScope.ALL) {
                            visible.partition { it.isLive }.let { (l, h) -> listOf(R.string.sessions_live to l, R.string.sessions_history to h) }
                        } else {
                            listOf(null to visible)
                        }
                        groups.forEach { (groupTitle, list) ->
                            if (list.isEmpty()) return@forEach
                            if (groupTitle != null) item(key = "h-$groupTitle") { SmallTitle(stringResource(groupTitle)) }
                            item(key = "g-$groupTitle") {
                                // One row per conversation: resumed / continued sessions fold under the newest one.
                                val grouped = androidx.compose.runtime.remember(list) { io.github.tianli0.switchtether.domain.SessionGrouping.group(list) }
                                GroupCard {
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
