package io.github.tianli0.switchtether.ui.manage

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.basic.CardListPosition
import io.github.suqi8.coui.kmp.basic.ChipTabData
import io.github.suqi8.coui.kmp.basic.ChipTabGroup
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.cardListPositionOf
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.tianli0.coui17.patterns.EmptyMessage
import io.github.tianli0.coui17.patterns.GroupCard
import io.github.tianli0.coui17.patterns.Page
import io.github.tianli0.coui17.patterns.PageDefaults
import io.github.tianli0.coui17.patterns.SectionHeader
import io.github.tianli0.coui17.patterns.ToolbarIcon
import io.github.tianli0.coui17.patterns.pageListPadding
import io.github.tianli0.coui17.patterns.pageScroll
import io.github.tianli0.coui17.patterns.rememberPageListState
import io.github.tianli0.switchtether.R
import io.github.tianli0.switchtether.domain.LocalMoney
import io.github.tianli0.switchtether.remote.protocol.ProjectUsage
import io.github.tianli0.switchtether.remote.protocol.ProjectUsageView
import io.github.tianli0.switchtether.remote.protocol.SessionUsage
import io.github.tianli0.switchtether.remote.protocol.UsageTotals
import io.github.tianli0.switchtether.remote.usage.UsageFormat
import io.github.tianli0.switchtether.remote.usage.UsageRangePreset
import io.github.tianli0.switchtether.ui.appContainer
import io.github.tianli0.switchtether.ui.common.ConnectionBanner
import io.github.tianli0.switchtether.ui.common.Formatters
import io.github.tianli0.switchtether.ui.common.Loadable
import io.github.tianli0.switchtether.ui.common.relativeTime
import io.github.tianli0.switchtether.ui.containerViewModel
import io.github.tianli0.switchtether.ui.icons.AppIcons
import io.github.tianli0.switchtether.ui.nav.Route

/**
 * "按项目" on the usage page: projects by cost, each a row with its share of the period's cost
 * that opens the project's page; logs without a session are one quiet row at the end (§26.3).
 */
fun LazyListScope.projectUsageSection(projects: Loadable<ProjectUsageView>?, onRetry: () -> Unit, onOpenProject: (String) -> Unit) {
    projects ?: return // older desktop without the API
    item(key = "projects-title") { SectionHeader(stringResource(R.string.usage_by_project)) }
    item(key = "projects") {
        GroupCard(Modifier.animateItem()) {
            SmallLoadable(projects, onRetry) { view -> ProjectRows(view, onOpenProject) }
        }
    }
    if (projects is Loadable.Ready && projects.value.projects.isNotEmpty()) {
        item(key = "projects-note") {
            Text(
                stringResource(R.string.usage_projects_note),
                style = COUITheme.textStyles.footnote1,
                color = COUITheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = PageDefaults.TextGutter).padding(bottom = 12.dp),
            )
        }
    }
}

@Composable
private fun ProjectRows(view: ProjectUsageView, onOpenProject: (String) -> Unit) {
    val extra = listOfNotNull(
        view.otherProjects?.let { stringResource(R.string.usage_other_projects) to it },
        view.unattributed?.let { stringResource(R.string.usage_unattributed) to it },
    )
    if (view.projects.isEmpty() && extra.isEmpty()) {
        EmptyMessage(stringResource(R.string.usage_no_data))
        return
    }
    val all = view.projects.sumOf { it.costUsd } + extra.sumOf { it.second.costUsd }
    val count = view.projects.size + extra.size
    Column(Modifier.testTag("usage-projects")) {
        view.projects.forEachIndexed { i, p ->
            UsageRow(
                title = p.project,
                summary = stringResource(R.string.usage_project_sessions, UsageFormat.tokens(p.totalTokens) + " tokens", p.sessionCount),
                costUsd = p.costUsd,
                share = if (all > 0) (p.costUsd / all).toFloat() else 0f,
                position = cardListPositionOf(i, count),
                onClick = { onOpenProject(p.project) },
            )
        }
        extra.forEachIndexed { i, (label, t) ->
            UsageRow(
                title = label,
                summary = UsageFormat.tokens(t.totalTokens) + " tokens",
                costUsd = t.costUsd,
                share = if (all > 0) (t.costUsd / all).toFloat() else 0f,
                position = cardListPositionOf(view.projects.size + i, count),
                onClick = null,
                quiet = true,
            )
        }
    }
}

