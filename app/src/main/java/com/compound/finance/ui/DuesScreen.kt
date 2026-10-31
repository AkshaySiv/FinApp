@file:OptIn(ExperimentalMaterial3Api::class)

package com.compound.finance.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.compound.finance.core.Categories
import com.compound.finance.core.Frequency
import com.compound.finance.core.LoanMath
import com.compound.finance.data.BillEntity
import com.compound.finance.data.DebtEntity
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

private val monthYear = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH)

@Composable
fun DuesScreen(vm: MainViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
            listOf("Loans & EMIs", "Cards", "Bills").forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
            }
        }
        when (tab) {
            0 -> LoansTab(vm)
            1 -> CardsTab(vm)
            else -> BillsTab(vm)
        }
    }
}

// ------------------------------------------------------------------ loans
@Composable
private fun LoansTab(vm: MainViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val debts by vm.debts.collectAsStateWithLifecycle()
    val loans = debts.filter { it.type == "LOAN" }
    var editing by remember { mutableStateOf<DebtEntity?>(null) }
    val today = LocalDate.now()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        if (loans.isEmpty()) {
            EmptyState("No loans added",
                "Add each loan with its outstanding balance, rate and EMI. Compound tracks months left and total interest, and marks the EMI paid when the bank SMS arrives.",
                "Add a loan") { editing = newLoan(s.mainCurrency) }
        } else {
            val total = loans.sumOf { s.toMain(it.outstanding, it.currency) }
            val monthly = loans.sumOf { s.toMain(it.emi, it.currency) }
            val projections = loans.map { it to LoanMath.project(it.outstanding, it.annualRate, it.emi, today) }
            val debtFree = projections.filter { it.second.payable }.maxOfOrNull { it.second.payoffDate }
            Muted("Total outstanding")
            Text(money(total, s.mainCurrency, decimals = false), style = MaterialTheme.typography.displaySmall)
            Muted("${money(monthly, s.mainCurrency, decimals = false)} a month in EMIs" +
                (debtFree?.let { " · debt-free by ${it.format(monthYear)}" } ?: ""))

            // Prepayment priority: the highest rate costs the most per dirham.
            if (loans.size > 1) {
                val first = loans.maxBy { it.annualRate }
                Spacer(Modifier.height(12.dp))
                Panel { Text("Pay extra on ${first.name} first"); Muted("At ${first.annualRate}% it's your most expensive debt, so each extra payment there saves the most interest.") }
            }
            projections.forEach { (d, pr) ->
                Spacer(Modifier.height(16.dp))
                LoanCard(d, pr, onEdit = { editing = d })
            }
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = { editing = newLoan(s.mainCurrency) }) { Text("Add a loan") }
        }
    }
    editing?.let { d -> LoanEditor(vm, d) { editing = null } }
}

private fun newLoan(ccy: String) = DebtEntity(type = "LOAN", name = "", lender = "", currency = ccy, outstanding = 0.0)

