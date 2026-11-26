package io.github.tianli0.switchtether.ui.home

import androidx.lifecycle.repeatOnLifecycle
import io.github.suqi8.coui.kmp.basic.preciseClickAnchor
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.res.pluralStringResource
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
    // Running agents / tasks refresh only while Home is shown and the app is in front.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.LaunchedEffect(vm, lifecycle) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) { vm.followActivity() }
    }
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
                        item(key = "att") { Box(Modifier.animateItem()) { SessionGroup(state.attention, onOpen, state.activity) } }
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
                                SessionGroup(state.running.take(MAX_ROWS), onOpen, state.activity)
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
                        item(key = "idle") { Box(Modifier.animateItem()) { SessionGroup(state.idle.take(MAX_IDLE_ROWS), onOpen, state.activity) } }
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
                    UsageRow(state.usageToday, onRefresh, cardListPositionOf(0, 4)) { onOpen(Route.Usage) }
                    ArrowPreference(
                        title = stringResource(R.string.manage_providers),
                        startAction = { RowIcon(AppIcons.Providers) },
                        onClick = { onOpen(Route.Providers) },
                        cardListPosition = cardListPositionOf(1, 4),
                    )
                    ArrowPreference(
                        title = stringResource(R.string.settings_desktop),
                        startAction = { RowIcon(AppIcons.Info) },
                        onClick = { onOpen(Route.DesktopInfo) },
                        cardListPosition = cardListPositionOf(2, 4),
                    )
                    // Manage left the bottom bar (three tabs, like the system Gallery): everything it
                    // held — providers, usage, MCP, skills, prompts, settings — is one row away here.
                    ArrowPreference(
                        title = stringResource(R.string.tab_manage),
                        summary = stringResource(R.string.home_manage_summary),
                        startAction = { RowIcon(AppIcons.Manage) },
                        onClick = { onOpen(Route.Manage) },
                        cardListPosition = cardListPositionOf(3, 4),
                        modifier = Modifier.testTag("home-manage"),
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
private fun SessionGroup(
    list: List<io.github.tianli0.switchtether.remote.protocol.SessionSummary>,
    onOpen: (Route) -> Unit,
    activity: Map<String, SessionActivity> = emptyMap(),
) {
    val pins by io.github.tianli0.switchtether.ui.appContainer().liveUpdates.pins.collectAsStateWithLifecycle()
    var menuFor by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<io.github.tianli0.switchtether.remote.protocol.SessionSummary?>(null) }
    val touch = io.github.suqi8.coui.kmp.basic.rememberPreciseClickState()
    io.github.tianli0.switchtether.ui.sessions.PinMenu(menuFor, menuFor?.id in pins, touch) { menuFor = null }
    GroupCard(Modifier.preciseClickAnchor(touch)) {
        list.forEachIndexed { i, it ->
            val running = activity[it.id]?.takeIf { a -> !a.isEmpty }
            SessionRow(
                it,
                onClick = { onOpen(Route.SessionDetail(it.id, it.title)) },
                position = cardListPositionOf(i, list.size),
                compact = true,
                pinned = it.id in pins,
                onLongClick = { menuFor = it },
                bottom = running?.let { a -> { ActivityLines(a) { onOpen(Route.SubagentTree(it.id, it.agent)) } } },
            )
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

/**
 * What runs inside a session, under its row: each sub-agent (name and what it is doing, or that
 * it waits for the user) and each background task (command and how long it has run), with the
 * same animated glyphs as the session page's capsules. At most [MAX_ACTIVITY_LINES]; a tap opens
 * the session's Agent map.
 */
@Composable
private fun ActivityLines(a: SessionActivity, onOpenMap: () -> Unit) {
    val dev = LocalDevColors.current
    val now = io.github.tianli0.switchtether.ui.common.MinuteClock.now.longValue
    val lines = buildList<@Composable () -> Unit> {
        a.agents.forEach { n ->
            add {
                val waiting = n.waiting == true
                ActivityLine(
                    glyph = {
                        if (waiting) io.github.tianli0.switchtether.ui.common.WaitingGlyph(tint = dev.warn, size = 16.dp)
                        else io.github.tianli0.switchtether.ui.common.SubagentGlyph(tint = dev.info, running = true, size = 16.dp)
                    },
                    title = n.description?.takeIf { it.isNotBlank() } ?: n.agentType?.takeIf { it.isNotBlank() } ?: stringResource(R.string.home_activity_agent),
                    detail = if (waiting) stringResource(R.string.agent_map_waiting_permission) else n.activity?.takeIf { it.isNotBlank() },
                    detailColor = if (waiting) dev.warn else COUITheme.colorScheme.onSurfaceVariantSummary,
                    trailing = io.github.tianli0.switchtether.ui.sessions.agentElapsed(n, now)?.let { io.github.tianli0.switchtether.ui.sessions.durationText(it) },
                    onClick = onOpenMap,
                )
            }
        }
        a.tasks.forEach { t ->
            add {
                ActivityLine(
                    glyph = { io.github.tianli0.switchtether.ui.common.BackgroundTaskGlyph(tint = dev.info, running = true, size = 16.dp) },
                    title = t.title?.takeIf { it.isNotBlank() } ?: t.summary?.takeIf { it.isNotBlank() } ?: stringResource(R.string.home_activity_task),
                    detail = null,
                    detailColor = COUITheme.colorScheme.onSurfaceVariantSummary,
                    trailing = io.github.tianli0.switchtether.ui.sessions.rfcMs(t.startedAt)?.let { io.github.tianli0.switchtether.ui.sessions.durationText(now - it) },
                    onClick = onOpenMap,
                )
            }
        }
    }
    // Indented to the row's text (the compact avatar and its gap), so the lines read as the row's.
    Column(Modifier.fillMaxWidth().padding(start = ACTIVITY_INDENT, end = 12.dp, top = 0.dp, bottom = 2.dp)) {
        lines.take(MAX_ACTIVITY_LINES).forEach { it() }
        if (lines.size > MAX_ACTIVITY_LINES) {
            Text(
                pluralStringResource(R.plurals.home_activity_more, lines.size - MAX_ACTIVITY_LINES, lines.size - MAX_ACTIVITY_LINES),
                style = COUITheme.textStyles.footnote2,
                color = COUITheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onOpenMap).padding(vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun ActivityLine(
    glyph: @Composable () -> Unit,
    title: String,
    detail: String?,
    detailColor: androidx.compose.ui.graphics.Color,
    trailing: String?,
    onClick: () -> Unit,
) {
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().clip(io.github.suqi8.coui.kmp.squircle.SquircleShape(8.dp)).clickable(onClick = onClick).heightIn(min = 26.dp).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        glyph()
        androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
        Text(
            androidx.compose.ui.text.buildAnnotatedString {
                append(title)
                if (detail != null) {
                    pushStyle(androidx.compose.ui.text.SpanStyle(color = detailColor))
                    append(" · ")
                    append(detail)
                    pop()
                }
            },
            style = COUITheme.textStyles.footnote1,
            color = COUITheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
            Text(trailing, style = COUITheme.textStyles.footnote2, color = COUITheme.colorScheme.onSurfaceVariantSummary, maxLines = 1)
        }
    }
}

/** Lines of running agents / tasks under one Home row before "N more". */
private const val MAX_ACTIVITY_LINES = 3

/** The compact row's avatar (32dp) and its gap to the text. */
private val ACTIVITY_INDENT = 48.dp
