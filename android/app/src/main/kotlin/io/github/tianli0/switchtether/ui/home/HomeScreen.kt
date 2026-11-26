package io.github.tianli0.switchtether.ui.home

import io.github.tianli0.coui17.patterns.PageDefaults
import io.github.tianli0.coui17.patterns.LinkButton
import io.github.tianli0.coui17.patterns.SmallButton
import io.github.tianli0.switchtether.ui.icons.AppIcons

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.basic.CardListPosition
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.SmallTitle
import io.github.suqi8.coui.kmp.basic.cardListPositionOf
import io.github.tianli0.coui17.patterns.GroupCard
import io.github.tianli0.coui17.patterns.pageListPadding
import io.github.tianli0.coui17.patterns.pageScroll
import io.github.tianli0.coui17.patterns.rememberPageListState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.preference.ArrowPreference
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.tianli0.switchtether.R
import io.github.tianli0.switchtether.connection.ConnectionState
import io.github.tianli0.switchtether.remote.protocol.UsageSummary
import io.github.tianli0.switchtether.ui.common.Formatters
import io.github.tianli0.switchtether.ui.common.Loadable
import io.github.tianli0.coui17.patterns.Page
import io.github.tianli0.switchtether.ui.common.UiError
import io.github.tianli0.switchtether.ui.common.connectionColor
import io.github.tianli0.switchtether.ui.common.connectionText
import io.github.tianli0.switchtether.ui.common.title
import io.github.tianli0.coui17.patterns.EmptyMessage
import io.github.tianli0.coui17.patterns.SectionHeader
import io.github.tianli0.switchtether.ui.containerViewModel
import io.github.tianli0.switchtether.ui.nav.Route
import io.github.tianli0.switchtether.ui.nav.Tab
import io.github.tianli0.switchtether.ui.sessions.SessionRow
import io.github.tianli0.switchtether.ui.theme.DevType
import io.github.tianli0.switchtether.ui.theme.LocalDevColors

@Composable
fun HomeScreen(onOpen: (Route) -> Unit, onSelectTab: (Tab) -> Unit) {
    val vm = containerViewModel { HomeViewModel(it.connection, it.sessions, it.management) }
    val state by vm.state.collectAsStateWithLifecycle()
    HomeContent(state, onOpen, onSelectTab, onRetry = vm::retryConnection, onRefresh = vm::pullRefresh)
}

@Composable
fun HomeContent(state: HomeState, onOpen: (Route) -> Unit, onSelectTab: (Tab) -> Unit, onRetry: () -> Unit, onRefresh: () -> Unit) {
    // Task-first: no "connected" card when healthy, attention items on top, compact rows.
    val online = state.connection as? ConnectionState.Online
    Page(title = online?.desktop?.name ?: stringResource(R.string.tab_home), large = false, immersive = true) {
        io.github.suqi8.coui.kmp.basic.PullToRefresh(
            isRefreshing = state.refreshing,
            onRefresh = onRefresh,
            topAppBarScrollBehavior = null,
            // The list runs under the title bar; the pull indicator appears below it.
            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = io.github.tianli0.coui17.patterns.LocalPageTopInset.current),
        ) {
        LazyColumn(state = rememberPageListState(), modifier = Modifier.fillMaxSize().pageScroll().testTag("home-list"), contentPadding = pageListPadding(top = 4.dp)) {
            // Only problems are shown (connecting / offline / revoked / incompatible), as a thin strip.
            item(key = "connection") { io.github.tianli0.switchtether.ui.common.ConnectionBanner(state.connection, onRetry) }
            when (val s = state.sessions) {
                Loadable.Loading -> item(key = "loading") { CompactNote(stringResource(R.string.state_loading)) }
                is Loadable.Failed -> item(key = "error") {
                    GroupCard {
                        BasicComponent(
                            title = s.error.title(),
                            summary = s.error.detail,
                            endActions = { SmallButton(stringResource(R.string.action_retry), onRefresh) },
                            cardListPosition = CardListPosition.Full,
                        )
                    }
                }
                // Each section (its title, its card) is one item: when a section comes or goes
                // (a session starts waiting, the last one finishes) it fades and the rest glide.
                is Loadable.Ready -> {
                    if (state.attention.isNotEmpty()) {
                        item(key = "att-h") { Box(Modifier.animateItem()) { CompactTitle(stringResource(R.string.home_attention, state.attention.size), highlight = true) } }
                        item(key = "att") { Box(Modifier.animateItem()) { SessionGroup(state.attention, onOpen) } }
                    }
                    item(key = "live-h") { Box(Modifier.animateItem()) { CompactTitle(stringResource(R.string.home_running, state.running.size)) } }
                    item(key = "live") {
                        Box(Modifier.animateItem()) {
                            if (state.running.isEmpty()) {
                                // One row, like a settings entry: what to do, and why the group is empty.
                                val c = io.github.tianli0.switchtether.ui.appContainer()
                                GroupCard {
                                    ArrowPreference(
                                        title = stringResource(R.string.new_session_title),
                                        summary = stringResource(R.string.home_no_live),
                                        startAction = { RowIcon(AppIcons.Add) },
                                        onClick = { c.newSessionReopen.value = true },
                                        cardListPosition = CardListPosition.Full,
                                        modifier = Modifier.testTag("home-new-session"),
                                    )
                                }
                            } else {
                                SessionGroup(state.running.take(MAX_ROWS), onOpen)
                            }
                        }
                    }
                    if (state.quotas.isNotEmpty()) {
                        item(key = "quota-h") { Box(Modifier.animateItem()) { CompactTitle(stringResource(R.string.home_quota_title)) } }
                        item(key = "quota") { Box(Modifier.animateItem()) { QuotaGlance(state.quotas) { onOpen(Route.Usage) } } }
                    }
                    if (state.idle.isNotEmpty()) {
                        item(key = "idle-h") { Box(Modifier.animateItem()) { CompactTitle(stringResource(R.string.home_idle, state.idle.size)) } }
                        // Idle sessions are background: a few are enough here, the rest are one tap away.
                        item(key = "idle") { Box(Modifier.animateItem()) { SessionGroup(state.idle.take(MAX_IDLE_ROWS), onOpen) } }
                    }
                    if (state.running.size > MAX_ROWS || state.idle.size > MAX_IDLE_ROWS) {
                        item(key = "all-live") {
                            // A quiet link under the groups, not a slab across the page.
                            Box(Modifier.animateItem().fillMaxWidth().padding(bottom = 8.dp), contentAlignment = Alignment.Center) {
                                LinkButton(stringResource(R.string.home_see_all_live, state.live.size), { onSelectTab(Tab.SESSIONS) })
                            }
                        }
                    }
                    if (state.recent.isNotEmpty()) {
                        item(key = "recent-h") { Box(Modifier.animateItem()) { CompactTitle(stringResource(R.string.home_recent)) } }
                        item(key = "recent") { Box(Modifier.animateItem()) { SessionGroup(state.recent, onOpen) } }
                        if (state.historyCount > MAX_RECENT_ROWS) {
                            item(key = "all-recent") {
                                Box(Modifier.animateItem().fillMaxWidth().padding(bottom = 8.dp), contentAlignment = Alignment.Center) {
                                    LinkButton(stringResource(R.string.home_see_all_sessions), { onSelectTab(Tab.SESSIONS) })
                                }
                            }
                        }
                    }
                }
            }
            item(key = "more-h") { Box(Modifier.animateItem()) { CompactTitle(stringResource(R.string.home_more)) } }
            item(key = "more") {
                GroupCard(Modifier.animateItem()) {
                    UsageRow(state.usageToday, onRefresh, cardListPositionOf(0, 3)) { onOpen(Route.Usage) }
                    ArrowPreference(
                        title = stringResource(R.string.manage_providers),
                        startAction = { RowIcon(AppIcons.Providers) },
                        onClick = { onOpen(Route.Providers) },
                        cardListPosition = cardListPositionOf(1, 3),
                    )
                    ArrowPreference(
                        title = stringResource(R.string.settings_desktop),
                        startAction = { RowIcon(AppIcons.Info) },
                        onClick = { onOpen(Route.DesktopInfo) },
                        cardListPosition = cardListPositionOf(2, 3),
                    )
                }
            }
        }
        }
    }
}

