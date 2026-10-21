package vip.ringling.recon.domain

/** One action that would improve several targets at once. */
data class CheckupFix(val title: String, val detail: String, val targets: List<String>, val urgent: Boolean)

data class Checkup(val targetCount: Int, val worstRisk: Int, val exposedTotal: Int, val fixes: List<CheckupFix>)

/**
 * The "security checkup": looks at the LATEST saved scan of every target and ranks the fixes by how many targets each one helps.
 * Handled findings are excluded. Pure and on-device, built only from what the scans already stored.
 */
object CheckupBuilder {
    private fun passwordish(f: Finding) = f.tags.any { it.contains("password", ignoreCase = true) }

    fun build(records: List<ScanRecord>, isHandled: (Finding) -> Boolean = { false }): Checkup? {
        val latest = records.filter { !it.input.type.countsOnly && it.input.type != InputType.IMAGE }
            .groupBy { it.input.type to it.input.raw.trim().lowercase() }.values.map { l -> l.maxBy { it.timestampMillis } }
        if (latest.isEmpty()) return null
        fun live(r: ScanRecord) = r.findings.filter { it.scorable && it.severity == Severity.EXPOSED && !isHandled(it) }
        val stealer = latest.filter { r -> Exposure.hasStealerLogs(r.findings) }.map { it.input.raw }
        val fixes = mutableListOf<CheckupFix>()
        if (stealer.isNotEmpty()) fixes += CheckupFix("Treat a device as compromised", "Stealer logs come from infected devices: scan them, then change every password.", stealer, urgent = true)
        // Same breach / finding across several targets collapses into one fix.
        val byFinding = linkedMapOf<Pair<ModuleId, String>, MutableList<Pair<String, Finding>>>()
        latest.forEach { r -> live(r).forEach { f -> byFinding.getOrPut(f.module to f.title) { mutableListOf() } += r.input.raw to f } }
        byFinding.forEach { (key, hits) ->
            val targets = hits.map { it.first }.distinct(); val f = hits.first().second
            fixes += when (key.first) {
                ModuleId.BREACH -> CheckupFix("Change passwords from ${f.title}", if (passwordish(f)) "Passwords or hints were exposed. Use a unique password and turn on 2FA." else "Sensitive details were exposed; watch for phishing that uses them.", targets, urgent = false)
                else -> CheckupFix("Fix: ${f.title}", f.detail.lineSequence().firstOrNull().orEmpty().take(120), targets, urgent = false)
            }
        }
        val pw = latest.filter { r -> live(r).any { it.module == ModuleId.BREACH && passwordish(it) } }.map { it.input.raw }
        if (pw.isNotEmpty()) fixes += CheckupFix("Move key accounts to passkeys", "Passwords leaked for these targets; a passkey leaves nothing to steal.", pw, urgent = false)
        val ranked = fixes.sortedWith(compareByDescending<CheckupFix> { it.urgent }.thenByDescending { it.targets.size })
        return Checkup(latest.size, latest.maxOf { RiskScore.compute(it.findings) }, latest.sumOf { live(it).size }, ranked)
    }
}