@Composable
private fun LoanCard(d: DebtEntity, pr: com.compound.finance.core.LoanProjection, onEdit: () -> Unit) {
    val p = LocalPalette.current
    var extra by remember { mutableStateOf("") }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(d.name, style = MaterialTheme.typography.titleMedium)
                Muted(listOf(d.lender, "${d.annualRate}%", if (d.dueDay > 0) "EMI on day ${d.dueDay}" else "").filter { it.isNotBlank() }.joinToString(" · "))
            }
            TextButton(onClick = onEdit) { Text("Edit") }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column { Muted("Outstanding"); Text(money(d.outstanding, d.currency, decimals = false)) }
            Column { Muted("EMI"); Text(money(d.emi, d.currency, decimals = false)) }
            Column { Muted("Paid off"); Text(if (pr.payable) pr.payoffDate.format(monthYear) else "Never", color = if (pr.payable) MaterialTheme.colorScheme.onSurface else p.neg) }
        }
        Spacer(Modifier.height(6.dp))
        if (pr.payable) Muted("${pr.monthsLeft} months left · about ${money(pr.totalInterest, d.currency, decimals = false)} interest still to pay")
        else Text("The EMI doesn't cover the monthly interest. Check the rate and EMI.", color = p.neg, style = MaterialTheme.typography.bodySmall)
        if (d.lastPaidEpochDay > 0) Muted("Last EMI recorded ${fmtDay(LocalDate.ofEpochDay(d.lastPaidEpochDay))}")

        if (pr.payable && d.outstanding > 0) {
            Spacer(Modifier.height(10.dp))
            NumberField(extra, { extra = it }, "What if I pay extra each month? (${d.currency})")
            extra.toAmount()?.takeIf { it > 0 }?.let { x ->
                val (base, faster) = LoanMath.withExtra(d.outstanding, d.annualRate, d.emi, x, LocalDate.now())
                Spacer(Modifier.height(4.dp))
                Text("Done ${base.monthsLeft - faster.monthsLeft} months sooner (${faster.payoffDate.format(monthYear)}) and saves ${money(base.totalInterest - faster.totalInterest, d.currency, decimals = false)} in interest.",
                    color = p.pos, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun LoanEditor(vm: MainViewModel, d: DebtEntity, onDone: () -> Unit) {
    var name by remember { mutableStateOf(d.name) }
    var lender by remember { mutableStateOf(d.lender) }
    var ccy by remember { mutableStateOf(d.currency) }
    var outstanding by remember { mutableStateOf(if (d.outstanding > 0) "%.2f".format(d.outstanding) else "") }
    var rate by remember { mutableStateOf(if (d.annualRate > 0) d.annualRate.toString() else "") }
    var emi by remember { mutableStateOf(if (d.emi > 0) "%.2f".format(d.emi) else "") }
    var day by remember { mutableStateOf(if (d.dueDay > 0) d.dueDay.toString() else "") }
    var keyword by remember { mutableStateOf(d.matchKeyword) }
    var confirm by remember { mutableStateOf(false) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDone, sheetState = sheet) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (d.id == 0L) "Add a loan" else "Edit loan", style = MaterialTheme.typography.titleLarge)
            InputField(name, { name = it }, "Name, e.g. Home loan")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InputField(lender, { lender = it }, "Lender", Modifier.weight(1f))
                InputField(ccy, { ccy = it.uppercase().take(4) }, "Currency", Modifier.width(110.dp))
            }
            NumberField(outstanding, { outstanding = it }, "Outstanding balance")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(rate, { rate = it }, "Interest % a year", Modifier.weight(1f))
                NumberField(emi, { emi = it }, "Monthly EMI", Modifier.weight(1f))
            }
            NumberField(day, { day = it.filter(Char::isDigit).take(2) }, "EMI day of month")
            InputField(keyword, { keyword = it }, "Words in the EMI SMS (optional)")
            Muted("When a debit SMS mentions these words, or says EMI with this amount, the instalment is marked paid and the balance goes down.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    if (name.isBlank()) { vm.say("Give the loan a name"); return@Button }
                    vm.saveDebt(d.copy(name = name.trim(), lender = lender.trim(), currency = ccy.ifBlank { "AED" },
                        outstanding = outstanding.toAmount() ?: 0.0, annualRate = rate.toAmount() ?: 0.0, emi = emi.toAmount() ?: 0.0,
                        dueDay = day.toIntOrNull()?.coerceIn(0, 31) ?: 0, matchKeyword = keyword.trim()))
                    onDone()
                }) { Text("Save") }
                if (d.id != 0L) TextButton(onClick = { confirm = true }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    if (confirm) ConfirmDialog("Delete ${d.name}?", "This removes the loan from Compound.", "Delete", { vm.deleteDebt(d); onDone() }, { confirm = false })
}

