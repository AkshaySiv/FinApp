@file:OptIn(ExperimentalMaterial3Api::class)

package com.compound.finance.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.compound.finance.core.Categories
import com.compound.finance.core.Direction
import com.compound.finance.data.TxEntity
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun SpendingScreen(vm: MainViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val ym by vm.month.collectAsStateWithLifecycle()
    val txs by vm.monthTx.collectAsStateWithLifecycle()
    val p = LocalPalette.current
    var filter by remember { mutableStateOf("Spending") }
    var category by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<TxEntity?>(null) }
    var adding by remember { mutableStateOf(false) }

    val shown = txs.filter {
        when (filter) {
            "Spending" -> vm.repo.isSpending(it)
            "Income" -> vm.repo.isIncome(it)
            else -> true
        } && (category == null || it.category == category)
    }
    val spent = txs.filter { vm.repo.isSpending(it) }.sumOf { s.toMain(it.amount, it.currency) }
    val income = txs.filter { vm.repo.isIncome(it) }.sumOf { s.toMain(it.amount, it.currency) }
    val cats = txs.filter { if (filter == "Income") vm.repo.isIncome(it) else vm.repo.isSpending(it) }
        .groupBy { it.category }.mapValues { (_, l) -> l.sumOf { s.toMain(it.amount, it.currency) } }
        .entries.sortedByDescending { it.value }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { vm.month.value = ym.minusMonths(1) }) { Icon(Icons.Outlined.ChevronLeft, "Previous month") }
                    Text("${ym.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} ${ym.year}",
                        style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = { vm.month.value = ym.plusMonths(1) }, enabled = ym < YearMonth.now()) {
                        Icon(Icons.Outlined.ChevronRight, "Next month")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column { Muted("Spent"); Text(money(spent, s.mainCurrency, decimals = false), style = MaterialTheme.typography.headlineSmall) }
                    Column { Muted("Income"); Text(money(income, s.mainCurrency, decimals = false), style = MaterialTheme.typography.headlineSmall) }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Spending", "Income", "All").forEach { f ->
                        FilterChip(selected = filter == f, onClick = { filter = f; category = null }, label = { Text(f) })
                    }
                }
                if (cats.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(cats) { (c, v) ->
                            FilterChip(selected = category == c, onClick = { category = if (category == c) null else c },
                                label = { Text("$c ${compact(v)}") })
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            if (shown.isEmpty()) item {
                EmptyState("Nothing here", if (txs.isEmpty()) "No transactions recorded this month." else "No transactions match this filter.")
            }
            val byDay = shown.groupBy { vm.repo.dateOf(it.timestamp) }
            byDay.forEach { (day, list) ->
                item(key = "d$day") {
                    Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp)) {
                        Text(fmtDay(day), style = MaterialTheme.typography.labelLarge, color = p.muted, modifier = Modifier.weight(1f))
                        val daySpent = list.filter { vm.repo.isSpending(it) }.sumOf { s.toMain(it.amount, it.currency) }
                        if (daySpent > 0) Text(money(daySpent, s.mainCurrency), style = MaterialTheme.typography.labelLarge, color = p.muted)
                    }
                }
                items(list, key = { it.id }) { t -> TxRow(t, s.mainCurrency) { editing = t } }
            }
        }
        FloatingActionButton(onClick = { adding = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            containerColor = MaterialTheme.colorScheme.primary) { Icon(Icons.Outlined.Add, "Add a transaction") }
    }

    editing?.let { t -> TxEditor(vm, t) { editing = null } }
    if (adding) AddTxSheet(vm, s.mainCurrency) { adding = false }
}

@Composable
fun TxRow(t: TxEntity, main: String, onClick: () -> Unit) {
    val p = LocalPalette.current
    val credit = t.direction == Direction.CREDIT.name
    val dim = t.excluded || t.category in Categories.notSpending
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).padding(1.dp)) { Bar(1.0, categoryColor(t.category), height = 8) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t.merchant ?: kindLabel(t.kind, credit), maxLines = 1,
                color = if (dim) p.muted else MaterialTheme.colorScheme.onBackground)
            Muted(listOfNotNull(t.category, t.bank, t.account?.let { "••$it" }, if (t.excluded) "not counted" else null).joinToString(" · "), maxLines = 1)
        }
        Text((if (credit) "+" else "") + money(t.amount, t.currency),
            color = if (credit) p.pos else if (dim) p.muted else MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Medium)
    }
}

