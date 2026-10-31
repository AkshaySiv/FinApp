package com.compound.finance.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SmsParserTest {

    private fun tx(sender: String, body: String, filter: SenderFilter = SenderFilter()): ParsedTx {
        val r = SmsParser.parse(sender, body, filter)
        assertTrue("expected a transaction, got $r", r is ParseResult.Transaction)
        return (r as ParseResult.Transaction).tx
    }

    // ---- real messages (from the user) --------------------------------------------------

    @Test fun adcb_internet_banking_transfer() {
        val t = tx("ADCBAlert",
            "AED10000.00 transferred via ADCB Personal Internet Banking / Mobile App from acc. no. XXX on Oct 31 2026 11:40AM. Avl. bal. AED 10038.58.")
        assertEquals(10000.00, t.amount, 0.001)
        assertEquals("AED", t.currency)
        assertEquals(Direction.DEBIT, t.direction)
        assertEquals(TxKind.TRANSFER, t.kind)
        assertEquals("ADCB", t.bank)
        assertEquals(10038.58, t.balanceAfter!!, 0.001)
        assertNull(t.merchant)
        assertNull(t.account)
    }

    // ---- typical UAE formats (to be replaced with real samples as they arrive) ----------

    @Test fun card_purchase_with_merchant_and_limit() {
        val t = tx("EmiratesNBD",
            "Purchase of AED 245.50 with Credit Card ending 4321 at CARREFOUR MARKET, ABU DHABI. Avl Cr. Limit is AED 18,754.50.")
        assertEquals(245.50, t.amount, 0.001)
        assertEquals(Direction.DEBIT, t.direction)
        assertEquals(TxKind.PURCHASE, t.kind)
        assertEquals("CARREFOUR MARKET", t.merchant)
        assertEquals("4321", t.account)
        assertEquals(18754.50, t.balanceAfter!!, 0.001)
        assertTrue(t.isCard)
    }

    @Test fun foreign_currency_purchase() {
        val t = tx("FAB", "Your FAB Debit Card XXXX1234 was used for USD 19.99 at NETFLIX.COM on 12/10/2026. Available balance AED 5,210.00")
        assertEquals(19.99, t.amount, 0.001)
        assertEquals("USD", t.currency)
        assertEquals("NETFLIX.COM", t.merchant)
        assertEquals("1234", t.account)
    }

    @Test fun salary_credit() {
        val t = tx("Mashreq", "Your account 0123XXXX5678 has been credited with AED 46,000.00 Salary on 25-Oct-2026. Available balance AED 71,000.00")
        assertEquals(46000.0, t.amount, 0.001)
        assertEquals(Direction.CREDIT, t.direction)
        assertEquals(TxKind.SALARY, t.kind)
    }

    @Test fun atm_withdrawal() {
        val t = tx("ADCB", "Cash withdrawal of AED 500.00 from ATM using Debit Card XXX9876 on 30/10/2026. Avl bal AED 9,500.00")
        assertEquals(TxKind.ATM, t.kind)
        assertEquals(500.0, t.amount, 0.001)
    }

    @Test fun dhs_is_aed() {
        val t = tx("ENBD", "Dhs 32.00 spent on your card ending 1111 at TALABAT. Avl bal Dhs 1,000.00")
        assertEquals("AED", t.currency)
        assertEquals(32.0, t.amount, 0.001)
        assertEquals("TALABAT", t.merchant)
    }

    @Test fun card_payment_received_is_not_spending() {
        val t = tx("EmiratesNBD", "Payment of AED 3,500.00 has been received on your Credit Card ending 4321. Thank you.")
        assertEquals(TxKind.CARD_PAYMENT, t.kind)
        assertEquals(Direction.CREDIT, t.direction)
    }

    @Test fun card_statement_due() {
        val r = SmsParser.parse("EmiratesNBD",
            "Your Credit Card ending 4321 statement: Total amount due AED 6,240.75, Minimum due AED 312.04. Payment due date 25/11/2026.")
        assertTrue(r.toString(), r is ParseResult.Statement)
        val s = (r as ParseResult.Statement).statement
        assertEquals(6240.75, s.totalDue!!, 0.001)
        assertEquals(312.04, s.minDue!!, 0.001)
        assertEquals(LocalDate.of(2026, 11, 25), s.dueDate)
        assertEquals("4321", s.account)
    }

    @Test fun due_date_with_month_name() {
        assertEquals(LocalDate.of(2026, 11, 25), SmsParser.parseDate("25-NOV-2026"))
        assertEquals(LocalDate.of(2026, 11, 25), SmsParser.parseDate("25 Nov 2026"))
    }

    // ---- things that must be ignored -------------------------------------------------

    @Test fun otp_is_ignored() {
        val r = SmsParser.parse("ADCB", "123456 is your OTP for purchase of AED 99.00 at AMAZON. Do not share it with anyone.")
        assertTrue(r is ParseResult.Ignored)
    }

    @Test fun declined_is_ignored() {
        val r = SmsParser.parse("FAB", "Transaction of AED 1,200.00 at IKEA was declined due to insufficient funds.")
        assertTrue(r is ParseResult.Ignored)
    }

    @Test fun promotion_is_ignored() {
        val r = SmsParser.parse("Mashreq", "Get up to AED 500 cashback! Apply now for a Mashreq card. T&C apply www.mashreq.com")
        assertTrue(r is ParseResult.Ignored)
    }

    // ---- shared / pasted messages ------------------------------------------------------

    @Test fun shared_message_skips_sender_filter() {
        val r = SmsParser.parse("Shared",
            "AED10000.00 transferred via ADCB Personal Internet Banking / Mobile App from acc. no. XXX on Oct 31 2026 11:40AM. Avl. bal. AED 10038.58.",
            SenderFilter(), trusted = true)
        assertTrue(r is ParseResult.Transaction)
        assertEquals("ADCB", (r as ParseResult.Transaction).tx.bank)
    }

    @Test fun dates_inside_messages() {
        assertEquals(java.time.LocalDateTime.of(2026, 10, 31, 11, 40),
            SmsParser.findDateTime("transferred from acc. no. XXX on Oct 31 2026 11:40AM. Avl. bal. AED 10038.58."))
        assertEquals(java.time.LocalDateTime.of(2026, 10, 30, 21, 5),
            SmsParser.findDateTime("used at TALABAT on 30/10/2026 09:05 PM. Avl bal AED 1,000.00"))
        assertEquals(LocalDate.of(2026, 10, 25),
            SmsParser.findDateTime("credited with AED 46,000.00 Salary on 25-Oct-2026. Available balance")?.toLocalDate())
        assertEquals(LocalDate.of(2026, 10, 12),
            SmsParser.findDateTime("Purchase of AED 12.00 on 2026-10-12 at NOON")?.toLocalDate())
        assertNull(SmsParser.findDateTime("Purchase of AED 245.50 at CARREFOUR MARKET"))
    }

    // ---- authorised senders ------------------------------------------------------------

    @Test fun unlisted_sender_is_ignored_by_default() {
        val r = SmsParser.parse("SomeShop", "Purchase of AED 50.00 at SOMESHOP.")
        assertTrue(r is ParseResult.Ignored)
    }

    @Test fun phone_number_sender_can_be_authorised() {
        val f = SenderFilter(senders = listOf("+971 50 123 4567"))
        assertTrue(f.allowsSender("0501234567"))
        assertTrue(f.allowsSender("+971501234567"))
        assertFalse(f.allowsSender("0509999999"))
        assertFalse(f.allowsSender("ADCB"))
    }

    @Test fun keyword_filter_limits_messages() {
        val f = SenderFilter(senders = listOf("ADCB"), keywords = listOf("transferred", "debited"))
        assertTrue(f.allows("ADCBAlert", "AED10.00 transferred via ADCB"))
        assertFalse(f.allows("ADCBAlert", "Your statement is ready"))
    }

    @Test fun open_mode_accepts_bank_like_senders_only() {
        val f = SenderFilter(senders = emptyList(), onlyListed = false)
        assertTrue(f.allowsSender("RAKBANK"))
        assertFalse(f.allowsSender("+971501234567"))
    }
}
