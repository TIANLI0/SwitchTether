package io.github.tianli0.switchtether.ui.manage

import androidx.compose.foundation.layout.Box
import io.github.suqi8.coui.kmp.squircle.SquircleShape

import androidx.compose.foundation.clickable

import androidx.compose.ui.platform.testTag

import io.github.tianli0.switchtether.remote.usage.UsageFormat

import io.github.tianli0.switchtether.ui.icons.AppIcons

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.Switch
import io.github.suqi8.coui.kmp.basic.TabRowWithContour
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.layout.DialogButtonBar
import io.github.suqi8.coui.kmp.layout.DialogButtonBarAction
import io.github.suqi8.coui.kmp.preference.ArrowPreference
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.tianli0.switchtether.R
import io.github.suqi8.coui.kmp.basic.CardListPosition
import io.github.suqi8.coui.kmp.basic.cardListPositionOf
import io.github.suqi8.coui.kmp.preference.RadioButtonPreference
import io.github.suqi8.coui.kmp.preference.SwitchPreference
import io.github.tianli0.switchtether.ui.home.RowIcon
import io.github.tianli0.switchtether.ui.common.GroupCard
import io.github.tianli0.switchtether.ui.common.pageListPadding
import io.github.tianli0.switchtether.ui.common.pageScroll
import io.github.tianli0.switchtether.remote.protocol.DailyUsage
import io.github.tianli0.switchtether.remote.protocol.ProviderView
import io.github.tianli0.switchtether.remote.protocol.QuotaView
import io.github.tianli0.switchtether.remote.protocol.UsageBreakdownRow
import io.github.tianli0.switchtether.remote.protocol.UsageSummary
import io.github.tianli0.switchtether.ui.appContainer
import io.github.tianli0.switchtether.ui.chart.BarChart
import kotlinx.coroutines.launch
import io.github.tianli0.switchtether.ui.chart.BarPoint
import io.github.tianli0.switchtether.ui.common.CommandPromptDialog
import io.github.tianli0.switchtether.ui.common.ConfirmDialog
import io.github.tianli0.switchtether.ui.common.ConnectionBanner
import io.github.tianli0.switchtether.ui.common.Formatters
import io.github.tianli0.switchtether.ui.common.Loadable
import io.github.tianli0.switchtether.ui.common.LoadableContent
import io.github.tianli0.switchtether.ui.common.Page
import io.github.tianli0.switchtether.ui.common.ToolbarIcon
import io.github.tianli0.switchtether.ui.common.UiError
import io.github.tianli0.switchtether.ui.common.title
import io.github.tianli0.switchtether.ui.components.EmptyMessage
import io.github.tianli0.switchtether.ui.components.PageCard
import io.github.tianli0.switchtether.ui.components.SectionHeader
import io.github.tianli0.switchtether.ui.components.StatusPill
import io.github.tianli0.switchtether.ui.containerViewModel
import io.github.tianli0.switchtether.ui.nav.Route
import io.github.tianli0.switchtether.ui.theme.DevType
import io.github.tianli0.switchtether.ui.theme.LocalDevColors

