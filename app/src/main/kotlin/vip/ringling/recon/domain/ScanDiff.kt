package vip.ringling.recon.domain

/** What changed between two scans of the same target. Pure: derived only from the two snapshots and the new findings' titles. */
data class ScanDiff(
    val input: ScanInput,
    /** Risk of the previous scan; null when this target had never been scanned. */
    val before: Int?,
    val after: Int,
    val newTitles: List<String>,
    val goneCount: Int,
    val failed: Boolean = false,
) {
    val delta: Int? get() = before?.let { after - it }
    val changed: Boolean get() = failed || before == null || delta != 0 || newTitles.isNotEmpty() || goneCount > 0
}

object ScanDiffs {
    fun compare(prev: ScanSnapshot?, now: ScanSnapshot, findings: List<Finding>): ScanDiff {
        val before = prev?.findingIds?.toSet().orEmpty()
        val fresh = now.findingIds.filter { it !in before }.toSet()
        val titles = findings.filter { it.id in fresh }.map { it.title }
        return ScanDiff(ScanInput(now.value, now.type), prev?.risk, now.risk, titles, if (prev == null) 0 else before.count { it !in now.findingIds.toSet() })
    }

    /** Targets worth re-scanning in bulk: registry (live lookups, user-run only) and photo inputs are never batched. */
    fun batchable(inputs: List<ScanInput>): List<ScanInput> =
        inputs.filter { !it.type.countsOnly && it.type != InputType.IMAGE }.distinctBy { it.type to it.raw }
}