// ------------------------------------------------------------------ cards
@Composable
private fun CardsTab(vm: MainViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val debts by vm.debts.collectAsStateWithLifecycle()
    val cards = debts.filter { it.type == "CARD" }
    val p = LocalPalette.current
    var editing by remember { mutableStateOf<DebtEntity?>(null) }
    val today = LocalDate.now()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        if (cards.isEmpty()) {
            EmptyState("No credit cards yet",
                "Cards appear here automatically when a statement or payment-due SMS arrives. You can also add one now.",
                "Add a card") { editing = DebtEntity(type = "CARD", name = "", lender = "", currency = s.mainCurrency, outstanding = 0.0) }
        } else {
            Muted("Owed on cards")
            Text(money(cards.sumOf { s.toMain(it.outstanding, it.currency) }, s.mainCurrency, decimals = false), style = MaterialTheme.typography.displaySmall)
            cards.forEach { c ->
                Spacer(Modifier.height(16.dp))
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(c.name.ifBlank { "Card" }, style = MaterialTheme.typography.titleMedium)
                            Muted(listOf(c.lender, if (c.account.isNotBlank()) "••${c.account}" else "").filter { it.isNotBlank() }.joinToString(" · "))
                        }
                        TextButton(onClick = { editing = c }) { Text("Edit") }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        Column { Muted("Balance"); Text(money(c.outstanding, c.currency, decimals = false)) }
                        if (c.statementDue > 0) Column { Muted("Statement due"); Text(money(c.statementDue, c.currency)) }
                        if (c.minDue > 0) Column { Muted("Minimum"); Text(money(c.minDue, c.currency)) }
                    }
                    if (c.creditLimit > 0) {
                        Spacer(Modifier.height(8.dp))
                        val u = c.outstanding / c.creditLimit
                        Bar(u, if (u > 0.5) p.warn else MaterialTheme.colorScheme.secondary)
                        Muted("${pct(u)} of ${money(c.creditLimit, c.currency, decimals = false)} limit used" + if (u > 0.3) ". Keeping it under 30% helps your credit score." else "")
                    }
                    if (c.dueDate > 0 && c.statementDue > 0) {
                        val due = LocalDate.ofEpochDay(c.dueDate)
                        val days = ChronoUnit.DAYS.between(today, due)
                        Spacer(Modifier.height(8.dp))
                        Text(when { days < 0 -> "Overdue since ${fmtDay(due)}"; days == 0L -> "Due today"; else -> "Due ${fmtDay(due)}, in $days days" },
                            color = if (days <= 3) p.neg else MaterialTheme.colorScheme.onSurface)
                        Muted("Paying the full statement amount avoids interest, which on UAE cards is often 3% or more a month.")
                        TextButton(onClick = { vm.markCardPaid(c) }) { Text("Mark statement paid") }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = { editing = DebtEntity(type = "CARD", name = "", lender = "", currency = s.mainCurrency, outstanding = 0.0) }) { Text("Add a card") }
        }
    }
    editing?.let { d -> CardEditor(vm, d) { editing = null } }
}

@Composable
private fun CardEditor(vm: MainViewModel, d: DebtEntity, onDone: () -> Unit) {
    var name by remember { mutableStateOf(d.name) }
    var bank by remember { mutableStateOf(d.lender) }
    var last4 by remember { mutableStateOf(d.account) }
    var balance by remember { mutableStateOf(if (d.outstanding > 0) "%.2f".format(d.outstanding) else "") }
    var limit by remember { mutableStateOf(if (d.creditLimit > 0) "%.0f".format(d.creditLimit) else "") }
    var due by remember { mutableStateOf(if (d.statementDue > 0) "%.2f".format(d.statementDue) else "") }
    var dueDate by remember { mutableStateOf(if (d.dueDate > 0) LocalDate.ofEpochDay(d.dueDate) else LocalDate.now().plusDays(20)) }
    var confirm by remember { mutableStateOf(false) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDone, sheetState = sheet) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (d.id == 0L) "Add a card" else "Edit card", style = MaterialTheme.typography.titleLarge)
            InputField(name, { name = it }, "Name, e.g. ENBD Visa")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InputField(bank, { bank = it }, "Bank", Modifier.weight(1f))
                InputField(last4, { last4 = it.filter(Char::isDigit).takeLast(4) }, "Last 4 digits", Modifier.width(140.dp))
            }
            Muted("The last 4 digits link this card to its SMS.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(balance, { balance = it }, "Current balance", Modifier.weight(1f))
                NumberField(limit, { limit = it }, "Credit limit", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(due, { due = it }, "Statement due", Modifier.weight(1f))
                DateField("Due date", dueDate, { dueDate = it }, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    vm.saveDebt(d.copy(name = name.trim().ifBlank { "$bank card" }, lender = bank.trim(), account = last4,
                        outstanding = balance.toAmount() ?: 0.0, creditLimit = limit.toAmount() ?: 0.0,
                        statementDue = due.toAmount() ?: 0.0, dueDate = dueDate.toEpochDay()))
                    onDone()
                }) { Text("Save") }
                if (d.id != 0L) TextButton(onClick = { confirm = true }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    if (confirm) ConfirmDialog("Delete ${d.name}?", "This removes the card from Compound. Its transactions stay.", "Delete", { vm.deleteDebt(d); onDone() }, { confirm = false })
}

// ------------------------------------------------------------------ bills & subscriptions
@Composable
private fun BillsTab(vm: MainViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val bills by vm.bills.collectAsStateWithLifecycle()
    val found by vm.recurring.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<BillEntity?>(null) }
    val today = LocalDate.now()
    val p = LocalPalette.current

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        if (bills.isNotEmpty()) {
            val subs = bills.filter { it.isSubscription }
            val yearly = bills.sumOf { s.toMain(it.amount, it.currency) * Frequency.valueOf(it.frequency).perYear() }
            Muted("Bills and subscriptions cost you")
            Text("${money(yearly, s.mainCurrency, decimals = false)} a year", style = MaterialTheme.typography.headlineSmall)
            if (subs.isNotEmpty()) Muted("${subs.size} subscriptions: ${money(subs.sumOf { s.toMain(it.amount, it.currency) * Frequency.valueOf(it.frequency).perYear() }, s.mainCurrency, decimals = false)} a year")
            Spacer(Modifier.height(12.dp))
            Panel {
                bills.forEachIndexed { i, b ->
                    if (i > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outline)
                    val due = LocalDate.ofEpochDay(b.nextDue)
                    val days = ChronoUnit.DAYS.between(today, due)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(b.name, maxLines = 1)
                            Muted("${Frequency.valueOf(b.frequency).label} · ${if (days < 0) "overdue since ${fmtDay(due)}" else "next ${fmtDay(due)}"}")
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(money(b.amount, b.currency))
                            Row {
                                TextButton(onClick = { vm.markBillPaid(b) }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("Paid") }
                                TextButton(onClick = { editing = b }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("Edit") }
                            }
                        }
                    }
                }
            }
        } else {
            Text("Bills and subscriptions", style = MaterialTheme.typography.titleMedium)
            Muted("Add rent, DEWA or ADDC, Etisalat or du, school fees and subscriptions to get a reminder before each one is due. A matching bank SMS marks it paid.")
        }

        if (found.isNotEmpty()) {
            SectionTitle("Found in your SMS")
            Muted("These charges repeat regularly. Add them to get reminders and see what they cost a year.")
            Spacer(Modifier.height(8.dp))
            Panel {
                found.forEachIndexed { i, r ->
                    if (i > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outline)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.merchant, maxLines = 1)
                            Muted("${r.frequency.label}, ${money(r.typicalAmount, s.mainCurrency)} · ${money(r.yearlyCost, s.mainCurrency, decimals = false)} a year")
                            if (r.priceIncrease) Text("Price went up to ${money(r.lastAmount, s.mainCurrency)}", color = p.warn, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { vm.addBillFromRecurring(r) }) { Text("Add") }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = {
            editing = BillEntity(name = "", amount = 0.0, currency = s.mainCurrency, frequency = Frequency.MONTHLY.name,
                nextDue = today.plusDays(7).toEpochDay(), category = "Utilities")
        }) { Text("Add a bill") }
    }
    editing?.let { b -> BillEditor(vm, b) { editing = null } }
}

