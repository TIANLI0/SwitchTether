package io.github.tianli0.switchtether.ui.sessions

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.basic.ChipTabData
import io.github.suqi8.coui.kmp.basic.ChipTabGroup
import io.github.suqi8.coui.kmp.basic.SmallTitle
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.preference.ArrowPreference
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.squircle.SquircleShape
import io.github.tianli0.coui17.patterns.GroupCard
import io.github.tianli0.coui17.patterns.PageDefaults
import io.github.tianli0.coui17.patterns.PanelSheet
import io.github.suqi8.coui.kmp.basic.cardListPositionOf
import io.github.tianli0.switchtether.R
import io.github.tianli0.switchtether.domain.LocalMoney
import io.github.tianli0.switchtether.remote.usage.UsageFormat
import io.github.tianli0.switchtether.ui.common.relativeTime
import io.github.tianli0.switchtether.ui.theme.LocalDevColors

/** Which value the per-turn chart shows. */
enum class CostMetric { COST, TOKENS }

/** The cost capsule's label: the money total, or the token total when no turn carried a cost. */
@Composable
fun costChipLabel(b: SessionUsageBreakdown): String =
    b.costUsd?.let { UsageFormat.money(it, LocalMoney.current, if (it < 0.1) 3 else 2) }
        ?: androidx.compose.ui.res.stringResource(R.string.cost_chip_tokens, UsageFormat.tokens(b.totalTokens))

/**
 * "费用与用量": the session's cost, a per-turn bar chart (cost, or tokens stacked as cache read /
 * cache write / input / output), the selected turn's numbers, the token mix with the cache hit rate
 * and the last request's context, and a link to the project's usage. Opened from the cost capsule
 * on the session page (docs/ANDROID_UI.md §26).
 */
@Composable
fun SessionCostSheet(
    show: Boolean,
    breakdown: SessionUsageBreakdown?,
    partial: Boolean,
    project: String?,
    onOpenProjectUsage: ((String) -> Unit)?,
    onDismiss: () -> Unit,
) {
    PanelSheet(show = show, title = stringResource(R.string.cost_sheet_title), onDismissRequest = onDismiss, modifier = Modifier.testTag("cost-sheet"), imePadding = false) {
        val b = breakdown ?: return@PanelSheet
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            SessionCostBody(b, partial, project, onOpenProjectUsage)
        }
    }
}

/** The sheet's content (also rendered on its own by the screenshot tests). */
@Composable
fun SessionCostBody(
    b: SessionUsageBreakdown,
    partial: Boolean,
    project: String?,
    onOpenProjectUsage: ((String) -> Unit)?,
) {
    val hasCost = b.costUsd != null
    GroupCard { CostHero(b) }

    SmallTitle(stringResource(R.string.cost_per_turn))
    var metric by rememberSaveable { mutableStateOf(if (hasCost) CostMetric.COST else CostMetric.TOKENS) }
    var selected by rememberSaveable(b.turns.size) { mutableIntStateOf(b.turns.lastIndex) }
    GroupCard {
        Column(Modifier.padding(vertical = 12.dp)) {
            if (hasCost) {
                val metrics = CostMetric.entries
                ChipTabGroup(
                    tabs = metrics.map { ChipTabData(stringResource(if (it == CostMetric.COST) R.string.cost_metric_cost else R.string.cost_metric_tokens)) },
                    selectedIndex = metrics.indexOf(metric),
                    onSelectedChange = { metric = metrics[it] },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                    modifier = Modifier.padding(bottom = 8.dp).testTag("cost-metric"),
                )
            }
            Column(Modifier.padding(horizontal = 16.dp)) {
                TurnBarChart(b.turns, metric, selected, onSelect = { selected = it })
                if (metric == CostMetric.TOKENS) TokenLegend(Modifier.padding(top = 10.dp))
                // The picked turn's numbers cross-fade in place, like the usage heatmap's detail.
                AnimatedContent(
                    targetState = b.turns.getOrNull(selected),
                    transitionSpec = { fadeIn(tween(180, delayMillis = 40)) togetherWith fadeOut(tween(110)) },
                    label = "turn-detail",
                ) { t -> if (t != null) TurnDetail(t, hasCost) }
            }
        }
    }

    SmallTitle(stringResource(R.string.cost_breakdown))
    GroupCard { TokenMix(b) }
    val notes = listOfNotNull(
        stringResource(R.string.cost_partial).takeIf { partial },
        stringResource(R.string.cost_running_total).takeIf { hasCost && partial },
    )
    notes.forEach { note ->
        Text(
            note,
            style = COUITheme.textStyles.footnote1,
            color = COUITheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(horizontal = PageDefaults.TextGutter).padding(bottom = 12.dp),
        )
    }
    if (project != null && onOpenProjectUsage != null) {
        GroupCard {
            ArrowPreference(
                title = stringResource(R.string.cost_project_usage, project),
                summary = stringResource(R.string.cost_project_usage_summary),
                onClick = { onOpenProjectUsage(project) },
                modifier = Modifier.testTag("cost-project-link"),
            )
        }
    }
}

