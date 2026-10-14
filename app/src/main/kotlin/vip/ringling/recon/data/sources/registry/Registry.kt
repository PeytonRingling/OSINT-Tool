package vip.ringling.recon.data.sources.registry

import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import vip.ringling.recon.domain.Confidence
import vip.ringling.recon.domain.Finding
import vip.ringling.recon.domain.InputType
import vip.ringling.recon.domain.ModuleId
import vip.ringling.recon.domain.OsintSource
import vip.ringling.recon.domain.ScanInput
import vip.ringling.recon.domain.Severity

data class RegistryQuery(val zip: String? = null, val city: String? = null, val county: String? = null)

/** One registrant, already summarized for display. Nothing here is persisted. */
data class RegistryEntry(val name: String, val summary: String, val sourceUrl: String)

/** [totalMatches] is what the registry reports; [entries] is only the first page we fetched. */
data class RegistryResult(val totalMatches: Int, val entries: List<RegistryEntry>, val searchUrl: String, val notes: List<String> = emptyList())

class AdapterUnavailable(message: String) : Exception(message)

/**
 * One adapter per state registry. Contract (Idaho Code §74-120): live per-query lookups only —
 * adapters must not cache, persist or compile results, and must never walk beyond the first page of a query.
 */
interface RegistryAdapter {
    val stateCode: String
    val stateName: String
    fun handlesZip(zip: String): Boolean
    suspend fun search(query: RegistryQuery): RegistryResult
}

class RegistrySource @Inject constructor(private val adapters: Set<@JvmSuppressWildcards RegistryAdapter>) : OsintSource {
    override val id = ModuleId.REGISTRY
    override val supportedInputTypes = setOf(InputType.ZIP)

    override suspend fun run(input: ScanInput): Flow<Finding> = flow {
        val zip = input.raw.take(5)
        val adapter = adapters.firstOrNull { it.handlesZip(zip) }
        if (adapter == null) {
            emit(Finding("REGISTRY-nostate", ModuleId.REGISTRY, "No registry adapter for this ZIP", "Only states with an adapter can be searched (currently: ${adapters.joinToString { it.stateName }.ifEmpty { "none" }}).", Severity.INFO))
            return@flow
        }
        val r = adapter.search(RegistryQuery(zip = zip))
        r.notes.forEachIndexed { i, n ->
            emit(Finding("REGISTRY-note-$i", ModuleId.REGISTRY, "Part of the registry could not be searched", n, Severity.INFO))
        }
        if (r.totalMatches == 0 && r.entries.isEmpty()) {
            emit(Finding("REGISTRY-none", ModuleId.REGISTRY, "No records for $zip", "The ${adapter.stateName} registry reports no matches for this ZIP.", Severity.CLEAN, r.searchUrl))
            return@flow
        }
        val partial = r.totalMatches > r.entries.size
        emit(
            Finding(
                id = "REGISTRY-summary", module = ModuleId.REGISTRY,
                title = "${r.totalMatches} match${if (r.totalMatches == 1) "" else "es"} in $zip",
                detail = (if (partial) "Showing the first ${r.entries.size}; open the official ${adapter.stateName} registry for the rest. " else "") +
                    "Matches are by location only. The registry warns that records may not relate to the person you have in mind; verify before relying on any of this.",
                severity = Severity.INFO, url = r.searchUrl,
            ),
        )
        r.entries.forEachIndexed { i, e ->
            emit(
                Finding(
                    id = "REGISTRY-${adapter.stateCode}-$i", module = ModuleId.REGISTRY, title = e.name, detail = e.summary,
                    severity = Severity.INFO, url = e.sourceUrl, raw = "", confidence = Confidence.HIGH,
                ),
            )
        }
    }
}
