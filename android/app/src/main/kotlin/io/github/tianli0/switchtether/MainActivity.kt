package io.github.tianli0.switchtether

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import io.github.tianli0.switchtether.ui.SwitchTetherRoot

/**
 * Single activity. Extends FragmentActivity because BiometricPrompt (step-up) needs it; the UI
 * is 100 % Compose (no WebView anywhere).
 */
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handle(intent)
        setContent { SwitchTetherRoot() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** `switchtether://pair?...` VIEW intents prefill the pairing confirm screen (never auto-pair). */
    private fun handle(intent: Intent?) {
        val data = intent?.data ?: return
        if (intent.action == Intent.ACTION_VIEW && data.scheme == "switchtether") {
            (application as SwitchTetherApp).container.incomingPairingLink.value = data.toString()
        }
    }
}
