package vip.ringling.recon.domain

/**
 * One finished scan of one target. For ZIP / offender-registry scans only the record COUNT is kept (in [info]); [risk] is 0, [exposed] 0
 * and [findingIds] empty, so nothing about any registrant is ever stored. [info] = -1 marks a count that is unknown (older history rows).
 */
data class ScanSnapshot(
    val id: String,
    val type: InputType,
    val value: String,
    val ts: Long,
    val risk: Int,
    val exposed: Int,
    val info: Int,
    val findingIds: List<String>,
) {
    val key: String get() = "${type.name}:$value"
    val isArea: Boolean get() = type.countsOnly
    /** Registry record count for area scans, null when unknown. */
    val records: Int? get() = if (isArea && info >= 0) info else null

    companion object {
        fun of(id: String, input: ScanInput, findings: List<Finding>, ts: Long): ScanSnapshot =
            if (input.type.countsOnly) {
                ScanSnapshot(id, input.type, input.raw, ts, 0, 0, findings.count { it.isRegistryRecord || it.isPersonMatch }, emptyList())
            } else {
                val scored = findings.filter { it.scorable }
                ScanSnapshot(
                    id, input.type, input.raw, ts, RiskScore.compute(findings),
                    scored.count { it.severity == Severity.EXPOSED }, scored.count { it.severity == Severity.INFO }, scored.map { it.id },
                )
            }

        /** Rebuilds a snapshot from an older History row (registry findings were never stored, so area counts are unknown). */
        fun fromRecord(r: ScanRecord): ScanSnapshot =
            if (r.input.type.countsOnly) ScanSnapshot(r.id, r.input.type, r.input.raw, r.timestampMillis, 0, 0, -1, emptyList())
            else of(r.id, r.input, r.findings, r.timestampMillis)
    }
}
