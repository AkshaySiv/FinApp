package com.compound.finance.core

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Reads UAE bank SMS. Works in layers so unfamiliar banks still mostly parse:
 *  1. decide whether the sender is a bank and whether the message is a transaction at all
 *     (OTPs, offers and declined payments are dropped);
 *  2. find every money amount and label the ones that are balances or limits;
 *  3. pick the direction and kind from the verbs, the merchant from "at / to / from".
 * Anything that looks financial but can't be read confidently becomes [ParseResult.Unrecognized]
 * and goes to the Review list in the app.
 */
object SmsParser {

    /** Lower-case fragments of sender IDs used by UAE banks. Users can add more in Settings. */
    val defaultBankSenders = listOf(
        "enbd", "emiratesnbd", "emirates nbd", "liv", "adcb", "fab", "firstabudhabi", "mashreq",
        "adib", "dib", "cbd", "rakbank", "rak bank", "wio", "hsbc", "citi", "nbf", "ajman",
        "sib", "uab", "eib", "nbq", "invest", "alhilal", "al hilal", "emiratesislamic",
        "standardchartered", "sc uae", "ruya", "zand", "mbank", "payit", "e&money", "careempay",
    )

    private val bankNames = linkedMapOf(
        "emirates nbd" to "Emirates NBD", "emiratesnbd" to "Emirates NBD", "enbd" to "Emirates NBD",
        "liv" to "Liv", "adcb" to "ADCB", "first abu dhabi" to "FAB", "fab" to "FAB",
        "mashreq" to "Mashreq", "adib" to "ADIB", "emirates islamic" to "Emirates Islamic",
        "dib" to "DIB", "cbd" to "CBD", "rakbank" to "RAKBANK", "wio" to "Wio", "hsbc" to "HSBC",
        "citi" to "Citibank", "nbf" to "NBF", "ajman bank" to "Ajman Bank", "zand" to "Zand",
        "ruya" to "Ruya", "standard chartered" to "Standard Chartered",
    )

    private const val CCY = "AED|USD|EUR|GBP|INR|SAR|QAR|OMR|BHD|KWD|PKR|PHP|EGP|JPY|CHF|CAD|AUD|SGD|CNY|TRY|THB|LKR|NPR|DHS?|DH"
    private val amountRe = Regex(
        """(?i)(?<![A-Z])(?:($CCY)\.?\s*([0-9][0-9,]*(?:\.[0-9]+)?)|([0-9][0-9,]*(?:\.[0-9]+)?)\s*($CCY)(?![A-Z]))"""
    )

    private val otpWords = listOf("otp", "one time password", "one-time password", "verification code",
        "passcode", "do not share", "never share", "activation code", "security code")
    private val promoWords = listOf("offer", "apply now", "win ", "congratulations", "pre-approved",
        "preapproved", "eligible for", "click", "http", "www.", "% off", "discount", "limited time",
        "upgrade", "t&c", "tnc", "reward points", "earn up to", "get up to")
    private val failedWords = listOf("declined", "unsuccessful", "failed", "insufficient", "not processed",
        "could not be", "was rejected", "reversed due")
    private val creditWords = listOf("credited", "received", "deposited", "deposit of", "salary",
        "refund", "reversal", "has been added", "credit of", "inward", "cashback of")
    private val debitWords = listOf("debited", "purchase", "spent", "paid", "payment of", "withdrawn",
        "withdrawal", "transferred", "transfer of", "sent", "used for", "used at", "charged",
        "deducted", "debit of", "pos ", "txn of", "transaction of", "was used")
    private val balanceWords = listOf("bal", "balance", "avl", "available", "avail", "limit", "outstanding")
    private val statementWords = listOf("statement", "minimum due", "min due", "min. due", "minimum amount",
        "min amt", "min. amount", "total due", "total amount due", "payment due", "due date", "is due on")

    data class Input(val sender: String, val body: String)