@Composable
private fun CostHero(b: SessionUsageBreakdown) {
    val money = LocalMoney.current
    val n = b.turns.size
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column {
            Text(
                stringResource(if (b.costUsd != null) R.string.cost_total else R.string.cost_total_tokens),
                style = COUITheme.textStyles.footnote1,
                color = COUITheme.colorScheme.onSurfaceVariantSummary,
            )
            Text(
                b.costUsd?.let { UsageFormat.money(it, money, 4) } ?: UsageFormat.tokens(b.totalTokens),
                style = COUITheme.textStyles.title2,
                fontWeight = FontWeight.SemiBold,
                color = COUITheme.colorScheme.onSurface,
                modifier = Modifier.testTag("cost-total"),
            )
            b.costUsd?.takeIf { n > 1 }?.let {
                Text(
                    stringResource(R.string.cost_avg_turn, UsageFormat.money(it / n, money, 4)),
                    style = COUITheme.textStyles.footnote1,
                    color = COUITheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        Row {
            HeroStat(stringResource(R.string.cost_turns_label), n.toString(), Modifier.weight(1f))
            // Without a cost the headline above already is the token total: not a second time here.
            if (b.costUsd != null) HeroStat(stringResource(R.string.usage_total_tokens), UsageFormat.tokens(b.totalTokens), Modifier.weight(1f))
            HeroStat(stringResource(R.string.cost_cache_hit), UsageFormat.hitRate(b.cacheHitRate), Modifier.weight(1f))
        }
    }
}

@Composable
private fun HeroStat(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(value, style = COUITheme.textStyles.title4, color = COUITheme.colorScheme.onSurface)
        Text(label, style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurfaceVariantSummary)
    }
}

/** Colours of the four token kinds: one accent family for the prompt, a warm tone for the reply. */
private class TokenColors(val cacheRead: Color, val cacheWrite: Color, val input: Color, val output: Color)

@Composable
private fun tokenColors(): TokenColors {
    val accent = COUITheme.colorScheme.primary
    return TokenColors(
        cacheRead = accent.copy(alpha = 0.28f),
        cacheWrite = accent.copy(alpha = 0.55f),
        input = accent,
        output = LocalDevColors.current.warn,
    )
}

/**
 * One bar per turn, bottom-aligned, growing in with the expand spring when the sheet opens or the
 * metric changes. Tap a bar to pick its turn; the others dim. Bars are 6–22dp wide; when the turns
 * do not fit the card scrolls sideways and starts at the latest turn.
 */
@Composable
private fun TurnBarChart(turns: List<TurnUsage>, metric: CostMetric, selected: Int, onSelect: (Int) -> Unit) {
    val colors = tokenColors()
    val dev = LocalDevColors.current
    val money = LocalMoney.current
    val track = COUITheme.colorScheme.onSurface.copy(alpha = 0.06f)
    val grow = remember { Animatable(0f) }
    LaunchedEffect(metric, turns.size) {
        grow.snapTo(0f)
        grow.animateTo(1f, spring(dampingRatio = 0.9f, stiffness = 420f))
    }
    val values = turns.map { if (metric == CostMetric.COST) it.costUsd ?: 0.0 else it.totalTokens.toDouble() }
    val max = values.maxOrNull()?.takeIf { it > 0 } ?: 1.0
    val fmt: (Double) -> String = if (metric == CostMetric.COST) { v -> UsageFormat.money(v, money, 4) } else { v -> UsageFormat.tokens(v.toLong()) }
    val summary = turns.zip(values).joinToString("; ") { (t, v) -> "${t.index}: ${fmt(v)}" }
    val a11y = stringResource(R.string.cost_chart_a11y, turns.size, summary)
    val chartHeight = 132.dp
    Column {
        Text(fmt(max), style = COUITheme.textStyles.footnote2, color = COUITheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(bottom = 4.dp))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val gap = 4.dp
            val n = turns.size.coerceAtLeast(1)
            val fit = (maxWidth - gap * (n - 1)) / n
            val barWidth: Dp = fit.coerceIn(6.dp, 22.dp)
            // Few turns: the bars keep their width and spread a little, centred. Spreading them over
            // the whole card put two turns at its far edges with an empty middle.
            val slot: Dp = if (n > 1 && fit > barWidth) ((maxWidth - barWidth) / (n - 1)).coerceAtMost(barWidth * 3) else barWidth + gap
            val contentWidth = if (n > 1) slot * (n - 1) + barWidth else barWidth
            val chartBoxWidth = maxWidth
            val scroll = rememberScrollState()
            LaunchedEffect(n) { scroll.scrollTo(scroll.maxValue) }
            val density = LocalDensity.current
            Box(Modifier.fillMaxWidth().horizontalScroll(scroll, enabled = contentWidth > maxWidth)) {
              Box(Modifier.widthIn(min = chartBoxWidth), contentAlignment = Alignment.Center) {
                Canvas(
                    Modifier
                        .width(contentWidth)
                        .height(chartHeight)
                        .semantics { contentDescription = a11y }
                        .testTag("cost-chart")
                        .pointerInput(n, barWidth, slot) {
                            detectTapGestures { p ->
                                // The nearest bar's centre wins, so a tap between two bars still picks one.
                                val s = with(density) { slot.toPx() }
                                val half = with(density) { barWidth.toPx() } / 2
                                onSelect(((p.x - half) / s).let { kotlin.math.round(it) }.toInt().coerceIn(0, n - 1))
                            }
                        },
                ) {
                    val bw = barWidth.toPx()
                    val step = slot.toPx()
                    val h = size.height
                    val radius = CornerRadius(minOf(bw / 2, 6.dp.toPx()))
                    turns.forEachIndexed { i, t ->
                        val x = i * step
                        drawRoundRect(track, Offset(x, 0f), Size(bw, h), radius)
                        val dim = if (i == selected) 1f else 0.5f
                        val full = (values[i] / max).toFloat() * h * grow.value
                        if (metric == CostMetric.COST) {
                            if (t.costUsd == null) {
                                drawRoundRect(dev.neutral.copy(alpha = 0.4f * dim), Offset(x, h - 3.dp.toPx()), Size(bw, 3.dp.toPx()), radius)
                            } else if (full > 0f) {
                                val bh = full.coerceAtLeast(3.dp.toPx())
                                drawRoundRect(colors.input.copy(alpha = colors.input.alpha * dim), Offset(x, h - bh), Size(bw, bh), radius)
                            }
                        } else if (t.totalTokens > 0) {
                            // Stacked bottom-up; the stack is clipped to the bar's rounded outline.
                            val total = t.totalTokens.toFloat()
                            val bh = full.coerceAtLeast(3.dp.toPx())
                            var y = h
                            val parts = listOf(
                                colors.cacheRead to t.cacheReadTokens,
                                colors.cacheWrite to t.cacheWriteTokens,
                                colors.input to t.inputTokens,
                                colors.output to t.outputTokens,
                            )
                            val path = androidx.compose.ui.graphics.Path().apply {
                                addRoundRect(androidx.compose.ui.geometry.RoundRect(x, h - bh, x + bw, h, radius))
                            }
                            drawContext.canvas.save()
                            drawContext.canvas.clipPath(path)
                            parts.forEach { (c, v) ->
                                if (v <= 0) return@forEach
                                val ph = bh * (v / total)
                                drawRect(c.copy(alpha = c.alpha * dim), Offset(x, y - ph), Size(bw, ph))
                                y -= ph
                            }
                            drawContext.canvas.restore()
                        }
                    }
                }
              }
            }
        }
    }
}

