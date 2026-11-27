package io.github.tianli0.switchtether.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.tianli0.switchtether.SwitchTetherApp

/** Buttons and swipe-away of a pinned task's Live Update. Not exported (PendingIntents only). */
class LiveUpdateActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? SwitchTetherApp ?: return
        val live = app.link?.liveUpdates ?: return
        val session = intent.getStringExtra(EXTRA_SESSION) ?: return
        val request = intent.getStringExtra(EXTRA_REQUEST)
        when (intent.action) {
            ACTION_APPROVE -> request?.let { live.answer(session, it, "allow") }
            ACTION_DENY -> request?.let { live.answer(session, it, "deny") }
            ACTION_UNPIN -> live.unpin(session)
            ACTION_DISMISSED -> live.onDismissed(session)
        }
    }

    companion object {
        const val ACTION_APPROVE = "io.github.tianli0.switchtether.live.APPROVE"
        const val ACTION_DENY = "io.github.tianli0.switchtether.live.DENY"
        const val ACTION_UNPIN = "io.github.tianli0.switchtether.live.UNPIN"
        const val ACTION_DISMISSED = "io.github.tianli0.switchtether.live.DISMISSED"
        const val EXTRA_SESSION = "session"
        const val EXTRA_REQUEST = "request"
    }
}
