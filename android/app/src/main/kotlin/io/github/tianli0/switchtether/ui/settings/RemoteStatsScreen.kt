package io.github.tianli0.switchtether.ui.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.basic.ChipTabData
import io.github.suqi8.coui.kmp.basic.ChipTabGroup
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.cardListPositionOf
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.tianli0.coui17.patterns.EmptyMessage
import io.github.tianli0.coui17.patterns.GroupCard
import io.github.tianli0.coui17.patterns.Page
import io.github.tianli0.coui17.patterns.PageDefaults
import io.github.tianli0.coui17.patterns.SectionHeader
import io.github.tianli0.coui17.patterns.pageListPadding
import io.github.tianli0.coui17.patterns.pageScroll
import io.github.tianli0.coui17.patterns.rememberPageListState
import io.github.tianli0.switchtether.R
import io.github.tianli0.switchtether.domain.LocalMoney
import io.github.tianli0.switchtether.remote.protocol.DayStats
import io.github.tianli0.switchtether.remote.protocol.DeviceStats
import io.github.tianli0.switchtether.remote.protocol.RemoteStatsView
import io.github.tianli0.switchtether.remote.usage.UsageFormat
import io.github.tianli0.switchtether.ui.appContainer
import io.github.tianli0.switchtether.ui.common.ConnectionBanner
import io.github.tianli0.switchtether.ui.common.Loadable
import io.github.tianli0.switchtether.ui.common.LoadableContent
import io.github.tianli0.switchtether.ui.common.RefreshToolbarIcon
import io.github.tianli0.switchtether.ui.containerViewModel
import java.time.LocalDate
import java.time.format.TextStyle

/** `Route.RemoteStats`: what the paired phones did through SwitchTether (PROTOCOL.md §7.15). */
@Composable
fun RemoteStatsScreen(onBack: () -> Unit) {
    val container = appContainer()
    val vm = containerViewModel { RemoteStatsViewModel(it.connection, it.management) }
    val state by vm.state.collectAsStateWithLifecycle()
    val conn by container.connection.state.collectAsStateWithLifecycle()
    Page(
        title = stringResource(R.string.stats_title),
        onBack = onBack,
        large = false,
        actions = { RefreshToolbarIcon(vm::load, refreshing = (state.stats as? Loadable.Ready<*>)?.refreshing == true) },
    ) {
        ConnectionBanner(conn, { container.connection.retryNow() })
        RemoteStatsContent(state, onPreset = vm::setPreset, onRetry = vm::load)
    }
}

@Composable
fun RemoteStatsContent(state: RemoteStatsState, onPreset: (StatsPreset) -> Unit, onRetry: () -> Unit) = Column(Modifier.fillMaxSize()) {
    if (state.supported == false) {
        EmptyMessage(stringResource(R.string.stats_unsupported))
        return@Column
    }
    val presets = StatsPreset.entries
    ChipTabGroup(
        tabs = presets.map { ChipTabData(stringResource(presetRes(it))) },
        selectedIndex = presets.indexOf(state.preset).coerceAtLeast(0),
        onSelectedChange = { onPreset(presets[it]) },
        contentPadding = PaddingValues(horizontal = 16.dp),
        modifier = Modifier.padding(vertical = 4.dp).testTag("stats-range"),
    )
    LoadableContent(state.stats, onRetry) { v -> StatsList(v) }
}

private fun presetRes(p: StatsPreset): Int = when (p) {
    StatsPreset.THIS_WEEK -> R.string.stats_preset_this_week
    StatsPreset.LAST_WEEK -> R.string.stats_preset_last_week
    StatsPreset.THIS_MONTH -> R.string.stats_preset_this_month
    StatsPreset.ALL -> R.string.stats_preset_all
}

@Composable
private fun StatsList(v: RemoteStatsView) {
    val t = v.totals
    LazyColumn(
        state = rememberPageListState(),
        flingBehavior = io.github.tianli0.coui17.patterns.pageFlingBehavior(),
        modifier = Modifier.fillMaxSize().pageScroll().testTag("stats"),
        contentPadding = pageListPadding(top = 4.dp),
    ) {
        item(key = "hero") { GroupCard(Modifier.animateItem()) { Hero(v) } }
        // Up to a month of days as bars; "All" over a long time lists no chart.
        if (v.days.size in 2..31 && v.days.any { it.tasks > 0 }) {
            item(key = "daily-title") { SectionHeader(stringResource(R.string.stats_daily)) }
            item(key = "daily") { GroupCard(Modifier.animateItem()) { DailyBars(v.days) } }
        }
        item(key = "time-title") { SectionHeader(stringResource(R.string.stats_time_section)) }
        item(key = "time") {
            GroupCard(Modifier.animateItem()) {
                BasicComponent(
                    title = stringResource(R.string.stats_task_time),
                    summary = stringResource(R.string.stats_task_time_summary),
                    endActions = { Value(durationText(t.taskMs)) },
                    cardListPosition = cardListPositionOf(0, 2),
                )
                BasicComponent(
                    title = stringResource(R.string.stats_foreground_time),
                    summary = stringResource(R.string.stats_foreground_time_summary),
                    endActions = { Value(durationText(t.foregroundMs)) },
                    cardListPosition = cardListPositionOf(1, 2),
                )
            }
        }
        item(key = "cost-title") { SectionHeader(stringResource(R.string.stats_cost_section)) }
        item(key = "cost") {
            GroupCard(Modifier.animateItem()) {
                val c = v.cost
                BasicComponent(
                    title = stringResource(R.string.stats_cost),
                    summary = if (c == null) {
                        stringResource(R.string.stats_cost_unavailable)
                    } else {
                        stringResource(R.string.stats_cost_summary, UsageFormat.tokens(c.totalTokens), c.requests)
                    },
                    endActions = { Value(if (c == null) "—" else UsageFormat.money(c.costUsd, LocalMoney.current, 2)) },
                    cardListPosition = cardListPositionOf(0, 1),
                )
            }
        }
        if (v.devices.isNotEmpty()) {
            item(key = "devices-title") { SectionHeader(stringResource(R.string.stats_devices)) }
            item(key = "devices") {
                GroupCard(Modifier.animateItem()) {
                    v.devices.forEachIndexed { i, d -> DeviceRow(d, i, v.devices.size) }
                }
            }
        }
        item(key = "note") {
            val since = v.since?.let { stringResource(R.string.stats_since, dateLabel(it)) + "\n" }.orEmpty()
            Text(
                since + stringResource(R.string.stats_note),
                style = COUITheme.textStyles.footnote1,
                color = COUITheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = PageDefaults.TextGutter).padding(top = 4.dp, bottom = 24.dp),
            )
        }
    }
}

