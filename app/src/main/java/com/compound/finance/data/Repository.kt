package com.compound.finance.data

import android.content.Context
import android.net.Uri
import com.compound.finance.core.BudgetMath
import com.compound.finance.core.Categories
import com.compound.finance.core.Categorizer
import com.compound.finance.core.Direction
import com.compound.finance.core.Frequency
import com.compound.finance.core.ParseResult
import com.compound.finance.core.ParsedTx
import com.compound.finance.core.Recurring
import com.compound.finance.core.RecurringDetector
import com.compound.finance.core.Rule
import com.compound.finance.core.SmsParser
import com.compound.finance.core.TxKind
import com.compound.finance.core.TxPoint
import com.compound.finance.work.Notifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

class Repository(private val context: Context, val db: AppDatabase, val prefs: Prefs) {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val lock = Mutex()

    // ------------------------------------------------------------------ time helpers
    fun monthRange(ym: YearMonth): Pair<Long, Long> =
        ym.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli() to
            ym.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()

    fun dateOf(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    // ------------------------------------------------------------------ SMS import
    data class ImportResult(val read: Int, val transactions: Int, val statements: Int, val review: Int)

    /** Reads the SMS inbox from [sinceMillis]. Only authorised senders are looked at or stored. */
    suspend fun importInbox(sinceMillis: Long): ImportResult = withContext(Dispatchers.IO) {
        val filter = prefs.current.filter
        val uri = Uri.parse("content://sms/inbox")
        var read = 0; var txs = 0; var stmts = 0; var review = 0
        val known = db.sms().keys().toHashSet()
        context.contentResolver.query(uri, arrayOf("address", "body", "date"), "date >= ?",
            arrayOf(sinceMillis.toString()), "date ASC")?.use { c ->
            val iA = c.getColumnIndexOrThrow("address")
            val iB = c.getColumnIndexOrThrow("body")
            val iD = c.getColumnIndexOrThrow("date")
            while (c.moveToNext()) {
                val sender = c.getString(iA) ?: continue
                val body = c.getString(iB) ?: continue
                if (!filter.allows(sender, body)) continue
                val ts = c.getLong(iD)
                if (keyOf(sender, body, ts) in known) continue
                read++
                when (process(sender, body, ts, notify = false)) {
                    Outcome.TX -> txs++
                    Outcome.STATEMENT -> stmts++
                    Outcome.REVIEW -> review++
                    else -> {}
                }
            }
        }
        ImportResult(read, txs, stmts, review)
    }

    enum class Outcome { TX, STATEMENT, REVIEW, IGNORED, DUPLICATE, NOT_ALLOWED }

    private fun tally(outcomes: List<Outcome>) = ImportResult(
        read = outcomes.count { it != Outcome.DUPLICATE && it != Outcome.NOT_ALLOWED },
        transactions = outcomes.count { it == Outcome.TX },
        statements = outcomes.count { it == Outcome.STATEMENT },
        review = outcomes.count { it == Outcome.REVIEW },
    )

    /**
     * Messages you shared or pasted. There's no sender or arrival time, so the sender filter is
     * skipped and the date comes from the message text (today if it has none). Blank lines
     * separate messages when several are pasted at once.
     */
    suspend fun importText(text: String): ImportResult = withContext(Dispatchers.IO) {
        val messages = text.replace("\r", "").split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.length >= 10 }
        tally(messages.map { body ->
            val ts = SmsParser.findDateTime(body)?.atZone(zone)?.toInstant()?.toEpochMilli() ?: System.currentTimeMillis()
            process("Shared", body, ts, notify = false, trusted = true)
        })
    }