/** A row with a cost on the right and a thin share bar under it; an arrow when it opens a page. */
@Composable
private fun UsageRow(
    title: String,
    summary: String,
    costUsd: Double,
    share: Float,
    position: CardListPosition,
    onClick: (() -> Unit)?,
    quiet: Boolean = false,
) {
    BasicComponent(
        title = title,
        summary = summary,
        endActions = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.widthIn(min = 72.dp)) {
                    Text(
                        UsageFormat.money(costUsd, LocalMoney.current, 2),
                        style = COUITheme.textStyles.body2,
                        color = if (quiet) COUITheme.colorScheme.onSurfaceVariantSummary else COUITheme.colorScheme.onSurface,
                    )
                    ShareBar(share, Modifier.padding(top = 4.dp))
                }
                if (onClick != null) {
                    Spacer(Modifier.width(4.dp))
                    Icon(AppIcons.ChevronRight, contentDescription = null, tint = COUITheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.size(16.dp))
                }
            }
        },
        cardListPosition = position,
        onClick = onClick,
    )
}

@Composable
private fun ShareBar(share: Float, modifier: Modifier = Modifier) {
    val v by androidx.compose.animation.core.animateFloatAsState(
        share.coerceIn(0f, 1f),
        androidx.compose.animation.core.spring(dampingRatio = 0.9f, stiffness = 420f),
        label = "share",
    )
    Box(modifier.width(64.dp).height(4.dp).clip(CircleShape).background(COUITheme.colorScheme.onSurface.copy(alpha = 0.06f))) {
        Box(Modifier.fillMaxWidth(v).height(4.dp).clip(CircleShape).background(COUITheme.colorScheme.primary))
    }
}

/** `Route.ProjectUsage`: one project's cost over a period and its sessions, most expensive first. */
@Composable
fun ProjectUsageScreen(project: String, onBack: () -> Unit, onOpen: (Route) -> Unit) {
    val container = appContainer()
    val vm = containerViewModel(key = "project-usage:$project") { ProjectUsageViewModel(project, it.connection, it.management) }
    val state by vm.state.collectAsStateWithLifecycle()
    val conn by container.connection.state.collectAsStateWithLifecycle()
    Page(
        title = project,
        subtitle = stringResource(R.string.project_usage_title),
        onBack = onBack,
        large = false,
        actions = { ToolbarIcon(AppIcons.Refresh, stringResource(R.string.action_refresh), vm::load) },
    ) {
        ConnectionBanner(conn, { container.connection.retryNow() })
        ProjectUsageContent(state, onPreset = vm::setPreset, onRetry = vm::load, onOpenSession = { id, title -> onOpen(Route.SessionDetail(id, title)) })
    }
}

