@file:OptIn(ExperimentalMaterial3Api::class)

package com.compound.finance.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.compound.finance.core.BudgetMath
import com.compound.finance.core.Categories
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.ceil

@Composable
fun BudgetsScreen(vm: MainViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val budgets by vm.budgets.collectAsStateWithLifecycle()
    val txs by vm.recentTx.collectAsStateWithLifecycle()
    val p = LocalPalette.current
    val today = LocalDate.now()
    val ym = YearMonth.from(today)
    var editing by remember { mutableStateOf<Pair<String, Double>?>(null) }

    fun spentIn(cat: String, m: YearMonth) = txs.filter {
        it.category == cat && vm.repo.isSpending(it) && YearMonth.from(vm.repo.dateOf(it.timestamp)) == m
    }.sumOf { s.toMain(it.amount, it.currency) }

    /** Average of the last three full months: a sensible starting budget. */
    fun average(cat: String) = (1L..3L).map { spentIn(cat, ym.minusMonths(it)) }.average()

    val budgeted = budgets.map { it.category }.toSet()
    val unbudgeted = Categories.spending.filter { it !in budgeted && it !in Categories.notSpending }
        .map { it to average(it) }.filter { it.second > 0 }.sortedByDescending { it.second }
    val totalLimit = budgets.sumOf { it.monthlyLimit }
    val totalSpent = budgets.sumOf { spentIn(it.category, ym) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
        Spacer(Modifier.height(16.dp))
        if (budgets.isEmpty()) {
            Text("Set a monthly limit for each category", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Muted("You'll get a notification at 80% and 100%, and the home screen warns you when a category is spending faster than the month is passing.")
        } else {
            Muted("Budgeted spending this month")
            Text("${money(totalSpent, s.mainCurrency, decimals = false)} of ${money(totalLimit, s.mainCurrency, decimals = false)}",
                style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Bar(totalSpent / totalLimit, if (totalSpent > totalLimit) p.neg else MaterialTheme.colorScheme.secondary, height = 10)
            SectionTitle("Your budgets")
            Panel {
                budgets.sortedByDescending { spentIn(it.category, ym) / it.monthlyLimit }.forEachIndexed { i, b ->
                    if (i > 0) HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outline)
                    val spent = spentIn(b.category, ym)
                    val pace = BudgetMath.pace(spent, b.monthlyLimit, today)
                    val color = when { spent > b.monthlyLimit -> p.neg; pace.overPace -> p.warn; else -> categoryColor(b.category) }
                    Column(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(b.category, Modifier.weight(1f))
                            TextButton(onClick = { editing = b.category to b.monthlyLimit }) { Text("Edit") }
                        }
                        Bar(spent / b.monthlyLimit, color)
                        Spacer(Modifier.height(4.dp))
                        val left = b.monthlyLimit - spent
                        Muted(
                            "${money(spent, s.mainCurrency, decimals = false)} of ${money(b.monthlyLimit, s.mainCurrency, decimals = false)} · " +
                                if (left >= 0) "${money(left, s.mainCurrency, decimals = false)} left" +
                                    (if (pace.overPace) ", on track for ${money(pace.projected, s.mainCurrency, decimals = false)}" else "")
                                else "${money(-left, s.mainCurrency, decimals = false)} over"
                        )
                    }
                }
            }
        }

        if (unbudgeted.isNotEmpty()) {
            SectionTitle("Suggested from your spending")
            Muted("Average of the last three months. Tap to set a budget.")
            Spacer(Modifier.height(8.dp))
            Panel {
                unbudgeted.forEachIndexed { i, (cat, avg) ->
                    if (i > 0) HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outline)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(cat)
                            Muted("About ${money(avg, s.mainCurrency, decimals = false)} a month · ${money(spentIn(cat, ym), s.mainCurrency, decimals = false)} so far")
                        }
                        TextButton(onClick = { editing = cat to roundUp(avg) }) { Text("Set") }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = { editing = "" to 0.0 }) { Text("Add a budget") }
    }

    editing?.let { (cat, limit) ->
        var category by remember(cat) { mutableStateOf(cat.ifEmpty { Categories.spending.firstOrNull { it !in budgeted && it !in Categories.notSpending } ?: "Groceries" }) }
        var amount by remember(cat) { mutableStateOf(if (limit > 0) "%.0f".format(limit) else "") }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (cat.isEmpty()) "Add a budget" else "$cat budget") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (cat.isEmpty()) ChoiceField("Category", category, Categories.spending.filter { it !in Categories.notSpending }, { category = it })
                    NumberField(amount, { amount = it }, "Monthly limit (${s.mainCurrency})")
                    val avg = average(category)
                    if (avg > 0) Muted("You've averaged ${money(avg, s.mainCurrency, decimals = false)} a month.")
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.saveBudget(category, amount.toAmount() ?: 0.0); editing = null }) { Text("Save") }
            },
            dismissButton = {
                if (cat.isNotEmpty()) TextButton(onClick = { vm.saveBudget(cat, 0.0); editing = null }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                } else TextButton(onClick = { editing = null }) { Text("Cancel") }
            },
        )
    }
}

private fun roundUp(v: Double): Double = when {
    v >= 1000 -> ceil(v / 100) * 100
    v >= 100 -> ceil(v / 50) * 50
    else -> ceil(v / 10) * 10
}
