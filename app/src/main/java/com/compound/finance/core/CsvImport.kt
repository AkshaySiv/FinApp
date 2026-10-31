package com.compound.finance.core

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Reads a bank or card statement exported as CSV. Columns are found by their header names,
 * so most banks' exports work without setup:
 *   date      : "Date", "Transaction Date", "Value Date", "Posting Date"
 *   text      : "Description", "Details", "Narration", "Particulars", "Merchant", "Remarks"
 *   amount    : one signed "Amount" column, or separate "Debit" / "Credit" (Withdrawal / Deposit)
 *   currency  : optional "Currency"
 */
object CsvImport {

    data class Row(val date: LocalDate, val description: String, val amount: Double, val direction: Direction, val currency: String?)

    data class Result(val rows: List<Row>, val skipped: Int, val problem: String?)

    fun parse(text: String): Result {
        val lines = text.replace("\r", "").split("\n").filter { it.isNotBlank() }
        if (lines.isEmpty()) return Result(emptyList(), 0, "The file is empty.")
        val delim = listOf(',', ';', '\t').maxBy { d -> lines.take(5).sumOf { l -> l.count { it == d } } }

        // The header is the first line that names a date column (statements often start with account info).
        val headerIdx = lines.take(30).indexOfFirst { l -> split(l, delim).any { norm(it).contains("date") } }
        if (headerIdx < 0) return Result(emptyList(), 0, "Couldn't find a header row with a Date column.")
        val header = split(lines[headerIdx], delim).map(::norm)

        // Exact header match first, then whole-word match ("dr" matches "Dr Cr", not "Address").
        fun col(vararg names: String): Int = names.firstNotNullOfOrNull { n ->
            header.indexOfFirst { it == n }.takeIf { it >= 0 }
        } ?: names.firstNotNullOfOrNull { n ->
            header.indexOfFirst { h -> if (' ' in n) h.contains(n) else h.split(' ').contains(n) }.takeIf { it >= 0 }
        } ?: -1

        val iDate = col("transaction date", "date", "posting date", "value date", "txn date")
        val iDesc = col("description", "details", "narration", "particulars", "merchant", "remarks", "transaction details", "payee")
        val iAmount = header.indexOfFirst { it == "amount" || it.startsWith("amount") || it == "transaction amount" }
        val iDebit = col("debit", "withdrawal", "dr", "money out", "paid out")
        val iCredit = col("credit", "deposit", "cr", "money in", "paid in")
        val iCcy = col("currency", "ccy")
        if (iDate < 0) return Result(emptyList(), 0, "Couldn't find the Date column.")
        if (iAmount < 0 && iDebit < 0 && iCredit < 0) return Result(emptyList(), 0, "Couldn't find an Amount, Debit or Credit column.")

        val rows = mutableListOf<Row>()
        var skipped = 0
        for (line in lines.drop(headerIdx + 1)) {
            val c = split(line, delim)
            fun at(i: Int) = if (i in c.indices) c[i].trim() else ""
            val date = parseDate(at(iDate))
            if (date == null) { skipped++; continue }
            val desc = at(iDesc).ifBlank { "Statement entry" }
            val debit = amount(at(iDebit))
            val credit = amount(at(iCredit))
            val signed = amount(at(iAmount))
            val (value, dir) = when {
                debit != null && debit != 0.0 -> kotlin.math.abs(debit) to Direction.DEBIT
                credit != null && credit != 0.0 -> kotlin.math.abs(credit) to Direction.CREDIT
                signed != null && signed != 0.0 -> {
                    // Some banks mark direction in a separate "Dr/Cr" column instead of a sign.
                    val marker = c.map { norm(it) }.firstOrNull { it == "dr" || it == "cr" || it == "debit" || it == "credit" }
                    val isDebit = when (marker) { "dr", "debit" -> true; "cr", "credit" -> false; else -> signed < 0 }
                    kotlin.math.abs(signed) to (if (isDebit) Direction.DEBIT else Direction.CREDIT)
                }
                else -> { skipped++; continue }
            }
            rows += Row(date, desc, value, dir, at(iCcy).uppercase(Locale.ROOT).takeIf { it.length in 3..4 && it.all(Char::isLetter) })
        }
        return Result(rows, skipped, if (rows.isEmpty()) "No transactions found under the header row." else null)
    }

    private fun norm(s: String) = s.trim().trim('"').lowercase(Locale.ROOT).replace(Regex("[^a-z ]"), " ").replace(Regex("\\s+"), " ").trim()

    /** Splits one CSV line, respecting quoted fields that contain the delimiter. */
    fun split(line: String, delim: Char): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                ch == '"' -> quoted = !quoted
                ch == delim && !quoted -> { out += sb.toString(); sb.clear() }
                else -> sb.append(ch)
            }
            i++
        }
        out += sb.toString()
        return out
    }

    /** "1,234.50", "-45.00", "(45.00)", "AED 45.00", "45.00 DR" */
    fun amount(raw: String): Double? {
        if (raw.isBlank()) return null
        var s = raw.trim().uppercase(Locale.ROOT)
        val negative = s.startsWith("(") && s.endsWith(")") || s.startsWith("-") || s.endsWith("DR") || s.endsWith("-")
        s = s.replace(Regex("[^0-9.]"), "")
        val v = s.toDoubleOrNull() ?: return null
        return if (negative) -v else v
    }

    private val formats = listOf(
        "d/M/yyyy", "d/M/yy", "d-M-yyyy", "d-M-yy", "d.M.yyyy", "yyyy-MM-dd", "yyyy/MM/dd",
        "d MMM yyyy", "d-MMM-yyyy", "d-MMM-yy", "d MMM yy", "dd MMM yyyy", "MMM d, yyyy", "d/MMM/yyyy",
    ).map { java.time.format.DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(it).toFormatter(Locale.ENGLISH) }

    fun parseDate(raw: String): LocalDate? {
        val t = raw.trim().trim('"')
        // keep "31 Oct 2026" style dates whole; drop a trailing time from "31/10/2026 14:05"
        val s = if (t.count { it == ' ' } >= 2 && t.any { it.isLetter() }) t else t.substringBefore(' ')
        for (f in formats) try { return LocalDate.parse(s, f) } catch (_: Exception) { }
        return null
    }
}