    /** Heuristic used only when "read only my listed senders" is switched off. */
    fun looksLikeBankSender(sender: String): Boolean {
        val s = sender.lowercase(Locale.ROOT).replace("-", "").replace("_", "").replace(" ", "")
        if (s.any { it.isLetter() }.not()) return false          // phone numbers: personal messages
        return defaultBankSenders.any { frag -> s.contains(frag.replace(" ", "")) }
    }

    fun parse(sender: String, body: String, filter: SenderFilter = SenderFilter()): ParseResult {
        if (!filter.allows(sender, body)) return ParseResult.Ignored("sender not authorised")
        val text = body.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
        val low = text.lowercase(Locale.ROOT)
        val amounts = findAmounts(text)
        if (otpWords.any { low.contains(it) }) return ParseResult.Ignored("one-time password")
        if (failedWords.any { low.contains(it) }) return ParseResult.Ignored("declined or failed")
        if (amounts.isEmpty()) return ParseResult.Ignored("no amount")

        val bank = detectBank(sender, low)
        val account = findAccount(text)
        val isCard = Regex("""(?i)\b(credit card|debit card|card)\b""").containsMatchIn(text)

        // Card statements and due reminders update what you owe; they are not spending.
        if (statementWords.any { low.contains(it) } && !hasStrongTxVerb(low)) {
            return statement(text, low, bank, account, amounts)
        }

        val looksPromo = promoWords.any { low.contains(it) }
        val firstCredit = creditWords.mapNotNull { w -> low.indexOf(w).takeIf { it >= 0 } }.minOrNull()
        val firstDebit = debitWords.mapNotNull { w -> low.indexOf(w).takeIf { it >= 0 } }.minOrNull()
        if (firstCredit == null && firstDebit == null) {
            if (looksPromo) return ParseResult.Ignored("promotion")
            val a = amounts.firstOrNull { !it.isBalance }
            return ParseResult.Unrecognized(a?.value, a?.currency)
        }
        if (looksPromo && !hasStrongTxVerb(low)) return ParseResult.Ignored("promotion")

        var direction = when {
            firstCredit == null -> Direction.DEBIT
            firstDebit == null -> Direction.CREDIT
            else -> if (firstCredit < firstDebit) Direction.CREDIT else Direction.DEBIT
        }
        val kind = detectKind(low, direction, isCard)
        if (kind == TxKind.CARD_PAYMENT) direction = Direction.CREDIT   // a payment received on a card

        val main = amounts.firstOrNull { !it.isBalance } ?: return ParseResult.Unrecognized(null, null)
        val bal = amounts.firstOrNull { it.isBalance && it.start > main.start }
        val merchant = findMerchant(text, direction)

        return ParseResult.Transaction(
            ParsedTx(
                amount = main.value,
                currency = main.currency,
                direction = direction,
                kind = kind,
                merchant = merchant,
                account = account,
                bank = bank,
                balanceAfter = bal?.value,
                balanceCurrency = bal?.currency,
                isCard = isCard,
            )
        )
    }

    // ---------------------------------------------------------------- pieces

    data class Amount(val value: Double, val currency: String, val start: Int, val isBalance: Boolean)

    fun findAmounts(text: String): List<Amount> = amountRe.findAll(text).mapNotNull { m ->
        val ccyRaw = (m.groups[1]?.value ?: m.groups[4]?.value ?: return@mapNotNull null)
        val num = (m.groups[2]?.value ?: m.groups[3]?.value ?: return@mapNotNull null).replace(",", "")
        val v = num.trimEnd('.').toDoubleOrNull() ?: return@mapNotNull null
        val ccy = normalizeCurrency(ccyRaw)
        val before = text.substring(maxOf(0, m.range.first - 28), m.range.first).lowercase(Locale.ROOT)
        // Only balance words right before the amount count ("Avl bal AED 1,000"), not ones far back.
        val isBal = balanceWords.any { w -> before.lastIndexOf(w).let { it >= 0 && it >= before.length - 22 } }
        Amount(v, ccy, m.range.first, isBal)
    }.toList()

