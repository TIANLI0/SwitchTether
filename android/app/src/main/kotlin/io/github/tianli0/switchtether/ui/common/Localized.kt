package io.github.tianli0.switchtether.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.tianli0.switchtether.R
import io.github.tianli0.switchtether.remote.protocol.CapStatus
import io.github.tianli0.switchtether.ui.components.StepStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Localized relative time ("刚刚" / "5 分钟前" …), falling back to a short local date after a week. */
@Composable
fun relativeTime(epochMs: Long?, nowMs: Long = System.currentTimeMillis()): String {
    if (epochMs == null || epochMs <= 0) return "—"
    val d = (nowMs - epochMs).coerceAtLeast(0) / 1000
    return when {
        d < 45 -> stringResource(R.string.time_now)
        d < 3600 -> stringResource(R.string.time_minutes, (d / 60).toInt().coerceAtLeast(1))
        d < 86_400 -> stringResource(R.string.time_hours, (d / 3600).toInt())
        d < 7 * 86_400 -> stringResource(R.string.time_days, (d / 86_400).toInt())
        else -> DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(Locale.getDefault())
            .format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))
    }
}

/** RFC 3339 → local "HH:mm" (subtle timeline timestamps). */
fun shortClock(ts: String): String = runCatching {
    DateTimeFormatter.ofPattern("HH:mm").format(Instant.parse(ts).atZone(ZoneId.systemDefault()))
}.getOrDefault("")

@Composable
fun capStatusLabel(status: CapStatus): String = stringResource(
    when (status) {
        CapStatus.VERIFIED -> R.string.cap_status_verified
        CapStatus.PARTIAL -> R.string.cap_status_partial
        CapStatus.BLOCKED -> R.string.cap_status_blocked
        CapStatus.UNTESTED -> R.string.cap_status_untested
        CapStatus.UNKNOWN -> R.string.cap_status_unknown
    },
)

@Composable
fun stepStatusLabel(status: StepStatus): String = stringResource(
    when (status) {
        StepStatus.Pending -> R.string.step_pending
        StepStatus.Running -> R.string.step_running
        StepStatus.Completed -> R.string.step_completed
        StepStatus.Failed -> R.string.step_failed
        StepStatus.Denied -> R.string.step_denied
        StepStatus.Interrupted -> R.string.step_interrupted
        StepStatus.Unknown -> R.string.step_unknown
    },
)

@Composable
fun waitingLabel(waitingFor: String): String = when (waitingFor) {
    "approval" -> stringResource(R.string.waiting_for_approval)
    "input", "question" -> stringResource(R.string.waiting_for_input)
    else -> stringResource(R.string.waiting_for_other, waitingFor)
}

@Composable
fun originLabel(origin: String, managed: Boolean): String =
    if (managed || origin == "managed") stringResource(R.string.origin_managed) else stringResource(R.string.origin_external)

/** Client the user's own process runs in: "VS Code", "Codex Desktop", "终端" … */
@Composable
fun clientLabel(origin: String, agent: String): String = when (origin) {
    "vscode" -> "VS Code"
    "desktop_app" -> if (agent == "codex") "Codex Desktop" else "Claude Desktop"
    "cli", "terminal" -> stringResource(R.string.client_terminal)
    else -> origin
}

/** Badge for a session SwitchTether is attached to through the opt-in wrapper: "已接管 · VS Code". */
@Composable
fun attachedLabel(s: io.github.tianli0.switchtether.remote.protocol.SessionSummary): String =
    stringResource(R.string.attached_badge, clientLabel(s.origin, s.agent))

/** "已允许（在电脑上）" — decision ids and resolver kinds from the protocol, localized. */
@Composable
fun resolutionLabel(decision: String?, by: String?): String {
    val d = when (decision) {
        "allow" -> stringResource(R.string.decision_allow)
        "allow_session" -> stringResource(R.string.decision_allow_session)
        "deny" -> stringResource(R.string.decision_deny)
        "abort" -> stringResource(R.string.decision_abort)
        null -> stringResource(R.string.render_answered)
        else -> decision
    }
    val b = when {
        by == null -> ""
        by == "desktop" || by == "computer" || by == "local" -> stringResource(R.string.resolved_by_desktop)
        by.startsWith("device") || by == "phone" || by == "mobile" || by == "remote" -> stringResource(R.string.resolved_by_phone)
        else -> ""
    }
    return stringResource(R.string.resolved_line, d, b).trim()
}

/** A user-visible message produced outside composition (ViewModels): resource id + args. Int args that are string ids are resolved too. */
data class UiText(val res: Int, val args: List<Any> = emptyList())

@Composable
fun UiText.resolve(): String {
    val resolved = args.map { a -> if (a is Int && a > 0x7f000000) stringResource(a) else a }
    return stringResource(res, *resolved.toTypedArray())
}
