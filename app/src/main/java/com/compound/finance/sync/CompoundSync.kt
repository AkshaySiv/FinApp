package com.compound.finance.sync

import android.util.Base64
import com.compound.finance.data.Repository
import com.compound.finance.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Talks to the Compound server you already run: pulls net worth, and pushes income and
 * spending so the server's savings-rate figures include it. Optional; the app works without it.
 */
class CompoundSync(private val repo: Repository) {

    data class NetWorth(val currency: String, val netWorth: Double, val assets: Double, val liabilities: Double,
                        val others: Map<String, Double>, val asOf: String)

    private fun conn(s: Settings, path: String, method: String): HttpURLConnection {
        val base = s.compoundUrl.trim().trimEnd('/')
        require(base.startsWith("http")) { "Add your Compound server address in Settings." }
        val c = URL(base + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 8000
        c.readTimeout = 15000
        if (s.compoundUser.isNotBlank()) {
            val token = Base64.encodeToString("${s.compoundUser}:${s.compoundPassword}".toByteArray(), Base64.NO_WRAP)
            c.setRequestProperty("Authorization", "Basic $token")
        }
        return c
    }

    private fun read(c: HttpURLConnection): String {
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
        if (code == 401) throw IllegalStateException("The server rejected the username or password.")
        if (code !in 200..299) throw IllegalStateException("Server answered $code: ${body.take(160)}")
        return body
    }

    suspend fun netWorth(): NetWorth = withContext(Dispatchers.IO) {
        val s = repo.prefs.current
        val c = conn(s, "/api/summary?ccy=${s.mainCurrency}", "GET")
        val j = JSONObject(read(c))
        val alt = j.optJSONObject("alt_rates")
        val nw = j.getDouble("net_worth")
        val others = buildMap { alt?.keys()?.forEach { k -> put(k, nw * alt.getDouble(k)) } }
        NetWorth(j.getString("currency"), nw, j.getDouble("assets"), j.getDouble("liabilities"), others, j.getString("date"))
    }

    /** Sends transactions that haven't been sent yet. Returns how many were sent. */
    suspend fun push(): Int = withContext(Dispatchers.IO) {
        val s = repo.prefs.current
        val pending = repo.db.tx().unsynced().filter { repo.isSpending(it) || repo.isIncome(it) }
        val done = mutableListOf<Long>()
        for (t in pending) {
            val body = JSONObject()
                .put("date", repo.dateOf(t.timestamp).toString())
                .put("type", if (t.direction == "CREDIT") "income" else "expense")
                .put("amount", t.amount)
                .put("currency", t.currency)
                .put("category", t.category)
                .put("note", listOfNotNull(t.merchant, t.note.ifBlank { null }).joinToString(" · ").take(200))
            val c = conn(s, "/api/transactions", "POST")
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            read(c)
            done += t.id
            if (done.size % 50 == 0) { repo.db.tx().markSynced(done.toList()); done.clear() }
        }
        if (done.isNotEmpty()) repo.db.tx().markSynced(done)
        pending.size
    }
}
