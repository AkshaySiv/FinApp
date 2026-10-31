package com.compound.finance.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.compound.finance.data.TxEntity
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/**
 * Monthly reports: six-month trend of spending against income, where this month's money went
 * compared with your usual, top merchants, and a few headline numbers.
 */
@Composable
fun ReportsView(vm: MainViewModel, ym: YearMonth, monthTx: List<TxEntity>, recent: List<TxEntity>) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val p = LocalPalette.current
    val ccy = s.mainCurrency
    fun main(t: TxEntity) = s.toMain(t.amount, t.currency)
    fun monthOf(t: TxEntity) = YearMonth.from(vm.repo.dateOf(t.timestamp))

    // ---- six-month trend
    val months = (5 downTo 0).map { ym.minusMonths(it.toLong()) }
    val byMonth = recent.groupBy { monthOf(it) }
    val spendSeries = months.map { m -> byMonth[m].orEmpty().filter { vm.repo.isSpending(it) }.sumOf { main(it) } }
    val incomeSeries = months.map { m -> byMonth[m].orEmpty().filter { vm.repo.isIncome(it) }.sumOf { main(it) } }

    // ---- this month by category vs the previous three months' average
    val spending = monthTx.filter { vm.repo.isSpending(it) }
    val total = spending.sumOf { main(it) }
    val byCat = spending.groupBy { it.category }.mapValues { (_, l) -> l.sumOf { main(it) } }.entries.sortedByDescending { it.value }
    val prior = (1L..3L).map { ym.minusMonths(it) }
    fun avgFor(cat: String) = prior.map { m -> byMonth[m].orEmpty().filter { it.category == cat && vm.repo.isSpending(it) }.sumOf { main(it) } }.average()
    val merchants = spending.filter { !it.merchant.isNullOrBlank() }.groupBy { it.merchantKey.ifBlank { it.merchant!! } }
        .map { (_, l) -> Triple(l.first().merchant!!, l.sumOf { main(it) }, l.size) }.sortedByDescending { it.second }.take(5)

    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        SectionTitle("Last six months")
        Panel {
            TrendChart(months, spendSeries, incomeSeries)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                LegendDot(p.series[1], "Spending")
                LegendDot(p.series[0], "Income")
            }
            val avgSpend = spendSeries.dropLast(1).filter { it > 0 }.let { if (it.isEmpty()) 0.0 else it.average() }
            if (avgSpend > 0) {
                Spacer(Modifier.height(6.dp))
                Muted("You usually spend about ${money(avgSpend, ccy, decimals = false)} a month.")
            }
        }

        SectionTitle("Where ${ym.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)}'s money went")
        if (byCat.isEmpty()) Muted("No spending recorded this month.")
        else Panel {
            byCat.forEachIndexed { i, (cat, v) ->
                if (i > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outline)
                val avg = avgFor(cat)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(cat)
                        Spacer(Modifier.height(4.dp))
                        Bar(if (total > 0) v / total else 0.0, categoryColor(cat))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(110.dp)) {
                        Text(money(v, ccy, decimals = false))
                        val note = when {
                            avg <= 0 -> "${pct(v / total)} of total"
                            v > avg * 1.15 -> "+${pct(v / avg - 1)} vs usual"
                            v < avg * 0.85 -> "-${pct(1 - v / avg)} vs usual"
                            else -> "about usual"
                        }
                        Text(note, style = MaterialTheme.typography.bodySmall,
                            color = if (avg > 0 && v > avg * 1.15) p.neg else if (avg > 0 && v < avg * 0.85) p.pos else p.muted)
                    }
                }
            }
        }

        if (merchants.isNotEmpty()) {
            SectionTitle("Top merchants")
            Panel {
                merchants.forEachIndexed { i, (name, v, n) ->
                    if (i > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outline)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(name, maxLines = 1); Muted("$n payment${if (n == 1) "" else "s"}") }
                        Text(money(v, ccy, decimals = false))
                    }
                }
            }
        }

        if (spending.isNotEmpty()) {
            SectionTitle("In numbers")
            val days = if (ym == YearMonth.now()) java.time.LocalDate.now().dayOfMonth else ym.lengthOfMonth()
            val biggest = spending.maxBy { main(it) }
            Panel {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column { Muted("Per day"); Text(money(total / days, ccy, decimals = false), style = MaterialTheme.typography.titleMedium) }
                    Column { Muted("Payments"); Text("${spending.size}", style = MaterialTheme.typography.titleMedium) }
                    Column { Muted("Average"); Text(money(total / spending.size, ccy, decimals = false), style = MaterialTheme.typography.titleMedium) }
                }
                Spacer(Modifier.height(8.dp))
                Muted("Biggest: ${money(biggest.amount, biggest.currency)} at ${biggest.merchant ?: biggest.category} on ${fmtDay(vm.repo.dateOf(biggest.timestamp))}")
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun LegendDot(color: androidx.compose.ui.graphics.Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(10.dp)) { drawRoundRect(color, cornerRadius = CornerRadius(3.dp.toPx())) }
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = LocalPalette.current.muted)
    }
}

/** Paired bars per month: spending and income, with month labels and the top value marked. */
@Composable
private fun TrendChart(months: List<YearMonth>, spend: List<Double>, income: List<Double>) {
    val p = LocalPalette.current
    val max = (spend + income).maxOrNull()?.takeIf { it > 0 } ?: 1.0
    val spendColor = p.series[1]
    val incomeColor = p.series[0]
    val grid = p.track
    Column {
        Muted("Top of chart: ${compact(max)}")
        Spacer(Modifier.height(6.dp))
        Canvas(Modifier.fillMaxWidth().height(150.dp)) {
            val n = months.size
            val slot = size.width / n
            val barW = (slot * 0.28f).coerceAtMost(18.dp.toPx())
            val gap = 2.dp.toPx()
            val r = CornerRadius(3.dp.toPx())
            drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
            drawLine(grid, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx())
            for (i in 0 until n) {
                val cx = slot * i + slot / 2
                val hs = (spend[i] / max * size.height).toFloat()
                val hi = (income[i] / max * size.height).toFloat()
                if (hs > 0) drawRoundRect(spendColor, Offset(cx - barW - gap / 2, size.height - hs), Size(barW, hs), r)
                if (hi > 0) drawRoundRect(incomeColor, Offset(cx + gap / 2, size.height - hi), Size(barW, hi), r)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            months.forEach { m ->
                Text(m.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH), Modifier.weight(1f),
                    textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall, color = p.muted)
            }
        }
        Row(Modifier.fillMaxWidth()) {
            months.indices.forEach { i ->
                Text(compact(spend[i]), Modifier.weight(1f), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall, color = p.muted)
            }
        }
    }
}
