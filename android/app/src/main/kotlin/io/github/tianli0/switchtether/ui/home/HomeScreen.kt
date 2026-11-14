package io.github.tianli0.switchtether.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.basic.CardListPosition
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.SmallTitle
import io.github.suqi8.coui.kmp.basic.cardListPositionOf
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.AddCircle
import io.github.suqi8.coui.kmp.icon.extended.Info
import io.github.suqi8.coui.kmp.icon.extended.Layers
import io.github.suqi8.coui.kmp.icon.extended.Link
import io.github.suqi8.coui.kmp.icon.extended.Recent
import io.github.tianli0.switchtether.ui.common.GroupCard
import io.github.tianli0.switchtether.ui.common.pageListPadding
import io.github.tianli0.switchtether.ui.common.pageScroll
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.preference.ArrowPreference
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.tianli0.switchtether.R
import io.github.tianli0.switchtether.connection.ConnectionState
import io.github.tianli0.switchtether.remote.protocol.UsageSummary
import io.github.tianli0.switchtether.ui.common.Formatters
import io.github.tianli0.switchtether.ui.common.Loadable
import io.github.tianli0.switchtether.ui.common.Page
import io.github.tianli0.switchtether.ui.common.UiError
import io.github.tianli0.switchtether.ui.common.connectionColor
import io.github.tianli0.switchtether.ui.common.connectionText
import io.github.tianli0.switchtether.ui.common.title
import io.github.tianli0.switchtether.ui.components.EmptyMessage
import io.github.tianli0.switchtether.ui.components.SectionHeader
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
    HomeContent(state, onOpen, onSelectTab, onRetry = vm::retryConnection, onRefresh = vm::refresh)
}

