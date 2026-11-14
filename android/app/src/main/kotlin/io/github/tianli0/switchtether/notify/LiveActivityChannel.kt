package io.github.tianli0.switchtether.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import io.github.tianli0.switchtether.MainActivity
import io.github.tianli0.switchtether.R
import io.github.tianli0.switchtether.remote.live.LiveAction
import io.github.tianli0.switchtether.remote.live.LiveUpdateSpec

/**
 * Where a pinned task's live status is shown: one entity per pinned session with a stable id,
 * updated in place and ended explicitly. [AndroidLiveUpdateChannel] posts Android 16 Live Updates,
 * which ColorOS 16 shows in Fluid Cloud (流体云); the content follows OPPO's Fluid Cloud template
 * 2.0 layout rules (capsule icon + ≤ 5 characters, core text + auxiliary lines, text buttons only,
 * a 2–4 node progress track).
 */
interface LiveActivityChannel {
    /** [subtitle]: "Claude Code · 项目" (OPPO auxiliary text [C3]). */
    fun update(spec: LiveUpdateSpec, agent: String, subtitle: String?)
    fun end(sessionId: String)
    /** Whether the system will show it promoted (status chip / Fluid Cloud capsule). */
    fun canPromote(): Boolean
}

class AndroidLiveUpdateChannel(private val context: Context) : LiveActivityChannel {
    private val nm = NotificationManagerCompat.from(context)

    init {
        val mgr = context.getSystemService(NotificationManager::class.java)
        // DEFAULT importance: Live Updates must not use a MIN channel; sound only on the alert post.
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.live_channel), NotificationManager.IMPORTANCE_DEFAULT).apply {
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
                setShowBadge(false)
            },
        )
    }

    override fun canPromote(): Boolean =
        Build.VERSION.SDK_INT >= 36 && runCatching { context.getSystemService(NotificationManager::class.java).canPostPromotedNotifications() }.getOrDefault(false)

    override fun end(sessionId: String) {
        runCatching { nm.cancel(TAG, notificationId(sessionId)) }
    }

    override fun update(spec: LiveUpdateSpec, agent: String, subtitle: String?) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val id = notificationId(spec.sessionId)
        val accent = when (spec.tone) {
            LiveUpdateSpec.Tone.NORMAL -> 0xFF1F6BFF.toInt()
            LiveUpdateSpec.Tone.WARNING -> 0xFFE8A23B.toInt()
            LiveUpdateSpec.Tone.ERROR -> 0xFFE5484D.toInt()
        }
        val public = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(spec.publicText) // "SwitchTether · 待确认": never the command or request text
            .build()
        val b = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_notify) // monochrome: required for the chip / capsule
            .setContentTitle(spec.title)
            .setContentText(spec.text)
            .setSubText(subtitle)
            .setColor(accent) // accent only; never setColorized (would disqualify promotion)
            .setOngoing(spec.promoted)
            .setRequestPromotedOngoing(spec.promoted)
            .setShortCriticalText(spec.chip)
            .setOnlyAlertOnce(!spec.alert)
            .setSilent(!spec.alert)
            .setCategory(if (spec.actions.contains(LiveAction.APPROVE)) NotificationCompat.CATEGORY_REMINDER else NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setContentIntent(openIntent(spec.sessionId))
            .setDeleteIntent(actionIntent(LiveUpdateActionReceiver.ACTION_DISMISSED, spec.sessionId, null))
            .setAutoCancel(false)
        val base = spec.chronometerBaseMs
        if (base != null) {
            b.setWhen(base).setShowWhen(true).setUsesChronometer(true)
        } else {
            b.setShowWhen(false)
        }
        // Milestone track: 开始 → 运行 → (待确认) → 完成, agent mark as the tracker.
        // Stage track: one coloured segment per stage (sum = 100), no points (a point in the gap
        // between segments reads as a stray block). Always determinate with the tracker at the
        // current position: ColorOS Fluid Cloud shows no bar at all for an indeterminate style.
        if (spec.progress != LiveUpdateSpec.Progress.NONE) {
            b.setStyle(
                NotificationCompat.ProgressStyle()
                    .setStyledByProgress(true)
                    .setProgressSegments(spec.segments.map { NotificationCompat.ProgressStyle.Segment(it).setColor(accent) })
                    .setProgressTrackerIcon(trackerIcon(agent))
                    .setProgress(spec.progressValue.coerceIn(0, spec.segments.sum())),
            )
        }
        spec.actions.forEach { a -> actionFor(a, spec)?.let(b::addAction) }
        runCatching { nm.notify(TAG, id, b.build()) }
    }

    /**
     * Progress tracker: a dedicated 24dp square vector with the agent mark centred and padded
     * (`ic_tracker_*`), loaded by resource so the platform (and ColorOS) sizes it for the slot.
     */
    private fun trackerIcon(agent: String): IconCompat = IconCompat.createWithResource(
        context,
        when (agent) {
            "claude_code", "claude" -> R.drawable.ic_tracker_claude
            "codex" -> R.drawable.ic_tracker_codex
            else -> R.drawable.ic_stat_notify
        },
    )

    private fun actionFor(a: LiveAction, spec: LiveUpdateSpec): NotificationCompat.Action? = when (a) {
        LiveAction.APPROVE -> NotificationCompat.Action.Builder(null, context.getString(R.string.live_action_approve), actionIntent(LiveUpdateActionReceiver.ACTION_APPROVE, spec.sessionId, spec.requestId))
            .setAuthenticationRequired(true).build() // the phone must be unlocked
        LiveAction.DENY -> NotificationCompat.Action.Builder(null, context.getString(R.string.live_action_deny), actionIntent(LiveUpdateActionReceiver.ACTION_DENY, spec.sessionId, spec.requestId))
            .setAuthenticationRequired(true).build()
        LiveAction.OPEN -> NotificationCompat.Action.Builder(null, context.getString(R.string.live_action_open), openIntent(spec.sessionId)).build()
        LiveAction.ANSWER -> NotificationCompat.Action.Builder(null, context.getString(R.string.live_action_answer), openIntent(spec.sessionId))
            .setAuthenticationRequired(true).build()
        LiveAction.UNPIN -> NotificationCompat.Action.Builder(null, context.getString(R.string.live_action_unpin), actionIntent(LiveUpdateActionReceiver.ACTION_UNPIN, spec.sessionId, null)).build()
    }

    private fun openIntent(sessionId: String): PendingIntent = PendingIntent.getActivity(
        context,
        ("open:$sessionId").hashCode(),
        Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_SESSION_ID, sessionId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun actionIntent(action: String, sessionId: String, requestId: String?): PendingIntent = PendingIntent.getBroadcast(
        context,
        ("$action:$sessionId:$requestId").hashCode(),
        Intent(context, LiveUpdateActionReceiver::class.java).setAction(action)
            .putExtra(LiveUpdateActionReceiver.EXTRA_SESSION, sessionId)
            .putExtra(LiveUpdateActionReceiver.EXTRA_REQUEST, requestId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val CHANNEL = "live_updates"
        private const val TAG = "live"

        /** Stable per-session entity id (tag "live" + this id). */
        fun notificationId(sessionId: String): Int = sessionId.hashCode()
    }
}
