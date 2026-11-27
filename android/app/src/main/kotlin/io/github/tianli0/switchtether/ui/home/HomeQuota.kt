package io.github.tianli0.switchtether.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.CardListPosition
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.cardListPositionOf
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.tianli0.coui17.patterns.GroupCard
import io.github.tianli0.coui17.patterns.groupRowShape
import io.github.tianli0.switchtether.R
import io.github.tianli0.switchtether.remote.protocol.QuotaTier
import io.github.tianli0.switchtether.remote.protocol.QuotaView
import io.github.tianli0.switchtether.ui.common.Formatters
import io.github.tianli0.switchtether.ui.theme.LocalDevColors

/**
 * Remaining subscription quota at a glance on Home: one block per agent, one aligned line per
 * window — a short name ("5 小时", "本周"), a bar of what is LEFT, and the percentage left — so
 * three windows fit without truncating. The agent's line says when its tightest window resets,
 * as a short relative time. The full cards (exact reset times) stay on the Usage page.
 */
@Composable
fun QuotaGlance(quotas: List<QuotaView>, onOpen: () -> Unit) {
    GroupCard(Modifier.testTag("home-quota")) {
        quotas.forEachIndexed { i, q ->
            QuotaGlanceBlock(q, cardListPositionOf(i, quotas.size), onOpen)
        }
    }
}

@Composable
private fun QuotaGlanceBlock(q: QuotaView, position: CardListPosition, onOpen: () -> Unit) {
    val tiers = q.tiers.filter { it.name !in HOME_HIDDEN_TIERS }.take(4)
    val tightest = tiers.filter { it.utilizationPercent != null }.maxByOrNull { it.utilizationPercent!! }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(groupRowShape(position))
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                Formatters.agentLabel(q.tool) + (q.planName?.let { " · $it" } ?: ""),
                style = COUITheme.textStyles.body2,
                fontWeight = FontWeight.Medium,
                color = COUITheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            tightest?.let { resetIn(it.resetsAt) }?.let {
                Text(it, style = COUITheme.textStyles.footnote2, color = COUITheme.colorScheme.onSurfaceVariantSummary, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
            }
        }
        tiers.forEach { TierLine(it) }
    }
}

/** "5 小时  ━━━━━━━━━━━━──  93%": the bar is what is left, coloured as it runs low. */
@Composable
private fun TierLine(t: QuotaTier) {
    val dev = LocalDevColors.current
    val left = t.utilizationPercent?.let { (100.0 - it).coerceIn(0.0, 100.0) }
    val color: Color = when {
        left == null -> COUITheme.colorScheme.onSurfaceVariantSummary
        left <= 10 -> dev.danger
        left <= 30 -> dev.warn
        else -> COUITheme.colorScheme.primary
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            shortTierLabel(t.name),
            style = COUITheme.textStyles.footnote1,
            color = COUITheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(72.dp),
        )
        val fraction = animateFloatAsState(((left ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f), label = "quota-left").value
        Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(COUITheme.colorScheme.onSurface.copy(alpha = 0.08f))) {
            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(color))
        }
        Text(
            left?.let { "%.0f%%".format(it) } ?: "—",
            style = COUITheme.textStyles.footnote1,
            fontWeight = FontWeight.Medium,
            color = color,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(48.dp),
        )
    }
}

/**
 * Windows left off Home: Antigravity's Claude / GPT allowance. Its Gemini windows are what the
 * glance is for; the Usage page still lists every window.
 */
internal val HOME_HIDDEN_TIERS = setOf("agy_third_party_five_hour", "agy_third_party_weekly")

/** The window's name short enough for the fixed label column: "5 小时", "本周", "本周 Opus". */
@Composable
private fun shortTierLabel(name: String): String {
    val model = name.substringAfter("seven_day_", "").takeIf { it.isNotEmpty() }?.replaceFirstChar { it.uppercase() }
    return when {
        name == "five_hour" -> stringResource(R.string.home_tier_5h)
        name == "seven_day" || name == "weekly_limit" -> stringResource(R.string.home_tier_week)
        model != null -> stringResource(R.string.home_tier_week) + " " + model
        name == "monthly" || name == "30_day" -> stringResource(R.string.home_tier_month)
        name == "credits" -> stringResource(R.string.quota_tier_credits)
        // Antigravity: Home shows only its Gemini windows (the block already says Antigravity).
        name == "agy_gemini_five_hour" -> stringResource(R.string.home_tier_5h)
        name == "agy_gemini_weekly" -> stringResource(R.string.home_tier_week)
        name == "agy_gemini_argon_weekly" -> stringResource(R.string.home_tier_week) + " Argon"
        else -> io.github.tianli0.switchtether.ui.manage.quotaTierLabel(name)
    }
}

/** "5 天后重置" / "3 小时后重置" / "20 分钟后重置"; null when unknown or already due. */
@Composable
private fun resetIn(raw: String?): String? {
    val at = Formatters.parseInstant(raw) ?: return null
    val mins = java.time.Duration.between(java.time.Instant.now(), at).toMinutes()
    return when {
        mins <= 0 -> null
        mins < 60 -> stringResource(R.string.home_reset_minutes, mins.toInt())
        mins < 48 * 60 -> stringResource(R.string.home_reset_hours, ((mins + 30) / 60).toInt())
        else -> stringResource(R.string.home_reset_days, ((mins + 720) / 1440).toInt())
    }
}
