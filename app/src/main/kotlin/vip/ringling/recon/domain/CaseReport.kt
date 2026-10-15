package vip.ringling.recon.domain

/** Markdown report for a case, built from stored scans, notes and derived suggestions only. PDF and share render this same text. */
object CaseReport {
    fun markdown(file: CaseFile, states: List<CaseTargetState>, events: List<ChangeEvent>, pivots: List<CasePivot>, generated: String): String = buildString {
        appendLine("# ${file.name}")
        appendLine("Recon case report · generated $generated · ${states.size} target${if (states.size == 1) "" else "s"}")
        appendLine()
        appendLine("> Compiled from public sources and stored scans. Verify before relying on it; matching names or handles do not prove they belong to the same person. Offender-registry records are never stored or included, only the searched ZIP and a record count.")
        val exposure = CaseLogic.exposure(states)
        appendLine(); appendLine("## Overview")
        appendLine("- Status: ${CaseLogic.status(states).label}")
        if (exposure != null) appendLine("- Case exposure: $exposure/100 (${Exposure.headline(exposure)})")
        CaseChanges.sinceFirst(CaseChanges.exposureSeries(states))?.let { appendLine("- Since first scan: ${if (it >= 0) "▲" else "▼"} ${kotlin.math.abs(it)}") }
        appendLine(); appendLine("## Targets")
        states.forEach { st ->
            val l = st.latest
            val line = when {
                l == null -> "not scanned yet"
                st.isArea -> (l.records?.let { "$it records" } ?: "area scanned") + " (${st.scans.size} scan${if (st.scans.size == 1) "" else "s"})"
                else -> "risk ${l.risk}, ${l.exposed} exposed, ${l.info} info (${st.scans.size} scan${if (st.scans.size == 1) "" else "s"})"
            }
            appendLine("- **${st.target.value}** (${st.target.type.label}): $line")
        }
        val edges = CaseLogic.edges(states.map { it.target }).filter { it.kind != EdgeKind.NONE }
        if (edges.isNotEmpty()) {
            appendLine(); appendLine("## Links")
            edges.forEach { appendLine("- ${states[it.a].target.value} ↔ ${states[it.b].target.value}: ${it.kind.label.lowercase()}") }
        }
        if (events.isNotEmpty()) {
            appendLine(); appendLine("## Changes")
            events.forEach { e -> appendLine("- ${java.time.Instant.ofEpochMilli(e.snapshot.ts).toString().take(16).replace('T', ' ')} · ${e.snapshot.type.label} ${e.snapshot.value} · ${e.tag} — ${e.summary}") }
        }
        if (pivots.isNotEmpty()) {
            appendLine(); appendLine("## Suggested next scans")
            pivots.forEach { appendLine("- ${it.query}: ${it.title}. ${it.reason}") }
        }
        if (file.notes.isNotEmpty()) {
            appendLine(); appendLine("## Notes")
            file.notes.forEach { appendLine("- ${java.time.Instant.ofEpochMilli(it.ts).toString().take(10)}: ${it.text}") }
        }
    }
}
