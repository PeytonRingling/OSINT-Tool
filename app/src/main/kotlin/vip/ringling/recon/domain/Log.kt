package vip.ringling.recon.domain

/** All scans of one target, oldest first, as shown in the Log's "By target" view. */
data class LogTarget(val type: InputType, val value: String, val scans: List<ScanSnapshot>) {
    val key: String get() = "${type.name}:$value"
    val latest: ScanSnapshot get() = scans.last()
    val isArea: Boolean get() = type.countsOnly
    /** Risk series for the sparkline (areas have no score). */
    val risks: List<Int> get() = if (isArea) emptyList() else scans.map { it.risk }
    /** Change of the latest scan against the FIRST scan of this target; null with a single scan or for areas. */
    val delta: Int? get() = if (isArea || scans.size < 2) null else scans.last().risk - scans.first().risk
}

object LogMath {
    /** Groups snapshots by target, newest-scanned target first. */
    fun targets(snapshots: List<ScanSnapshot>): List<LogTarget> =
        snapshots.groupBy { it.key }.values.map { l -> l.sortedBy { it.ts }.let { LogTarget(it.first().type, it.first().value, it) } }.sortedByDescending { it.latest.ts }

    /** "▲ 66 · 100", "▼ 12 · 40", or just the score for a first scan / unchanged score. */
    fun deltaLabel(t: LogTarget): String {
        val d = t.delta
        return when {
            d == null || d == 0 -> "${t.latest.risk}"
            d > 0 -> "▲ $d · ${t.latest.risk}"
            else -> "▼ ${-d} · ${t.latest.risk}"
        }
    }

    fun riskTone(risk: Int): Int = when { risk >= 50 -> 2; risk >= 1 -> 1; else -> 0 }
}
