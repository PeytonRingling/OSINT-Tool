package vip.ringling.recon.domain

object InputDetector {
    private val email = Regex("^[A-Za-z0-9._%+'-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}$")
    private val zip = Regex("^\\d{5}(-\\d{4})?$")
    private val phone = Regex("^\\+?[\\d\\s().-]{7,20}$")
    private val domain = Regex("^(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)+[A-Za-z]{2,24}$")
    private val ipv4 = Regex("^(?:(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)$")
    private val ipv6 = Regex("^(?:[0-9A-Fa-f]{0,4}:){2,7}[0-9A-Fa-f]{0,4}$")
    private val btc = Regex("^(?:bc1[a-z0-9]{25,60}|[13][a-km-zA-HJ-NP-Z1-9]{25,34})$")
    private val eth = Regex("^0x[0-9a-fA-F]{40}$")
    private val username = Regex("^@?[A-Za-z0-9._-]{2,40}$")

    /** Best guess for [raw], or null when nothing plausible (blank / unparseable). */
    fun detect(raw: String): InputType? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        return when {
            email.matches(s) -> InputType.EMAIL
            ipv4.matches(s) || (ipv6.matches(s) && ':' in s) -> InputType.IP
            eth.matches(s) || btc.matches(s) -> InputType.WALLET
            zip.matches(s) -> InputType.ZIP
            phone.matches(s) && s.count(Char::isDigit) in 7..15 -> InputType.PHONE
            domain.matches(s.removePrefix("https://").removePrefix("http://").substringBefore('/')) -> InputType.DOMAIN
            username.matches(s) -> InputType.USERNAME
            else -> null
        }
    }

    /** Canonical form used by sources (lowercased emails/domains, digits-only phones, no '@'). */
    fun normalize(raw: String, type: InputType): String {
        val s = raw.trim()
        return when (type) {
            InputType.EMAIL -> s.lowercase()
            InputType.DOMAIN -> s.lowercase().removePrefix("https://").removePrefix("http://").substringBefore('/')
            InputType.PHONE -> (if (s.startsWith("+")) "+" else "") + s.filter(Char::isDigit)
            InputType.USERNAME -> s.removePrefix("@")
            InputType.ZIP -> s.take(5)
            InputType.IMAGE -> s
            InputType.IP -> s.lowercase()
            InputType.WALLET -> s
            InputType.PERSON -> s.replace(Regex("\\s+"), " ")
        }
    }
}
