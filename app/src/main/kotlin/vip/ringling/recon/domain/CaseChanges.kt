package vip.ringling.recon.domain

enum class ChangeKind { FIRST, WORSE, BETTER, INFO_MORE, SAME }

/** One scan of one target, described against the previous scan of the SAME target. Built only from stored snapshots; nothing about causes is inferred. */
data class ChangeEvent(
    val snapshot: ScanSnapshot,
    val previous: ScanSnapshot?,
    val kind: ChangeKind,
    /** Short tag: "First scan", "▲ 66", "▼ 12", "+2 info", "No change". */
    val tag: String,
    /** One-line diff: "Risk 34 → 100. Exposed 3 → 12." */
    val summary: String,
) {
    val worse: Boolean get() = kind == ChangeKind.WORSE
    val better: Boolean get() = kind == ChangeKind.BETTER
}

object CaseChanges {
    private fun plural(n: Int, one: String, many: String = one + "s") = if (n == 1) one else many

    fun describe(prev: ScanSnapshot?, cur: ScanSnapshot): ChangeEvent {
        if (cur.isArea) return describeArea(prev, cur)
        if (prev == null) {
            return ChangeEvent(cur, null, ChangeKind.FIRST, "First scan", "Risk ${cur.risk}. ${cur.exposed} exposed, ${cur.info} info.")
        }
        val dRisk = cur.risk - prev.risk
        val newIds = cur.findingIds.toSet() - prev.findingIds.toSet()
        val goneIds = prev.findingIds.toSet() - cur.findingIds.toSet()
        val parts = buildList {
            if (dRisk != 0) add("Risk ${prev.risk} → ${cur.risk}.")
            if (cur.exposed != prev.exposed) add("Exposed ${prev.exposed} → ${cur.exposed}.")
            else if (dRisk == 0 && cur.info == prev.info && newIds.isEmpty() && goneIds.isEmpty()) add("No change since the previous scan.")
            else if (cur.exposed == prev.exposed && dRisk != 0) add("Exposed stays at ${cur.exposed}.")
            if (cur.info != prev.info) add("Info findings ${prev.info} → ${cur.info}.")
            if (newIds.isNotEmpty() || goneIds.isNotEmpty()) add(listOfNotNull(newIds.size.takeIf { it > 0 }?.let { "$it new ${plural(it, "finding")}" }, goneIds.size.takeIf { it > 0 }?.let { "$it gone" }).joinToString(", ") + ".")
        }
        val summary = parts.joinToString(" ")
        return when {
            dRisk > 0 -> ChangeEvent(cur, prev, ChangeKind.WORSE, "▲ $dRisk", summary)
            dRisk < 0 -> ChangeEvent(cur, prev, ChangeKind.BETTER, "▼ ${-dRisk}", summary)
            cur.exposed > prev.exposed -> ChangeEvent(cur, prev, ChangeKind.WORSE, "+${cur.exposed - prev.exposed} exposed", summary)
            cur.exposed < prev.exposed -> ChangeEvent(cur, prev, ChangeKind.BETTER, "−${prev.exposed - cur.exposed} exposed", summary)
            cur.info > prev.info -> ChangeEvent(cur, prev, ChangeKind.INFO_MORE, "+${cur.info - prev.info} info", summary)
            cur.info < prev.info -> ChangeEvent(cur, prev, ChangeKind.BETTER, "−${prev.info - cur.info} info", summary)
            else -> ChangeEvent(cur, prev, ChangeKind.SAME, "No change", summary)
        }
    }

    private fun describeArea(prev: ScanSnapshot?, cur: ScanSnapshot): ChangeEvent {
        val n = cur.records
        if (prev == null) return ChangeEvent(cur, null, ChangeKind.FIRST, "First scan", if (n != null) "$n ${plural(n, "record")}." else "Area scan.")
        val p = prev.records
        if (n == null || p == null) return ChangeEvent(cur, prev, ChangeKind.SAME, "Scanned", "Area scanned again.")
        return when {
            n > p -> ChangeEvent(cur, prev, ChangeKind.INFO_MORE, "+${n - p} records", "Records $p → $n.")
            n < p -> ChangeEvent(cur, prev, ChangeKind.INFO_MORE, "−${p - n} records", "Records $p → $n.")
            else -> ChangeEvent(cur, prev, ChangeKind.SAME, "No change", "Records unchanged at $n.")
        }
    }

    /** Every scan of every target, each diffed against the previous scan of the same target, newest first. */
    fun events(states: List<CaseTargetState>): List<ChangeEvent> =
        states.flatMap { st -> st.scans.sortedBy { it.ts }.let { l -> l.mapIndexed { i, s -> describe(l.getOrNull(i - 1), s) } } }.sortedByDescending { it.snapshot.ts }

    /** Case exposure (highest risk among scored targets, each at its latest scan so far) at each moment a scored target was scanned, oldest first. */
    fun exposureSeries(states: List<CaseTargetState>): List<Pair<Long, Int>> {
        val scored = states.filter { !it.isArea }.flatMap { it.scans }.sortedBy { it.ts }
        val latest = mutableMapOf<String, Int>()
        return scored.map { s -> latest[s.key] = s.risk; s.ts to latest.values.max() }
    }

    /** "▲ n since first scan": the current case exposure against the exposure after the very first scan. Null with fewer than two points. */
    fun sinceFirst(series: List<Pair<Long, Int>>): Int? = if (series.size < 2) null else series.last().second - series.first().second
}
