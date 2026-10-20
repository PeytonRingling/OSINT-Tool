package vip.ringling.recon.domain

/** A plain-English read-out of a scan: one headline sentence, supporting points and the first thing to do. Templated on-device from the findings, nothing is sent anywhere. */
data class Briefing(val headline: String, val points: List<String>, val firstStep: String?)

object BriefingBuilder {
    private fun plural(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"

    fun build(type: InputType?, findings: List<Finding>, ran: Collection<ModuleId>): Briefing {
        val scored = findings.filter { it.scorable }
        val exposed = scored.count { it.severity == Severity.EXPOSED }
        val breaches = scored.filter { it.module == ModuleId.BREACH }
        val points = mutableListOf<String>()
        val events = Exposure.timeline(findings)
        if (breaches.isNotEmpty()) {
            val hot = breaches.count { it.severity == Severity.EXPOSED }
            val span = if (events.size >= 2) " from ${events.first().year} to ${events.last().year}" else events.firstOrNull()?.let { " in ${it.year}" }.orEmpty()
            points += "Appears in ${plural(breaches.size, "data breach", "data breaches")}$span" + if (hot > 0) "; $hot exposed passwords, financial, ID or contact details." else "; none exposed passwords or financial details."
            events.lastOrNull()?.let { points += "Most recent: ${it.title.substringBefore(" ·")} (${monthName(it.month)} ${it.year})." }
        }
        val other = scored.filter { it.module != ModuleId.BREACH }.groupBy { it.module }.entries.sortedByDescending { e -> e.value.count { it.severity == Severity.EXPOSED } * 100 + e.value.size }
        other.take(4).forEach { (m, list) ->
            val hot = list.count { it.severity == Severity.EXPOSED }
            points += "${Exposure.label(m).lowercase().replaceFirstChar { it.uppercase() }}: ${plural(list.size, "finding")}" + if (hot > 0) ", $hot flagged as exposed." else "."
        }
        val failed = findings.count { it.id.endsWith("-error") }
        if (failed > 0) points += "${plural(failed, "source")} couldn't be reached, so this picture may be incomplete."
        val what = type?.label?.lowercase() ?: "target"
        val headline = when {
            scored.isEmpty() && ran.isNotEmpty() -> "Nothing exposed was found for this $what by the sources that ran."
            exposed > 0 -> "This $what has ${plural(exposed, "exposed finding")} out of ${scored.size} total. Act on the exposed ones first."
            else -> "This $what shows ${plural(scored.size, "finding")}, none of them flagged as exposed."
        }
        return Briefing(headline, points, Exposure.fixFirst(findings).firstOrNull()?.let { "${it.title}: ${it.detail}" })
    }

    private fun monthName(m: Int) = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")[(m - 1).coerceIn(0, 11)]
}
