package vip.ringling.recon.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object Export {
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    @Serializable
    private data class ScanExport(
        val query: String,
        val type: InputType,
        val risk: Int,
        val findings: List<Finding>,
    )

    fun finding(f: Finding): String {
        require(exportable(f)) { "registry records are not exportable" }
        return json.encodeToString(f)
    }

    /** Registry findings are never exported: a saved file of registrants is a compiled list (Idaho Code §74-120). */
    fun exportable(f: Finding) = f.module != ModuleId.REGISTRY

    fun scan(input: ScanInput, findings: List<Finding>): String {
        val kept = findings.filter(::exportable)
        return json.encodeToString(ScanExport(input.raw, input.type, RiskScore.compute(kept), kept))
    }

    /** Plain-text summary used by Share. */
    fun text(f: Finding): String = buildString {
        append("[${f.module.label}] ${f.title} — ${f.severity.label}\n${f.detail}")
        f.url?.let { append("\n$it") }
    }
}
