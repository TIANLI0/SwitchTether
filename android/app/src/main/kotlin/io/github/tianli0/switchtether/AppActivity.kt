package io.github.tianli0.switchtether

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * What every window of the app shares: edge-to-edge, hardware keyboard shortcuts and Android 17
 * Handoff of the open session. Extends FragmentActivity because BiometricPrompt (step-up) needs it;
 * the UI is 100 % Compose.
 */
abstract class AppActivity : FragmentActivity() {
    /** Hardware keyboard shortcuts of this window (docs/ANDROID_UI.md §35). */
    protected val shortcuts = io.github.tianli0.switchtether.ui.keys.ShortcutRegistry()

    protected val container: AppContainer get() = (application as SwitchTetherApp).container

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
        if (android.os.Build.VERSION.SDK_INT >= 37) {
            // Offer the open session detail to the user's other device (tablet ↔ phone).
            lifecycleScope.launch {
                container.handoffSession.collect { id ->
                    runCatching {
                        setHandoffEnabled(id != null, android.app.HandoffActivityParams.Builder().setAllowHandoffWithoutPackageInstalled(false).build())
                    }
                }
            }
        }
    }

    /** The receiving device opens the session through [MainActivity], which checks the pairing. */
    @androidx.annotation.RequiresApi(37)
    override fun onHandoffActivityDataRequested(info: android.app.HandoffActivityDataRequestInfo): android.app.HandoffActivityData? {
        val sessionId = container.handoffSession.value ?: return null
        val serverId = container.pairedServerIdCache ?: return null
        return android.app.HandoffActivityData.Builder(android.content.ComponentName(this, MainActivity::class.java))
            .setExtras(android.os.PersistableBundle().apply {
                putString(MainActivity.HANDOFF_SESSION, sessionId)
                putString(MainActivity.HANDOFF_SERVER, serverId)
            })
            .build()
    }

    /** Puts this window's content in place, with its shortcuts. */
    protected fun setAppContent(content: @androidx.compose.runtime.Composable () -> Unit) {
        setContent {
            androidx.compose.runtime.CompositionLocalProvider(io.github.tianli0.switchtether.ui.keys.LocalShortcuts provides shortcuts, content = content)
        }
    }
}
