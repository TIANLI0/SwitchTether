package io.github.tianli0.switchtether.ui.common

import java.text.DecimalFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs

object Formatters {
    fun tokens(n: Long): String = when {
        abs(n) >= 1_000_000_000 -> DecimalFormat("0.##").format(n / 1e9) + "B"
        abs(n) >= 1_000_000 -> DecimalFormat("0.##").format(n / 1e6) + "M"
        abs(n) >= 10_000 -> DecimalFormat("0.#").format(n / 1e3) + "K"
        else -> n.toString()
    }

    fun usd(v: Double?): String = when {
        v == null -> "—"
        v >= 100 -> "$" + DecimalFormat("#,##0").format(v)
        v >= 1 -> "$" + DecimalFormat("0.00").format(v)
        else -> "$" + DecimalFormat("0.000").format(v)
    }

    fun percent(v: Double?): String = when {
        v == null -> "—"
        v <= 1.0 -> DecimalFormat("0.#").format(v * 100) + "%"
        else -> DecimalFormat("0.#").format(v) + "%"
    }

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
