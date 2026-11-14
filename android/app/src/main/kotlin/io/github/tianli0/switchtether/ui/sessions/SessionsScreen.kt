package io.github.tianli0.switchtether.ui.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.suqi8.coui.kmp.basic.ChipTabData
import io.github.suqi8.coui.kmp.basic.ChipTabGroup
import io.github.suqi8.coui.kmp.basic.EmptyState
import io.github.suqi8.coui.kmp.basic.FloatingActionButton
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.PullToRefresh
import io.github.suqi8.coui.kmp.basic.SmallTitle
import io.github.suqi8.coui.kmp.basic.TabRowWithContour
import io.github.suqi8.coui.kmp.basic.cardListPositionOf
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.tianli0.switchtether.ui.common.GroupCard
import io.github.tianli0.switchtether.ui.common.Loadable
import io.github.tianli0.switchtether.ui.common.LocalBottomBarSpace
import io.github.tianli0.switchtether.ui.common.LocalPageScrollBehavior
import io.github.tianli0.switchtether.ui.common.pageListPadding
import io.github.tianli0.switchtether.ui.common.pageScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.suqi8.coui.kmp.basic.TabRow
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Add
import io.github.suqi8.coui.kmp.icon.extended.Refresh
import io.github.tianli0.switchtether.R
import io.github.tianli0.switchtether.ui.common.ConnectionBanner
import io.github.tianli0.switchtether.ui.common.Formatters
import io.github.tianli0.switchtether.ui.common.LoadableContent
import io.github.tianli0.switchtether.ui.common.Page
import io.github.tianli0.switchtether.ui.common.TextInput
import io.github.tianli0.switchtether.ui.common.ToolbarIcon
import io.github.tianli0.switchtether.ui.components.EmptyMessage
import io.github.tianli0.switchtether.ui.components.FilterChip
import io.github.tianli0.switchtether.ui.components.SectionHeader
import io.github.tianli0.switchtether.ui.containerViewModel
import io.github.tianli0.switchtether.ui.nav.Route
import io.github.tianli0.switchtether.ui.appContainer

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
    val refreshing = state.items is Loadable.Ready && (state.items as Loadable.Ready).refreshing
    Page(
        title = stringResource(R.string.tab_sessions),
        actions = { ToolbarIcon(COUIIcons.Refresh, stringResource(R.string.action_refresh), onReload) },
    ) {
        ConnectionBanner(state.connection, onRetryConnection)
        TextInput(
            value = state.query,
            onValueChange = onQuery,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("session-search"),
            label = stringResource(R.string.sessions_search),
            showClearButton = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        )
        TabRowWithContour(
            tabs = listOf(stringResource(R.string.sessions_live), stringResource(R.string.sessions_history), stringResource(R.string.sessions_all)),
            selectedTabIndex = state.scope.ordinal,
            onTabSelected = { onScope(SessionScope.entries[it]) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        )
        if (state.knownAgents.size > 1) {
            val agents = listOf<String?>(null) + state.knownAgents
            ChipTabGroup(
                tabs = agents.map { ChipTabData(if (it == null) stringResource(R.string.sessions_all_agents) else Formatters.agentLabel(it)) },
                selectedIndex = agents.indexOf(state.agent).coerceAtLeast(0),
                onSelectedChange = { onAgent(agents[it]) },
                contentPadding = PaddingValues(horizontal = 16.dp),
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
        LoadableContent(state.items, onRetry = onReload) { _ ->
            val visible = state.visible
            PullToRefresh(isRefreshing = refreshing, onRefresh = onReload, topAppBarScrollBehavior = LocalPageScrollBehavior.current) {
                LazyColumn(Modifier.fillMaxSize().pageScroll().testTag("session-list"), contentPadding = pageListPadding(top = 8.dp)) {
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
                    groups.forEach { (title, list) ->
                        if (list.isEmpty()) return@forEach
                        if (title != null) item(key = "h-$title") { SmallTitle(stringResource(title)) }
                        item(key = "g-$title") {
                            GroupCard {
                                list.forEachIndexed { i, s ->
                                    SessionRow(s, onClick = { onOpen(Route.SessionDetail(s.id, s.title)) }, position = cardListPositionOf(i, list.size))
                                }
                            }
                        }
                    }
                    if (state.nextCursor != null) {
                        item(key = "more") {
                            TextButton(
                                text = if (state.loadingMore) stringResource(R.string.state_loading) else stringResource(R.string.action_load_more),
                                onClick = onLoadMore,
                                enabled = !state.loadingMore,
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
