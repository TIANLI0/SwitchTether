package io.github.tianli0.switchtether.ui.manage

import io.github.tianli0.coui17.patterns.PageDefaults
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.cardListPositionOf
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.tianli0.coui17.patterns.PanelSheet
import io.github.tianli0.switchtether.R
import io.github.tianli0.switchtether.domain.LocalMoney
import io.github.tianli0.switchtether.remote.protocol.RequestLog
import io.github.tianli0.switchtether.remote.usage.UsageFormat
import io.github.tianli0.switchtether.ui.common.Formatters
import io.github.tianli0.coui17.patterns.GroupCard
import io.github.tianli0.switchtether.ui.theme.LocalDevColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")

/** Time of a request in the desktop's zone (the same clock as the desktop's request log). */
fun requestTime(ms: Long, zone: ZoneId): String = TIME.format(Instant.ofEpochMilli(ms).atZone(zone))

/** One request-log row: time · model on top; provider, tokens, cost (desktop money(·, 4)), status below. */
@Composable
fun RequestLogRow(log: RequestLog, zone: ZoneId, position: io.github.suqi8.coui.kmp.basic.CardListPosition, onClick: () -> Unit) {
    val money = LocalMoney.current
    val dev = LocalDevColors.current
    BasicComponent(
        title = listOfNotNull(log.model ?: log.requestModel, log.providerName).joinToString(" · ").ifEmpty { log.requestId.take(8) },
        summary = listOfNotNull(
            requestTime(log.createdAtMs, zone),
            stringResource(R.string.usage_log_tokens, UsageFormat.tokens(log.totalTokens)),
            log.latencyMs?.let { "%.1fs".format(it / 1000.0) },
        ).joinToString(" · "),
        endActions = {
            Column(horizontalAlignment = Alignment.End) {
                Text(UsageFormat.money(log.totalCostUsd, money, 4), style = COUITheme.textStyles.body2, color = COUITheme.colorScheme.onSurface)
                if (!log.ok) Text(log.statusCode.toString(), style = COUITheme.textStyles.footnote2, color = dev.danger)
            }
        },
        cardListPosition = position,
        onClick = onClick,
        modifier = Modifier.testTag("log-${log.requestId}"),
    )
}

/** Request detail (metadata only: the desktop never sends request or response bodies). */
@Composable
fun RequestLogSheet(log: RequestLog?, zone: ZoneId, onDismiss: () -> Unit) {
    PanelSheet(show = log != null, title = stringResource(R.string.usage_log_detail), onDismissRequest = onDismiss) {
        val l = log ?: return@PanelSheet
        val money = LocalMoney.current
        val rows = listOfNotNull(
            stringResource(R.string.usage_log_time) to requestTime(l.createdAtMs, zone),
            l.appType?.let { stringResource(R.string.usage_log_app) to Formatters.agentLabel(it) },
            l.providerName?.let { stringResource(R.string.usage_log_provider) to it },
            (l.model ?: l.requestModel)?.let { stringResource(R.string.usage_log_model) to listOfNotNull(l.model, l.requestModel?.takeIf { r -> r != l.model }).joinToString(" ← ") },
            stringResource(R.string.usage_log_status) to (l.statusCode?.toString() ?: "—") + (l.errorMessage?.let { " · $it" } ?: ""),
            stringResource(R.string.usage_log_tokens_detail) to stringResource(
                R.string.usage_log_token_split,
                UsageFormat.tokens(l.inputTokens), UsageFormat.tokens(l.outputTokens), UsageFormat.tokens(l.cacheReadTokens), UsageFormat.tokens(l.cacheCreationTokens),
            ),
            stringResource(R.string.usage_log_cost) to listOfNotNull(
                UsageFormat.money(l.totalCostUsd, money, 6),
                if (money.converted) "(" + UsageFormat.money(l.totalCostUsd, io.github.tianli0.switchtether.remote.usage.MoneyDisplay.USD, 6) + ")" else null,
            ).joinToString(" "),
            stringResource(R.string.usage_log_timing) to listOfNotNull(
                l.firstTokenMs?.let { stringResource(R.string.usage_log_first_token, UsageFormat.firstTokenSeconds(it.toDouble()) ?: "—") },
                l.latencyMs?.let { stringResource(R.string.usage_log_latency, "%.1fs".format(it / 1000.0)) },
                if (l.isStreaming) stringResource(R.string.usage_log_streaming) else null,
            ).joinToString(" · ").ifEmpty { "—" },
            l.dataSource?.let { stringResource(R.string.usage_log_source) to it },
            stringResource(R.string.usage_log_id) to l.requestId,
        )
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            GroupCard {
                rows.forEachIndexed { i, (k, v) -> BasicComponent(title = k, summary = v, cardListPosition = cardListPositionOf(i, rows.size)) }
            }
        }
    }
}

/** "会话日志同步于 …" under the summary (desktop clock). */
@Composable
fun SyncNote(lastSyncMs: Long?, zone: ZoneId) {
    lastSyncMs ?: return
    Row(Modifier.fillMaxWidth().padding(horizontal = PageDefaults.TextGutter, vertical = 4.dp), horizontalArrangement = Arrangement.Start) {
        Text(
            stringResource(R.string.usage_last_sync, requestTime(lastSyncMs, zone).dropLast(3)),
            style = COUITheme.textStyles.footnote2,
            color = COUITheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}
