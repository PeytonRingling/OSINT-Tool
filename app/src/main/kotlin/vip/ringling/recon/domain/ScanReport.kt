package vip.ringling.recon.domain

import java.time.Instant

/** A shareable report of one scan: briefing, e-mail grade (domains), then every exportable finding by module. Registry and name-search results are never included. */
object ScanReport {
    fun markdown(input: ScanInput, findings: List<Finding>, nowMillis: Long): String = buildString {
        val shown = findings.filter { Export.exportable(it) && it.module != ModuleId.DORKS && !it.id.endsWith("-error") }
        val scored = shown.filter { it.scorable }
        val b = BriefingBuilder.build(input.type, findings, findings.map { it.module }.toSet())
        appendLine("# ${input.type.label}: ${input.raw}")
        appendLine("Recon scan report · generated ${Instant.ofEpochMilli(nowMillis).toString().take(16).replace('T', ' ')} UTC")
        appendLine()
        appendLine("> Compiled on-device from public sources. Matches by name or handle do not prove the same person. Verify before relying on it.")
        appendLine(); appendLine("## Summary")
        appendLine("- Risk: ${RiskScore.compute(findings)}/100 (${Exposure.headline(RiskScore.compute(findings))})")
        appendLine("- ${b.headline}")
        b.points.forEach { appendLine("- $it") }
        b.firstStep?.let { appendLine("- Start with: $it") }
        MailGradeBuilder.compute(findings)?.takeIf { input.type == InputType.DOMAIN }?.let { g ->
            appendLine(); appendLine("## E-mail spoofing protection: grade ${g.letter} (${g.score}/100)")
            g.checks.forEach { appendLine("- ${it.name}: ${when (it.state) { CheckState.PASS -> "pass"; CheckState.PARTIAL -> "partial"; CheckState.FAIL -> "fail" }}. ${it.advice}") }
        }
        scored.groupBy { it.module }.forEach { (m, list) ->
            appendLine(); appendLine("## ${m.label}")
            list.sortedBy { it.severity.ordinal.let { o -> -o } }.forEach { f ->
                appendLine("### ${f.title} [${f.severity.label}]")
                appendLine(f.detail.lineSequence().take(6).joinToString("\n"))
                f.facts.take(6).forEach { appendLine("- ${it.label}: ${it.value}") }
                f.url?.let { appendLine("Source: $it") }
            }
        }
    }

    fun cover(input: ScanInput, findings: List<Finding>, nowMillis: Long): vip.ringling.recon.data.PdfWriter.Cover {
        val risk = RiskScore.compute(findings); val b = BriefingBuilder.build(input.type, findings, findings.map { it.module }.toSet())
        return vip.ringling.recon.data.PdfWriter.Cover(
            "Recon // Scan report", input.raw, "${input.type.label} · ${Instant.ofEpochMilli(nowMillis).toString().take(10)}",
            risk.toString(), "Risk · ${Exposure.headline(risk)}", listOf(b.headline) + b.points.take(3),
            "Compiled on-device from public sources. Matches by name or handle do not prove the same person.",
        )
    }

    fun fileName(input: ScanInput, nowMillis: Long) =
        "recon-report-${input.type.name.lowercase()}-${input.raw.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(30)}-${Instant.ofEpochMilli(nowMillis).toString().take(10)}.pdf"
}