    fun normalizeCurrency(raw: String): String {
        val u = raw.uppercase(Locale.ROOT)
        return if (u == "DH" || u == "DHS") "AED" else u
    }

    private fun hasStrongTxVerb(low: String) =
        listOf("debited", "purchase of", "spent", "withdrawn", "transferred", "credited", "has been used", "was used").any { low.contains(it) }

    private fun detectBank(sender: String, low: String): String? {
        val s = sender.lowercase(Locale.ROOT)
        bankNames.forEach { (k, v) -> if (s.replace(" ", "").contains(k.replace(" ", ""))) return v }
        bankNames.forEach { (k, v) -> if (Regex("\\b${Regex.escape(k)}\\b").containsMatchIn(low)) return v }
        return null
    }

    private val accountRe = Regex(
        """(?i)(?:card|a/c|acct|account|acc)\.?(?:\s*(?:no\.?|number|num|ending(?:\s+with|\s+in)?|ending:|#))?\s*[:\-]?\s*[X\*x•\.]*\s*(\d{3,6})\b"""
    )

    fun findAccount(text: String): String? = accountRe.find(text)?.groupValues?.get(1)?.takeLast(4)

    private fun detectKind(low: String, dir: Direction, isCard: Boolean): TxKind = when {
        listOf("payment received", "payment towards your credit card", "thank you for your payment",
            "payment of", "has been received").any { low.contains(it) } && isCard &&
            (low.contains("received") || low.contains("thank you") || low.contains("towards")) -> TxKind.CARD_PAYMENT
        listOf("atm", "cash withdrawal", "cash withdrawn").any { low.contains(it) } -> TxKind.ATM
        listOf("salary", "payroll", "wps").any { low.contains(it) } && dir == Direction.CREDIT -> TxKind.SALARY
        listOf("refund", "reversal", "reversed").any { low.contains(it) } && dir == Direction.CREDIT -> TxKind.REFUND
        listOf(" emi", "emi ", "loan instal", "instalment", "installment", "finance instal").any { low.contains(it) } -> TxKind.EMI
        listOf("transfer", "trf", "sent to", "remittance", "iban", "fund transfer", "aani", "ipp").any { low.contains(it) } -> TxKind.TRANSFER
        listOf("bill payment", "bill paid", "utility").any { low.contains(it) } -> TxKind.BILL
        dir == Direction.DEBIT -> TxKind.PURCHASE
        else -> TxKind.OTHER
    }

    private val merchantStop =
        """(?=\s+on\s|\s+dated|\s*,|\s*\.\s|\s*\.$|\s+\d{1,2}[/\-]|\s+using|\s+with\s|\s+via\s|\s+in\s|\s+for\s|\s+ref|\s+avl|\s+avail|\s+bal|;|$)"""
    private val merchantRes = listOf(
        Regex("""(?i)\bat\s+([A-Za-z0-9&'@*#/\-\. ]{2,45}?)$merchantStop"""),
        Regex("""(?i)\bat:\s*([A-Za-z0-9&'@*#/\-\. ]{2,45}?)$merchantStop"""),
        Regex("""(?i)\b(?:merchant|mer)\s*[:\-]?\s*([A-Za-z0-9&'@*#/\-\. ]{2,45}?)$merchantStop"""),
    )
    private val toRe = Regex("""(?i)\b(?:to|towards|beneficiary)\s*[:\-]?\s+(?!your\b|you\b|acc|a/c|account|card)([A-Za-z0-9&'@*#/\-\. ]{2,45}?)$merchantStop""")
    private val fromRe = Regex("""(?i)\bfrom\s+(?!your\b|you\b|acc|a/c|account|card)([A-Za-z0-9&'@*#/\-\. ]{2,45}?)$merchantStop""")

