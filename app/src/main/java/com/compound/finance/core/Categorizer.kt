package com.compound.finance.core

import java.util.Locale

data class Rule(val pattern: String, val category: String, val fromUser: Boolean)

/**
 * Assigns a category from the merchant name. Your own corrections always win over the
 * built-in list, and longer patterns win over shorter ones ("careem food" before "careem").
 */
class Categorizer(rules: List<Rule>) {
    private val ordered: List<Pair<String, Rule>> = rules
        .map { normalize(it.pattern) to it }
        .filter { it.first.isNotBlank() }
        .sortedWith(compareByDescending<Pair<String, Rule>> { it.second.fromUser }.thenByDescending { it.first.length })

    fun categorize(merchant: String?, kind: TxKind, direction: Direction): String {
        when (kind) {
            TxKind.CARD_PAYMENT -> return Categories.CARD_PAYMENT
            TxKind.SALARY -> return Categories.SALARY
            TxKind.REFUND -> return Categories.REFUNDS
            else -> {}
        }
        val key = normalize(merchant)
        if (key.isNotEmpty()) {
            ordered.firstOrNull { (p, _) -> matches(key, p) }?.let { return it.second.category }
        }
        return when (kind) {
            TxKind.ATM -> Categories.CASH
            TxKind.EMI -> Categories.LOAN_EMI
            TxKind.TRANSFER -> Categories.TRANSFERS
            else -> if (direction == Direction.CREDIT) Categories.INCOME else Categories.UNCATEGORISED
        }
    }

    companion object {
        /**
         * A pattern must start at a word boundary ("mcdonald" matches "mcdonalds" but "du" doesn't
         * match "dunkin"); patterns of three characters or fewer must match a whole word.
         */
        fun matches(key: String, pattern: String): Boolean =
            if (pattern.length <= 3) " $key ".contains(" $pattern ") else " $key".contains(" $pattern")

        private val noise = Regex(
            """\b(llc|l\.l\.c|fze|fzco|fz llc|est|trading|co|ltd|pjsc|abu dhabi|dubai|sharjah|ajman|al ain|rak|fujairah|uae|are|ae|ad|dxb|auh)\b"""
        )

        /** Lower-case, drop digits, city names and company suffixes: "CARREFOUR MKT 123 ABU DHABI" -> "carrefour mkt". */
        fun normalize(merchant: String?): String = (merchant ?: "")
            .lowercase(Locale.ROOT)
            .replace(Regex("[0-9#*]+"), " ")
            .replace(noise, " ")
            .replace(Regex("[^a-z&.' ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        /** Built-in UAE merchant list. Anything you correct in the app is added on top. */
        val seed: List<Rule> = listOf(
            "Groceries" to listOf("carrefour", "lulu", "spinneys", "choithrams", "waitrose", "union coop",
                "abu dhabi coop", "al maya", "west zone", "viva", "kibsons", "grandiose", "nesto", "safeer",
                "geant", "megamart", "talabat mart", "instashop", "noon minutes", "el grocer", "baqala", "supermarket", "hypermarket"),
            "Dining" to listOf("talabat", "deliveroo", "careem food", "noon food", "zomato", "starbucks", "costa",
                "tim hortons", "mcdonald", "kfc", "burger king", "shake shack", "restaurant", "cafe", "coffee",
                "pizza", "dominos", "subway", "five guys", "pf chang", "cheesecake", "bakery", "eatery", "grill", "kitchen"),
            "Transport" to listOf("careem", "uber", "rta", "salik", "darb", "adnoc", "enoc", "eppco", "emarat",
                "parking", "mawaqif", "nol", "taxi", "hala", "petrol", "fuel", "car wash", "autopro"),
            "Shopping" to listOf("amazon", "noon", "namshi", "ikea", "ace hardware", "sharaf dg", "emax", "jumbo",
                "virgin megastore", "h&m", "zara", "centrepoint", "max fashion", "decathlon", "apple store", "shein",
                "home centre", "pan emirates", "dragon mart", "mall", "lifestyle", "splash", "babyshop", "sun & sand"),
            "Utilities" to listOf("dewa", "addc", "taqa", "aadc", "sewa", "fewa", "etisalat", "e&", "du", "empower",
                "tabreed", "lootah", "emirates gas", "tasleem"),
            "Subscriptions" to listOf("netflix", "spotify", "youtube", "google storage", "google one", "icloud",
                "apple.com/bill", "apple.com", "osn", "shahid", "starzplay", "disney", "prime video", "amazon prime",
                "chatgpt", "openai", "anthropic", "claude.ai", "microsoft", "adobe", "linkedin", "github", "notion"),
            "Health" to listOf("pharmacy", "aster", "life pharmacy", "boots", "clinic", "hospital", "nmc", "mediclinic",
                "cleveland", "burjeel", "dental", "medical", "seha", "healthpoint", "optic"),
            "Travel" to listOf("emirates airline", "etihad", "flydubai", "air arabia", "airbnb", "booking.com", "agoda",
                "expedia", "hotel", "indigo", "air india", "akbar travels", "musafir", "wego", "resort"),
            "Education" to listOf("school", "gems", "aldar academies", "nursery", "university", "udemy", "coursera",
                "academy", "tuition"),
            "Insurance" to listOf("insurance", "takaful", "axa", "orient", "daman", "adnic", "gig", "sukoon", "salama"),
            "Entertainment" to listOf("vox", "reel cinemas", "novo", "cinema", "playstation", "steam", "xbox",
                "yas waterworld", "ferrari world", "warner bros", "platinumlist", "ticketmaster"),
            "Fitness" to listOf("gym", "fitness first", "gold's gym", "barry's", "yoga", "crossfit", "padel"),
            "Government" to listOf("tamm", "icp", "amer", "tasheel", "traffic fine", "municipality", "mohre",
                "emirates id", "adjd", "dubai police", "abu dhabi police", "ministry"),
            "Personal care" to listOf("salon", "barber", "spa", "tips & toes", "faces", "sephora", "bath & body"),
        ).flatMap { (cat, pats) -> pats.map { Rule(it, cat, fromUser = false) } }
    }
}
