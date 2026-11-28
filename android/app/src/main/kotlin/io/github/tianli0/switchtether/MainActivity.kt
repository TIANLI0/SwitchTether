package io.github.tianli0.switchtether

import android.content.Intent
import android.os.Bundle
import kotlinx.coroutines.launch
import io.github.tianli0.switchtether.share.ShareFiles
import io.github.tianli0.switchtether.share.ShareIntake
import io.github.tianli0.switchtether.share.ShareIntents
import io.github.tianli0.switchtether.ui.SwitchTetherRoot

/**
 * The main window: onboarding, the tabs and (on tablets) the list | detail panes. Detail pages on a
 * phone open in their own [PageActivity] above it.
 */
class MainActivity : AppActivity() {
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent, fresh = savedInstanceState == null)
        setAppContent { SwitchTetherRoot() }
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
        intent?.getStringExtra(EXTRA_SESSION_ID)?.let { container.openSessionRequest.value = it }
        intent?.getStringExtra(EXTRA_TERMINAL_ID)?.let { container.openTerminalRequest.value = it }
        if (intent?.getBooleanExtra(EXTRA_SHOW_UPDATE, false) == true) container.showUpdateRequest.value = true
        // Handoff from another device: open the session only if this phone is paired with the same desktop.
        val handoffSession = intent?.getStringExtra(HANDOFF_SESSION)
        val handoffServer = intent?.getStringExtra(HANDOFF_SERVER)
        if (handoffSession != null && handoffServer != null) {
            if (container.pairedServerIdCache == handoffServer) container.openSessionRequest.value = handoffSession
        }
        val data = intent?.data ?: return
        if (intent.action == Intent.ACTION_VIEW && data.scheme == "switchtether") {
            container.incomingPairingLink.value = data.toString()
        }
    }

    private fun receiveShare(intent: Intent) {
        val raw = runCatching { ShareIntents.read(intent) }.getOrNull() ?: return
        val content = ShareIntake.parse(raw, ShareIntents.ownAuthority(this)) ?: return
        val container = container
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
