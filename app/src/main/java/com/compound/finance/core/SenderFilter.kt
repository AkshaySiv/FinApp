package com.compound.finance.core

import java.util.Locale

/**
 * Which SMS the app is allowed to read.
 *
 * @param senders     authorised senders: bank sender IDs ("ADCBAlert", "EmiratesNBD") or phone
 *                    numbers ("+971501234567", "0501234567"). IDs match if the sender contains them;
 *                    numbers match on their last 9 digits, so +971 and 0 prefixes both work.
 * @param onlyListed  true: read nothing except these senders (the default and safest).
 *                    false: also read any sender that looks like a UAE bank.
 * @param keywords    optional: when not empty, a message must contain at least one of these
 *                    words or phrases (case-insensitive) to be read.
 */
data class SenderFilter(
    val senders: List<String> = DEFAULT_SENDERS,
    val onlyListed: Boolean = true,
    val keywords: List<String> = emptyList(),
) {
    fun allowsSender(sender: String): Boolean {
        if (matchesList(sender)) return true
        return !onlyListed && SmsParser.looksLikeBankSender(sender)
    }

    fun allows(sender: String, body: String): Boolean {
        if (!allowsSender(sender)) return false
        if (keywords.isEmpty()) return true
        val low = body.lowercase(Locale.ROOT)
        return keywords.any { k -> k.isNotBlank() && low.contains(k.trim().lowercase(Locale.ROOT)) }
    }

    fun matchesList(sender: String): Boolean {
        val s = norm(sender)
        val sDigits = sender.filter { it.isDigit() }
        return senders.any { entry ->
            val e = norm(entry)
            if (e.isEmpty()) return@any false
            val eDigits = entry.filter { it.isDigit() }
            val entryIsNumber = eDigits.length >= 7 && entry.none { it.isLetter() }
            if (entryIsNumber) sDigits.length >= 7 && sDigits.takeLast(9) == eDigits.takeLast(9)
            else s.contains(e)
        }
    }

    companion object {
        private fun norm(x: String) = x.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() || it == '&' }

        /** Common UAE bank sender IDs. Edit in Settings to match exactly what your phone shows. */
        val DEFAULT_SENDERS = listOf(
            "EmiratesNBD", "ENBD", "ADCB", "FAB", "Mashreq", "ADIB", "DIB", "CBD", "RAKBANK",
            "Wio", "Liv", "HSBC", "EmiratesIslamic",
        )
    }
}
