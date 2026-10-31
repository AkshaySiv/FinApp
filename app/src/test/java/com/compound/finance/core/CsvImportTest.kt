package com.compound.finance.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class CsvImportTest {

    @Test fun debit_credit_columns_with_preamble() {
        val csv = """
            Account Statement
            Account Number,XXXX1234
            Transaction Date,Description,Debit,Credit,Balance
            31/10/2026,"CARREFOUR MARKET, ABU DHABI",245.50,,10038.58
            25/10/2026,SALARY OCT,,"46,000.00",10284.08
            not a row,,,,
        """.trimIndent()
        val r = CsvImport.parse(csv)
        assertNull(r.problem)
        assertEquals(2, r.rows.size)
        assertEquals(1, r.skipped)
        val a = r.rows[0]
        assertEquals(LocalDate.of(2026, 10, 31), a.date)
        assertEquals("CARREFOUR MARKET, ABU DHABI", a.description)
        assertEquals(245.50, a.amount, 0.001)
        assertEquals(Direction.DEBIT, a.direction)
        assertEquals(46000.0, r.rows[1].amount, 0.001)
        assertEquals(Direction.CREDIT, r.rows[1].direction)
    }

    @Test fun signed_amount_semicolon_and_currency() {
        val csv = "Date;Details;Amount;Currency\n2026-10-12;NETFLIX.COM;-19.99;USD\n2026-10-13;REFUND IKEA;150.00;AED\n"
        val r = CsvImport.parse(csv)
        assertEquals(2, r.rows.size)
        assertEquals(Direction.DEBIT, r.rows[0].direction)
        assertEquals("USD", r.rows[0].currency)
        assertEquals(Direction.CREDIT, r.rows[1].direction)
    }

    @Test fun dr_cr_marker_column() {
        val csv = "Txn Date,Narration,Amount,Dr/Cr\n12-Oct-2026,UPI ZOMATO,450.00,DR\n13-Oct-2026,INTEREST,12.00,CR\n"
        val r = CsvImport.parse(csv)
        assertEquals(Direction.DEBIT, r.rows[0].direction)
        assertEquals(Direction.CREDIT, r.rows[1].direction)
        assertEquals(LocalDate.of(2026, 10, 12), r.rows[0].date)
    }

    @Test fun amounts() {
        assertEquals(-45.0, CsvImport.amount("(45.00)")!!, 0.001)
        assertEquals(1234.5, CsvImport.amount("AED 1,234.50")!!, 0.001)
        assertEquals(-45.0, CsvImport.amount("45.00 DR")!!, 0.001)
        assertNull(CsvImport.amount(""))
    }
}
