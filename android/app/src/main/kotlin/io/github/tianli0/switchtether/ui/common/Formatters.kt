package io.github.tianli0.switchtether.ui.common

import java.text.DecimalFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs

object Formatters {
    /** Same strings as the desktop (UsageFormat.tokens): 1,234 / 48.2K / 18.2M. */
    fun tokens(n: Long): String = io.github.tianli0.switchtether.remote.usage.UsageFormat.tokens(n)

    /** Compact cost per turn in the desktop's display currency: $0.0081 / ¥0.087 / $1.25. */
    fun usd(v: Double?, display: io.github.tianli0.switchtether.remote.usage.MoneyDisplay): String =
        if (v == null) "—" else io.github.tianli0.switchtether.remote.usage.UsageFormat.moneyCompact(v, display)

    fun dateTime(epochMs: Long?): String = epochMs?.let {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(Locale.getDefault())
            .format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))
    } ?: "—"

    /** RFC 3339 timestamp → local HH:mm:ss (event rows). */
    fun clock(ts: String): String = runCatching {
        DateTimeFormatter.ofPattern("HH:mm:ss").format(Instant.parse(ts).atZone(ZoneId.systemDefault()))
    }.getOrDefault("")

    /**
     * A reset timestamp from the desktop (RFC 3339 with offset/`Z`, a zone-less ISO date-time taken
     * as UTC like the desktop writes it, or epoch seconds/millis) as an [Instant].
     */
    fun parseInstant(raw: String?): Instant? {
        val s = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        s.toLongOrNull()?.let { n ->
            if (n <= 0) return null
            return if (n >= 1_000_000_000_000L) Instant.ofEpochMilli(n) else Instant.ofEpochSecond(n)
        }
        return runCatching { java.time.OffsetDateTime.parse(s).toInstant() }.getOrNull()
            ?: runCatching { Instant.parse(s) }.getOrNull()
            ?: runCatching { java.time.LocalDateTime.parse(s.replace(' ', 'T')).toInstant(java.time.ZoneOffset.UTC) }.getOrNull()
    }

    /** Local wall-clock of [at] in the device zone: "HH:mm" today, else a short localized date + time. */
    fun localDayAndTime(at: Instant, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): Pair<Int, String> {
        val t = at.atZone(zone)
        val days = java.time.temporal.ChronoUnit.DAYS.between(now.atZone(zone).toLocalDate(), t.toLocalDate()).toInt()
        val time = DateTimeFormatter.ofPattern("HH:mm", locale).format(t)
        val date = if (locale.language == "zh") DateTimeFormatter.ofPattern("M月d日 HH:mm", locale).format(t)
        else DateTimeFormatter.ofPattern("MMM d, HH:mm", locale).format(t)
        return days to if (days == 0 || days == 1) time else date
    }

    fun bytes(n: Long): String = when {
        n >= 1 shl 20 -> DecimalFormat("0.#").format(n / 1048576.0) + " MiB"
        n >= 1 shl 10 -> DecimalFormat("0.#").format(n / 1024.0) + " KiB"
        else -> "$n B"
    }

    fun agentLabel(agent: String): String = when (agent) {
        "claude_code", "claude" -> "Claude Code"
        "codex" -> "Codex"
        "gemini" -> "Gemini"
        "opencode" -> "OpenCode"
        else -> agent.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
}
