package vip.ringling.recon.domain

import kotlin.math.sqrt

/** A module on the exposure map's orbit: node size grows with its finding count; [dots] is the ring of dots (one per breach). */
data class OrbitNode(val module: ModuleId, val label: String, val count: Int, val worst: Severity?, val dots: List<Severity>) {
    /** Node radius in design units: 9 for an empty module up to 24 for a busy one. */
    val radius: Double get() = 9.0 + minOf(15.0, sqrt(count.toDouble()) * 3.3)
}

data class TimelineEvent(val year: Int, val month: Int, val severity: Severity, val title: String) {
    val monthIndex: Int get() = year * 12 + (month - 1)
}

data class FixStep(val title: String, val detail: String, val urgent: Boolean, /** Findings this step is about (tapping the step lists them). */ val findingIds: List<String> = emptyList())

/** Everything the exposure screens derive from a scan's findings. Pure functions of the findings, so they are unit-tested. */
object Exposure {
    private val shortLabel = mapOf(
        ModuleId.BREACH to "BREACH", ModuleId.IDENTITY to "IDENTITY", ModuleId.PHONE to "PHONE", ModuleId.DOMAIN to "DOMAIN", ModuleId.REGISTRY to "REGISTRY",
        ModuleId.IMAGE to "PHOTO", ModuleId.NETWORK to "NETWORK", ModuleId.CRYPTO to "CRYPTO", ModuleId.CODE to "CODE", ModuleId.EMAILINTEL to "EMAIL INTEL", ModuleId.DORKS to "DORKS",
    )

    fun label(m: ModuleId) = shortLabel[m] ?: m.label.uppercase()

    fun headline(score: Int): String = when {
        score >= 50 -> "Critical exposure"
        score >= 14 -> "Moderate exposure"
        else -> "Low exposure"
    }

    /** One node per module that ran (search dorks excluded: they hold no evidence), busiest first. */
    fun nodes(findings: List<Finding>, ran: Collection<ModuleId>): List<OrbitNode> =
        ran.filter { it != ModuleId.DORKS }.map { m ->
            val own = findings.filter { it.module == m && it.scorable }
            val dots = if (m == ModuleId.BREACH) own.sortedBy { if (it.severity == Severity.EXPOSED) 0 else 1 }.map { it.severity } else emptyList()
            OrbitNode(m, label(m), own.size, own.maxByOrNull { it.severity.ordinal }?.severity, dots)
        }.sortedWith(compareByDescending<OrbitNode> { it.count }.thenBy { it.module.ordinal })

    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    /** Parses the "Breach date" fact ("Jun 15, 2026") of each breach finding. */
    fun timeline(findings: List<Finding>): List<TimelineEvent> = findings.filter { it.module == ModuleId.BREACH }.mapNotNull { f ->
        val d = f.facts.firstOrNull { it.label == "Breach date" }?.value ?: return@mapNotNull null
        val m = Regex("""([A-Za-z]{3})[a-z]*\s+\d{1,2},\s*(\d{4})""").find(d) ?: return@mapNotNull null
        val mi = months.indexOf(m.groupValues[1].lowercase()).takeIf { it >= 0 } ?: return@mapNotNull null
        TimelineEvent(m.groupValues[2].toInt(), mi + 1, f.severity, f.title)
    }.sortedBy { it.monthIndex }

    fun hasStealerLogs(findings: List<Finding>) = findings.any { f -> f.module == ModuleId.BREACH && f.facts.any { it.label == "Flags" && it.value.contains("stealer", ignoreCase = true) } }

    /** Up to three ordered actions: stealer logs first, then passwords, then passkeys; non-breach scans list their top exposed findings. */
    fun fixFirst(findings: List<Finding>): List<FixStep> {
        val scored = findings.filter { it.scorable }
        val exposed = scored.filter { it.severity == Severity.EXPOSED }
        val breaches = scored.filter { it.module == ModuleId.BREACH }
        if (breaches.isEmpty()) {
            return exposed.take(3).map { FixStep(it.title, it.detail.lineSequence().first().ifBlank { it.title }, urgent = false, findingIds = listOf(it.id)) }.mapIndexed { i, s -> s.copy(urgent = i == 0) }
        }
        val steps = mutableListOf<FixStep>()
        if (hasStealerLogs(findings)) steps += FixStep("Treat a device as compromised", "Stealer logs come from infected devices.", true, breaches.filter { f -> f.facts.any { it.label == "Flags" && it.value.contains("stealer", ignoreCase = true) } }.map { it.id })
        if (exposed.isNotEmpty()) steps += FixStep("Rotate exposed passwords", "${exposed.size} finding${if (exposed.size == 1) "" else "s"} flagged as exposed.", steps.isEmpty(), exposed.map { it.id })
        val withPasswords = breaches.filter { f -> f.tags.any { it.contains("password", ignoreCase = true) } }
        steps += FixStep("Move key accounts to passkeys", "No password left to steal.", steps.isEmpty(), withPasswords.map { it.id })
        return steps.take(3)
    }
}
