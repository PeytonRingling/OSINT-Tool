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

    /** One plain sentence on why a leaked data class matters; null for classes with nothing useful to add. */
    fun explain(dataClass: String): String? {
        val c = dataClass.lowercase()
        return when {
            c.contains("password hint") -> "Hints often give away the password itself."
            c.contains("password") -> "Can unlock this account, and any other where it was reused."
            c.contains("auth token") || c.contains("session") -> "Can let someone in without a password until it expires."
            c.contains("security question") -> "Answers are reused to reset accounts. Change them where you can."
            c.contains("credit card") || c.contains("bank") || c.contains("payment") || c.contains("financial") -> "Financial details: watch statements for charges you don't recognize."
            c.contains("social security") || c.contains("passport") || c.contains("government") || c.contains("driver") -> "Identity documents are the hardest to replace and enable identity theft."
            c.contains("physical address") -> "Where you live. Enables targeted scams and mail fraud."
            c.contains("phone number") -> "Enables scam calls, texts and SIM-swap attempts."
            c.contains("date of birth") -> "Often used to verify your identity elsewhere."
            c.contains("email address") -> "Makes you a target for phishing that mentions this service."
            c.contains("ip address") -> "Roughly where you connected from."
            c.contains("geographic") -> "Approximate location."
            c.contains("username") -> "Handles can be matched across sites."
            c.contains("name") -> "Makes phishing messages more convincing."
            else -> null
        }
    }
}
