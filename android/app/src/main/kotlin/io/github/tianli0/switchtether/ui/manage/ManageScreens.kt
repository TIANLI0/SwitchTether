package io.github.tianli0.switchtether.ui.manage

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.Switch
import io.github.suqi8.coui.kmp.basic.TabRow
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Refresh
import io.github.suqi8.coui.kmp.preference.ArrowPreference
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.tianli0.switchtether.R
import io.github.suqi8.coui.kmp.basic.CardListPosition
import io.github.suqi8.coui.kmp.basic.cardListPositionOf
import io.github.suqi8.coui.kmp.icon.extended.Layers
import io.github.suqi8.coui.kmp.icon.extended.Link
import io.github.suqi8.coui.kmp.icon.extended.Notes
import io.github.suqi8.coui.kmp.icon.extended.Recent
import io.github.suqi8.coui.kmp.icon.extended.Tasks
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

@Composable
fun ManageScreen(onOpen: (Route) -> Unit) {
    val container = appContainer()
    val conn by container.connection.state.collectAsStateWithLifecycle()
    Page(title = stringResource(R.string.tab_manage)) {
        ConnectionBanner(conn, { container.connection.retryNow() })
        LazyColumn(Modifier.fillMaxSize().pageScroll(), contentPadding = pageListPadding()) {
            item {
                GroupCard {
                    ArrowPreference(title = stringResource(R.string.manage_providers), summary = stringResource(R.string.manage_providers_summary), startAction = { RowIcon(COUIIcons.Layers) }, onClick = { onOpen(Route.Providers) }, cardListPosition = CardListPosition.Head)
                    ArrowPreference(title = stringResource(R.string.manage_usage), summary = stringResource(R.string.manage_usage_summary), startAction = { RowIcon(COUIIcons.Recent) }, onClick = { onOpen(Route.Usage) }, cardListPosition = CardListPosition.Tail)
                }
            }
            item {
                GroupCard {
                    ArrowPreference(title = stringResource(R.string.manage_mcp), startAction = { RowIcon(COUIIcons.Link) }, onClick = { onOpen(Route.Mcp) }, cardListPosition = CardListPosition.Head)
                    ArrowPreference(title = stringResource(R.string.manage_skills), startAction = { RowIcon(COUIIcons.Tasks) }, onClick = { onOpen(Route.Skills) }, cardListPosition = CardListPosition.Middle)
                    ArrowPreference(title = stringResource(R.string.manage_prompts), startAction = { RowIcon(COUIIcons.Notes) }, onClick = { onOpen(Route.Prompts) }, cardListPosition = CardListPosition.Tail)
                }
            }
            item { Text(stringResource(R.string.manage_write_note), style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(16.dp)) }
        }
    }
}

@Composable
private fun AppTabs(apps: List<String>, selected: String, onSelect: (String) -> Unit) {
    TabRow(
        tabs = apps.map { Formatters.agentLabel(it) },
        selectedTabIndex = apps.indexOf(selected).coerceAtLeast(0),
        onTabSelected = { onSelect(apps[it]) },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
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
        actions = { ToolbarIcon(COUIIcons.Refresh, stringResource(R.string.action_refresh), vm::load) },
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
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.provider_credential_note, p.name), style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurfaceVariantSummary)
                io.github.suqi8.coui.kmp.basic.TextField(
                    value = value, onValueChange = { value = it }, label = f.name, singleLine = true, showPasswordToggle = true,
                    backgroundMode = io.github.suqi8.coui.kmp.basic.TextFieldMode.Rectangle,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(text = stringResource(R.string.action_cancel), onClick = { credTarget = null }, modifier = Modifier.weight(1f))
                    io.github.suqi8.coui.kmp.basic.Button(
                        onClick = { vm.setCredential("$credLabel: ${p.name}", p.id, f.path, value); credTarget = null },
                        enabled = value.isNotBlank(), modifier = Modifier.weight(1f),
                        colors = io.github.suqi8.coui.kmp.basic.ButtonDefaults.buttonColorsPrimary(),
                    ) { Text(stringResource(R.string.files_save), color = COUITheme.colorScheme.onPrimary) }
                }
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
                    (if (t.ok) "✓ " else "✗ ") + listOfNotNull(t.message, t.latencyMs?.let { "${it} ms" }).joinToString(" · ").ifEmpty { if (t.ok) "OK" else "failed" },
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
    Page(title = stringResource(R.string.manage_usage), onBack = onBack, actions = { ToolbarIcon(COUIIcons.Refresh, stringResource(R.string.action_refresh), vm::load) }) {
        ConnectionBanner(conn, { container.connection.retryNow() })
        TabRow(
            tabs = listOf(stringResource(R.string.usage_today), stringResource(R.string.usage_7d), stringResource(R.string.usage_30d)),
            selectedTabIndex = state.range.ordinal,
            onTabSelected = { vm.setRange(UsageRange.entries[it]) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        )
        UsageContent(state, vm::load)
    }
}

