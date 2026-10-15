package vip.ringling.recon.domain

import java.security.MessageDigest
import java.time.Instant

/**
 * A self-describing evidence page for one finding: what it said, when Recon captured it and a SHA-256 of that content, so a later copy
 * can be checked against it. It records what a public source returned at that time; it does not prove the source's claim is true.
 * Offender-registry records are never exportable ([Export.exportable]), so they can't be turned into evidence pages either.
 */
object Evidence {
    fun hash(f: Finding): String = sha256(Export.text(f) + "\n" + f.facts.joinToString("\n") { "${it.label}: ${it.value}" })

    fun markdown(f: Finding, capturedAtMillis: Long): String = buildString {
        appendLine("# Evidence record")
        appendLine()
        appendLine("- Captured: ${Instant.ofEpochMilli(capturedAtMillis)} (UTC)")
        appendLine("- Source module: ${f.module.label}")
        appendLine("- Finding: ${f.title}")
        appendLine("- Severity: ${f.severity.label}")
        f.url?.let { appendLine("- Source URL: $it") }
        appendLine("- Content SHA-256: ${hash(f)}")
        appendLine()
        appendLine("## What the source returned")
        appendLine(f.detail)
        if (f.facts.isNotEmpty()) {
            appendLine()
            appendLine("## Details")
            f.facts.forEach { appendLine("- ${it.label}: ${it.value}") }
        }
        appendLine()
        appendLine("Recorded by Recon on the device. The hash covers the finding text and details above, so any later edit changes it. This page shows what a public source returned at the time of capture; it does not verify that the source is correct.")
    }

    fun fileName(f: Finding, capturedAtMillis: Long) =
        "recon-evidence-${f.module.name.lowercase()}-${Instant.ofEpochMilli(capturedAtMillis).toString().take(10)}-${hash(f).take(8)}.pdf"

    private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