    /**
     * Reads a backup file from SMS Backup & Restore (XML). Only received messages from your
     * authorised senders are used; messages already recorded are skipped.
     */
    suspend fun importBackup(uri: Uri, alerts: Boolean = false): ImportResult = withContext(Dispatchers.IO) {
        val filter = prefs.current.filter
        val known = db.sms().keys().toHashSet()
        val outcomes = mutableListOf<Outcome>()
        context.contentResolver.openInputStream(uri)?.use { input ->
            val xp = android.util.Xml.newPullParser()
            xp.setFeature(org.xmlpull.v1.XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            xp.setInput(input, null)
            var ev = xp.eventType
            while (ev != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (ev == org.xmlpull.v1.XmlPullParser.START_TAG && xp.name == "sms") {
                    val type = xp.getAttributeValue(null, "type")
                    val sender = xp.getAttributeValue(null, "address")
                    val body = xp.getAttributeValue(null, "body")
                    val ts = xp.getAttributeValue(null, "date")?.toLongOrNull()
                    // type 1 = received; skip sent messages and drafts
                    if (type == "1" && sender != null && body != null && ts != null &&
                        filter.allows(sender, body) && keyOf(sender, body, ts) !in known) {
                        outcomes += process(sender, body, ts, notify = false, alerts = alerts)
                    }
                }
                ev = xp.next()
            }
        } ?: throw IllegalStateException("Couldn't open that file.")
        tally(outcomes)
    }

    /**
     * Checks the SMS Backup & Restore folder you picked and imports the newest backup if it's
     * new since last time. Returns null when there's nothing to do.
     */
    suspend fun syncBackupFolder(alerts: Boolean): ImportResult? = withContext(Dispatchers.IO) {
        val s = prefs.current
        if (s.backupFolder.isBlank()) return@withContext null
        val dir = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, Uri.parse(s.backupFolder))
            ?: return@withContext null
        val newest = dir.listFiles()
            .filter { f -> f.isFile && (f.name ?: "").lowercase().let { it.endsWith(".xml") && !it.startsWith("calls") } }
            .maxByOrNull { it.lastModified() }
        prefs.update { it.copy(lastBackupCheck = System.currentTimeMillis()) }
        if (newest == null || newest.lastModified() <= s.backupImportedAt) return@withContext null
        val r = importBackup(newest.uri, alerts)
        prefs.update { it.copy(backupImportedAt = newest.lastModified()) }
        r
    }

    /** Handles one SMS (from the inbox import or a live broadcast). Safe to call twice. */
    suspend fun process(sender: String, body: String, timestamp: Long, notify: Boolean,
                        trusted: Boolean = false, alerts: Boolean = notify): Outcome = lock.withLock {
        val s = prefs.current
        if (!trusted && !s.filter.allows(sender, body)) return Outcome.NOT_ALLOWED
        // Shared messages have no arrival time, so the same text always gets the same key.
        val key = if (trusted) keyOf("shared", body, 0) else keyOf(sender, body, timestamp)
        when (val r = SmsParser.parse(sender, body, s.filter, trusted)) {
            is ParseResult.Transaction -> {
                if (db.sms().insert(SmsEntity(key, sender, body, timestamp, "PARSED")) == -1L) return Outcome.DUPLICATE
                val tx = toEntity(r.tx, key, timestamp)
                val id = db.tx().insert(tx)
                db.sms().setStatus(key, "PARSED", id)
                val saved = tx.copy(id = id)
                afterTransaction(saved, r.tx)
                if (notify && s.notifyEachTransaction) Notifier.transaction(context, saved, s)
                if (alerts && s.notifyBudgets) checkBudget(saved.category)
                Outcome.TX
            }
            is ParseResult.Statement -> {
                if (db.sms().insert(SmsEntity(key, sender, body, timestamp, "STATEMENT")) == -1L) return Outcome.DUPLICATE
                applyStatement(r.statement, timestamp)
                Outcome.STATEMENT
            }
            is ParseResult.Unrecognized -> {
                if (db.sms().insert(SmsEntity(key, sender, body, timestamp, "REVIEW")) == -1L) return Outcome.DUPLICATE
                Outcome.REVIEW
            }
            is ParseResult.Ignored -> {
                // Remember we've seen it, but never keep the text of one-time passwords.
                val keep = if (r.reason == "one-time password") "" else body
                db.sms().insert(SmsEntity(key, sender, keep, timestamp, "IGNORED", r.reason))
                Outcome.IGNORED
            }
        }
    }

