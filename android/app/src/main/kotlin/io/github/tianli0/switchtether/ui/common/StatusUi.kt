package io.github.tianli0.switchtether.ui.common

import androidx.compose.ui.platform.testTag

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import io.github.tianli0.coui17.patterns.NoticeBanner
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TopTips
import io.github.suqi8.coui.kmp.basic.TopTipsDefaults
import io.github.tianli0.coui17.patterns.PageDefaults
import io.github.tianli0.switchtether.ui.theme.DevColors
import io.github.tianli0.switchtether.ui.theme.LocalDevColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
        is ConnectionState.Online -> if (state.endpoint.host == io.github.tianli0.switchtether.remote.relay.RELAY_HOST) {
            stringResource(R.string.conn_via_relay, relayHostOf(state.desktop.relayUrl))
        } else {
            stringResource(R.string.conn_online, state.desktop.name, state.endpoint.host.let { h -> if (':' in h) "IPv6" else h })
        }
        is ConnectionState.Connecting -> stringResource(R.string.conn_connecting, state.desktop.name)
        is ConnectionState.Offline -> {
            val secs = state.retryAtMs?.let { ((it - now) / 1000).coerceAtLeast(0) }
            val base = if (secs != null) stringResource(R.string.conn_offline_retry, secs) else stringResource(R.string.conn_offline)
            when {
                // The relay refused our relay key (RELAY.md §2.0): say so in words.
                state.reason?.contains("relay: auth_failed") == true -> "$base\n" + stringResource(R.string.conn_relay_key_invalid)
                state.reason != null && state.reason != io.github.tianli0.switchtether.connection.ConnectionManager.LOCAL_NETWORK_DENIED -> "$base\n${state.reason}"
                else -> base
            }
        }
        is ConnectionState.Revoked -> stringResource(R.string.conn_revoked, state.reason)
        is ConnectionState.Incompatible -> when (state.kind) {
            Hello.Compatibility.DESKTOP_TOO_OLD -> stringResource(R.string.conn_desktop_too_old, state.serverVersion)
            else -> stringResource(R.string.conn_app_too_old, state.serverVersion)
        }
        ConnectionState.Unpaired -> stringResource(R.string.conn_unpaired)
    }
}

/**
 * A strip or notice in a column that folds in and out (height and fade) instead of popping, so
 * what is below it glides. Content that needs data which is gone while it folds away takes it
 * from [rememberLast].
 */
@Composable
fun NoticeVisibility(visible: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    AnimatedVisibility(visible = visible, modifier = modifier, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) { content() }
}

/** The last non-null [value]: what a notice keeps showing while it folds away after its data is gone. */
@Composable
fun <T : Any> rememberLast(value: T?): T? {
    val last = remember { androidx.compose.ui.node.Ref<T>() }
    if (value != null) last.value = value
    return last.value
}

/** Thin banner for non-online states, used at the top of pages. */
@Composable
fun ConnectionBanner(state: ConnectionState, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    // Android 17: LAN access not granted → say what still works and offer to fix it.
    LocalNetworkStrip()
    // Online (direct or through the relay) is never a banner: the path is shown once, on the
    // Home connection card and in Manage → computer details. The banner folds away when the link
    // comes back, still showing the state it had.
    val shown = rememberLast(state.takeIf { it !is ConnectionState.Online })
    NoticeVisibility(state !is ConnectionState.Online) {
        if (shown != null) ConnectionNotice(shown, onRetry, modifier)
    }
}

@Composable
private fun ConnectionNotice(state: ConnectionState, onRetry: () -> Unit, modifier: Modifier) {
    // The computer reported Wake-on-LAN targets: "唤醒并重试" sends magic packets on this network
    // (only effective when the phone is on the computer's LAN and the computer allows it), then retries.
    val wake = (state as? ConnectionState.Offline)?.desktop?.wake.orEmpty()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    NoticeBanner(
        text = connectionText(state),
        color = connectionColor(state),
        modifier = modifier,
        action = when {
            state !is ConnectionState.Offline -> null
            wake.isNotEmpty() -> stringResource(R.string.conn_wake_action)
            else -> stringResource(R.string.action_retry)
        },
        onAction = {
            if (wake.isEmpty()) {
                onRetry()
            } else {
                scope.launch {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        io.github.tianli0.switchtether.remote.wake.WakeOnLan.send(wake)
                    }
                    onRetry()
                }
            }
        },
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

/** Host part of a relay URL for display ("relay.example.com"). */
fun relayHostOf(url: String?): String = url?.let { runCatching { java.net.URI(it.trim()).host }.getOrNull() } ?: "—"

/**
 * A notice the user has to read in full (security warnings while pairing): the same COUI17 top
 * tip and status dot as [NoticeBanner], but the message wraps instead of scrolling as a one-line
 * marquee, which cut "no secure lock screen …" off mid-sentence on a phone.
 */
@Composable
fun WrapNotice(text: String, color: Color, modifier: Modifier = Modifier) {
    TopTips(
        text = text,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = PageDefaults.CardGutter, vertical = 4.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        startIcon = { Box(Modifier.size(8.dp).clip(CircleShape).background(color)) },
        content = {
            Text(text, style = TopTipsDefaults.textStyle(), color = TopTipsDefaults.topTipsColors().contentColor)
        },
    )
}
