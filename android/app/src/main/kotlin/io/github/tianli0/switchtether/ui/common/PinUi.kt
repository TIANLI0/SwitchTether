package io.github.tianli0.switchtether.ui.common

import io.github.tianli0.coui17.patterns.PanelSheet
import io.github.suqi8.coui.kmp.layout.DialogButtonBarAction
import io.github.tianli0.coui17.patterns.PageDefaults
import io.github.tianli0.coui17.patterns.SheetButtons
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.tianli0.switchtether.R
import io.github.tianli0.switchtether.remote.protocol.SessionSummary
import io.github.tianli0.switchtether.ui.appContainer
import kotlinx.coroutines.flow.MutableStateFlow

/** Session waiting for the "promoted notifications are off" explanation before it is pinned. */
val pinExplain = MutableStateFlow<SessionSummary?>(null)

/** OPPO / OnePlus / realme (ColorOS family): Fluid Cloud may need its own switch. */
fun isColorOsFamily(): Boolean = Build.MANUFACTURER.lowercase() in setOf("oppo", "oneplus", "realme")

/** System page where the user allows this app's Live Updates (Android 16+). */
fun promotionSettingsIntent(context: Context): Intent {
    val promotion = Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    // Older Android 16 builds have no dedicated page: fall back to the app's notification settings.
    return if (promotion.resolveActivity(context.packageManager) != null) promotion else
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/** ColorOS Fluid Cloud settings (unverified action name, used only when something resolves it). */
fun fluidCloudSettingsIntent(context: Context): Intent? =
    Intent("oplus.fluid.seeding.settings.main").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        .takeIf { isColorOsFamily() && it.resolveActivity(context.packageManager) != null }

/**
 * Pins / unpins a session to Fluid Cloud (Live Update). When the system will not promote this
 * app's notifications, explains it first; the pin still works as a normal ongoing notification.
 */
fun togglePin(context: Context, summary: SessionSummary) {
    val live = (context.applicationContext as io.github.tianli0.switchtether.SwitchTetherApp).container.liveUpdates
    if (live.isPinned(summary.id)) {
        live.unpin(summary.id)
        return
    }
    if (Build.VERSION.SDK_INT >= 36 && !live.canPromote()) {
        pinExplain.value = summary
        return
    }
    if (!live.pin(summary)) Toast.makeText(context, context.getString(R.string.pin_limit, io.github.tianli0.switchtether.notify.LiveUpdates.MAX_PINS), Toast.LENGTH_SHORT).show()
}

@Composable
fun PinExplainSheet() {
    val context = LocalContext.current
    val container = appContainer()
    val pending by pinExplain.collectAsStateWithLifecycle()
    PanelSheet(show = pending != null, title = stringResource(R.string.pin_sheet_title), onDismissRequest = { pinExplain.value = null }) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.pin_sheet_body),
                style = COUITheme.textStyles.body2,
                color = COUITheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = PageDefaults.TextGutter).padding(bottom = 12.dp),
            )
            val fluidCloud = fluidCloudSettingsIntent(context)
            val openSettings = DialogButtonBarAction(text = stringResource(R.string.pin_open_settings), onClick = { runCatching { context.startActivity(promotionSettingsIntent(context)) } })
            val pinAnyway = DialogButtonBarAction(
                text = stringResource(R.string.pin_anyway),
                onClick = {
                    pending?.let { s ->
                        if (!container.liveUpdates.pin(s)) Toast.makeText(context, context.getString(R.string.pin_limit, io.github.tianli0.switchtether.notify.LiveUpdates.MAX_PINS), Toast.LENGTH_SHORT).show()
                    }
                    pinExplain.value = null
                },
            )
            if (fluidCloud != null) {
                SheetButtons(
                    primary = openSettings,
                    secondary = DialogButtonBarAction(text = stringResource(R.string.pin_open_fluid_cloud), onClick = { runCatching { context.startActivity(fluidCloud) } }),
                    tertiary = pinAnyway,
                    modifier = Modifier.testTag("pin-open-settings"),
                )
            } else {
                SheetButtons(primary = openSettings, secondary = pinAnyway, modifier = Modifier.testTag("pin-open-settings"))
            }
        }
    }
}
