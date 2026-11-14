package io.github.tianli0.switchtether.domain

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import io.github.tianli0.switchtether.connection.ConnectionController
import io.github.tianli0.switchtether.connection.ConnectionState
import io.github.tianli0.switchtether.remote.usage.MoneyDisplay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/** Display currency for every cost on screen (the desktop's `usageDisplay` setting). */
val LocalMoney = staticCompositionLocalOf { MoneyDisplay.USD }

/**
 * Follows the desktop's cost display (currency + exchange rate) from `GET /v1/settings`
 * (`usageDisplay`). Read on every connect and when the Usage page refreshes; the last value is
 * kept per paired computer (display preferences only, no secrets) so offline pages still show
 * the same currency. Without the field the desktop's own default applies: USD.
 */
class MoneySettings(context: Context, private val scope: CoroutineScope, private val conn: ConnectionController) {
    private val prefs = context.getSharedPreferences("money_display", Context.MODE_PRIVATE)
    private val _display = MutableStateFlow(load())
    val display: StateFlow<MoneyDisplay> = _display.asStateFlow()

    fun start() {
        scope.launch { conn.state.collect { if (it is ConnectionState.Online) refresh() } }
    }

    suspend fun refresh() {
        val api = conn.apiOrNull() ?: return
        val settings = runCatching { api.settings() }.getOrNull() as? JsonObject ?: return
        val d = MoneyDisplay.resolve(settings["usageDisplay"])
        _display.value = d
        prefs.edit().putString("currency", d.currency).putString("rate", d.rate.toString()).apply()
    }

    private fun load(): MoneyDisplay {
        val c = prefs.getString("currency", null)?.takeIf { it in MoneyDisplay.CURRENCIES } ?: return MoneyDisplay.USD
        val r = prefs.getString("rate", null)?.toDoubleOrNull() ?: 0.0
        return if (c == "USD" || r <= 0) MoneyDisplay.USD else MoneyDisplay(c, r)
    }

    /** Unpair: forget the previous computer's preference. */
    fun clear() {
        prefs.edit().clear().apply()
        _display.value = MoneyDisplay.USD
    }
}
