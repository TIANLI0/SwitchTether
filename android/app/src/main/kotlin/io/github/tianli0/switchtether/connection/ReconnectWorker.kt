package io.github.tianli0.switchtether.connection

import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.tianli0.switchtether.SwitchTetherApp
import io.github.tianli0.switchtether.notify.MonitorService
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

/**
 * Background recovery worker (boot, app update, periodic network-connected check).
 *
 * Respects Android 12-16+ foreground-service restrictions: only starts [MonitorService] from
 * background when exempt (API < 31 or battery optimization ignored). Otherwise performs a bounded
 * pinned-mTLS check of live sessions so pending approvals/questions/status still notify the user.
 */
class ReconnectWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? SwitchTetherApp ?: return Result.success()
        val container = app.container
        if (container.pairingStore.current() == null) {
            cancel(applicationContext)
            return Result.success()
        }
        val bg = container.settingsStore.backgroundConnection.first()
        val hasPins = container.liveUpdates.pins.value.isNotEmpty()
        if (!bg && !hasPins) return Result.success()

        val canStartFgsFromBg = Build.VERSION.SDK_INT < 31 ||
            applicationContext.getSystemService(PowerManager::class.java)
                ?.isIgnoringBatteryOptimizations(applicationContext.packageName) == true
        // With a bound computer that is away ("nearby only"), a bounded probe is enough: the
        // presence service starts the monitor again when the computer comes back.
        if (canStartFgsFromBg && container.wantsMonitor()) {
            MonitorService.start(applicationContext)
        }

        // Probe once over pinned mTLS (HostRace + NSD + Relay) so even without a persistent FGS,
        // pending approvals/questions or newly completed tasks are surfaced.
        if (container.connection.state.value !is ConnectionState.Online) {
            container.connection.probeOnce(timeoutMs = 15_000L)
        }
        return Result.success()
    }

    companion object {

        private const val WORK_IMMEDIATE = "switchtether_reconnect_now"
        private const val WORK_PERIODIC = "switchtether_reconnect_periodic"

        private fun constraints() = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun scheduleImmediate(context: Context) {
            val req = OneTimeWorkRequestBuilder<ReconnectWorker>()
                .setConstraints(constraints())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_IMMEDIATE, ExistingWorkPolicy.REPLACE, req)
        }

        fun schedulePeriodic(context: Context) {
            val req = PeriodicWorkRequestBuilder<ReconnectWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun cancel(context: Context) {
            val wm = WorkManager.getInstance(context)
            wm.cancelUniqueWork(WORK_IMMEDIATE)
            wm.cancelUniqueWork(WORK_PERIODIC)
        }
    }
}