@Composable
private fun Hero(v: RemoteStatsView) {
    val t = v.totals
    Column(Modifier.padding(16.dp).testTag("stats-hero")) {
        Text(stringResource(R.string.stats_tasks), style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurfaceVariantSummary)
        Text(
            t.tasks.toString(),
            style = COUITheme.textStyles.title2,
            fontWeight = FontWeight.SemiBold,
            color = COUITheme.colorScheme.onSurface,
            modifier = Modifier.testTag("stats-tasks"),
        )
        Text(
            if (t.tasks == 0L && t.messages == 0L && t.approvals == 0L) stringResource(R.string.stats_none) else stringResource(R.string.stats_tasks_hint),
            style = COUITheme.textStyles.footnote2,
            color = COUITheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.height(12.dp))
        Row {
            Figure(stringResource(R.string.stats_messages), t.messages.toString(), Modifier.weight(1f))
            Figure(stringResource(R.string.stats_approvals), t.approvals.toString(), Modifier.weight(1f))
            Figure(stringResource(R.string.stats_answers), t.answers.toString(), Modifier.weight(1f))
            Figure(stringResource(R.string.stats_sessions), v.sessions.toString(), Modifier.weight(1f))
        }
        if (t.approvals > 0) {
            Text(
                stringResource(R.string.stats_approvals_detail, t.approvalsAllowed, t.approvalsDenied),
                style = COUITheme.textStyles.footnote2,
                color = COUITheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun Figure(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(value, style = COUITheme.textStyles.title4, color = COUITheme.colorScheme.onSurface)
        Text(label, style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurfaceVariantSummary, maxLines = 1)
    }
}

@Composable
private fun Value(text: String) {
    Text(text, style = COUITheme.textStyles.body2, color = COUITheme.colorScheme.onSurface)
}

/** Tasks per day as bars, the count above each bar and the day below it. */
@Composable
private fun DailyBars(days: List<DayStats>) {
    val max = days.maxOf { it.tasks }.coerceAtLeast(1)
    val week = days.size <= 7
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    Row(
        Modifier.fillMaxWidth().height(148.dp).padding(horizontal = 12.dp, vertical = 12.dp).testTag("stats-daily"),
        horizontalArrangement = Arrangement.spacedBy(if (week) 10.dp else 3.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        days.forEachIndexed { i, d ->
            val date = runCatching { LocalDate.parse(d.date) }.getOrNull()
            val label = when {
                date == null -> ""
                week -> date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
                i == 0 || date.dayOfMonth == 1 || date.dayOfMonth % 7 == 1 -> date.dayOfMonth.toString()
                else -> ""
            }
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                if (week && d.tasks > 0) {
                    Text(d.tasks.toString(), style = COUITheme.textStyles.footnote2, color = COUITheme.colorScheme.onSurfaceVariantSummary)
                }
                val share by animateFloatAsState((d.tasks.toFloat() / max).coerceIn(0f, 1f), spring(dampingRatio = 0.9f, stiffness = 420f), label = "bar")
                Box(Modifier.fillMaxWidth().weight(1f, fill = true), contentAlignment = Alignment.BottomCenter) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(share.coerceAtLeast(0.02f))
                            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                            .background(if (d.tasks > 0) COUITheme.colorScheme.primary else COUITheme.colorScheme.onSurface.copy(alpha = 0.08f)),
                    )
                }
                Text(
                    label,
                    style = COUITheme.textStyles.footnote2,
                    color = COUITheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    softWrap = false,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun DeviceRow(d: DeviceStats, index: Int, count: Int) {
    val name = d.name ?: stringResource(R.string.stats_device_unknown)
    BasicComponent(
        title = if (d.revoked) "$name · ${stringResource(R.string.stats_device_revoked)}" else name,
        summary = stringResource(R.string.stats_device_summary, d.tasks, d.messages, durationText(d.foregroundMs)),
        cardListPosition = cardListPositionOf(index, count),
    )
}

@Composable
private fun durationText(ms: Long): String {
    val minutes = ms / 60_000
    return when {
        ms <= 0 -> stringResource(R.string.stats_duration_m, 0L)
        minutes == 0L -> stringResource(R.string.stats_duration_lt1)
        minutes < 60 -> stringResource(R.string.stats_duration_m, minutes)
        else -> stringResource(R.string.stats_duration_hm, minutes / 60, minutes % 60)
    }
}

/** `YYYY-MM-DD` in the phone's medium date format. */
private fun dateLabel(day: String): String = runCatching {
    LocalDate.parse(day).format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM))
}.getOrDefault(day)