fun kindLabel(kind: String, credit: Boolean) = when (kind) {
    "TRANSFER" -> if (credit) "Transfer in" else "Transfer out"
    "ATM" -> "Cash withdrawal"
    "SALARY" -> "Salary"
    "EMI" -> "Loan instalment"
    "CARD_PAYMENT" -> "Card payment"
    "REFUND" -> "Refund"
    else -> if (credit) "Money in" else "Payment"
}

@Composable
fun TxEditor(vm: MainViewModel, t: TxEntity, onDone: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var category by remember { mutableStateOf(t.category) }
    var applyAll by remember { mutableStateOf(t.merchantKey.isNotBlank()) }
    var excluded by remember { mutableStateOf(t.excluded) }
    var note by remember { mutableStateOf(t.note) }
    var merchant by remember { mutableStateOf(t.merchant ?: "") }
    var confirmDelete by remember { mutableStateOf(false) }
    val options = if (t.direction == Direction.CREDIT.name) Categories.income + Categories.spending else Categories.all

    ModalBottomSheet(onDismissRequest = onDone, sheetState = sheet) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text((if (t.direction == "CREDIT") "+" else "") + money(t.amount, t.currency), style = MaterialTheme.typography.headlineSmall)
            Muted("${fmtDay(vm.repo.dateOf(t.timestamp))} · ${listOfNotNull(t.bank, t.account?.let { "••$it" }, t.source.lowercase()).joinToString(" · ")}")
            InputField(merchant, { merchant = it }, "Merchant or description")
            ChoiceField("Category", category, options, { category = it })
            if (t.merchantKey.isNotBlank()) SwitchRow("Always use this for ${t.merchant}", "Future payments here are sorted automatically", applyAll) { applyAll = it }
            SwitchRow("Don't count in totals", "For money moved between your own accounts", excluded) { excluded = it }
            InputField(note, { note = it }, "Note")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val updated = t.copy(excluded = excluded, note = note, merchant = merchant.ifBlank { null })
                    if (category != t.category || applyAll) vm.setCategory(updated, category, applyAll)
                    else vm.updateTx(updated)
                    onDone()
                }) { Text("Save") }
                TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    if (confirmDelete) ConfirmDialog("Delete this transaction?", "It will be removed from your totals. The SMS stays on your phone.",
        "Delete", { vm.deleteTx(t); onDone() }, { confirmDelete = false })
}

@Composable
fun AddTxSheet(vm: MainViewModel, mainCcy: String, onDone: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var amount by remember { mutableStateOf("") }
    var ccy by remember { mutableStateOf(mainCcy) }
    var dir by remember { mutableStateOf(Direction.DEBIT) }
    var merchant by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Groceries") }
    var date by remember { mutableStateOf(LocalDate.now()) }
    var note by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDone, sheetState = sheet) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Add a transaction", style = MaterialTheme.typography.titleLarge)
            Muted("For cash and anything your bank doesn't text you about.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = dir == Direction.DEBIT, onClick = { dir = Direction.DEBIT; category = "Groceries" }, label = { Text("Spending") })
                FilterChip(selected = dir == Direction.CREDIT, onClick = { dir = Direction.CREDIT; category = Categories.INCOME }, label = { Text("Income") })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(amount, { amount = it }, "Amount", Modifier.weight(1f))
                InputField(ccy, { ccy = it.uppercase().take(4) }, "Currency", Modifier.width(110.dp))
            }
            InputField(merchant, { merchant = it }, "Where / who")
            ChoiceField("Category", category, if (dir == Direction.CREDIT) Categories.income else Categories.spending, { category = it })
            DateField("Date", date, { date = it })
            InputField(note, { note = it }, "Note")
            Button(onClick = {
                val a = amount.toAmount()
                if (a == null || a <= 0) { vm.say("Enter an amount"); return@Button }
                vm.addManual(date, a, ccy, dir, merchant, category, note); onDone()
            }) { Text("Add transaction") }
        }
    }
}
