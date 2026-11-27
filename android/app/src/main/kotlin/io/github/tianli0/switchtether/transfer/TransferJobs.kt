package io.github.tianli0.switchtether.transfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.tianli0.switchtether.MainActivity
import io.github.tianli0.switchtether.R
import io.github.tianli0.switchtether.SwitchTetherApp
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Android 14+ user-initiated data transfer jobs (JobScheduler `setUserInitiated(true)`,
 * `RUN_USER_INITIATED_JOBS`): one job per running transfer, in the `transfers` job namespace (no id
 * clash with WorkManager). The job holds the progress notification (`JobService.setNotification`) with
 * a Cancel action, and while it runs the system lets the app keep the network and the CPU in the
 * background (no App Standby / freezer for the transfer). The bytes move in the caller's coroutine
 * (see [TransferManager]); the job ends with the transfer. Never rescheduled: a job the system stops
 * or that comes back after process death ends at once and the transfer is reported, not resumed.
 *
 * WorkManager 2.12 cannot schedule user-initiated jobs (only expedited work, which is limited to a few
 * minutes), so this uses JobScheduler directly.
 */
class JobTransferEnvelope(
    private val context: Context,
    private val isForeground: () -> Boolean,
) : TransferEnvelope {
    private val scheduler: JobScheduler? by lazy {
        runCatching { context.getSystemService(JobScheduler::class.java)?.forNamespace(NAMESPACE) }.getOrNull()
    }
    private val seq = AtomicInteger(0)
    private val jobIds = ConcurrentHashMap<String, Int>()
    private val running = ConcurrentHashMap<String, Pair<JobService, JobParameters>>()
    private val entries = ConcurrentHashMap<String, TransferEntry>()

    override fun begin(entry: TransferEntry): Boolean {
        val js = scheduler ?: return false
        ensureChannel(context)
        val jobId = seq.incrementAndGet()
        jobIds[entry.id] = jobId
        entries[entry.id] = entry
        val total: Long = entry.total ?: JobInfo.NETWORK_BYTES_UNKNOWN.toLong()
        val info = JobInfo.Builder(jobId, ComponentName(context, TransferJobService::class.java))
            .setUserInitiated(true)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setEstimatedNetworkBytes(if (entry.kind.isUpload) 0L else total, if (entry.kind.isUpload) total else 0L)
            .setExtras(PersistableBundle().apply { putString(EXTRA_TRANSFER, entry.id) })
            .build()
        // Refused when the app is not visible (a user-initiated job must start from a user action) or
        // the permission is missing: the transfer then runs as before, without the job.
        val ok = runCatching { js.schedule(info) == JobScheduler.RESULT_SUCCESS }.getOrDefault(false)
        if (!ok) {
            jobIds.remove(entry.id)
            entries.remove(entry.id)
        }
        return ok
    }

    override fun update(entry: TransferEntry) {
        entries[entry.id] = entry
        val (service, params) = running[entry.id] ?: return
        runCatching {
            service.setNotification(params, notificationId(entry.id), build(entry), JobService.JOB_END_NOTIFICATION_POLICY_REMOVE)
            if (entry.kind.isUpload) service.updateTransferredNetworkBytes(params, 0, entry.done)
            else service.updateTransferredNetworkBytes(params, entry.done, 0)
        }
    }

    override fun end(entry: TransferEntry) {
        entries.remove(entry.id)
        val jobId = jobIds.remove(entry.id)
        val run = running.remove(entry.id)
        if (run != null) runCatching { run.first.jobFinished(run.second, false) }
        else if (jobId != null) runCatching { scheduler?.cancel(jobId) }
        if (jobId == null) return
        // The job's notification goes with the job; out of the app a short result stays.
        if (!isForeground() && entry.phase != TransferPhase.CANCELLED) postResult(context, entry)
    }

    /** From [TransferJobService.onStartJob]; returns whether the job keeps running. */
    fun onStart(service: JobService, params: JobParameters): Boolean {
        ensureChannel(context)
        val id = params.extras.getString(EXTRA_TRANSFER)
        val entry = id?.let { entries[it] }
        val nid = id?.let { notificationId(it) } ?: NOTIFICATION_BASE
        // Must be called before onStartJob returns, even for a job that ends at once.
        runCatching {
            service.setNotification(params, nid, build(entry ?: TransferEntry(id ?: "", TransferKind.FILE_DOWNLOAD, "")), JobService.JOB_END_NOTIFICATION_POLICY_REMOVE)
        }
        // A transfer that already ended, or a job the system brought back after the process died: nothing
        // to resume (a new process never continues a transfer).
        if (id == null || entry == null || entry.phase.terminal) return false
        running[id] = service to params
        update(entry)
        return true
    }

    /** From [TransferJobService.onStopJob]: the system (or the user in Task Manager) stopped the job. */
    fun onStop(params: JobParameters, manager: TransferManager): Boolean {
        val id = params.extras.getString(EXTRA_TRANSFER) ?: return false
        running.remove(id)
        manager.onSystemStop(id)
        return false
    }

    private fun notificationId(transferId: String): Int = NOTIFICATION_BASE + (jobIds[transferId] ?: 0) % 1000

    private fun build(entry: TransferEntry): Notification {
        val title = context.getString(if (entry.kind.isUpload) R.string.transfer_upload_title else R.string.transfer_download_title, entry.label)
        val total = entry.total
        val text = if (total != null) {
            context.getString(R.string.transfer_progress, Formatter.formatShortFileSize(context, entry.done), Formatter.formatShortFileSize(context, total))
        } else Formatter.formatShortFileSize(context, entry.done)
        val nid = notificationId(entry.id)
        val cancel = PendingIntent.getBroadcast(
            context, nid,
            Intent(context, TransferActionReceiver::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_TRANSFER, entry.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(100, entry.percent ?: 0, entry.percent == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setGroup(GROUP)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .addAction(NotificationCompat.Action.Builder(null, context.getString(R.string.transfer_cancel), cancel).build())
            .build()
    }

    companion object {
        const val NAMESPACE = "transfers"
        const val CHANNEL = "transfers"
        private const val GROUP = "transfers"
        const val EXTRA_TRANSFER = "transfer"
        const val ACTION_CANCEL = "io.github.tianli0.switchtether.CANCEL_TRANSFER"
        private const val NOTIFICATION_BASE = 0x5100
        private const val RESULT_BASE = 0x5600
        private const val INTERRUPTED_ID = 0x5A00

        fun ensureChannel(context: Context) {
            runCatching {
                context.getSystemService(NotificationManager::class.java)
                    .createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.transfer_channel), NotificationManager.IMPORTANCE_LOW))
            }
        }

        private fun postResult(context: Context, e: TransferEntry) {
            val text = when (e.phase) {
                TransferPhase.SUCCEEDED -> context.getString(if (e.kind.isUpload) R.string.transfer_done_upload else R.string.transfer_done_download, e.label)
                TransferPhase.STOPPED_BY_SYSTEM -> context.getString(R.string.transfer_stopped, e.label)
                else -> context.getString(R.string.transfer_failed, e.label)
            }
            notify(context, RESULT_BASE + (e.id.hashCode() and 0xFF), text, null)
        }

        /** At the next start: transfers the previous process was running. Nothing was resent. */
        fun postInterrupted(context: Context, entries: List<TransferEntry>) {
            if (entries.isEmpty()) return
            ensureChannel(context)
            val names = entries.joinToString("、") { it.label }.take(200)
            notify(context, INTERRUPTED_ID, context.getString(R.string.transfer_interrupted_title), context.getString(R.string.transfer_interrupted_text, names))
        }

        private fun notify(context: Context, id: Int, title: String, text: String?) {
            ensureChannel(context)
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setContentTitle(title)
                .apply { if (text != null) setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text)) }
                .setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
                .build()
            runCatching { NotificationManagerCompat.from(context).notify(id, n) }
        }
    }
}

/** The user-initiated data transfer job of one transfer (see [JobTransferEnvelope]). */
class TransferJobService : JobService() {
    private val container get() = (application as SwitchTetherApp).container

    override fun onStartJob(params: JobParameters): Boolean = container.transferJobs.onStart(this, params)

    override fun onStopJob(params: JobParameters): Boolean = container.transferJobs.onStop(params, container.transfers)
}

/** Cancel on a transfer's notification. */
class TransferActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != JobTransferEnvelope.ACTION_CANCEL) return
        val container = (context.applicationContext as? SwitchTetherApp)?.container ?: return
        val id = intent.getStringExtra(JobTransferEnvelope.EXTRA_TRANSFER) ?: return
        container.transfers.cancel(id)
    }
}