/** Manage + Settings in one page: computer, agent management, then app preferences. */
@Composable
fun ManageScreen(onOpen: (Route) -> Unit) {
    io.github.tianli0.switchtether.ui.settings.SettingsScreen(onOpen = onOpen, manageItems = {
        item { SectionHeader(stringResource(R.string.manage_agents_section)) }
        item {
            GroupCard {
                ArrowPreference(title = stringResource(R.string.manage_providers), summary = stringResource(R.string.manage_providers_summary), startAction = { RowIcon(AppIcons.Providers) }, onClick = { onOpen(Route.Providers) }, cardListPosition = CardListPosition.Head)
                ArrowPreference(title = stringResource(R.string.manage_usage), summary = stringResource(R.string.manage_usage_summary), startAction = { RowIcon(AppIcons.Usage) }, onClick = { onOpen(Route.Usage) }, cardListPosition = CardListPosition.Middle)
                ArrowPreference(title = stringResource(R.string.manage_mcp), startAction = { RowIcon(AppIcons.Mcp) }, onClick = { onOpen(Route.Mcp) }, cardListPosition = CardListPosition.Middle)
                ArrowPreference(title = stringResource(R.string.manage_skills), startAction = { RowIcon(AppIcons.Terminal) }, onClick = { onOpen(Route.Skills) }, cardListPosition = CardListPosition.Middle)
                ArrowPreference(title = stringResource(R.string.manage_prompts), startAction = { RowIcon(AppIcons.Prompts) }, onClick = { onOpen(Route.Prompts) }, cardListPosition = CardListPosition.Tail)
            }
        }
        item { Text(stringResource(R.string.manage_write_note), style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
    })
}

@Composable
private fun AppTabs(apps: List<String>, selected: String, onSelect: (String) -> Unit) {
    TabRowWithContour(
        tabs = apps.map { Formatters.agentLabel(it) },
        selectedTabIndex = apps.indexOf(selected).coerceAtLeast(0),
        onTabSelected = { onSelect(apps[it]) },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

// ---- Providers ----------------------------------------------------------------------------------

@Composable
fun ProvidersScreen(onBack: () -> Unit) {
    val container = appContainer()
    val vm = containerViewModel { ProvidersViewModel(it.connection, it.management, it.stepUpGate) }
    var credTarget by remember { mutableStateOf<Pair<ProviderView, io.github.tianli0.switchtether.remote.protocol.SecretFieldStatus>?>(null) }
    val credLabel = stringResource(R.string.provider_credential)
    val state by vm.state.collectAsStateWithLifecycle()
    val conn by container.connection.state.collectAsStateWithLifecycle()
    val prompt by vm.commands.prompt.collectAsStateWithLifecycle()
    val busy by vm.commands.busy.collectAsStateWithLifecycle()
    var confirmSwitch by remember { mutableStateOf<ProviderView?>(null) }
    val switchLabel = stringResource(R.string.provider_switch)
    val testLabel = stringResource(R.string.provider_test)
    Page(
        title = stringResource(R.string.manage_providers),
        onBack = onBack,
        actions = { ToolbarIcon(AppIcons.Refresh, stringResource(R.string.action_refresh), vm::load) },
    ) {
        ConnectionBanner(conn, { container.connection.retryNow() })
        AppTabs(state.apps, state.app, vm::selectApp)
        LoadableContent(state.providers, onRetry = vm::load) { view ->
            LazyColumn(Modifier.fillMaxSize().pageScroll(), contentPadding = pageListPadding()) {
                if (view.providers.isEmpty()) item { EmptyMessage(stringResource(R.string.provider_empty)) }
                items(view.providers, key = { it.id }) { p ->
                    ProviderCard(
                        p = p,
                        current = p.id == view.currentId,
                        test = state.testResults[p.id],
                        busy = busy,
                        onSwitch = { confirmSwitch = p },
                        onTest = { vm.test("$testLabel: ${p.name}", p.id) },
                        onCredential = { f -> credTarget = p to f },
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
    ConfirmDialog(
        show = confirmSwitch != null,
        title = stringResource(R.string.provider_switch_title),
        message = stringResource(R.string.provider_switch_body, confirmSwitch?.name.orEmpty(), Formatters.agentLabel(state.app)),
        confirmText = stringResource(R.string.provider_switch),
        onConfirm = {
            confirmSwitch?.let { vm.switchTo("$switchLabel: ${it.name}", it.id) }
            confirmSwitch = null
        },
        onDismiss = { confirmSwitch = null },
    )
    CommandPromptDialog(prompt, vm.commands::checkStatus, vm.commands::retry, vm.commands::dismiss)
    credTarget?.let { (p, f) ->
        var value by remember(p.id, f.path) { mutableStateOf("") }
        io.github.suqi8.coui.kmp.window.WindowDialog(show = true, title = "$credLabel · ${f.name}", onDismissRequest = { credTarget = null }) {
            Column(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.provider_credential_note, p.name), style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurfaceVariantSummary)
                    io.github.suqi8.coui.kmp.basic.TextField(
                        value = value, onValueChange = { value = it }, label = f.name, singleLine = true, showPasswordToggle = true,
                        backgroundMode = io.github.suqi8.coui.kmp.basic.TextFieldMode.Rectangle,
                    )
                }
                DialogButtonBar(
                    negative = DialogButtonBarAction(text = stringResource(R.string.action_cancel), onClick = { credTarget = null }),
                    positive = DialogButtonBarAction(
                        text = stringResource(R.string.files_save),
                        enabled = value.isNotBlank(),
                        onClick = { vm.setCredential("$credLabel: ${p.name}", p.id, f.path, value); credTarget = null },
                    ),
                )
            }
        }
    }
}

@Composable
private fun ProviderCard(
    p: ProviderView,
    current: Boolean,
    test: io.github.tianli0.switchtether.remote.protocol.OperationResult?,
    busy: String?,
    onSwitch: () -> Unit,
    onTest: () -> Unit,
    onCredential: (io.github.tianli0.switchtether.remote.protocol.SecretFieldStatus) -> Unit = {},
) {
    val dev = LocalDevColors.current
    GroupCard {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.name, style = COUITheme.textStyles.body1, fontWeight = FontWeight.SemiBold, color = COUITheme.colorScheme.onSurface, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (current) StatusPill(stringResource(R.string.provider_current), dev.ok)
            }
            listOfNotNull(p.category, p.websiteUrl).takeIf { it.isNotEmpty() }?.let {
                Text(it.joinToString(" · "), style = DevType.codeSmall, color = COUITheme.colorScheme.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            p.notes?.takeIf { it.isNotBlank() }?.let { Text(it, style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurfaceVariantSummary, maxLines = 3) }
            // Secrets: only configured/hint as reported by the server; values are never requested.
            p.secretFields.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${s.name}: " + if (s.configured) stringResource(R.string.provider_secret_set, s.hint ?: "••••") else stringResource(R.string.provider_secret_unset),
                        style = DevType.codeSmall,
                        color = if (s.configured) dev.gutter else dev.warn,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(text = stringResource(R.string.provider_update), onClick = { onCredential(s) }, enabled = busy == null)
                }
            }
            test?.let { t ->
                Text(
                    (if (t.ok) "✓ " else "✗ ") + listOfNotNull(t.message, t.latencyMs?.let { "${it} ms" }).joinToString(" · ").ifEmpty { stringResource(if (t.ok) R.string.provider_test_ok else R.string.provider_test_failed) },
                    style = DevType.codeSmall,
                    color = if (t.ok) dev.ok else dev.danger,
                )
            }
            Row {
                TextButton(text = stringResource(R.string.provider_test), onClick = onTest, enabled = busy == null)
                Spacer(Modifier.size(8.dp))
                if (!current) TextButton(text = stringResource(R.string.provider_switch), onClick = onSwitch, enabled = busy == null)
            }
        }
    }
}

// ---- Usage --------------------------------------------------------------------------------------

@Composable
fun UsageScreen(onBack: () -> Unit) {
    val container = appContainer()
    val vm = containerViewModel { UsageViewModel(it.connection, it.management) }
    val state by vm.state.collectAsStateWithLifecycle()
    val conn by container.connection.state.collectAsStateWithLifecycle()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // The desktop's currency may have changed since we connected: re-read it with the numbers.
    androidx.compose.runtime.LaunchedEffect(Unit) { container.money.refresh() }
    val refresh = { vm.load(); scope.launch { container.money.refresh() }; Unit }
    Page(title = stringResource(R.string.manage_usage), onBack = onBack, actions = { ToolbarIcon(AppIcons.Refresh, stringResource(R.string.action_refresh), refresh) }) {
        ConnectionBanner(conn, { container.connection.retryNow() })
        val presets = io.github.tianli0.switchtether.remote.usage.UsageRangePreset.entries
        io.github.suqi8.coui.kmp.basic.ChipTabGroup(
            tabs = presets.map { io.github.suqi8.coui.kmp.basic.ChipTabData(presetLabel(it)) },
            selectedIndex = presets.indexOf(state.preset),
            onSelectedChange = { vm.setPreset(presets[it]) },
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
            modifier = Modifier.padding(vertical = 4.dp).testTag("usage-range"),
        )
        // Apps switched off on the desktop (`visibleApps`) are not offered here either.
        val apps = listOf<String?>(null) + DEFAULT_APPS.filter { it !in state.hiddenApps }
        io.github.suqi8.coui.kmp.basic.ChipTabGroup(
            tabs = apps.map { io.github.suqi8.coui.kmp.basic.ChipTabData(if (it == null) stringResource(R.string.usage_all_apps) else Formatters.agentLabel(it).let { l -> if (it == "claude") "Claude" else l }) },
            selectedIndex = apps.indexOf(state.app).coerceAtLeast(0),
            onSelectedChange = { vm.setApp(apps[it]) },
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
            modifier = Modifier.padding(vertical = 2.dp),
        )
        UsageContent(state, vm::load, vm::setMetric, onMoreLogs = vm::loadMoreLogs, onOpenLog = vm::openLog)
    }
    RequestLogSheet(state.selectedLog, java.time.ZoneId.systemDefault(), onDismiss = { vm.openLog(null) })
}

@Composable
private fun presetLabel(p: io.github.tianli0.switchtether.remote.usage.UsageRangePreset): String = stringResource(
    when (p) {
        io.github.tianli0.switchtether.remote.usage.UsageRangePreset.TODAY -> R.string.usage_preset_today
        io.github.tianli0.switchtether.remote.usage.UsageRangePreset.LAST_24H -> R.string.usage_preset_24h
        io.github.tianli0.switchtether.remote.usage.UsageRangePreset.DAYS_7 -> R.string.usage_preset_7d
        io.github.tianli0.switchtether.remote.usage.UsageRangePreset.DAYS_14 -> R.string.usage_preset_14d
        io.github.tianli0.switchtether.remote.usage.UsageRangePreset.DAYS_30 -> R.string.usage_preset_30d
        io.github.tianli0.switchtether.remote.usage.UsageRangePreset.ALL -> R.string.usage_preset_all
    },
)

@Composable
fun UsageContent(
    state: UsageState,
    onRetry: () -> Unit,
    onMetric: (UsageMetric) -> Unit = {},
    onMoreLogs: () -> Unit = {},
    onOpenLog: (io.github.tianli0.switchtether.remote.protocol.RequestLog) -> Unit = {},
) {
    // One honest page-level error instead of repeating it on every card (e.g. 503 from CC Switch).
    (state.summary as? Loadable.Failed)?.let { failed ->
        LoadableContent(failed, onRetry = onRetry) {}
        return
    }
    LazyColumn(Modifier.fillMaxSize().pageScroll().testTag("usage-list"), contentPadding = pageListPadding(top = 4.dp)) {
        item { PageCard { SmallLoadable(state.summary, onRetry) { s -> SummaryHero(s) } } }
        item { SyncNote(state.lastSyncMs, state.zone) }
        item { SectionHeader(stringResource(if (state.hourly) R.string.usage_trend_hourly else R.string.usage_trend_daily)) }
        item {
            PageCard {
                SmallLoadable(state.trend, onRetry) { days -> TrendChart(days, state.metric, state.hourly, onMetric) }
            }
        }
        item { SectionHeader(stringResource(R.string.usage_by_provider)) }
        item { PageCard { SmallLoadable(state.providers, onRetry) { BreakdownTable(it, showAvgCost = false) } } }
        item { SectionHeader(stringResource(R.string.usage_by_model)) }
        item { PageCard { SmallLoadable(state.models, onRetry) { BreakdownTable(it, showAvgCost = true) } } }
        item { SectionHeader(stringResource(R.string.usage_quota)) }
        items(state.quotas, key = { it.first }) { (_, q) ->
            PageCard { SmallLoadable(q, onRetry) { QuotaCard(it) } }
        }
        // Request log (newer desktops; an older one without the API hides the section).
        state.logs?.let { logs ->
            item { SectionHeader(stringResource(R.string.usage_logs)) }
            if (logs.isEmpty()) {
                item {
                    PageCard {
                        if (state.logsError && !state.logsLoading) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                Text(stringResource(R.string.usage_logs_failed), style = COUITheme.textStyles.body2, color = COUITheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.weight(1f))
                                TextButton(text = stringResource(R.string.action_retry), onClick = onMoreLogs)
                            }
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp)) {
                                val loading = state.logsLoading || state.logsPage < 0
                                if (loading) {
                                    io.github.suqi8.coui.kmp.basic.RotatingProgressIndicator(color = COUITheme.colorScheme.primary, size = 16.dp, ringDiameter = 12.8.dp, strokeWidth = 2.dp)
                                    Spacer(Modifier.size(8.dp))
                                }
                                Text(
                                    stringResource(if (loading) R.string.state_loading else R.string.usage_logs_empty),
                                    style = COUITheme.textStyles.body2,
                                    color = COUITheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                        }
                    }
                }
            } else {
                item(key = "logs") {
                    io.github.tianli0.switchtether.ui.common.GroupCard {
                        logs.forEachIndexed { i, l ->
                            RequestLogRow(l, java.time.ZoneId.systemDefault(), io.github.suqi8.coui.kmp.basic.cardListPositionOf(i, logs.size), onClick = { onOpenLog(l) })
                        }
                    }
                }
                if (state.logsHaveMore) {
                    item {
                        TextButton(
                            text = when {
                                state.logsLoading -> stringResource(R.string.state_loading)
                                state.logsError -> stringResource(R.string.usage_logs_failed_more)
                                else -> stringResource(R.string.usage_logs_more, logs.size, state.logsTotal)
                            },
                            onClick = onMoreLogs,
                            modifier = Modifier.fillMaxWidth().testTag("logs-more"),
                        )
                    }
                }
            }
        }
        item {
            Text(stringResource(R.string.usage_tz_note), style = COUITheme.textStyles.footnote2, color = COUITheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/** Compact in-card loadable (cards keep their place; errors explain themselves). */
@Composable
private fun <T> SmallLoadable(state: Loadable<T>, onRetry: () -> Unit, content: @Composable (T) -> Unit) {
    when (state) {
        Loadable.Loading -> Text(stringResource(R.string.state_loading), style = COUITheme.textStyles.body2, color = COUITheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(16.dp))
        is Loadable.Failed -> Column(Modifier.padding(16.dp)) {
            Text(state.error.title(), style = COUITheme.textStyles.body2, fontWeight = FontWeight.SemiBold, color = COUITheme.colorScheme.onSurface)
            Text(
                if (state.error.kind == UiError.Kind.BACKEND_UNAVAILABLE) stringResource(R.string.error_backend_unavailable_detail) else state.error.detail.orEmpty(),
                style = COUITheme.textStyles.footnote1,
                color = COUITheme.colorScheme.onSurfaceVariantSummary,
            )
            TextButton(text = stringResource(R.string.action_retry), onClick = onRetry)
        }
        is Loadable.Ready -> content(state.value)
    }
}

/** The desktop's hero: cost, requests, total tokens, cache hit rate; token details expandable. */
@Composable
private fun SummaryHero(s: UsageSummary) {
    val empty = s.totalRequests == 0L
    fun dash(v: String) = if (empty) "—" else v
    var open by remember { mutableStateOf(false) }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column {
            Text(stringResource(R.string.usage_cost), style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurfaceVariantSummary)
            Text(dash(UsageFormat.money(s.totalCostUsd, io.github.tianli0.switchtether.domain.LocalMoney.current, 2)), style = COUITheme.textStyles.title2, fontWeight = FontWeight.SemiBold, color = COUITheme.colorScheme.onSurface)
        }
        Row {
            Stat(stringResource(R.string.usage_requests), dash(UsageFormat.tokens(s.totalRequests)), Modifier.weight(1f))
            Stat(stringResource(R.string.usage_total_tokens), dash(UsageFormat.tokens(s.totalTokens)), Modifier.weight(1f))
            Stat(stringResource(R.string.usage_cache_hit), dash(UsageFormat.hitRate(s.cacheHitRate)), Modifier.weight(1f))
        }
        Row(
            Modifier.fillMaxWidth().clip(SquircleShape(12.dp)).clickable(role = androidx.compose.ui.semantics.Role.Button) { open = !open },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.usage_details), style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.primary, modifier = Modifier.weight(1f))
            io.github.suqi8.coui.kmp.basic.ExpandArrow(expanded = open)
        }
        if (open) {
            Row {
                Stat(stringResource(R.string.usage_input), dash(UsageFormat.tokens(s.inputTokens)), Modifier.weight(1f))
                Stat(stringResource(R.string.usage_output), dash(UsageFormat.tokens(s.outputTokens)), Modifier.weight(1f))
            }
            Row {
                Stat(stringResource(R.string.usage_cache_write), dash(UsageFormat.tokens(s.cacheCreationTokens)), Modifier.weight(1f))
                Stat(stringResource(R.string.usage_cache_read), dash(UsageFormat.tokens(s.cacheReadTokens)), Modifier.weight(1f))
            }
            Stat(stringResource(R.string.usage_success), dash(UsageFormat.successRate(s.successRate)), Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(value, style = COUITheme.textStyles.title4, color = COUITheme.colorScheme.onSurface)
        Text(label, style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurfaceVariantSummary)
    }
}

/** Bucket label: HH:mm for hourly ranges, MM-dd for days, read from the server's RFC3339 date (desktop-local). */
private fun bucketLabel(date: String, hourly: Boolean): String = runCatching {
    val t = java.time.OffsetDateTime.parse(date)
    if (hourly) "%02d:%02d".format(t.hour, t.minute) else "%02d-%02d".format(t.monthValue, t.dayOfMonth)
}.getOrElse { date.take(10).let { if (it.length == 10) it.substring(5) else it } }

@Composable
private fun TrendChart(days: List<DailyUsage>, metric: UsageMetric, hourly: Boolean, onMetric: (UsageMetric) -> Unit) {
    if (days.isEmpty() || days.all { it.requests == 0L && it.tokens == 0L }) {
        EmptyMessage(stringResource(R.string.usage_no_data))
        return
    }
    val metrics = UsageMetric.entries
    Column(Modifier.padding(vertical = 12.dp)) {
        io.github.suqi8.coui.kmp.basic.ChipTabGroup(
            tabs = metrics.map {
                io.github.suqi8.coui.kmp.basic.ChipTabData(
                    stringResource(
                        when (it) {
                            UsageMetric.TOKENS -> R.string.usage_metric_tokens
                            UsageMetric.COST -> R.string.usage_metric_cost
                            UsageMetric.REQUESTS -> R.string.usage_metric_requests
                        },
                    ),
                )
            },
            selectedIndex = metrics.indexOf(metric),
            onSelectedChange = { onMetric(metrics[it]) },
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
        )
        val value: (DailyUsage) -> Double = when (metric) {
            UsageMetric.TOKENS -> { d -> d.tokens.toDouble() }
            UsageMetric.COST -> { d -> d.costUsd ?: 0.0 }
            UsageMetric.REQUESTS -> { d -> d.requests.toDouble() }
        }
        val money = io.github.tianli0.switchtether.domain.LocalMoney.current
        // Bars carry the value label (desktop chart tooltip: money(value, 4)).
        val fmt: (Double) -> String = when (metric) {
            UsageMetric.COST -> { v -> UsageFormat.money(v, money, 4) }
            else -> { v -> UsageFormat.tokens(v.toLong()) }
        }
        val points = days.map { d -> BarPoint(bucketLabel(d.date, hourly), value(d)) }
        val summary = stringResource(R.string.usage_trend_a11y, days.size) + " " + points.joinToString("; ") { "${it.label}: ${fmt(it.value)}" }
        Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            BarChart(points = points, summary = summary, formatValue = fmt)
        }
    }
}

/**
 * Desktop `getUsageProviderLabel`: placeholder names of usage read from session logs
 * ("Claude (Session)") are shown as "Claude · 会话日志"; an empty name is "未知供应商".
 */
@Composable
private fun providerLabel(name: String): String {
    if (name.isBlank() || name == "?") return stringResource(R.string.usage_unknown_provider)
    Regex("""^(.+) \(Session\)$""").matchEntire(name)?.let { return stringResource(R.string.usage_session_provider, it.groupValues[1]) }
    Regex("""^(.+) \(OAuth\)$""").matchEntire(name)?.let { return stringResource(R.string.usage_session_account_provider, it.groupValues[1]) }
    return name
}

/** Provider / model rows: name, then requests · success · speed · first token; cost and tokens on the right. */
@Composable
private fun BreakdownTable(rows: List<UsageBreakdownRow>, showAvgCost: Boolean) {
    if (rows.isEmpty()) {
        EmptyMessage(stringResource(R.string.usage_no_data))
        return
    }
    Column(Modifier.padding(vertical = 6.dp)) {
        rows.take(30).forEach { r ->
            val details = listOfNotNull(
                r.app?.let { Formatters.agentLabel(it) },
                stringResource(R.string.usage_requests_n, r.requests.toInt()),
                r.successRate?.let { stringResource(R.string.usage_success_n, UsageFormat.successRate(it)) },
                UsageFormat.tokensPerSecond(r.tokensPerSecond)?.let { (if (r.speedEstimated) "≈" else "") + stringResource(R.string.usage_speed, it) },
                UsageFormat.firstTokenSeconds(r.firstTokenMs)?.let { stringResource(R.string.usage_first_token, (if (r.firstTokenEstimated) "≈" else "") + it) },
                if (showAvgCost) r.avgCostPerRequest?.let { stringResource(R.string.usage_avg_cost, UsageFormat.money(it, io.github.tianli0.switchtether.domain.LocalMoney.current, 4)) } else null,
            ).joinToString(" · ")
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (r.app != null) providerLabel(r.label) else r.label, style = COUITheme.textStyles.body2, fontWeight = FontWeight.Medium, color = COUITheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(details, style = COUITheme.textStyles.footnote2, color = COUITheme.colorScheme.onSurfaceVariantSummary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.size(10.dp))
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.widthIn(min = 72.dp)) {
                    Text(UsageFormat.money(r.costUsd, io.github.tianli0.switchtether.domain.LocalMoney.current, 2), style = COUITheme.textStyles.body2, color = COUITheme.colorScheme.onSurface)
                    Text(UsageFormat.tokens(r.tokens), style = COUITheme.textStyles.footnote2, color = COUITheme.colorScheme.onSurfaceVariantSummary)
                }
            }
        }
    }
}

/** Desktop tier ids (`five_hour`, `seven_day_opus`, …) as readable window names. */
@Composable
private fun quotaTierLabel(name: String): String {
    val model = name.substringAfter("seven_day_", "").takeIf { it.isNotEmpty() }
        ?.replaceFirstChar { it.uppercase() }
    return when {
        name == "five_hour" -> stringResource(R.string.quota_tier_5h)
        name == "seven_day" || name == "weekly_limit" -> stringResource(R.string.quota_tier_week)
        model != null -> stringResource(R.string.quota_tier_week) + " · " + model
        name == "monthly" || name == "30_day" -> stringResource(R.string.quota_tier_month)
        name == "credits" -> stringResource(R.string.quota_tier_credits)
        name.startsWith("gemini_") -> name.removePrefix("gemini_").split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }.let { "Gemini $it" }
        else -> name.replace('_', ' ')
    }
}

/** "Resets today 18:00 · in 3 h 20 min", in the device's time zone; null when unknown. */
@Composable
private fun quotaResetText(raw: String?): String? {
    val at = Formatters.parseInstant(raw) ?: return raw?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.quota_resets, it) }
    val now = java.time.Instant.now()
    val (days, clock) = Formatters.localDayAndTime(at, now)
    val whenText = when (days) {
        0 -> stringResource(R.string.quota_day_today, clock)
        1 -> stringResource(R.string.quota_day_tomorrow, clock)
        else -> clock
    }
    val mins = java.time.Duration.between(now, at).toMinutes()
    val left = when {
        mins <= 0 -> return stringResource(R.string.quota_reset_due)
        mins < 60 -> stringResource(R.string.quota_left_minutes, mins.toInt())
        mins < 24 * 60 -> stringResource(R.string.quota_left_hours, (mins / 60).toInt(), (mins % 60).toInt())
        else -> stringResource(R.string.quota_left_days, (mins / 1440).toInt(), ((mins % 1440) / 60).toInt())
    }
    return stringResource(R.string.quota_resets_at, whenText, left)
}