@Composable
private fun BillEditor(vm: MainViewModel, b: BillEntity, onDone: () -> Unit) {
    var name by remember { mutableStateOf(b.name) }
    var amount by remember { mutableStateOf(if (b.amount > 0) "%.2f".format(b.amount) else "") }
    var ccy by remember { mutableStateOf(b.currency) }
    var freq by remember { mutableStateOf(Frequency.valueOf(b.frequency)) }
    var due by remember { mutableStateOf(LocalDate.ofEpochDay(b.nextDue)) }
    var category by remember { mutableStateOf(b.category) }
    var match by remember { mutableStateOf(b.matchKey) }
    var sub by remember { mutableStateOf(b.isSubscription) }
    var confirm by remember { mutableStateOf(false) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDone, sheetState = sheet) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (b.id == 0L) "Add a bill" else "Edit bill", style = MaterialTheme.typography.titleLarge)
            InputField(name, { name = it }, "Name, e.g. DEWA")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(amount, { amount = it }, "Usual amount", Modifier.weight(1f))
                InputField(ccy, { ccy = it.uppercase().take(4) }, "Currency", Modifier.width(110.dp))
            }
            ChoiceField("How often", freq.label, Frequency.entries.map { it.label }, { l -> freq = Frequency.entries.first { it.label == l } })
            DateField("Next due", due, { due = it })
            ChoiceField("Category", category, Categories.spending, { category = it })
            InputField(match, { match = it }, "Merchant name in the SMS (optional)")
            Muted("A debit SMS from this merchant marks the bill paid and moves it to the next date.")
            SwitchRow("Subscription", null, sub) { sub = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    if (name.isBlank()) { vm.say("Give the bill a name"); return@Button }
                    vm.saveBill(b.copy(name = name.trim(), amount = amount.toAmount() ?: 0.0, currency = ccy.ifBlank { "AED" },
                        frequency = freq.name, nextDue = due.toEpochDay(), category = category,
                        matchKey = com.compound.finance.core.Categorizer.normalize(match.ifBlank { name }), isSubscription = sub))
                    onDone()
                }) { Text("Save") }
                if (b.id != 0L) TextButton(onClick = { confirm = true }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    if (confirm) ConfirmDialog("Delete ${b.name}?", "You'll stop getting reminders for it.", "Delete", { vm.deleteBill(b); onDone() }, { confirm = false })
}
