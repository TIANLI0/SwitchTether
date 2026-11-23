package io.github.tianli0.switchtether.ui.common

import io.github.suqi8.coui.kmp.layout.DialogButtonBarAction
import io.github.tianli0.switchtether.ui.common.SheetTextGutter
import io.github.tianli0.switchtether.ui.common.SheetButtons
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.tianli0.switchtether.R
import io.github.tianli0.switchtether.connection.LocalNetworkAccess
import io.github.tianli0.switchtether.remote.pairing.LocalNetwork
import io.github.tianli0.switchtether.ui.appContainer
import io.github.tianli0.switchtether.ui.components.Banner
import io.github.tianli0.switchtether.ui.icons.AppIcons
import io.github.tianli0.switchtether.ui.theme.LocalDevColors
import kotlinx.coroutines.flow.MutableStateFlow

/** A pending request to explain + ask for ACCESS_LOCAL_NETWORK; [then] runs afterwards (granted or not). */
class LocalNetworkAsk(val then: (() -> Unit)? = null)

/** App-wide trigger for the rationale sheet (pairing, first LAN connect, the fix-it strip). */
val localNetworkAsk = MutableStateFlow<LocalNetworkAsk?>(null)

/**
 * Asks for Android 17's local network permission with a Chinese rationale first. Shown at the
 * moment it is needed (before pairing over the LAN, on the first LAN connect, from the strip),
 * never at app start. If the system will not show its dialog again, opens the app's settings.
 */
@Composable
fun LocalNetworkPermissionHost() {
    val container = appContainer()
    val access = container.localNetwork
    val ask by localNetworkAsk.collectAsStateWithLifecycle()
    val activity = LocalContext.current as? android.app.Activity
    val context = LocalContext.current
    LifecycleResumeEffect(Unit) {
        // Back from system settings: the permission may have changed.
        if (access.refresh()) container.connection.retryNow()
        onPauseOrDispose { }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        access.refresh()
        val then = localNetworkAsk.value?.then
        localNetworkAsk.value = null
        if (granted) container.connection.retryNow()
        then?.invoke()
    }
    // Nothing to ask (older Android or already granted): just continue.
    LaunchedEffect(ask) {
        val a = ask ?: return@LaunchedEffect
        if (!access.required || access.isGranted()) {
            localNetworkAsk.value = null
            a.then?.invoke()
        }
    }
    val show = ask != null && access.required && !access.granted.value
    AppBottomSheet(
        show = show,
        title = stringResource(R.string.lan_perm_title),
        onDismissRequest = {
            val then = localNetworkAsk.value?.then
            localNetworkAsk.value = null
            access.rationaleShown = true
            then?.invoke()
        },
    ) {
        Column(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = SheetTextGutter).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(R.string.lan_perm_point_direct, R.string.lan_perm_point_discovery, R.string.lan_perm_point_optional).forEach {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(AppIcons.Done, contentDescription = null, tint = COUITheme.colorScheme.primary, modifier = Modifier.size(18.dp).padding(top = 2.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(it), style = COUITheme.textStyles.body2, color = COUITheme.colorScheme.onSurface)
                    }
                }
            }
            SheetButtons(
                primary = DialogButtonBarAction(
                    text = stringResource(R.string.lan_perm_continue),
                    onClick = {
                        val permanent = activity != null && access.permanentlyDenied(activity)
                        access.rationaleShown = true
                        if (permanent) {
                            // The system will not ask again: open this app's settings page.
                            runCatching {
                                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                            val then = localNetworkAsk.value?.then
                            localNetworkAsk.value = null
                            then?.invoke()
                        } else {
                            launcher.launch(LocalNetworkAccess.PERMISSION)
                        }
                    },
                ),
                modifier = Modifier.testTag("lan-perm-allow"),
            )
        }
    }
}

/**
 * Fix-it strip when the permission is missing and the paired desktop is only known by LAN
 * addresses: explains what still works and offers to grant it.
 */
@Composable
fun LocalNetworkStrip(modifier: Modifier = Modifier) {
    val container = appContainer()
    val access = container.localNetwork
    val granted by access.granted.collectAsStateWithLifecycle()
    val desktop by container.pairingStore.desktop.collectAsStateWithLifecycle(initialValue = null)
    val d = desktop ?: return
    if (!access.required || granted || d.hosts.none(LocalNetwork::isLocal)) return
    Banner(
        text = stringResource(if (d.relayUrl != null) R.string.lan_perm_strip_relay else R.string.lan_perm_strip),
        color = LocalDevColors.current.warn,
        modifier = modifier.testTag("lan-perm-strip"),
        action = stringResource(R.string.lan_perm_grant),
        onAction = { localNetworkAsk.value = LocalNetworkAsk() },
    )
}