@Composable
fun UsageContent(state: UsageState, onRetry: () -> Unit) {
    // One honest page-level error instead of repeating it on every card (e.g. 503 from CC Switch).
    (state.summary as? Loadable.Failed)?.let { failed ->
        LoadableContent(failed, onRetry = onRetry) {}
        return
    }
    LazyColumn(Modifier.fillMaxSize().pageScroll(), contentPadding = pageListPadding()) {
        item { SectionHeader(stringResource(R.string.usage_summary)) }
        item {
            PageCard {
                SmallLoadable(state.summary, onRetry) { s -> SummaryGrid(s) }
            }
        }
        item { SectionHeader(stringResource(R.string.usage_trend)) }
        item {
            PageCard {
                SmallLoadable(state.trend, onRetry) { days -> TrendChart(days) }
            }
        }
        item { SectionHeader(stringResource(R.string.usage_by_model)) }
        item { PageCard { SmallLoadable(state.models, onRetry) { BreakdownTable(it) } } }
        item { SectionHeader(stringResource(R.string.usage_by_provider)) }
        item { PageCard { SmallLoadable(state.providers, onRetry) { BreakdownTable(it) } } }
        item { SectionHeader(stringResource(R.string.usage_quota)) }
        items(state.quotas, key = { it.first }) { (tool, q) ->
            PageCard { SmallLoadable(q, onRetry) { QuotaCard(it) } }
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

@Composable
private fun SummaryGrid(s: UsageSummary) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row {
            Stat(stringResource(R.string.usage_cost), Formatters.usd(s.totalCostUsd), Modifier.weight(1f))
            Stat(stringResource(R.string.usage_tokens_label), Formatters.tokens(s.totalTokens), Modifier.weight(1f))
        }
        Row {
            Stat(stringResource(R.string.usage_requests), s.totalRequests.toString(), Modifier.weight(1f))
            Stat(stringResource(R.string.usage_success), Formatters.percent(s.successRate), Modifier.weight(1f))
        }
        Text(
            "in ${Formatters.tokens(s.inputTokens)} · out ${Formatters.tokens(s.outputTokens)} · cache r ${Formatters.tokens(s.cacheReadTokens)} w ${Formatters.tokens(s.cacheCreationTokens)}" +
                (s.cacheHitRate?.let { " · hit ${Formatters.percent(it)}" } ?: ""),
            style = DevType.codeSmall,
            color = COUITheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(value, style = COUITheme.textStyles.title3, color = COUITheme.colorScheme.onSurface)
        Text(label, style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurfaceVariantSummary)
    }
}

@Composable
private fun TrendChart(days: List<DailyUsage>) {
    if (days.isEmpty()) {
        EmptyMessage(stringResource(R.string.usage_no_data))
        return
    }
    val points = days.map { d -> BarPoint(d.date.take(10).let { if (it.length == 10) it.substring(5) else it }, d.tokens.toDouble()) }
    val summary = stringResource(R.string.usage_trend_a11y, days.size) + " " + days.joinToString("; ") { "${it.date}: ${Formatters.tokens(it.tokens)}" }
    Column(Modifier.padding(16.dp)) {
        Text(stringResource(R.string.usage_tokens_per_day), style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurfaceVariantSummary)
        BarChart(points = points, summary = summary, formatValue = { Formatters.tokens(it.toLong()) })
    }
}

@Composable
private fun BreakdownTable(rows: List<UsageBreakdownRow>) {
    if (rows.isEmpty()) {
        EmptyMessage(stringResource(R.string.usage_no_data))
        return
    }
    val maxTokens = rows.maxOf { it.tokens }.coerceAtLeast(1)
    Column(Modifier.padding(vertical = 8.dp)) {
        rows.sortedByDescending { it.tokens }.take(20).forEach { r ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(r.label, style = DevType.code, color = COUITheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    io.github.suqi8.coui.kmp.basic.LinearProgressIndicator(progress = r.tokens.toFloat() / maxTokens, modifier = Modifier.fillMaxWidth().padding(top = 3.dp))
                }
                Spacer(Modifier.size(10.dp))
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.widthIn(min = 72.dp)) {
                    Text(Formatters.tokens(r.tokens), style = DevType.codeSmall, color = COUITheme.colorScheme.onSurface)
                    Text(Formatters.usd(r.costUsd) + " · ${r.requests}", style = DevType.label, color = COUITheme.colorScheme.onSurfaceVariantSummary)
                }
            }
        }
    }
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
                Text("${t.name}: ${t.utilizationPercent?.let { "%.0f%%".format(it) } ?: "—"}" + (t.resetsAt?.let { " · resets $it" } ?: ""), style = DevType.codeSmall, color = COUITheme.colorScheme.onSurface)
                io.github.suqi8.coui.kmp.basic.LinearProgressIndicator(progress = ((t.utilizationPercent ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f), modifier = Modifier.fillMaxWidth())
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
    Page(title = title, onBack = onBack, actions = { ToolbarIcon(COUIIcons.Refresh, stringResource(R.string.action_refresh), vm::load) }) {
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