@Composable
fun ProjectUsageContent(
    state: ProjectUsageState,
    onPreset: (UsageRangePreset) -> Unit,
    onRetry: () -> Unit,
    onOpenSession: (String, String?) -> Unit,
) = Column(Modifier.fillMaxSize()) {
    val presets = listOf(UsageRangePreset.TODAY, UsageRangePreset.DAYS_7, UsageRangePreset.DAYS_30, UsageRangePreset.ALL)
    ChipTabGroup(
        tabs = presets.map { ChipTabData(stringResource(presetRes(it))) },
        selectedIndex = presets.indexOf(state.preset).coerceAtLeast(0),
        onSelectedChange = { onPreset(presets[it]) },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
        modifier = Modifier.padding(vertical = 4.dp).testTag("project-usage-range"),
    )
    val usage = state.usage
    if (usage == null) {
        EmptyMessage(stringResource(R.string.project_usage_unsupported))
        return@Column
    }
    LazyColumn(state = rememberPageListState(), modifier = Modifier.fillMaxSize().pageScroll().testTag("project-usage"), contentPadding = pageListPadding(top = 4.dp)) {
        item(key = "hero") {
            GroupCard(Modifier.animateItem()) {
                SmallLoadable(usage, onRetry) { p ->
                    if (p == null) EmptyMessage(stringResource(R.string.project_usage_none)) else ProjectHero(p)
                }
            }
        }
        val p = (usage as? Loadable.Ready)?.value
        if (p != null && p.sessions.isNotEmpty()) {
            item(key = "sessions-title") { SectionHeader(stringResource(R.string.project_usage_sessions)) }
            item(key = "sessions") {
                GroupCard(Modifier.animateItem()) {
                    p.sessions.forEachIndexed { i, s -> SessionUsageRow(s, p.costUsd, cardListPositionOf(i, p.sessions.size), onOpenSession) }
                }
            }
        }
    }
}

private fun presetRes(p: UsageRangePreset): Int = when (p) {
    UsageRangePreset.TODAY -> R.string.usage_preset_today
    UsageRangePreset.LAST_24H -> R.string.usage_preset_24h
    UsageRangePreset.DAYS_7 -> R.string.usage_preset_7d
    UsageRangePreset.DAYS_14 -> R.string.usage_preset_14d
    UsageRangePreset.DAYS_30 -> R.string.usage_preset_30d
    UsageRangePreset.ALL -> R.string.usage_preset_all
}

@Composable
private fun ProjectHero(p: ProjectUsage) {
    val totals = UsageTotals(p.requests, p.costUsd, p.inputTokens, p.outputTokens, p.cacheReadTokens, p.cacheCreationTokens, p.lastMs)
    Column(Modifier.padding(16.dp)) {
        Text(stringResource(R.string.usage_cost), style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurfaceVariantSummary)
        Text(
            UsageFormat.money(p.costUsd, LocalMoney.current, 2),
            style = COUITheme.textStyles.title2,
            fontWeight = FontWeight.SemiBold,
            color = COUITheme.colorScheme.onSurface,
            modifier = Modifier.testTag("project-usage-cost"),
        )
        p.cwd?.let {
            Text(it, style = COUITheme.textStyles.footnote2, color = COUITheme.colorScheme.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.StartEllipsis)
        }
        Spacer(Modifier.height(12.dp))
        Row {
            Figure(stringResource(R.string.usage_requests), UsageFormat.tokens(totals.requests), Modifier.weight(1f))
            Figure(stringResource(R.string.usage_total_tokens), UsageFormat.tokens(totals.totalTokens), Modifier.weight(1f))
            Figure(stringResource(R.string.project_usage_sessions), p.sessionCount.toString(), Modifier.weight(1f))
        }
    }
}

@Composable
private fun Figure(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(value, style = COUITheme.textStyles.title4, color = COUITheme.colorScheme.onSurface)
        Text(label, style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurfaceVariantSummary)
    }
}

@Composable
private fun SessionUsageRow(s: SessionUsage, projectCost: Double, position: CardListPosition, onOpen: (String, String?) -> Unit) {
    val title = s.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.project_usage_untitled)
    UsageRow(
        title = title,
        summary = listOf(
            Formatters.agentLabel(if (s.app == "claude") "claude_code" else s.app),
            UsageFormat.tokens(s.totalTokens) + " tokens",
            relativeTime(s.lastMs),
        ).joinToString(" · "),
        costUsd = s.costUsd,
        share = if (projectCost > 0) (s.costUsd / projectCost).toFloat() else 0f,
        position = position,
        onClick = { onOpen(s.id, s.title) },
    )
}
