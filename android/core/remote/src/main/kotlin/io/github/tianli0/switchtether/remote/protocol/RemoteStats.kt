package io.github.tianli0.switchtether.remote.protocol

import kotlinx.serialization.Serializable

/**
 * `GET /v1/stats/remote` (PROTOCOL.md §7.15): what all paired phones did, per desktop-local day.
 * Fixture `remote_stats.json`.
 */
@Serializable
data class RemoteStatsView(
    val timeZone: String? = null,
    val utcOffsetMinutes: Int? = null,
    /** First day with any statistics (`YYYY-MM-DD`), null when there are none yet. */
    val since: String? = null,
    val fromDate: String = "",
    val toDate: String = "",
    val totals: StatsCounters = StatsCounters(),
    /** Every day of ranges up to 92 days (zeros included), otherwise days with data only. */
    val days: List<DayStats> = emptyList(),
    /** Devices with activity in the range, most tasks first. */
    val devices: List<DeviceStats> = emptyList(),
    /** Sessions the phones drove in the range. */
    val sessions: Int = 0,
    /** Usage of those sessions in the range; null when the desktop's usage is unavailable. */
    val cost: UsageTotals? = null,
)

@Serializable
data class StatsCounters(
    val tasks: Long = 0,
    val messages: Long = 0,
    val approvalsAllowed: Long = 0,
    val approvalsDenied: Long = 0,
    val answers: Long = 0,
    /** How long agents worked on turns a phone started (waiting for the user excluded). */
    val taskMs: Long = 0,
    /** Time the app was in the foreground. */
    val foregroundMs: Long = 0,
) {
    val approvals: Long get() = approvalsAllowed + approvalsDenied
}

@Serializable
data class DayStats(
    val date: String,
    val startMs: Long = 0,
    val tasks: Long = 0,
    val messages: Long = 0,
    val approvalsAllowed: Long = 0,
    val approvalsDenied: Long = 0,
    val answers: Long = 0,
    val taskMs: Long = 0,
    val foregroundMs: Long = 0,
) {
    val counters: StatsCounters get() = StatsCounters(tasks, messages, approvalsAllowed, approvalsDenied, answers, taskMs, foregroundMs)
}

@Serializable
data class DeviceStats(
    val deviceId: String,
    /** Null for a device that is no longer in the desktop's registry. */
    val name: String? = null,
    val platform: String? = null,
    val revoked: Boolean = false,
    val tasks: Long = 0,
    val messages: Long = 0,
    val approvalsAllowed: Long = 0,
    val approvalsDenied: Long = 0,
    val answers: Long = 0,
    val taskMs: Long = 0,
    val foregroundMs: Long = 0,
) {
    val counters: StatsCounters get() = StatsCounters(tasks, messages, approvalsAllowed, approvalsDenied, answers, taskMs, foregroundMs)
}

/** One stretch of the app in the foreground, epoch ms `[startMs, endMs)`. */
@Serializable
data class ForegroundInterval(val startMs: Long, val endMs: Long)

/** `POST /v1/stats/foreground`. */
@Serializable
data class ForegroundReport(val commandId: String, val intervals: List<ForegroundInterval>)