@Composable
private fun TokenLegend(modifier: Modifier = Modifier) {
    val c = tokenColors()
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(
            c.cacheRead to R.string.usage_cache_read,
            c.cacheWrite to R.string.usage_cache_write,
            c.input to R.string.usage_input,
            c.output to R.string.usage_output,
        ).forEach { (color, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(5.dp))
                Text(stringResource(label), style = COUITheme.textStyles.footnote2, color = COUITheme.colorScheme.onSurfaceVariantSummary)
            }
        }
    }
}

/** Numbers of the picked turn: the message that started it, cost, tokens, duration, the mix. */
@Composable
private fun TurnDetail(t: TurnUsage, hasCost: Boolean) {
    val money = LocalMoney.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(SquircleShape(14.dp))
            .background(COUITheme.colorScheme.onSurface.copy(alpha = 0.05f))
            .padding(12.dp)
            .testTag("cost-turn-detail"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.cost_turn_title, t.index), style = COUITheme.textStyles.body2, fontWeight = FontWeight.SemiBold, color = COUITheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            parseTs(t.ts)?.let { Text(relativeTime(it), style = COUITheme.textStyles.footnote2, color = COUITheme.colorScheme.onSurfaceVariantSummary) }
        }
        Text(
            t.prompt ?: stringResource(R.string.cost_turn_no_prompt),
            style = COUITheme.textStyles.footnote1,
            color = if (t.prompt != null) COUITheme.colorScheme.onSurfaceSecondary else COUITheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row {
            if (hasCost) HeroStat(stringResource(R.string.cost_metric_cost), t.costUsd?.let { UsageFormat.money(it, money, 4) } ?: "—", Modifier.weight(1f))
            HeroStat(stringResource(R.string.usage_total_tokens), UsageFormat.tokens(t.totalTokens), Modifier.weight(1f))
            // No duration reported for the turn: leave the stat out rather than show a dash.
            t.durationMs?.let { HeroStat(stringResource(R.string.cost_duration), if (it < 1000) "${it}ms" else durationText(it), Modifier.weight(1f)) }
        }
        val parts = listOf(
            R.string.usage_input to t.inputTokens,
            R.string.usage_output to t.outputTokens,
            R.string.usage_cache_write to t.cacheWriteTokens,
            R.string.usage_cache_read to t.cacheReadTokens,
        ).filter { it.second > 0 }
        if (parts.isNotEmpty()) {
            Text(
                parts.map { (label, n) -> stringResource(label) + " " + UsageFormat.tokens(n) }.joinToString(" · "),
                style = COUITheme.textStyles.footnote2,
                color = COUITheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

/** The four token kinds as rows with a share bar each, then cache hit rate and context. */
@Composable
private fun TokenMix(b: SessionUsageBreakdown) {
    val c = tokenColors()
    val rows = listOf(
        Triple(R.string.usage_input, b.inputTokens, c.input),
        Triple(R.string.usage_output, b.outputTokens, c.output),
        Triple(R.string.usage_cache_write, b.cacheWriteTokens, c.cacheWrite),
        Triple(R.string.usage_cache_read, b.cacheReadTokens, c.cacheRead),
    )
    val total = b.totalTokens.coerceAtLeast(1)
    val extra = listOfNotNull(
        b.cacheHitRate?.let { stringResource(R.string.cost_cache_hit) to UsageFormat.hitRate(it) },
        b.contextTokens?.let { stringResource(R.string.cost_context) to stringResource(R.string.cost_context_value, UsageFormat.tokens(it)) },
    )
    val count = rows.size + extra.size
    rows.forEachIndexed { i, (label, n, color) ->
        val share by animateFloatAsState((n.toFloat() / total), spring(dampingRatio = 0.9f, stiffness = 420f), label = "share")
        BasicComponent(
            title = stringResource(label),
            startAction = { Box(Modifier.padding(end = 12.dp).size(10.dp).clip(CircleShape).background(color)) },
            endActions = {
                // Centred on the label's line, like the plain value rows below.
                Column(Modifier.align(Alignment.CenterVertically), horizontalAlignment = Alignment.End) {
                    Text(UsageFormat.tokens(n), style = COUITheme.textStyles.body2, color = COUITheme.colorScheme.onSurface)
                    Box(Modifier.padding(top = 4.dp).width(72.dp).height(4.dp).clip(CircleShape).background(COUITheme.colorScheme.onSurface.copy(alpha = 0.06f))) {
                        Box(Modifier.fillMaxWidth(share.coerceIn(0f, 1f)).height(4.dp).clip(CircleShape).background(color))
                    }
                }
            },
            cardListPosition = cardListPositionOf(i, count),
        )
    }
    extra.forEachIndexed { i, (k, v) ->
        BasicComponent(
            title = k,
            endActions = { Text(v, style = COUITheme.textStyles.body2, color = COUITheme.colorScheme.onSurface) },
            cardListPosition = cardListPositionOf(rows.size + i, count),
        )
    }
}

private fun parseTs(ts: String): Long? = runCatching { java.time.Instant.parse(ts).toEpochMilli() }.getOrNull()

