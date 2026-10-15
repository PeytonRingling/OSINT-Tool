package vip.ringling.recon.domain

import vip.ringling.recon.data.CaseItem

/** Turns a case into a shareable report. Plain Markdown is the source of truth; the PDF renders the same text. */
object CaseReport {
    /** Every pivot query across the case with how many findings mention it: the "entities" that tie findings together. */
    fun entities(items: List<CaseItem>): List<Pair<String, Int>> =
        items.flatMap { it.finding.pivots.map { p -> p.query.lowercase() }.distinct() }
            .groupingBy { it }.eachCount().entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key to it.value }

    fun markdown(items: List<CaseItem>, generated: String): String = buildString {
        appendLine("# Recon case report")
        appendLine("Generated $generated · ${items.size} finding${if (items.size == 1) "" else "s"}")
        appendLine()
        appendLine("> Compiled from public sources. Verify before relying on it; matching names or handles do not prove they belong to the same person.")
        val ent = entities(items)
        if (ent.isNotEmpty()) {
            appendLine(); appendLine("## Entities")
            ent.forEach { (q, n) -> appendLine("- $q" + if (n > 1) " (×$n)" else "") }
        }
        items.groupBy { it.finding.module }.toSortedMap().forEach { (module, group) ->
            appendLine(); appendLine("## ${module.label}")
            group.forEach { (_, f, note) ->
                appendLine(); appendLine("### ${f.title} — ${f.severity.label}")
                appendLine(f.detail)
                f.url?.let { appendLine(); appendLine(it) }
                f.geo?.let { appendLine(); appendLine("Location: ${it.lat}, ${it.lng}") }
                if (f.facts.isNotEmpty()) { appendLine(); f.facts.forEach { appendLine("- **${it.label}:** ${it.value.replace("\n", "; ")}") } }
                if (f.links.isNotEmpty()) { appendLine(); f.links.forEach { appendLine("- [${it.label}](${it.url})") } }
                if (note.isNotBlank()) { appendLine(); appendLine("_Note:_ $note") }
            }
        }
    }
}
