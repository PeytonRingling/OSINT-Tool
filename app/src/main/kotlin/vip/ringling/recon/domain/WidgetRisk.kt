package vip.ringling.recon.domain

/** The single number the home-screen risk widget shows: the highest latest risk among scored targets. Never a name, value or finding. */
object WidgetRisk {
    fun overall(snapshots: List<ScanSnapshot>): Int? =
        snapshots.filter { !it.isArea }.groupBy { it.key }.values.map { l -> l.maxBy { it.ts }.risk }.maxOrNull()

    fun label(risk: Int?): String = when {
        risk == null -> "NO SCANS"
        risk >= 50 -> "CRITICAL"
        risk >= 14 -> "MODERATE"
        else -> "LOW"
    }
}
