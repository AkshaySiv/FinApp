package com.compound.finance.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AnalysisTest {

    private val cat = Categorizer(Categorizer.seed)

    @Test fun categorises_common_merchants() {
        assertEquals("Groceries", cat.categorize("CARREFOUR MARKET", TxKind.PURCHASE, Direction.DEBIT))
        assertEquals("Dining", cat.categorize("CAREEM FOOD", TxKind.PURCHASE, Direction.DEBIT))
        assertEquals("Transport", cat.categorize("CAREEM RIDES", TxKind.PURCHASE, Direction.DEBIT))
        assertEquals("Subscriptions", cat.categorize("NETFLIX.COM", TxKind.PURCHASE, Direction.DEBIT))
        assertEquals("Dining", cat.categorize("MCDONALDS 1234 DUBAI", TxKind.PURCHASE, Direction.DEBIT))
        assertEquals(Categories.UNCATEGORISED, cat.categorize("DUNKIN", TxKind.PURCHASE, Direction.DEBIT))
        assertEquals("Utilities", cat.categorize("DU TELECOM", TxKind.PURCHASE, Direction.DEBIT))
        assertEquals(Categories.TRANSFERS, cat.categorize(null, TxKind.TRANSFER, Direction.DEBIT))
        assertEquals(Categories.SALARY, cat.categorize(null, TxKind.SALARY, Direction.CREDIT))
    }

    @Test fun your_rule_beats_built_in() {
        val c = Categorizer(Categorizer.seed + Rule("noon", "Groceries", fromUser = true))
        assertEquals("Groceries", c.categorize("NOON.COM", TxKind.PURCHASE, Direction.DEBIT))
    }

    @Test fun normalizes_merchant_names() {
        assertEquals("carrefour market", Categorizer.normalize("CARREFOUR MARKET 0123 ABU DHABI"))
        assertEquals("talabat", Categorizer.normalize("TALABAT*LLC DUBAI ARE"))
    }

    private fun pt(d: LocalDate, amt: Double, key: String = "netflix.com") = TxPoint(0, d, key, key.uppercase(), amt, "Subscriptions")

    @Test fun detects_monthly_subscription_and_price_rise() {
        val start = LocalDate.of(2026, 3, 12)
        val txs = (0..6).map { pt(start.plusMonths(it.toLong()), if (it < 6) 39.0 else 45.0) }
        val r = RecurringDetector.detect(txs, LocalDate.of(2026, 10, 1))
        assertEquals(1, r.size)
        assertEquals(Frequency.MONTHLY, r[0].frequency)
        assertTrue(r[0].priceIncrease)
        assertEquals(LocalDate.of(2026, 10, 12), r[0].nextDate)
    }

    @Test fun irregular_spending_is_not_recurring() {
        val d = LocalDate.of(2026, 1, 1)
        val txs = listOf(pt(d, 50.0, "lulu"), pt(d.plusDays(3), 210.0, "lulu"), pt(d.plusDays(19), 80.0, "lulu"), pt(d.plusDays(22), 15.0, "lulu"))
        assertTrue(RecurringDetector.detect(txs, d.plusDays(30)).isEmpty())
    }

    @Test fun finds_duplicate_charges() {
        val d = LocalDate.of(2026, 10, 1)
        val txs = listOf(pt(d, 39.0), pt(d.plusDays(1), 39.0), pt(d.plusDays(30), 39.0))
        assertEquals(1, RecurringDetector.duplicates(txs).size)
    }

    @Test fun loan_projection() {
        // 100,000 at 6% with a 1,933.28 EMI is a 60-month loan
        val p = LoanMath.project(100_000.0, 6.0, 1933.30, LocalDate.of(2026, 11, 1))
        assertEquals(60, p.monthsLeft)
        assertEquals(15_996.8, p.totalInterest, 5.0)
        val (base, faster) = LoanMath.withExtra(100_000.0, 6.0, 1933.30, 500.0, LocalDate.of(2026, 11, 1))
        assertTrue(faster.monthsLeft < base.monthsLeft)
        assertTrue(faster.totalInterest < base.totalInterest)
    }

    @Test fun emi_too_small_never_repays() {
        assertFalse(LoanMath.project(100_000.0, 12.0, 900.0, LocalDate.now()).payable)
    }
}
