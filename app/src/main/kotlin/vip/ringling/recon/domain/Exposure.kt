package vip.ringling.recon.domain

import kotlin.math.sqrt

/** A module on the exposure map's orbit: node size grows with its finding count; [dots] is the ring of dots (one per breach). */
data class OrbitNode(val module: ModuleId, val label: String, val count: Int, val worst: Severity?, val dots: List<Severity>) {
    /** Node radius in design units: 9 for an empty module up to 24 for a busy one. */
    val radius: Double get() = 9.0 + minOf(15.0, sqrt(count.toDouble()) * 3.3)
}

data class TimelineEvent(val year: Int, val month: Int, val severity: Severity, val title: String, val findingId: String = "") {
    val monthIndex: Int get() = year * 12 + (month - 1)
}

enum class SourceState { FOUND, CLEAN, SKIPPED, FAILED }

/** One breach source's outcome for a scan, so a clean, skipped or failed source is visible instead of silently missing. */
data class SourceCheck(val name: String, val state: SourceState, val summary: String, val findingId: String?, /** Everything this source found, so its row can list it. */ val findingIds: List<String> = emptyList())

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
        TimelineEvent(m.groupValues[2].toInt(), mi + 1, f.severity, f.title, f.id)
    }.sortedBy { it.monthIndex }

    /** Which breach sources ran and what each said (HIBP / LeakCheck). Empty when no breach source ran. */
    fun breachSources(findings: List<Finding>): List<SourceCheck> {
        val b = findings.filter { it.module == ModuleId.BREACH }
        val out = mutableListOf<SourceCheck>()
        val hibpRows = b.filter { it.id.startsWith("BREACH-hibp") }
        if (hibpRows.isNotEmpty()) {
            val err = hibpRows.firstOrNull { it.id == "BREACH-hibp-error" }
            val breaches = hibpRows.filter { it.id.startsWith("BREACH-hibp-") && it.id !in setOf("BREACH-hibp-pastes", "BREACH-hibp-none", "BREACH-hibp-error", "BREACH-hibp-nokey") }
            out += when {
                hibpRows.any { it.id == "BREACH-hibp-nokey" } -> SourceCheck("Have I Been Pwned", SourceState.SKIPPED, "No API key set", null)
                err != null -> SourceCheck("Have I Been Pwned", SourceState.FAILED, err.detail.lineSequence().first(), err.id)
                breaches.isEmpty() -> SourceCheck("Have I Been Pwned", SourceState.CLEAN, "No breaches", null)
                else -> SourceCheck("Have I Been Pwned", SourceState.FOUND, "${breaches.size} breach${if (breaches.size == 1) "" else "es"}", null, breaches.map { it.id })
            }
        }
        val leak = b.filter { it.id.startsWith("BREACH-leakcheck") }
        if (leak.isNotEmpty()) {
            val err = leak.firstOrNull { it.id.endsWith("-error") }
            val main = leak.firstOrNull { it.id == "BREACH-leakcheck" }
            val keyed = main?.facts?.firstOrNull { it.label == "Endpoint" }?.value?.startsWith("Keyed") == true
            out += when {
                err != null -> SourceCheck("LeakCheck", SourceState.FAILED, err.detail.lineSequence().first(), err.id)
                main == null || main.severity == Severity.CLEAN -> SourceCheck("LeakCheck", SourceState.CLEAN, "No results" + if (keyed) "" else " (free endpoint)", main?.id)
                else -> SourceCheck("LeakCheck", SourceState.FOUND, main.title.substringAfter("· ") + if (keyed) "" else " (free endpoint)", main.id, leak.filter { !it.id.endsWith("-error") && it.severity != Severity.CLEAN }.map { it.id })
            }
        }
        return out
    }

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