    fun findMerchant(text: String, dir: Direction): String? {
        val candidates = buildList {
            merchantRes.forEach { r -> r.find(text)?.let { add(it.groupValues[1]) } }
            if (dir == Direction.DEBIT) toRe.find(text)?.let { add(it.groupValues[1]) }
            else fromRe.find(text)?.let { add(it.groupValues[1]) }
        }
        return candidates.map { clean(it) }.firstOrNull { it.isNotBlank() && !looksLikeNoise(it) }
    }

    private fun clean(raw: String): String = raw.trim().trim('.', '-', '*', ',', ':').replace(Regex("\\s+"), " ")

    private fun looksLikeNoise(s: String): Boolean {
        val l = s.lowercase(Locale.ROOT)
        return l.length < 2 || l.all { it.isDigit() || it == ' ' } ||
            listOf("your", "the account", "account", "card ending", "mobile app", "online banking",
                "personal internet banking").any { l.startsWith(it) } ||
            amountRe.containsMatchIn(s)
    }

    // ---------------------------------------------------------------- statements

    private val totalDueRe = Regex("""(?i)(?:total\s+(?:amount\s+)?due|statement\s+(?:balance|amount)|outstanding\s+(?:balance|amount)|amount\s+due)[^0-9A-Z]{0,15}(?:is\s+|of\s+)?(?:($CCY)\.?\s*)?([0-9][0-9,]*(?:\.[0-9]+)?)""")
    private val minDueRe = Regex("""(?i)min(?:imum)?\.?\s*(?:amount|amt)?\.?\s*(?:due|payment)[^0-9A-Z]{0,15}(?:is\s+|of\s+)?(?:($CCY)\.?\s*)?([0-9][0-9,]*(?:\.[0-9]+)?)""")
    private val dueDateRe = Regex("""(?i)(?:due\s+(?:date|on|by)|payable\s+by|pay\s+by|before)\s*(?:is\s+|:\s*)?([0-9]{1,2}[\s/\-.][A-Za-z0-9]{2,9}[\s/\-.,]*[0-9]{2,4}|[A-Za-z]{3,9}\s+[0-9]{1,2},?\s+[0-9]{4})""")

    private fun statement(text: String, low: String, bank: String?, account: String?, amounts: List<Amount>): ParseResult {
        val total = totalDueRe.find(text)
        val min = minDueRe.find(text)
        val ccy = (total?.groups?.get(1)?.value ?: min?.groups?.get(1)?.value ?: amounts.first().currency).let(::normalizeCurrency)
        val totalV = total?.groupValues?.get(2)?.replace(",", "")?.toDoubleOrNull()
        val minV = min?.groupValues?.get(2)?.replace(",", "")?.toDoubleOrNull()
        val due = dueDateRe.find(text)?.groupValues?.get(1)?.let(::parseDate)
        if (totalV == null && minV == null) {
            val a = amounts.firstOrNull { !it.isBalance } ?: amounts.first()
            // A due reminder with a single amount: treat it as the total due.
            return if (low.contains("due")) ParseResult.Statement(CardStatement(bank, account, a.value, null, a.currency, due))
            else ParseResult.Unrecognized(a.value, a.currency)
        }
        return ParseResult.Statement(CardStatement(bank, account, totalV, minV, ccy, due))
    }

    private val dateFormats = listOf(
        "d/M/yyyy", "d/M/yy", "d-M-yyyy", "d-M-yy", "d.M.yyyy", "d MMM yyyy", "d-MMM-yyyy", "d-MMM-yy",
        "d/MMM/yyyy", "d MMM yy", "dd MMM yyyy", "MMM d yyyy", "MMM d, yyyy", "MMMM d, yyyy", "d MMMM yyyy",
    ).map { DateTimeFormatter.ofPattern(it, Locale.ENGLISH) }

    fun parseDate(raw: String): LocalDate? {
        val s = raw.trim().trimEnd('.', ',').replace(Regex("\\s+"), " ")
            .split(" ").joinToString(" ") { w -> w.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) } }
        for (f in dateFormats) {
            try { return LocalDate.parse(s, f) } catch (_: Exception) { }
        }
        return null
    }
}
