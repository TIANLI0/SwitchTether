package io.github.tianli0.switchtether.connection

import android.content.Context
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
import java.util.concurrent.TimeUnit

/**
 * Background recovery worker (boot, app update, periodic network-connected check).
 *
 * WorkManager runs in the UI process; the connection lives in the `:link` process (docs/ANDROID_UI.md
 * §38), so the work is handed over there ([LinkContainer.recover]): start the monitor service when
 * the system allows it from the background, otherwise one bounded pinned-mTLS check so pending
 * approvals / questions / finished tasks are still notified. Respects Android 12+
 * foreground-service start restrictions.
 */
class ReconnectWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        io.github.tianli0.switchtether.link.LinkReceiver.recover(applicationContext)
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
