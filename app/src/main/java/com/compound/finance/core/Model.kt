package com.compound.finance.core

/** Plain Kotlin types shared by the parser, rules and tests (no Android dependencies). */

enum class Direction { DEBIT, CREDIT }

enum class TxKind {
    PURCHASE, TRANSFER, ATM, SALARY, REFUND, EMI, CARD_PAYMENT, BILL, OTHER;

    /** Kinds that move money between your own pots rather than spend or earn it. */
    val isInternal: Boolean get() = this == CARD_PAYMENT
}

data class ParsedTx(
    val amount: Double,
    val currency: String,
    val direction: Direction,
    val kind: TxKind,
    val merchant: String?,
    val account: String?,        // last digits of the card/account when present
    val bank: String?,
    val balanceAfter: Double?,
    val balanceCurrency: String?,
    val isCard: Boolean,
)

data class CardStatement(
    val bank: String?,
    val account: String?,
    val totalDue: Double?,
    val minDue: Double?,
    val currency: String,
    val dueDate: java.time.LocalDate?,
)

sealed class ParseResult {
    data class Transaction(val tx: ParsedTx) : ParseResult()
    data class Statement(val statement: CardStatement) : ParseResult()
    data class Ignored(val reason: String) : ParseResult()
    /** Looks financial but couldn't be read with confidence: goes to the Review list. */
    data class Unrecognized(val amountGuess: Double?, val currencyGuess: String?) : ParseResult()
}

object Categories {
    const val UNCATEGORISED = "Uncategorised"
    const val INCOME = "Income"
    const val SALARY = "Salary"
    const val TRANSFERS = "Transfers"
    const val CASH = "Cash"
    const val LOAN_EMI = "Loan EMI"
    const val CARD_PAYMENT = "Card payment"
    const val REFUNDS = "Refunds"

    val spending = listOf(
        "Groceries", "Dining", "Transport", "Shopping", "Utilities", "Subscriptions",
        "Health", "Travel", "Education", "Insurance", "Entertainment", "Fitness",
        "Government", "Housing", "Family support", "Personal care", CASH, LOAN_EMI,
        TRANSFERS, UNCATEGORISED,
    )
    val income = listOf(SALARY, INCOME, REFUNDS, TRANSFERS)
    val all = (spending + income + CARD_PAYMENT).distinct()

    /**
     * Categories that are not spending even when money leaves the account. Transfers often go
     * between your own accounts; recategorise one (say to Housing or Family support) to count it.
     */
    val notSpending = setOf(CARD_PAYMENT, TRANSFERS)
}