    private fun keyOf(sender: String, body: String, ts: Long): String {
        val md = MessageDigest.getInstance("SHA-1").digest("$sender|$ts|$body".toByteArray())
        return md.joinToString("") { "%02x".format(it) }.take(24)
    }

    private suspend fun categorizer(): Categorizer =
        Categorizer(Categorizer.seed + db.rules().all().map { Rule(it.pattern, it.category, fromUser = true) })

    private suspend fun toEntity(p: ParsedTx, key: String?, ts: Long): TxEntity {
        val category = categorizer().categorize(p.merchant, p.kind, p.direction)
        return TxEntity(
            smsKey = key, timestamp = ts, amount = p.amount, currency = p.currency,
            direction = p.direction.name, kind = p.kind.name, merchant = p.merchant,
            merchantKey = Categorizer.normalize(p.merchant), category = category,
            bank = p.bank, account = p.account, balanceAfter = p.balanceAfter,
        )
    }

    /** Links a new transaction to loans, cards and bills it pays. */
    private suspend fun afterTransaction(tx: TxEntity, parsed: ParsedTx?) {
        val today = dateOf(tx.timestamp)
        val text = listOfNotNull(tx.merchant, tx.note).joinToString(" ").lowercase()
        for (d in db.debts().all()) {
            when (d.type) {
                "CARD" -> if (tx.account != null && tx.account == d.account) {
                    if (tx.kind == TxKind.CARD_PAYMENT.name) {
                        db.debts().upsert(d.copy(
                            statementDue = (d.statementDue - tx.amount).coerceAtLeast(0.0),
                            minDue = if (tx.amount >= d.minDue) 0.0 else d.minDue - tx.amount,
                            outstanding = (d.outstanding - tx.amount).coerceAtLeast(0.0),
                            lastPaidEpochDay = today.toEpochDay()))
                    } else if (parsed != null && parsed.isCard && parsed.balanceAfter != null && d.creditLimit > 0) {
                        // "Available limit" tells us the card balance exactly.
                        val available: Double = parsed.balanceAfter
                        db.debts().upsert(d.copy(outstanding = (d.creditLimit - available).coerceAtLeast(0.0)))
                    } else if (tx.direction == Direction.DEBIT.name) {
                        db.debts().upsert(d.copy(outstanding = d.outstanding + tx.amount))
                    }
                }
                "LOAN" -> {
                    val kw = d.matchKeyword.trim().lowercase()
                    val looksLikeEmi = tx.kind == TxKind.EMI.name || (kw.isNotEmpty() && text.contains(kw))
                    val amountMatches = d.emi > 0 && kotlin.math.abs(tx.amount - d.emi) <= d.emi * 0.02
                    if (tx.direction == Direction.DEBIT.name && looksLikeEmi && (amountMatches || kw.isNotEmpty() && text.contains(kw))) {
                        val interest = d.outstanding * d.annualRate / 100 / 12
                        db.debts().upsert(d.copy(
                            outstanding = (d.outstanding - (tx.amount - interest)).coerceAtLeast(0.0),
                            lastPaidEpochDay = today.toEpochDay()))
                    }
                }
            }
        }
        if (tx.direction == Direction.DEBIT.name && tx.merchantKey.isNotEmpty()) {
            for (b in db.bills().all()) {
                if (b.matchKey.isNotEmpty() && tx.merchantKey.contains(b.matchKey)) {
                    var next = LocalDate.ofEpochDay(b.nextDue)
                    val f = Frequency.valueOf(b.frequency)
                    while (!next.isAfter(today.plusDays(7))) next = f.next(next)
                    db.bills().upsert(b.copy(lastPaidEpochDay = today.toEpochDay(), nextDue = next.toEpochDay(),
                        amount = if (b.isSubscription) tx.amount else b.amount))
                }
            }
        }
    }

