package io.github.tianli0.switchtether

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import io.github.tianli0.switchtether.share.ShareFiles
import io.github.tianli0.switchtether.share.ShareIntake
import io.github.tianli0.switchtether.share.ShareIntents
import io.github.tianli0.switchtether.ui.SwitchTetherRoot

/**
 * Single activity. Extends FragmentActivity because BiometricPrompt (step-up) needs it; the UI
 * is 100 % Compose (no WebView anywhere).
 */
class MainActivity : FragmentActivity() {
    companion object {
        /** Notification tap → open this session. */
        const val EXTRA_SESSION_ID = "io.github.tianli0.switchtether.SESSION_ID"

        /** Notification tap → open this terminal (Workspace → Terminals). */
        const val EXTRA_TERMINAL_ID = "io.github.tianli0.switchtether.TERMINAL_ID"

        /** "New version" notification tap → show the update sheet. */
        const val EXTRA_SHOW_UPDATE = "io.github.tianli0.switchtether.SHOW_UPDATE"

        /** Android 17 Handoff extras: only ids, never secrets; the receiving device must already be paired. */
        const val HANDOFF_SESSION = "handoff_session_id"
        const val HANDOFF_SERVER = "handoff_server_id"
    }

    /** Hardware keyboard shortcuts of this window (docs/ANDROID_UI.md §35). */
    private val shortcuts = io.github.tianli0.switchtether.ui.keys.ShortcutRegistry()

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean =
        shortcuts.onKeyEvent(event) { super.dispatchKeyEvent(it) }

    /** Lists the shortcuts in the system's keyboard shortcuts helper (Meta+/), in the app's language. */
    override fun onProvideKeyboardShortcuts(data: MutableList<android.view.KeyboardShortcutGroup>, menu: android.view.Menu?, deviceId: Int) {
        super.onProvideKeyboardShortcuts(data, menu, deviceId)
        data.addAll(io.github.tianli0.switchtether.ui.keys.keyboardShortcutGroups(this))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handle(intent, fresh = savedInstanceState == null)
        setContent {
            androidx.compose.runtime.CompositionLocalProvider(io.github.tianli0.switchtether.ui.keys.LocalShortcuts provides shortcuts) { SwitchTetherRoot() }
        }
        if (android.os.Build.VERSION.SDK_INT >= 37) {
            // Offer the open session detail to the user's other device (tablet ↔ phone).
            lifecycleScope.launch {
                (application as SwitchTetherApp).container.handoffSession.collect { id ->
                    runCatching {
                        setHandoffEnabled(id != null, android.app.HandoffActivityParams.Builder().setAllowHandoffWithoutPackageInstalled(false).build())
                    }
                }
            }
        }
    }

    override fun onHandoffActivityDataRequested(info: android.app.HandoffActivityDataRequestInfo): android.app.HandoffActivityData? {
        val container = (application as SwitchTetherApp).container
        val sessionId = container.handoffSession.value ?: return null
        val serverId = container.pairedServerIdCache ?: return null
        return android.app.HandoffActivityData.Builder(android.content.ComponentName(this, MainActivity::class.java))
            .setExtras(android.os.PersistableBundle().apply {
                putString(HANDOFF_SESSION, sessionId)
                putString(HANDOFF_SERVER, serverId)
            })
            .build()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /**
     * `switchtether://pair?...` VIEW intents prefill the pairing confirm screen (never auto-pair).
     * SEND / SEND_MULTIPLE from other apps become the pending share (never sent automatically);
     * a recreated activity ([fresh] false) does not take the same share twice.
     */
    private fun handle(intent: Intent?, fresh: Boolean = true) {
        if (intent != null && fresh && ShareIntake.isShare(intent.action)) {
            receiveShare(intent)
            return
        }
        intent?.getStringExtra(EXTRA_SESSION_ID)?.let { (application as SwitchTetherApp).container.openSessionRequest.value = it }
        intent?.getStringExtra(EXTRA_TERMINAL_ID)?.let { (application as SwitchTetherApp).container.openTerminalRequest.value = it }
        if (intent?.getBooleanExtra(EXTRA_SHOW_UPDATE, false) == true) (application as SwitchTetherApp).container.showUpdateRequest.value = true
        // Handoff from another device: open the session only if this phone is paired with the same desktop.
        val handoffSession = intent?.getStringExtra(HANDOFF_SESSION)
        val handoffServer = intent?.getStringExtra(HANDOFF_SERVER)
        if (handoffSession != null && handoffServer != null) {
            val container = (application as SwitchTetherApp).container
            if (container.pairedServerIdCache == handoffServer) container.openSessionRequest.value = handoffSession
        }
        val data = intent?.data ?: return
        if (intent.action == Intent.ACTION_VIEW && data.scheme == "switchtether") {
            (application as SwitchTetherApp).container.incomingPairingLink.value = data.toString()
        }
    }

    private fun receiveShare(intent: Intent) {
        val raw = runCatching { ShareIntents.read(intent) }.getOrNull() ?: return
        val content = ShareIntake.parse(raw, ShareIntents.ownAuthority(this)) ?: return
        val container = (application as SwitchTetherApp).container
        // A newer share replaces one still waiting (its cache copies go with it).
        val replaced = container.shareInbox.state.value?.content
        container.shareInbox.receive(content)
        val app = applicationContext
        container.appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            replaced?.items?.forEach { it.cachedPath?.let { p -> java.io.File(p).parentFile?.deleteRecursively() } }
            ShareFiles.cleanup(app, keep = content.items)
        }
    }
}
