package io.github.tianli0.switchtether.push

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import io.github.tianli0.switchtether.SwitchTetherApp

/**
 * UnifiedPush "Messaging Broadcast Receiver" (exported: distributors are other apps). Every
 * request is checked against our connection token in [PushWake.handle]; unknown tokens are ignored.
 */
class PushReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as? SwitchTetherApp)?.container ?: return
        container.pushWake.handle(intent)
    }
}

/**
 * UnifiedPush "Service to raise to the foreground": a distributor may bind to it for 5 s while it
 * delivers a message, which lets the expedited wake work start from the background.
 */
class PushRaiseService : Service() {
    private val binder = Binder()
    override fun onBind(intent: Intent?): IBinder = binder
}