@Composable
fun HomeContent(state: HomeState, onOpen: (Route) -> Unit, onSelectTab: (Tab) -> Unit, onRetry: () -> Unit, onRefresh: () -> Unit) {
    Page(title = stringResource(R.string.tab_home), subtitle = (state.connection as? ConnectionState.Online)?.desktop?.name ?: "") {
        LazyColumn(Modifier.fillMaxSize().pageScroll(), contentPadding = pageListPadding()) {
            item(key = "conn") { ConnectionCard(state.connection, onRetry) }
            item(key = "summary") {
                GroupCard {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Metric(stringResource(R.string.home_live_sessions), state.live.size.toString(), Modifier.weight(1f).clickable { onSelectTab(Tab.SESSIONS) })
                        Metric(stringResource(R.string.home_waiting), state.waitingCount.toString(), Modifier.weight(1f), highlight = state.waitingCount > 0)
                    }
                }
            }
            item(key = "usage-h") { SmallTitle(stringResource(R.string.home_usage)) }
            item(key = "usage") { UsageCard(state.usageToday, state.usageMonth, onRefresh) { onOpen(Route.Usage) } }
            item(key = "live-h") { SmallTitle(stringResource(R.string.home_live_sessions)) }
            item(key = "live") {
                GroupCard {
                    when (val s = state.sessions) {
                        Loadable.Loading -> BasicComponent(title = stringResource(R.string.state_loading), cardListPosition = CardListPosition.Full)
                        is Loadable.Failed -> BasicComponent(title = s.error.title(), summary = s.error.detail, cardListPosition = CardListPosition.Full)
                        is Loadable.Ready -> {
                            val live = state.live.take(8)
                            if (live.isEmpty()) BasicComponent(title = stringResource(R.string.home_no_live), cardListPosition = CardListPosition.Full)
                            live.forEachIndexed { i, it ->
                                SessionRow(it, onClick = { onOpen(Route.SessionDetail(it.id, it.title)) }, position = cardListPositionOf(i, live.size))
                            }
                        }
                    }
                }
            }
            if (state.recent.isNotEmpty()) {
                item(key = "recent-h") { SmallTitle(stringResource(R.string.home_recent)) }
                item(key = "recent") {
                    GroupCard {
                        state.recent.forEachIndexed { i, it ->
                            SessionRow(it, onClick = { onOpen(Route.SessionDetail(it.id, it.title)) }, position = cardListPositionOf(i, state.recent.size))
                        }
                    }
                }
            }
            item(key = "links-h") { SmallTitle(stringResource(R.string.home_quick_links)) }
            item(key = "links") {
                GroupCard {
                    val links = listOf(
                        Triple(R.string.new_session_title, COUIIcons.AddCircle, Route.NewSession),
                        Triple(R.string.manage_providers, COUIIcons.Layers, Route.Providers),
                        Triple(R.string.manage_usage, COUIIcons.Recent, Route.Usage),
                        Triple(R.string.manage_mcp, COUIIcons.Link, Route.Mcp),
                        Triple(R.string.settings_desktop, COUIIcons.Info, Route.DesktopInfo),
                    )
                    links.forEachIndexed { i, (label, icon, route) ->
                        ArrowPreference(
                            title = stringResource(label),
                            startAction = { RowIcon(icon) },
                            onClick = { onOpen(route) },
                            cardListPosition = cardListPositionOf(i, links.size),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun RowIcon(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Icon(icon, contentDescription = null, tint = COUITheme.colorScheme.primary, modifier = Modifier.size(24.dp))
}

@Composable
private fun ConnectionCard(state: ConnectionState, onRetry: () -> Unit) {
    val color = connectionColor(state)
    GroupCard(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        BasicComponent(
            title = connectionText(state),
            startAction = { Box(Modifier.size(10.dp).clip(CircleShape).background(color)) },
            centerStartAction = true,
            endActions = {
                if (state is ConnectionState.Offline || state is ConnectionState.Connecting) {
                    TextButton(text = stringResource(R.string.action_retry), onClick = onRetry)
                }
            },
            cardListPosition = CardListPosition.Full,
        )
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier, highlight: Boolean = false) {
    Column(modifier) {
        Text(value, style = COUITheme.textStyles.title2, fontWeight = FontWeight.SemiBold, color = if (highlight) LocalDevColors.current.warn else COUITheme.colorScheme.onSurface)
        Text(label, style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurfaceVariantSummary)
    }
}

@Composable
private fun UsageCard(today: Loadable<UsageSummary>, month: Loadable<UsageSummary>, onRetry: () -> Unit, onOpen: () -> Unit) {
    GroupCard(Modifier.clickable(onClick = onOpen)) {
        val failed = (today as? Loadable.Failed)?.error ?: (month as? Loadable.Failed)?.error
        if (failed != null) {
            UsageUnavailable(failed, onRetry)
        } else {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                UsageColumn(stringResource(R.string.usage_today), today, Modifier.weight(1f))
                UsageColumn(stringResource(R.string.usage_month), month, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun UsageUnavailable(error: UiError, onRetry: () -> Unit) {
    Column(Modifier.padding(16.dp)) {
        Text(stringResource(R.string.usage_unavailable), style = COUITheme.textStyles.body2, fontWeight = FontWeight.SemiBold, color = COUITheme.colorScheme.onSurface)
        Text(
            if (error.kind == UiError.Kind.BACKEND_UNAVAILABLE) stringResource(R.string.error_backend_unavailable_detail) else (error.detail ?: error.title()),
            style = COUITheme.textStyles.footnote1,
            color = COUITheme.colorScheme.onSurfaceVariantSummary,
        )
        TextButton(text = stringResource(R.string.action_retry), onClick = onRetry)
    }
}

@Composable
private fun UsageColumn(label: String, value: Loadable<UsageSummary>, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurfaceVariantSummary)
        when (value) {
            is Loadable.Ready -> {
                Text(Formatters.usd(value.value.totalCostUsd), style = COUITheme.textStyles.title3, color = COUITheme.colorScheme.onSurface)
                Text(stringResource(R.string.usage_tokens, Formatters.tokens(value.value.totalTokens)), style = DevType.codeSmall, color = COUITheme.colorScheme.onSurfaceVariantSummary)
            }
            else -> Text("…", style = COUITheme.textStyles.title3, color = COUITheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}
