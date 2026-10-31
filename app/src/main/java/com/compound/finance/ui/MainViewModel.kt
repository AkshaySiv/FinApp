package com.compound.finance.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.compound.finance.CompoundApp
import com.compound.finance.core.Direction
import com.compound.finance.core.Frequency
import com.compound.finance.core.Recurring
import com.compound.finance.data.BillEntity
import com.compound.finance.data.BudgetEntity
import com.compound.finance.data.DebtEntity
import com.compound.finance.data.Repository
import com.compound.finance.data.Settings
import com.compound.finance.data.SmsEntity
import com.compound.finance.data.TxEntity
import com.compound.finance.sync.CompoundSync
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.time.YearMonth

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val compound = app as CompoundApp
    val repo: Repository = compound.repo
    private val sync: CompoundSync = compound.sync

    val settings: StateFlow<Settings> = repo.prefs.state
    val month = MutableStateFlow(YearMonth.now())
    val tab = MutableStateFlow("home")

    val monthTx: StateFlow<List<TxEntity>> = month.flatMapLatest { ym ->
        val (from, to) = repo.monthRange(ym)
        repo.db.tx().between(from, to)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Last 6 months, for trends on the home and budget screens. */
    val recentTx: StateFlow<List<TxEntity>> = repo.db.tx().between(
        repo.monthRange(YearMonth.now().minusMonths(5)).first, Long.MAX_VALUE
    ).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val budgets = repo.db.budgets().observe().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val debts = repo.db.debts().observe().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val bills = repo.db.bills().observe().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val review = repo.db.sms().review().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val reviewCount = repo.db.sms().reviewCount().stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    val txCount = repo.db.tx().count().stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val dues = MutableStateFlow<List<Repository.Due>>(emptyList())
    val insights = MutableStateFlow<List<Repository.Insight>>(emptyList())
    val recurring = MutableStateFlow<List<Recurring>>(emptyList())
    val netWorth = MutableStateFlow<CompoundSync.NetWorth?>(null)
    val message = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            combine(repo.db.tx().count(), debts, bills, budgets, settings) { _, _, _, _, _ -> Unit }
                .collectLatest { refreshDerived() }
        }
        if (settings.value.compoundUrl.isNotBlank()) refreshNetWorth(quiet = true)
    }

    private suspend fun refreshDerived() {
        runCatching { dues.value = repo.upcomingDues(30) }
        runCatching { insights.value = repo.insights() }
        runCatching { recurring.value = repo.recurring() }
    }

    fun say(m: String) { message.value = m }

    // ---------------------------------------------------------------- SMS
    fun importInbox(months: Long) = viewModelScope.launch {
        busy.value = true
        try {
            val since = LocalDate.now().minusMonths(months).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            val r = repo.importInbox(since)
            repo.prefs.update { it.copy(onboarded = true) }
            say(if (r.read == 0) "No new messages from your authorised senders."
                else "Read ${r.read} messages: ${r.transactions} transactions, ${r.statements} card statements" +
                    (if (r.review > 0) ", ${r.review} to review." else "."))
        } catch (e: SecurityException) {
            say("SMS access isn't allowed yet. Grant it from the Home screen.")
        } catch (e: Exception) {
            say("Couldn't read messages: ${e.message}")
        } finally { busy.value = false }
    }

    // ---------------------------------------------------------------- transactions
    fun setCategory(tx: TxEntity, category: String, all: Boolean) = viewModelScope.launch {
        repo.setCategory(tx, category, all)
        say(if (all && tx.merchantKey.isNotBlank()) "${tx.merchant ?: "This merchant"} will always be $category" else "Category changed")
    }
    fun updateTx(tx: TxEntity) = viewModelScope.launch { repo.updateTx(tx) }
    fun deleteTx(tx: TxEntity) = viewModelScope.launch { repo.deleteTx(tx); say("Transaction deleted") }
    fun addManual(date: LocalDate, amount: Double, ccy: String, dir: Direction, merchant: String, category: String, note: String) =
        viewModelScope.launch { repo.addManual(date, amount, ccy, dir, merchant, category, note); say("Transaction added") }

    // ---------------------------------------------------------------- review
    fun createFromReview(s: SmsEntity, amount: Double, ccy: String, dir: Direction, merchant: String, category: String) =
        viewModelScope.launch { repo.createFromReview(s, amount, ccy, dir, merchant, category); say("Transaction added") }
    fun dismiss(s: SmsEntity) = viewModelScope.launch { repo.dismissReview(s) }
    fun dismissSender(sender: String) = viewModelScope.launch { repo.dismissSender(sender); say("Hidden all from $sender") }

    // ---------------------------------------------------------------- budgets, debts, bills
    fun saveBudget(category: String, limit: Double) = viewModelScope.launch {
        if (limit > 0) repo.db.budgets().upsert(BudgetEntity(category, limit)) else repo.db.budgets().delete(category)
    }
    fun saveDebt(d: DebtEntity) = viewModelScope.launch { repo.db.debts().upsert(d); say("Saved") }
    fun deleteDebt(d: DebtEntity) = viewModelScope.launch { repo.db.debts().delete(d); say("Deleted") }
    fun markCardPaid(d: DebtEntity) = viewModelScope.launch {
        repo.db.debts().upsert(d.copy(outstanding = (d.outstanding - d.statementDue).coerceAtLeast(0.0),
            statementDue = 0.0, minDue = 0.0, lastPaidEpochDay = LocalDate.now().toEpochDay()))
        say("Marked as paid")
    }
    fun saveBill(b: BillEntity) = viewModelScope.launch { repo.db.bills().upsert(b); say("Saved") }
    fun deleteBill(b: BillEntity) = viewModelScope.launch { repo.db.bills().delete(b); say("Deleted") }
    fun markBillPaid(b: BillEntity) = viewModelScope.launch {
        val f = Frequency.valueOf(b.frequency)
        repo.db.bills().upsert(b.copy(lastPaidEpochDay = LocalDate.now().toEpochDay(),
            nextDue = f.next(LocalDate.ofEpochDay(b.nextDue)).toEpochDay()))
        say("${b.name} marked as paid")
    }
    fun addBillFromRecurring(r: Recurring) = viewModelScope.launch {
        repo.db.bills().upsert(BillEntity(
            name = r.merchant, amount = r.typicalAmount, currency = settings.value.mainCurrency,
            frequency = r.frequency.name, nextDue = r.nextDate.toEpochDay(), category = r.category,
            matchKey = r.merchantKey, isSubscription = r.category == "Subscriptions" || r.typicalAmount < 300,
        ))
        say("${r.merchant} added to bills")
    }

    // ---------------------------------------------------------------- settings & sync
    fun updateSettings(change: (Settings) -> Settings) = repo.prefs.update(change)

    fun refreshNetWorth(quiet: Boolean = false) = viewModelScope.launch {
        try { netWorth.value = sync.netWorth() }
        catch (e: Exception) { if (!quiet) say("Couldn't reach Compound: ${e.message}") }
    }

    fun syncNow() = viewModelScope.launch {
        busy.value = true
        try {
            val n = sync.push()
            netWorth.value = sync.netWorth()
            say(if (n == 0) "Up to date with Compound." else "Sent $n transactions to Compound.")
        } catch (e: Exception) { say("Sync failed: ${e.message}") }
        finally { busy.value = false }
    }

    fun exportCsv(context: Context) = viewModelScope.launch {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val f = File(dir, "compound-transactions-${LocalDate.now()}.csv")
        f.writeText(repo.exportCsv())
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", f)
        val send = Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Export transactions").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun clearAll() = viewModelScope.launch { repo.clearAll(); repo.prefs.update { it.copy(onboarded = false) }; say("All data deleted") }
}
