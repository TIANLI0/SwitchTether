package io.github.tianli0.switchtether.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import io.github.tianli0.coui17.patterns.NoticeBanner
import io.github.tianli0.switchtether.R
import io.github.tianli0.switchtether.connection.ConnectionState
import io.github.tianli0.switchtether.ui.theme.LocalDevColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Paired, but the computer cannot be used right now (asleep, unreachable, still connecting,
 * incompatible): what is shown is an offline snapshot and actions that need the computer are off.
 */
fun isAway(state: ConnectionState): Boolean =
    state is ConnectionState.Connecting || state is ConnectionState.Offline || state is ConnectionState.Incompatible

/** "10:42" for a snapshot taken today, else a short date and time ("2026/11/26 10:42"). */
fun snapshotTimeLabel(savedAtMs: Long, nowMs: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
    val at = Instant.ofEpochMilli(savedAtMs).atZone(zone)
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val pattern = if (at.toLocalDate() == today) DateTimeFormatter.ofPattern("HH:mm", locale)
    else DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale)
    return at.format(pattern)
}

/**
 * "离线快照 · 10:42 — 连接电脑后自动更新": the page shows what the phone last saw, not the computer's
 * current state. Folds away when a live load replaces the snapshot.
 */
@Composable
fun OfflineSnapshotNotice(snapshotAtMs: Long?, modifier: Modifier = Modifier) {
    val shown = rememberLast(snapshotAtMs)
    NoticeVisibility(snapshotAtMs != null) {
        if (shown != null) {
            NoticeBanner(
                text = stringResource(R.string.offline_snapshot_label, snapshotTimeLabel(shown)) + "\n" + stringResource(R.string.offline_snapshot_detail),
                color = LocalDevColors.current.neutral,
                modifier = modifier.testTag("offline-snapshot"),
            )
        }
    }
}
