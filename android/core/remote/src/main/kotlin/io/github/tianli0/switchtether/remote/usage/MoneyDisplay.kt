package io.github.tianli0.switchtether.remote.usage

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * How usage costs are shown, taken from the desktop's own setting (`settings.usageDisplay`,
 * src-tauri/src/settings.rs `UsageDisplaySettings`). Costs are stored in USD everywhere; the
 * desktop converts only for display with `rate` = how many units of [currency] one USD is.
 * Mirrors `resolveMoneyDisplay` in src/components/usage/format.ts; the phone never fetches
 * exchange rates itself, it uses whatever the desktop has.
 */
data class MoneyDisplay(val currency: String, val rate: Double) {
    val symbol: String get() = SYMBOLS[currency] ?: "$"
    val converted: Boolean get() = currency != "USD"

    companion object {
        val USD = MoneyDisplay("USD", 1.0)

        /** `DISPLAY_CURRENCIES`. */
        val CURRENCIES = listOf("USD", "CNY", "EUR", "JPY", "TWD", "TRY", "KRW")

        /** `DEFAULT_EXCHANGE_RATES`: used when neither a manual nor an automatic rate is usable. */
        val DEFAULT_RATES = mapOf("CNY" to 6.8, "EUR" to 0.87, "JPY" to 158.0, "TWD" to 32.0, "TRY" to 48.0, "KRW" to 1325.0)

        /** `CURRENCY_SYMBOL` (CNY and JPY are both written ¥, as each country does). */
        val SYMBOLS = mapOf("USD" to "$", "CNY" to "¥", "EUR" to "€", "JPY" to "¥", "TWD" to "NT$", "TRY" to "₺", "KRW" to "₩")

        /** `FEWER_DIGITS`: yen and won show two fewer decimals. */
        internal val FEWER_DIGITS = mapOf("JPY" to 2, "KRW" to 2)

        /**
         * `resolveMoneyDisplay(settings.usageDisplay)`: unknown currency → USD; in "auto" mode the
         * fetched rate wins over the manual one; an invalid rate falls through to the next source.
         * [usageDisplay] is the `usageDisplay` object of `GET /v1/settings` (null when the desktop
         * does not send it → the desktop's own default, USD).
         */
        fun resolve(usageDisplay: JsonElement?): MoneyDisplay {
            val o = usageDisplay as? JsonObject ?: return USD
            val currency = (o["currency"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it in CURRENCIES } ?: return USD
            if (currency == "USD") return USD
            fun rateIn(key: String) = ((o[key] as? JsonObject)?.get(currency) as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
            val auto = (o["rateSource"] as? JsonPrimitive)?.takeIf { it.isString }?.content == "auto"
            val candidates = if (auto) listOf(rateIn("autoExchangeRates"), rateIn("exchangeRates")) else listOf(rateIn("exchangeRates"))
            val rate = candidates.firstOrNull { it != null && it.isFinite() && it > 0 } ?: DEFAULT_RATES.getValue(currency)
            return MoneyDisplay(currency, rate)
        }
    }
}
