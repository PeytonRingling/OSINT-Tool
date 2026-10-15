package vip.ringling.recon.domain

/**
 * Breach severity comes from WHAT leaked, not from the fact of a breach.
 * EXPOSED when any exposed class is present: credentials (passwords and anything that unlocks accounts), financial data (full or partial
 * card data, bank details, transactions), government ID (SSN, passport, driver's licence, issued IDs), physical address, or phone number.
 * Everything else (email address, names, usernames, IP addresses, dates of birth, locations, …) is INFO.
 * Class names are HIBP's `DataClasses` strings, matched case-insensitively.
 */
object BreachSeverity {
    private val credentials = setOf("Passwords", "Historical passwords", "Password hints", "Security questions and answers", "Auth tokens")
    private val financial = setOf(
        "Credit cards", "Partial credit card data", "Bank account numbers", "Financial transactions", "Payment histories", "Payment methods",
        "Financial investments", "Credit status information", "Account balances",
    )
    private val governmentId = setOf("Government issued IDs", "Passport numbers", "Social security numbers", "Driver's licenses", "Drivers licenses", "National IDs", "Tax file numbers")
    private val contact = setOf("Physical addresses", "Phone numbers")

    val exposedClasses: Set<String> = (credentials + financial + governmentId + contact).map { it.lowercase() }.toSet()

    fun isExposedClass(dataClass: String) = dataClass.trim().lowercase() in exposedClasses

    fun of(dataClasses: List<String>): Severity = if (dataClasses.any(::isExposedClass)) Severity.EXPOSED else Severity.INFO

    /** Classes drawn in the warning colour: the exposing ones, plus private messages (sensitive but not scored as exposed). */
    fun hot(dataClasses: List<String>): Set<String> = dataClasses.filter { isExposedClass(it) || it.equals("Private messages", true) }.toSet()
}

/** Brings a finding saved by an older build in line with the current rules (used for History rows saved before [RiskScore.VERSION]). */
object Rescore {
    fun finding(f: Finding): Finding = when {
        f.module == ModuleId.REGISTRY -> f.copy(severity = Severity.INFO)
        f.id.startsWith("BREACH-hibp-") && f.id != "BREACH-hibp-pastes" && f.id != "BREACH-hibp-none" && f.tags.isNotEmpty() ->
            f.copy(severity = BreachSeverity.of(f.tags), hotTags = BreachSeverity.hot(f.tags))
        else -> f
    }
}