private const val MAX_ROWS = 8
private const val MAX_IDLE_ROWS = 3

@Composable
private fun SessionGroup(list: List<io.github.tianli0.switchtether.remote.protocol.SessionSummary>, onOpen: (Route) -> Unit) {
    val pins by io.github.tianli0.switchtether.ui.appContainer().liveUpdates.pins.collectAsStateWithLifecycle()
    var menuFor by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<io.github.tianli0.switchtether.remote.protocol.SessionSummary?>(null) }
    io.github.tianli0.switchtether.ui.sessions.PinMenuDialog(menuFor, menuFor?.id in pins) { menuFor = null }
    GroupCard {
        list.forEachIndexed { i, it ->
            SessionRow(it, onClick = { onOpen(Route.SessionDetail(it.id, it.title)) }, position = cardListPositionOf(i, list.size), compact = true, pinned = it.id in pins, onLongClick = { menuFor = it })
        }
    }
}

/** Section label tighter than SmallTitle (Home is a working list, not a settings page). */
@Composable
private fun CompactTitle(text: String, highlight: Boolean = false) {
    Text(
        text,
        style = COUITheme.textStyles.footnote1,
        fontWeight = FontWeight.Medium,
        color = if (highlight) LocalDevColors.current.warn else COUITheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(start = PageDefaults.TextGutter, end = PageDefaults.TextGutter, top = 14.dp, bottom = 6.dp).semantics { heading() },
    )
}

@Composable
private fun CompactNote(text: String) {
    Text(text, style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(horizontal = PageDefaults.TextGutter, vertical = 12.dp))
}

/** One line: today's cost and tokens (or why usage is unavailable); opens the Usage page. */
@Composable
private fun UsageRow(today: Loadable<UsageSummary>, onRetry: () -> Unit, position: CardListPosition, onOpen: () -> Unit) {
    val summary = when (today) {
        is Loadable.Ready -> stringResource(R.string.home_usage_today, io.github.tianli0.switchtether.remote.usage.UsageFormat.moneyShort(today.value.totalCostUsd, io.github.tianli0.switchtether.domain.LocalMoney.current), Formatters.tokens(today.value.totalTokens))
        is Loadable.Failed -> if (today.error.kind == UiError.Kind.BACKEND_UNAVAILABLE) stringResource(R.string.usage_unavailable) else today.error.title()
        Loadable.Loading -> stringResource(R.string.state_loading)
    }
    ArrowPreference(
        title = stringResource(R.string.home_usage),
        summary = summary,
        startAction = { RowIcon(AppIcons.Usage) },
        onClick = if (today is Loadable.Failed) onRetry else onOpen,
        cardListPosition = position,
    )
}

@Composable
fun RowIcon(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Icon(icon, contentDescription = null, tint = COUITheme.colorScheme.primary, modifier = Modifier.size(24.dp))
}