@Composable
private fun QuotaCard(q: QuotaView) {
    val dev = LocalDevColors.current
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(Formatters.agentLabel(q.tool) + (q.planName?.let { " · $it" } ?: ""), style = COUITheme.textStyles.body1, color = COUITheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            if (!q.available) StatusPill(stringResource(R.string.quota_unavailable), dev.neutral)
        }
        if (!q.available) {
            // Never fabricated: show the server's reason verbatim.
            Text(q.reason ?: stringResource(R.string.quota_no_reason), style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurfaceVariantSummary)
        } else {
            q.tiers.forEach { t ->
                val pct = t.utilizationPercent
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Text(quotaTierLabel(t.name), style = COUITheme.textStyles.body2, color = COUITheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                    Text(
                        pct?.let { "%.0f%%".format(it) } ?: "—",
                        style = COUITheme.textStyles.body2,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                        color = when {
                            pct == null -> COUITheme.colorScheme.onSurfaceVariantSummary
                            pct >= 90 -> dev.danger
                            pct >= 70 -> dev.warn
                            else -> COUITheme.colorScheme.onSurface
                        },
                    )
                }
                io.github.suqi8.coui.kmp.basic.LinearProgressIndicator(progress = ((pct ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f), modifier = Modifier.fillMaxWidth())
                quotaResetText(t.resetsAt)?.let {
                    Text(it, style = COUITheme.textStyles.footnote2, color = COUITheme.colorScheme.onSurfaceVariantSummary)
                }
            }
        }
    }
}

