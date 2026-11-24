package io.github.tianli0.switchtether.notify

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import io.github.tianli0.switchtether.MainActivity
import io.github.tianli0.switchtether.R
import io.github.tianli0.switchtether.SwitchTetherApp
import io.github.tianli0.switchtether.remote.command.CommandOutcome
import io.github.tianli0.switchtether.remote.notify.QuestionPrompt
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Buttons of ordinary notifications: allow / deny an approval, answer a single question, reply to a
 * session. Every action requires the phone to be unlocked (`setAuthenticationRequired`) and goes
 * through the same API and commandId idempotency as the in-app buttons. The notification is then
 * replaced by the outcome (sent, or why not), never silently retried. Not exported.
 */
class NotifyActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as? SwitchTetherApp)?.container ?: return
        val session = intent.getStringExtra(EXTRA_SESSION) ?: return
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION, 0)
        val typed = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_TEXT)?.toString()?.trim().orEmpty()
        val action = intent.action ?: return
        if ((action == ACTION_ANSWER || action == ACTION_REPLY) && typed.isEmpty()) {
            NotificationManagerCompat.from(context).cancel(notificationId)
            return
        }
        val pending = goAsync()
        container.appScope.launch {
            try {
                val outcome = withTimeoutOrNull(BUDGET_MS) {
                    if (!container.awaitOnline(CONNECT_MS)) return@withTimeoutOrNull null
                    val request = intent.getStringExtra(EXTRA_REQUEST)
                    when (action) {
                        ACTION_APPROVE -> container.sessions.resolveApproval(session, request ?: return@withTimeoutOrNull null, "allow")
                        ACTION_DENY -> container.sessions.resolveApproval(session, request ?: return@withTimeoutOrNull null, "deny")
                        ACTION_ANSWER -> {
                            val prompt = promptFrom(intent) ?: return@withTimeoutOrNull null
                            val answers = buildJsonObject { put(prompt.key, prompt.valueFor(typed)) }
                            container.sessions.answer(session, prompt.requestId, answers, action = "submit")
                        }
                        ACTION_REPLY -> container.sessions.sendMessage(session, typed)
                        else -> null
                    }
                }
                val request = intent.getStringExtra(EXTRA_REQUEST)
                if (outcome is CommandOutcome.Succeeded && (action == ACTION_APPROVE || action == ACTION_DENY) && request != null) {
                    // Not a plain cancel: another request of the session may still be open (and may
                    // already have taken this notification's place).
                    container.requestAnswered(session, request)
                } else {
                    report(context, notificationId, session, action, typed, outcome)
                }
            } finally {
                container.releaseBackgroundConnection()
                pending.finish()
            }
        }
    }

    /** Replaces the notification with the outcome (this also ends the inline-reply spinner). */
    private fun report(context: Context, id: Int, session: String, action: String, typed: String, outcome: CommandOutcome?) {
        val nm = NotificationManagerCompat.from(context)
        if (outcome is CommandOutcome.Succeeded && (action == ACTION_APPROVE || action == ACTION_DENY)) {
            runCatching { nm.cancel(id) }
            return
        }
        val (title, text) = when (outcome) {
            is CommandOutcome.Succeeded -> context.getString(R.string.ntf_sent) to typed
            is CommandOutcome.Rejected ->
                // 409: answered elsewhere (on the computer or in the app) in the meantime.
                if (outcome.error.httpStatus == 409) context.getString(R.string.ntf_handled_elsewhere) to ""
                else context.getString(R.string.ntf_not_sent) to outcome.error.body.message.ifBlank { outcome.error.code }
            is CommandOutcome.Unknown -> context.getString(R.string.ntf_not_sure) to context.getString(R.string.ntf_open_to_check)
            null -> context.getString(R.string.ntf_not_sent) to context.getString(R.string.ntf_offline_open)
            else -> context.getString(R.string.ntf_not_sent) to context.getString(R.string.ntf_open_to_check)
        }
        val tap = PendingIntent.getActivity(
            context, ("open:$session").hashCode(),
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_SESSION_ID, session)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val ok = outcome is CommandOutcome.Succeeded || (outcome is CommandOutcome.Rejected && outcome.error.httpStatus == 409)
        val channel = Notifier.channelId(
            if (action == ACTION_APPROVE || action == ACTION_DENY) io.github.tianli0.switchtether.remote.notify.NotifyType.WAITING_APPROVAL
            else io.github.tianli0.switchtether.remote.notify.NotifyType.WAITING_INPUT,
        )
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(title)
            .setContentText(text.ifBlank { null })
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .apply { if (ok) setTimeoutAfter(SENT_VISIBLE_MS) }
            .build()
        runCatching { nm.notify(id, n) }
    }

    companion object {
        const val ACTION_APPROVE = "io.github.tianli0.switchtether.notify.APPROVE"
        const val ACTION_DENY = "io.github.tianli0.switchtether.notify.DENY"
        const val ACTION_ANSWER = "io.github.tianli0.switchtether.notify.ANSWER"
        const val ACTION_REPLY = "io.github.tianli0.switchtether.notify.REPLY"
        const val KEY_TEXT = "text"
        private const val EXTRA_SESSION = "session"
        private const val EXTRA_REQUEST = "request"
        private const val EXTRA_NOTIFICATION = "notification"
        private const val EXTRA_KEY = "key"
        private const val EXTRA_LABELS = "labels"
        private const val EXTRA_VALUES = "values"
        private const val EXTRA_FREE = "free"
        private const val BUDGET_MS = 9_000L
        private const val CONNECT_MS = 6_000L
        private const val SENT_VISIBLE_MS = 4_000L

        private fun promptFrom(i: Intent): QuestionPrompt? {
            val request = i.getStringExtra(EXTRA_REQUEST) ?: return null
            return QuestionPrompt(
                requestId = request,
                key = i.getStringExtra(EXTRA_KEY) ?: return null,
                labels = i.getStringArrayExtra(EXTRA_LABELS)?.toList().orEmpty(),
                values = i.getStringArrayExtra(EXTRA_VALUES)?.toList().orEmpty(),
                freeText = i.getBooleanExtra(EXTRA_FREE, false),
            )
        }

        private fun base(context: Context, action: String, session: String, notificationId: Int): Intent =
            Intent(context, NotifyActionReceiver::class.java).setAction(action)
                .putExtra(EXTRA_SESSION, session)
                .putExtra(EXTRA_NOTIFICATION, notificationId)

        private fun broadcast(context: Context, intent: Intent, mutable: Boolean): PendingIntent = PendingIntent.getBroadcast(
            context,
            "${intent.action}:${intent.getIntExtra(EXTRA_NOTIFICATION, 0)}".hashCode(),
            intent,
            // Inline replies need a mutable PendingIntent (the system adds the typed text); the
            // intent is explicit, so nothing else can redirect it.
            (if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE) or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        fun decisionAction(context: Context, allow: Boolean, session: String, requestId: String, notificationId: Int): NotificationCompat.Action {
            val action = if (allow) ACTION_APPROVE else ACTION_DENY
            val pi = broadcast(context, base(context, action, session, notificationId).putExtra(EXTRA_REQUEST, requestId), mutable = false)
            return NotificationCompat.Action.Builder(null, context.getString(if (allow) R.string.live_action_approve else R.string.live_action_deny), pi)
                .setAuthenticationRequired(true) // the phone must be unlocked
                .build()
        }

        fun answerAction(context: Context, session: String, prompt: QuestionPrompt, notificationId: Int): NotificationCompat.Action {
            val intent = base(context, ACTION_ANSWER, session, notificationId)
                .putExtra(EXTRA_REQUEST, prompt.requestId)
                .putExtra(EXTRA_KEY, prompt.key)
                .putExtra(EXTRA_LABELS, prompt.labels.toTypedArray())
                .putExtra(EXTRA_VALUES, prompt.values.toTypedArray())
                .putExtra(EXTRA_FREE, prompt.freeText)
            val input = RemoteInput.Builder(KEY_TEXT)
                .setLabel(context.getString(R.string.ntf_answer_label))
                .setChoices(prompt.labels.toTypedArray())
                .setAllowFreeFormInput(prompt.freeText)
                .build()
            return NotificationCompat.Action.Builder(null, context.getString(R.string.ntf_action_answer), broadcast(context, intent, mutable = true))
                .addRemoteInput(input)
                .setAllowGeneratedReplies(false)
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                .setAuthenticationRequired(true)
                .build()
        }

        fun replyAction(context: Context, session: String, notificationId: Int): NotificationCompat.Action {
            val input = RemoteInput.Builder(KEY_TEXT).setLabel(context.getString(R.string.ntf_reply_label)).build()
            return NotificationCompat.Action.Builder(null, context.getString(R.string.ntf_action_reply), broadcast(context, base(context, ACTION_REPLY, session, notificationId), mutable = true))
                .addRemoteInput(input)
                .setAllowGeneratedReplies(false)
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                .setAuthenticationRequired(true)
                .build()
        }
    }
}
