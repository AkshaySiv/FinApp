package com.compound.finance.core

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/** Minimal view of a transaction for analysis (amount already in the main currency). */
data class TxPoint(val id: Long, val date: LocalDate, val merchantKey: String, val merchant: String,
                   val amount: Double, val category: String)

enum class Frequency(val days: Int, val label: String) {
    WEEKLY(7, "Weekly"), MONTHLY(30, "Monthly"), QUARTERLY(91, "Quarterly"), YEARLY(365, "Yearly");

    fun next(from: LocalDate): LocalDate = when (this) {
        WEEKLY -> from.plusWeeks(1)
        MONTHLY -> from.plusMonths(1)
        QUARTERLY -> from.plusMonths(3)
        YEARLY -> from.plusYears(1)
    }
    fun perYear(): Double = when (this) { WEEKLY -> 52.0; MONTHLY -> 12.0; QUARTERLY -> 4.0; YEARLY -> 1.0 }
}

data class Recurring(
    val merchantKey: String, val merchant: String, val category: String, val frequency: Frequency,
    val typicalAmount: Double, val lastAmount: Double, val lastDate: LocalDate, val nextDate: LocalDate,
    val occurrences: Int, val priceIncrease: Boolean,
) { val yearlyCost: Double get() = typicalAmount * frequency.perYear() }

data class Duplicate(val first: TxPoint, val second: TxPoint)

object RecurringDetector {
    /** Finds charges that repeat on a regular cycle at a similar amount. */
    fun detect(debits: List<TxPoint>, today: LocalDate): List<Recurring> =
        debits.filter { it.merchantKey.isNotBlank() }
            .groupBy { it.merchantKey }
            .mapNotNull { (key, list) -> analyse(key, list.sortedBy { it.date }, today) }
            .sortedByDescending { it.yearlyCost }

    private fun analyse(key: String, list: List<TxPoint>, today: LocalDate): Recurring? {
        // collapse same-day duplicates so they don't break the interval
        val pts = list.distinctBy { it.date }
        if (pts.size < 2) return null
        val gaps = pts.zipWithNext { a, b -> ChronoUnit.DAYS.between(a.date, b.date).toInt() }
        val median = gaps.sorted()[gaps.size / 2]
        val freq = Frequency.entries.firstOrNull { f ->
            val tol = when (f) { Frequency.WEEKLY -> 2; Frequency.MONTHLY -> 5; Frequency.QUARTERLY -> 12; Frequency.YEARLY -> 20 }
            abs(median - f.days) <= tol
        } ?: return null
        val needed = when (freq) { Frequency.YEARLY -> 2; Frequency.QUARTERLY -> 2; else -> 3 }
        if (pts.size < needed) return null
        val regular = gaps.count { abs(it - freq.days) <= freq.days * 0.25 + 3 }
        if (regular < (gaps.size * 0.6)) return null
        val amounts = pts.map { it.amount }
        val mean = amounts.average()
        val sd = sqrt(amounts.map { (it - mean).pow(2) }.average())
        if (mean <= 0 || sd / mean > 0.25) return null
        val last = pts.last()
        // stopped: no charge for two cycles
        if (ChronoUnit.DAYS.between(last.date, today) > freq.days * 2 + 7) return null
        val typical = amounts.dropLast(1).sorted().let { it[it.size / 2] }
        return Recurring(key, last.merchant, last.category, freq, typical, last.amount, last.date,
            freq.next(last.date), pts.size, priceIncrease = last.amount > typical * 1.05 && pts.size >= 3)
    }

    /** Same merchant, same amount, within two days. */
    fun duplicates(debits: List<TxPoint>): List<Duplicate> =
        debits.filter { it.merchantKey.isNotBlank() }.groupBy { it.merchantKey to "%.2f".format(it.amount) }
            .values.flatMap { g ->
                g.sortedBy { it.date }.zipWithNext()
                    .filter { (a, b) -> ChronoUnit.DAYS.between(a.date, b.date) <= 2 }
                    .map { (a, b) -> Duplicate(a, b) }
            }
}

data class LoanProjection(val monthsLeft: Int, val totalInterest: Double, val payoffDate: LocalDate, val payable: Boolean)

object LoanMath {
    /** Months to repay [outstanding] at [annualRatePct] with a fixed monthly [emi]. */
    fun project(outstanding: Double, annualRatePct: Double, emi: Double, from: LocalDate): LoanProjection {
        if (outstanding <= 0) return LoanProjection(0, 0.0, from, true)
        if (emi <= 0) return LoanProjection(Int.MAX_VALUE, Double.POSITIVE_INFINITY, from, false)
        val r = annualRatePct / 100 / 12
        val n: Double = if (r <= 0) outstanding / emi else {
            val x = 1 - r * outstanding / emi
            if (x <= 0) return LoanProjection(Int.MAX_VALUE, Double.POSITIVE_INFINITY, from, false)
            -ln(x) / ln(1 + r)
        }
        val months = ceil(n - 1e-9).toInt()
        val interest = (emi * n - outstanding).coerceAtLeast(0.0)
        return LoanProjection(months, interest, from.plusMonths(months.toLong()), true)
    }

    /** Effect of paying [extra] more each month. */
    fun withExtra(outstanding: Double, annualRatePct: Double, emi: Double, extra: Double, from: LocalDate): Pair<LoanProjection, LoanProjection> =
        project(outstanding, annualRatePct, emi, from) to project(outstanding, annualRatePct, emi + extra, from)
}

/** Budget pace: how much of the budget is used compared with how much of the month has passed. */
object BudgetMath {
    data class Pace(val used: Double, val monthElapsed: Double, val projected: Double) {
        val overPace: Boolean get() = used > monthElapsed + 0.1 && used > 0.3
    }

    fun pace(spent: Double, limit: Double, today: LocalDate): Pace {
        val days = today.lengthOfMonth().toDouble()
        val elapsed = today.dayOfMonth / days
        val used = if (limit > 0) spent / limit else 0.0
        val projected = if (today.dayOfMonth > 0) spent / today.dayOfMonth * days else spent
        return Pace(used, elapsed, projected)
    }
}