// ---- MCP / skills / prompts -------------------------------------------------------------------------

@Composable
fun ToggleListScreen(kind: ToggleListKind, onBack: () -> Unit) {
    val container = appContainer()
    val vm = containerViewModel(key = "toggle:$kind") { ToggleListViewModel(kind, it.connection, it.management) }
    val state by vm.state.collectAsStateWithLifecycle()
    val conn by container.connection.state.collectAsStateWithLifecycle()
    val prompt by vm.commands.prompt.collectAsStateWithLifecycle()
    val busy by vm.commands.busy.collectAsStateWithLifecycle()
    var pending by remember { mutableStateOf<Triple<String, String, Boolean>?>(null) }
    val title = stringResource(
        when (kind) {
            ToggleListKind.MCP -> R.string.manage_mcp
            ToggleListKind.SKILLS -> R.string.manage_skills
            ToggleListKind.PROMPTS -> R.string.manage_prompts
        },
    )
    Page(title = title, onBack = onBack, actions = { ToolbarIcon(AppIcons.Refresh, stringResource(R.string.action_refresh), vm::load) }) {
        ConnectionBanner(conn, { container.connection.retryNow() })
        AppTabs(DEFAULT_APPS, state.app, vm::selectApp)
        if (kind == ToggleListKind.PROMPTS) {
            LoadableContent(state.prompts, onRetry = vm::load) { prompts ->
                LazyColumn(Modifier.fillMaxSize().pageScroll(), contentPadding = pageListPadding()) {
                    if (prompts.isEmpty()) item { EmptyMessage(stringResource(R.string.toggle_empty)) }
                    if (prompts.isNotEmpty()) item(key = "prompts") {
                        GroupCard {
                            prompts.forEachIndexed { i, p ->
                                RadioButtonPreference(
                                    title = p.name,
                                    summary = p.description,
                                    selected = p.enabled,
                                    onClick = { if (!p.enabled && busy == null) pending = Triple(p.id, p.name, true) },
                                    cardListPosition = cardListPositionOf(i, prompts.size),
                                )
                            }
                        }
                    }
                }
            }
        } else {
            LoadableContent(state.items, onRetry = vm::load) { items ->
                LazyColumn(Modifier.fillMaxSize().pageScroll(), contentPadding = pageListPadding()) {
                    if (items.isEmpty()) item { EmptyMessage(stringResource(R.string.toggle_empty)) }
                    if (items.isNotEmpty()) item(key = "items") {
                        GroupCard {
                            items.forEachIndexed { i, it ->
                                val on = it.apps[state.app] == true
                                // The switch shows the desktop state; a tap only opens the confirm dialog.
                                SwitchPreference(
                                    checked = on,
                                    onCheckedChange = { _ -> pending = Triple(it.id, it.name, !on) },
                                    title = it.name,
                                    summary = it.description,
                                    enabled = busy == null,
                                    cardListPosition = cardListPositionOf(i, items.size),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    val enableText = stringResource(R.string.toggle_enable)
    val disableText = stringResource(R.string.toggle_disable)
    ConfirmDialog(
        show = pending != null,
        title = if (pending?.third == true) enableText else disableText,
        message = stringResource(R.string.toggle_confirm_body, pending?.second.orEmpty(), Formatters.agentLabel(state.app)),
        confirmText = if (pending?.third == true) enableText else disableText,
        onConfirm = {
            pending?.let { (id, name, on) -> vm.toggle("${if (on) enableText else disableText}: $name", id, on) }
            pending = null
        },
        onDismiss = { pending = null },
    )
    CommandPromptDialog(prompt, vm.commands::checkStatus, vm.commands::retry, vm.commands::dismiss)
}
