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

/** One row from a state's search results; [detailRef] is whatever the adapter needs to fetch the detail page. */
data class RegistryEntry(val name: String, val summary: String, val detailRef: String, val sourceUrl: String)

/** Thrown by an adapter that exists but can't run yet (e.g. form details not confirmed). */
class AdapterUnavailable(message: String) : Exception(message)

/**
 * One adapter per state registry. Contract (Idaho Code §74-120): live per-query lookups only —
 * adapters must not cache, persist or compile results, and must not page through the whole registry.
 */
interface RegistryAdapter {
    val stateCode: String
    val stateName: String
    fun handlesZip(zip: String): Boolean
    suspend fun search(query: RegistryQuery): List<RegistryEntry>
}

/** Placeholder until the live search form + result markup are confirmed. */
class IdahoRegistryAdapter @Inject constructor() : RegistryAdapter {
    override val stateCode = "ID"
    override val stateName = "Idaho"
    // Idaho ZIPs are 832xx–838xx.
    override fun handlesZip(zip: String) = zip.take(3).toIntOrNull() in 832..838
    override suspend fun search(query: RegistryQuery): List<RegistryEntry> =
        throw AdapterUnavailable(
            "The Idaho adapter is not wired yet: apps.isp.idaho.gov/sor_id request parameters and result markup " +
                "still need to be confirmed against the live site.",
        )
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
        val entries = adapter.search(RegistryQuery(zip = zip))
        if (entries.isEmpty()) {
            emit(Finding("REGISTRY-none", ModuleId.REGISTRY, "No records for $zip", "${adapter.stateName} registry returned no records for this ZIP.", Severity.CLEAN))
        }
        entries.forEachIndexed { i, e ->
            emit(
                Finding(
                    id = "REGISTRY-${adapter.stateCode}-$i", module = ModuleId.REGISTRY, title = e.name, detail = e.summary,
                    severity = Severity.INFO, url = e.sourceUrl, raw = "", confidence = Confidence.HIGH,
                ),
            )
        }
    }
}
