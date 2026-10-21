package vip.ringling.recon.domain

/** How a scan differs from the previous saved scan of the same target. Pure; derived only from the two finding lists. */
data class SinceLast(val previousTs: Long, val before: Int, val after: Int, val newTitles: List<String>, val goneCount: Int, val incomplete: Boolean) {
    val delta: Int get() = after - before
    val changed: Boolean get() = delta != 0 || newTitles.isNotEmpty() || goneCount > 0
}

object SinceLastBuilder {
    /** Null when there is no previous scan or the target type is a live count-only lookup (registry / name search). */
    fun compute(prev: ScanRecord?, type: InputType?, now: List<Finding>): SinceLast? {
        if (prev == null || type == null || type.countsOnly) return null
        val before = prev.findings.filter { it.scorable }; val cur = now.filter { it.scorable }
        val beforeIds = before.map { it.id }.toSet(); val curIds = cur.map { it.id }.toSet()
        // A source that failed this time would make its findings look "gone"; only report removals when everything answered.
        val incomplete = now.any { it.id.endsWith("-error") }
        return SinceLast(
            prev.timestampMillis, RiskScore.compute(prev.findings), RiskScore.compute(now),
            cur.filter { it.id !in beforeIds }.map { it.title }, if (incomplete) 0 else beforeIds.count { it !in curIds }, incomplete,
        )
    }

    fun headline(s: SinceLast, date: String): String = buildString {
        append("Since $date: ")
        append(when { s.delta > 0 -> "risk ${s.before} → ${s.after} (▲ ${s.delta})"; s.delta < 0 -> "risk ${s.before} → ${s.after} (▼ ${-s.delta})"; else -> "risk unchanged at ${s.after}" })
        if (s.newTitles.isNotEmpty()) append(" · ${s.newTitles.size} new")
        if (s.goneCount > 0) append(" · ${s.goneCount} gone")
    }
}
