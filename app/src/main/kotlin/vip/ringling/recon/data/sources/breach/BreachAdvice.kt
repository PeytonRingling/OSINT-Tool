package vip.ringling.recon.data.sources.breach

/** Plain-language next steps, derived only from which kinds of data a breach exposed. */
object BreachAdvice {
    fun forClasses(classes: List<String>): List<String> {
        val c = classes.map { it.lowercase() }
        fun has(vararg k: String) = c.any { cl -> k.any { cl.contains(it) } }
        return buildList {
            if (has("password", "auth token", "security question")) add("Change this account's password, and any other account where you reused it. Turn on two-factor authentication and use a password manager so every password is unique.")
            if (has("credit card", "bank account", "payment", "financial")) add("Watch card and bank statements for charges you don't recognize, and ask your bank about replacing the card.")
            if (has("social security", "government issued", "passport", "driver")) add("Consider freezing your credit (free at the three bureaus) and monitoring for identity theft.")
            if (has("phone number")) add("Expect scam texts and calls. Add a PIN to your mobile carrier account to prevent SIM-swap takeovers.")
            if (has("email address") && none { it.startsWith("Change") }) add("Expect phishing that uses details from this breach. Don't click links in unexpected messages; go to the site directly.")
            if (has("physical address", "geographic", "date of birth")) add("Personal details like these can be used to make scams more convincing. Be cautious with callers who already 'know' them.")
            if (isEmpty()) add("No password or financial data was reported, so urgent action is unlikely. Still be wary of phishing that mentions this service.")
        }
    }
}
