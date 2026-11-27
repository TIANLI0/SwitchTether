package io.github.tianli0.switchtether.connection

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.tianli0.switchtether.SwitchTetherApp
import kotlinx.coroutines.launch

/**
 * Schedules a compliant WorkManager reconnection check after device boot or app update when the
 * phone is paired and background connection (or pinned tasks) is enabled. Never attempts to bypass
 * Android 12-16+ foreground-service start restrictions from a background broadcast.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        // Runs in the `:link` process (docs/ANDROID_UI.md §38).
        val link = (context.applicationContext as? SwitchTetherApp)?.link ?: return
        val pending = goAsync()
        link.appScope.launch {
            try {
                link.afterBoot()
            } finally {
                pending.finish()
            }
        }
    }
}