    private suspend fun applyStatement(st: com.compound.finance.core.CardStatement, ts: Long) {
        val cards = db.debts().all().filter { it.type == "CARD" }
        val match = cards.firstOrNull { st.account != null && it.account == st.account }
            ?: cards.firstOrNull { st.account == null && st.bank != null && it.lender == st.bank && cards.count { c -> c.lender == st.bank } == 1 }
        val base = match ?: DebtEntity(
            type = "CARD", name = listOfNotNull(st.bank, "card", st.account?.let { "••$it" }).joinToString(" "),
            lender = st.bank ?: "", currency = st.currency, outstanding = st.totalDue ?: 0.0, account = st.account ?: "",
        )
        db.debts().upsert(base.copy(
            statementDue = st.totalDue ?: base.statementDue,
            minDue = st.minDue ?: base.minDue,
            dueDate = st.dueDate?.toEpochDay() ?: base.dueDate,
            outstanding = maxOf(base.outstanding, st.totalDue ?: 0.0),
        ))
    }

    // ------------------------------------------------------------------ edits
    suspend fun setCategory(tx: TxEntity, category: String, applyToMerchant: Boolean) {
        db.tx().update(tx.copy(category = category))
        if (applyToMerchant && tx.merchantKey.isNotBlank()) {
            db.rules().upsert(RuleEntity(pattern = tx.merchantKey, category = category))
            db.tx().byMerchant(tx.merchantKey).forEach { if (it.category != category) db.tx().update(it.copy(category = category)) }
        }
    }

    suspend fun updateTx(tx: TxEntity) = db.tx().update(tx)
    suspend fun deleteTx(tx: TxEntity) = db.tx().delete(tx)

    suspend fun addManual(date: LocalDate, amount: Double, currency: String, direction: Direction,
                          merchant: String, category: String, note: String): Long {
        val tx = TxEntity(
            smsKey = null, timestamp = date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli(),
            amount = amount, currency = currency.uppercase(), direction = direction.name,
            kind = if (direction == Direction.DEBIT) TxKind.PURCHASE.name else TxKind.OTHER.name,
            merchant = merchant.ifBlank { null }, merchantKey = Categorizer.normalize(merchant), category = category,
            bank = null, account = null, balanceAfter = null, note = note, source = "MANUAL",
        )
        val id = db.tx().insert(tx)
        afterTransaction(tx.copy(id = id), null)
        return id
    }

    suspend fun createFromReview(sms: SmsEntity, amount: Double, currency: String, direction: Direction,
                                 merchant: String, category: String) {
        val tx = TxEntity(
            smsKey = sms.key, timestamp = sms.timestamp, amount = amount, currency = currency.uppercase(),
            direction = direction.name, kind = if (direction == Direction.DEBIT) TxKind.PURCHASE.name else TxKind.OTHER.name,
            merchant = merchant.ifBlank { null }, merchantKey = Categorizer.normalize(merchant), category = category,
            bank = null, account = null, balanceAfter = null,
        )
        val id = db.tx().insert(tx)
        db.sms().setStatus(sms.key, "PARSED", id)
        if (merchant.isNotBlank()) db.rules().upsert(RuleEntity(pattern = Categorizer.normalize(merchant), category = category))
    }

    suspend fun dismissReview(sms: SmsEntity) = db.sms().setStatus(sms.key, "DISMISSED", null)
    suspend fun dismissSender(sender: String) = db.sms().dismissSender(sender)

    // ------------------------------------------------------------------ analysis
    fun isSpending(tx: TxEntity) = tx.direction == Direction.DEBIT.name && !tx.excluded && tx.category !in Categories.notSpending
    fun isIncome(tx: TxEntity) = tx.direction == Direction.CREDIT.name && !tx.excluded &&
        tx.category !in Categories.notSpending && tx.kind != TxKind.CARD_PAYMENT.name

    suspend fun spentInCategory(category: String, ym: YearMonth): Double {
        val (from, to) = monthRange(ym)
        val s = prefs.current
        return db.tx().since(from).filter { it.timestamp < to && it.category == category && isSpending(it) }
            .sumOf { s.toMain(it.amount, it.currency) }
    }

