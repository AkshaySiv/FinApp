package com.compound.finance.data

import android.content.Context
import com.compound.finance.core.SenderFilter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/** Small settings kept in SharedPreferences, exposed as a StateFlow so screens update live. */
data class Settings(
    val mainCurrency: String = "AED",
    /** Shown next to totals, e.g. INR beside AED. Empty to hide. */
    val secondCurrency: String = "INR",
    val senders: List<String> = SenderFilter.DEFAULT_SENDERS,
    val onlyListedSenders: Boolean = true,
    val keywords: List<String> = emptyList(),
    /** Units of each currency per 1 AED is awkward to type, so: value of 1 unit in the main currency. */
    val rates: Map<String, Double> = mapOf("USD" to 3.6725, "EUR" to 4.27, "GBP" to 4.90, "INR" to 0.0417, "SAR" to 0.979),
    val notifyEachTransaction: Boolean = true,
    val notifyBudgets: Boolean = true,
    val notifyDues: Boolean = true,
    val appLock: Boolean = false,
    val compoundUrl: String = "",
    val compoundUser: String = "",
    val compoundPassword: String = "",
    val onboarded: Boolean = false,
) {
    val filter: SenderFilter get() = SenderFilter(senders, onlyListedSenders, keywords)

    /** Converts to the main currency; unknown currencies are returned unconverted. */
    fun toMain(amount: Double, currency: String): Double =
        if (currency.equals(mainCurrency, true)) amount else amount * (rates[currency.uppercase()] ?: 1.0)

    /** Converts a main-currency amount to the second currency, or null if there's no rate. */
    fun toSecond(amountMain: Double): Double? =
        rates[secondCurrency.uppercase()]?.takeIf { secondCurrency.isNotBlank() && it > 0 }?.let { amountMain / it }

    fun hasRate(currency: String) = currency.equals(mainCurrency, true) || rates.containsKey(currency.uppercase())
}

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("compound", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<Settings> = _state
    val current: Settings get() = _state.value

    fun update(change: (Settings) -> Settings) {
        val next = change(_state.value)
        save(next)
        _state.value = next
    }

    private fun list(key: String, default: List<String>): List<String> =
        sp.getString(key, null)?.split("\n")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: default

    private fun load(): Settings {
        val d = Settings()
        val rates = sp.getString("rates", null)?.let { raw ->
            runCatching {
                val o = JSONObject(raw)
                o.keys().asSequence().associateWith { o.getDouble(it) }
            }.getOrNull()
        } ?: d.rates
        return Settings(
            mainCurrency = sp.getString("mainCurrency", d.mainCurrency)!!,
            secondCurrency = sp.getString("secondCurrency", d.secondCurrency)!!,
            senders = list("senders", d.senders),
            onlyListedSenders = sp.getBoolean("onlyListed", d.onlyListedSenders),
            keywords = list("keywords", d.keywords),
            rates = rates,
            notifyEachTransaction = sp.getBoolean("notifyTx", d.notifyEachTransaction),
            notifyBudgets = sp.getBoolean("notifyBudgets", d.notifyBudgets),
            notifyDues = sp.getBoolean("notifyDues", d.notifyDues),
            appLock = sp.getBoolean("appLock", d.appLock),
            compoundUrl = sp.getString("compoundUrl", "")!!,
            compoundUser = sp.getString("compoundUser", "")!!,
            compoundPassword = sp.getString("compoundPassword", "")!!,
            onboarded = sp.getBoolean("onboarded", false),
        )
    }

    private fun save(s: Settings) {
        sp.edit()
            .putString("mainCurrency", s.mainCurrency)
            .putString("secondCurrency", s.secondCurrency)
            .putString("senders", s.senders.joinToString("\n"))
            .putBoolean("onlyListed", s.onlyListedSenders)
            .putString("keywords", s.keywords.joinToString("\n"))
            .putString("rates", JSONObject(s.rates as Map<*, *>).toString())
            .putBoolean("notifyTx", s.notifyEachTransaction)
            .putBoolean("notifyBudgets", s.notifyBudgets)
            .putBoolean("notifyDues", s.notifyDues)
            .putBoolean("appLock", s.appLock)
            .putString("compoundUrl", s.compoundUrl)
            .putString("compoundUser", s.compoundUser)
            .putString("compoundPassword", s.compoundPassword)
            .putBoolean("onboarded", s.onboarded)
            .apply()
    }

    // Budget alert de-duplication: remember which thresholds already notified this month.
    fun alertSent(key: String): Boolean = sp.getBoolean("alert:$key", false)
    fun markAlert(key: String) = sp.edit().putBoolean("alert:$key", true).apply()
}
