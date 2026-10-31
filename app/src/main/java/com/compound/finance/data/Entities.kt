package com.compound.finance.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A money movement. Amounts are positive; [direction] says which way. Dates are epoch millis. */
@Entity(tableName = "transactions", indices = [Index("timestamp"), Index(value = ["smsKey"], unique = true), Index("merchantKey")])
data class TxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val smsKey: String?,              // unique per SMS so re-imports never duplicate
    val timestamp: Long,
    val amount: Double,
    val currency: String,
    val direction: String,            // DEBIT | CREDIT
    val kind: String,                 // TxKind name
    val merchant: String?,
    val merchantKey: String,          // normalised merchant used by rules and recurring detection
    val category: String,
    val bank: String?,
    val account: String?,
    val balanceAfter: Double?,
    val note: String = "",
    val source: String = "SMS",       // SMS | MANUAL
    val excluded: Boolean = false,    // hide from spending totals (e.g. moving money between your accounts)
    val syncedToCompound: Boolean = false,
)

/** Every SMS the app has looked at from an authorised sender, and what it decided. */
@Entity(tableName = "sms", indices = [Index("status"), Index("timestamp")])
data class SmsEntity(
    @PrimaryKey val key: String,      // sender + timestamp + body hash
    val sender: String,
    val body: String,
    val timestamp: Long,
    val status: String,               // PARSED | STATEMENT | IGNORED | REVIEW | DISMISSED
    val reason: String = "",
    val txId: Long? = null,
)

@Entity(tableName = "rules", indices = [Index(value = ["pattern"], unique = true)])
data class RuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pattern: String,
    val category: String,
)

@Entity(tableName = "budgets")
data class BudgetEntity(
    @PrimaryKey val category: String,
    val monthlyLimit: Double,
)

/** A loan, or a credit card. Cards are matched to statement SMS by bank + last digits. */
@Entity(tableName = "debts")
data class DebtEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,                 // LOAN | CARD
    val name: String,
    val lender: String,
    val currency: String,
    val outstanding: Double,
    val annualRate: Double = 0.0,     // %
    val emi: Double = 0.0,            // loans: monthly instalment; cards: unused
    val dueDay: Int = 0,              // loans: day of month the EMI is taken
    val account: String = "",         // last digits, for matching SMS
    val creditLimit: Double = 0.0,
    val statementDue: Double = 0.0,   // cards: total due on the latest statement
    val minDue: Double = 0.0,
    val dueDate: Long = 0,            // cards: next payment due date (epoch day)
    val lastPaidEpochDay: Long = 0,
    val matchKeyword: String = "",    // text that identifies this EMI in SMS, e.g. "SBI HOME LOAN"
    val active: Boolean = true,
)

@Entity(tableName = "bills")
data class BillEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val amount: Double,
    val currency: String,
    val frequency: String,            // Frequency name
    val nextDue: Long,                // epoch day
    val category: String,
    val matchKey: String = "",        // normalised merchant; a matching debit marks it paid
    val isSubscription: Boolean = false,
    val remindDaysBefore: Int = 2,
    val lastPaidEpochDay: Long = 0,
    val active: Boolean = true,
)