    private suspend fun checkBudget(category: String) {
        val budget = db.budgets().all().firstOrNull { it.category == category } ?: return
        val ym = YearMonth.now()
        val spent = spentInCategory(category, ym)
        val used = spent / budget.monthlyLimit
        for (level in listOf(1.0, 0.8)) {
            val k = "$ym:$category:$level"
            if (used >= level && !prefs.alertSent(k)) {
                prefs.markAlert(k)
                Notifier.budget(context, category, spent, budget.monthlyLimit, prefs.current)
                break
            }
        }
    }

    suspend fun recurring(): List<Recurring> {
        val s = prefs.current
        val today = LocalDate.now()
        val from = today.minusMonths(13).atStartOfDay(zone).toInstant().toEpochMilli()
        val pts = db.tx().since(from).filter { isSpending(it) && it.merchantKey.isNotBlank() }.map {
            TxPoint(it.id, dateOf(it.timestamp), it.merchantKey, it.merchant ?: it.merchantKey, s.toMain(it.amount, it.currency), it.category)
        }
        val billKeys = db.bills().all().map { it.matchKey }.filter { it.isNotEmpty() }
        return RecurringDetector.detect(pts, today).filter { r -> billKeys.none { r.merchantKey.contains(it) } }
    }

    suspend fun duplicates() = run {
        val s = prefs.current
        val from = LocalDate.now().minusDays(45).atStartOfDay(zone).toInstant().toEpochMilli()
        RecurringDetector.duplicates(db.tx().since(from).filter { isSpending(it) && it.merchantKey.isNotBlank() }.map {
            TxPoint(it.id, dateOf(it.timestamp), it.merchantKey, it.merchant ?: "", s.toMain(it.amount, it.currency), it.category)
        })
    }

    data class Due(val title: String, val detail: String, val date: LocalDate, val amount: Double, val currency: String, val kind: String)

    /** Everything due in the next [days] days: EMIs, card payments and bills. */
    suspend fun upcomingDues(days: Long = 14): List<Due> {
        val today = LocalDate.now()
        val until = today.plusDays(days)
        val out = mutableListOf<Due>()
        for (d in db.debts().all()) {
            if (d.type == "LOAN" && d.emi > 0 && d.dueDay in 1..31 && d.outstanding > 0) {
                var due = today.withDayOfMonth(minOf(d.dueDay, today.lengthOfMonth()))
                val paidThisCycle = d.lastPaidEpochDay >= due.minusDays(10).toEpochDay()
                if (due.isBefore(today) || paidThisCycle) due = due.plusMonths(1).let { it.withDayOfMonth(minOf(d.dueDay, it.lengthOfMonth())) }
                if (!due.isAfter(until)) out += Due(d.name, "EMI", due, d.emi, d.currency, "LOAN")
            }
            if (d.type == "CARD" && d.dueDate > 0 && d.statementDue > 0) {
                val due = LocalDate.ofEpochDay(d.dueDate)
                if (!due.isAfter(until) && !due.isBefore(today.minusDays(3)))
                    out += Due(d.name, if (d.minDue > 0) "Minimum ${"%,.2f".format(d.minDue)}" else "Card payment", due, d.statementDue, d.currency, "CARD")
            }
        }
        for (b in db.bills().all()) {
            val due = LocalDate.ofEpochDay(b.nextDue)
            if (!due.isAfter(until)) out += Due(b.name, if (b.isSubscription) "Subscription" else "Bill", due, b.amount, b.currency, "BILL")
        }
        return out.sortedBy { it.date }
    }

    data class Insight(val title: String, val detail: String, val warning: Boolean)

