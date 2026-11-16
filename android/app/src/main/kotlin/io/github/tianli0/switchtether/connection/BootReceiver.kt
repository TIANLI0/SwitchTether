package io.github.tianli0.switchtether.connection

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.tianli0.switchtether.SwitchTetherApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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
        val pending = goAsync()
        val app = context.applicationContext as? SwitchTetherApp
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val container = app?.container ?: return@launch
                if (container.pairingStore.current() == null) return@launch
                val bg = container.settingsStore.backgroundConnection.first()
                val hasPins = container.liveUpdates.pins.value.isNotEmpty()
                if (bg || hasPins) {
                    ReconnectWorker.scheduleImmediate(context)
                    ReconnectWorker.schedulePeriodic(context)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
