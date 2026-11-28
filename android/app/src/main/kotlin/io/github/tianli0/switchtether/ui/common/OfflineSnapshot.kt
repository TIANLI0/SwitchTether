package io.github.tianli0.switchtether.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
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

/**
 * The snapshot label for data read from the local cache while the connection is [state]: none while
 * online (the cache only paints the screen early; the live load replaces it), else [savedAtMs].
 */
fun snapshotLabelFor(state: ConnectionState, savedAtMs: Long): Long? =
    if (state is ConnectionState.Online) null else savedAtMs

/** How long the label must stay before it is shown: a quick reconnect never flashes it. */
const val SNAPSHOT_NOTICE_DELAY_MS = 1500L

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
    // Shown only once the label has lasted SNAPSHOT_NOTICE_DELAY_MS (a reconnect of a second or two
    // while the app comes back to the foreground is not "offline").
    var settled by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(snapshotAtMs != null) {
        settled = false
        if (snapshotAtMs != null) {
            kotlinx.coroutines.delay(SNAPSHOT_NOTICE_DELAY_MS)
            settled = true
        }
    }
    NoticeVisibility(snapshotAtMs != null && settled) {
        if (shown != null) {
            StatusNoticeRow(
                text = stringResource(R.string.offline_snapshot_label, snapshotTimeLabel(shown)),
                color = LocalDevColors.current.neutral,
                modifier = modifier.testTag("offline-snapshot"),
            )
        }
    }
}