    /** Plain-language observations shown on the home screen. All computed on the phone. */
    suspend fun insights(): List<Insight> {
        val s = prefs.current
        val today = LocalDate.now()
        val ym = YearMonth.from(today)
        val out = mutableListOf<Insight>()
        val (from, _) = monthRange(ym.minusMonths(3))
        val all = db.tx().since(from)
        fun spendIn(m: YearMonth, upToDay: Int = 31) = all.filter {
            val d = dateOf(it.timestamp); YearMonth.from(d) == m && d.dayOfMonth <= upToDay && isSpending(it)
        }
        val thisMonth = spendIn(ym).sumOf { s.toMain(it.amount, it.currency) }
        val lastSamePoint = spendIn(ym.minusMonths(1), today.dayOfMonth).sumOf { s.toMain(it.amount, it.currency) }
        if (lastSamePoint > 0 && thisMonth > lastSamePoint * 1.2)
            out += Insight("Spending is up ${"%.0f".format((thisMonth / lastSamePoint - 1) * 100)}% on last month",
                "${fmt(thisMonth, s.mainCurrency)} so far, against ${fmt(lastSamePoint, s.mainCurrency)} by this day last month.", true)

        for (b in db.budgets().all()) {
            val spent = spendIn(ym).filter { it.category == b.category }.sumOf { s.toMain(it.amount, it.currency) }
            val p = BudgetMath.pace(spent, b.monthlyLimit, today)
            if (spent > b.monthlyLimit) out += Insight("${b.category} is over budget",
                "${fmt(spent, s.mainCurrency)} spent of ${fmt(b.monthlyLimit, s.mainCurrency)}.", true)
            else if (p.overPace) out += Insight("${b.category} is ahead of pace",
                "${"%.0f".format(p.used * 100)}% used with ${"%.0f".format(p.monthElapsed * 100)}% of the month gone. On track for ${fmt(p.projected, s.mainCurrency)}.", true)
        }

        // Unusually large purchase: over 3x the typical amount in its category.
        val recent = all.filter { isSpending(it) && dateOf(it.timestamp).isAfter(today.minusDays(7)) }
        val byCat = all.filter { isSpending(it) }.groupBy { it.category }
        for (t in recent) {
            val amounts = byCat[t.category].orEmpty().map { s.toMain(it.amount, it.currency) }.sorted()
            if (amounts.size >= 6) {
                val median = amounts[amounts.size / 2]
                val v = s.toMain(t.amount, t.currency)
                if (v > median * 3 && v > 200) out += Insight("Large ${t.category.lowercase()} payment",
                    "${fmt(t.amount, t.currency)} at ${t.merchant ?: "unknown"} on ${dateOf(t.timestamp)}.", false)
            }
        }
        duplicates().take(3).forEach { d ->
            out += Insight("Possible double charge", "${d.second.merchant}: ${fmt(d.second.amount, s.mainCurrency)} on ${d.first.date} and ${d.second.date}.", true)
        }
        recurring().filter { it.priceIncrease }.take(3).forEach { r ->
            out += Insight("${r.merchant} went up", "Now ${fmt(r.lastAmount, s.mainCurrency)}, usually ${fmt(r.typicalAmount, s.mainCurrency)}.", true)
        }
        val unknown = spendIn(ym).count { it.category == Categories.UNCATEGORISED }
        if (unknown > 0) out += Insight("$unknown transaction${if (unknown > 1) "s" else ""} need a category",
            "Set one once and every future payment to that merchant is sorted automatically.", false)
        return out
    }

    // ------------------------------------------------------------------ export
    suspend fun exportCsv(): String {
        val sb = StringBuilder("date,direction,amount,currency,category,merchant,bank,account,note\n")
        db.tx().all().forEach { t ->
            fun q(x: String?) = "\"" + (x ?: "").replace("\"", "\"\"") + "\""
            sb.append("${dateOf(t.timestamp)},${t.direction},${t.amount},${t.currency},${q(t.category)},${q(t.merchant)},${q(t.bank)},${q(t.account)},${q(t.note)}\n")
        }
        return sb.toString()
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) { db.clearAllTables() }

    companion object {
        fun fmt(v: Double, ccy: String): String = "$ccy ${"%,.0f".format(v)}"
    }
}
