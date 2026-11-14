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
