package io.github.tianli0.switchtether.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import io.github.tianli0.switchtether.R
import io.github.tianli0.switchtether.connection.ConnectionState
import io.github.tianli0.switchtether.remote.protocol.CapStatus
import io.github.tianli0.switchtether.remote.protocol.ControlLevel
import io.github.tianli0.switchtether.remote.protocol.Hello
import io.github.tianli0.switchtether.ui.components.Banner
import io.github.tianli0.switchtether.ui.theme.DevColors
import io.github.tianli0.switchtether.ui.theme.LocalDevColors
import kotlinx.coroutines.delay

@Composable
fun connectionColor(state: ConnectionState, dev: DevColors = LocalDevColors.current): Color = when (state) {
    is ConnectionState.Online -> dev.ok
    is ConnectionState.Connecting -> dev.info
    is ConnectionState.Offline -> dev.warn
    is ConnectionState.Revoked, is ConnectionState.Incompatible -> dev.danger
    ConnectionState.Unpaired -> dev.neutral
}

@Composable
fun connectionText(state: ConnectionState): String {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state) {
        while (state is ConnectionState.Offline && state.retryAtMs != null) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    return when (state) {
        is ConnectionState.Online -> stringResource(R.string.conn_online, state.desktop.name, state.endpoint.toString())
        is ConnectionState.Connecting -> stringResource(R.string.conn_connecting, state.desktop.name)
        is ConnectionState.Offline -> {
            val secs = state.retryAtMs?.let { ((it - now) / 1000).coerceAtLeast(0) }
            val base = if (secs != null) stringResource(R.string.conn_offline_retry, secs) else stringResource(R.string.conn_offline)
            if (state.reason != null) "$base\n${state.reason}" else base
        }
        is ConnectionState.Revoked -> stringResource(R.string.conn_revoked, state.reason)
        is ConnectionState.Incompatible -> when (state.kind) {
            Hello.Compatibility.DESKTOP_TOO_OLD -> stringResource(R.string.conn_desktop_too_old, state.serverVersion)
            else -> stringResource(R.string.conn_app_too_old, state.serverVersion)
        }
        ConnectionState.Unpaired -> stringResource(R.string.conn_unpaired)
    }
}

/** Thin banner for non-online states, used at the top of pages. */
@Composable
fun ConnectionBanner(state: ConnectionState, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    if (state is ConnectionState.Online) return
    Banner(
        text = connectionText(state),
        color = connectionColor(state),
        modifier = modifier,
        action = if (state is ConnectionState.Offline) stringResource(R.string.action_retry) else null,
        onAction = onRetry,
    )
}

@Composable
fun capColor(status: CapStatus, dev: DevColors = LocalDevColors.current): Color = when (status) {
    CapStatus.VERIFIED -> dev.ok
    CapStatus.PARTIAL -> dev.warn
    CapStatus.BLOCKED -> dev.danger
    CapStatus.UNTESTED, CapStatus.UNKNOWN -> dev.neutral
}

@Composable
fun controlLabel(level: ControlLevel): String = when (level) {
    ControlLevel.FULL -> stringResource(R.string.control_full)
    ControlLevel.OBSERVE -> stringResource(R.string.control_observe)
    ControlLevel.HISTORY -> stringResource(R.string.control_history)
}

@Composable
fun controlColor(level: ControlLevel, dev: DevColors = LocalDevColors.current): Color = when (level) {
    ControlLevel.FULL -> dev.ok
    ControlLevel.OBSERVE -> dev.info
    ControlLevel.HISTORY -> dev.neutral
}

@Composable
fun sessionStatusColor(status: String, dev: DevColors = LocalDevColors.current): Color = when (status) {
    "busy" -> dev.info
    "waiting" -> dev.warn
    "error" -> dev.danger
    "completed" -> dev.ok
    else -> dev.neutral
}
