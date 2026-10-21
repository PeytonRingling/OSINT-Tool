package vip.ringling.recon.data.sources.registry

import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import vip.ringling.recon.domain.Confidence
import vip.ringling.recon.domain.Finding
import vip.ringling.recon.domain.GeoPoint
import vip.ringling.recon.domain.InputType
import vip.ringling.recon.domain.ModuleId
import vip.ringling.recon.domain.OsintSource
import vip.ringling.recon.domain.ScanInput
import vip.ringling.recon.domain.Severity

data class RegistryQuery(val zip: String? = null, val city: String? = null, val county: String? = null)

/** One registrant, already summarized for display. Nothing here is persisted. */
data class RegistryEntry(
    /** Stable per record + database, e.g. "A-36073", so a result is never listed twice. */
    val key: String,
    val name: String,
    val summary: String,
    val sourceUrl: String,
    val severity: Severity = Severity.INFO,
    val geo: GeoPoint? = null,
    val imageUrl: String? = null,
    val imageLargeUrl: String? = null,
    val tags: List<String> = emptyList(),
    val hotTags: Set<String> = emptySet(),
    /** Distance from the searched address (address searches only), used for ordering. */
    val miles: Double? = null,
)

/**
 * Streamed by an adapter: one [Header] as soon as the full list of rows is known, then an [Entry] per record as its
 * details finish loading (in any order).
 * [totalMatches] is what the registry reports; [listed] is how many rows we are showing; [capped] means some were left out.
 */
sealed interface RegistryEvent {
    data class Header(val totalMatches: Int, val listed: Int, val capped: Boolean, val searchUrl: String, val notes: List<String> = emptyList()) : RegistryEvent
    data class Entry(val entry: RegistryEntry) : RegistryEvent
}

class AdapterUnavailable(message: String) : Exception(message)

/**
 * One adapter per state registry. Contract (Idaho Code §74-120): live per-query lookups only —
 * adapters must not cache, persist or compile results, must never go beyond the user's own query, and must bound
 * how much of a very large result set they pull.
 */
interface RegistryAdapter {
    val stateCode: String
    val stateName: String
    fun handlesZip(zip: String): Boolean
    fun search(query: RegistryQuery): Flow<RegistryEvent>
    /** Registrants around one street address, via the registry's own map search. Null when the state has no such search. */
    fun searchAddress(query: vip.ringling.recon.domain.AddressQuery): Flow<RegistryEvent>? = null
}

class RegistrySource @Inject constructor(private val adapters: Set<@JvmSuppressWildcards RegistryAdapter>) : OsintSource {
    override val id = ModuleId.REGISTRY
    override val supportedInputTypes = setOf(InputType.ZIP, InputType.ADDRESS)

    override suspend fun run(input: ScanInput): Flow<Finding> = if (input.type == InputType.ADDRESS) runAddress(input) else flow {
        val zip = input.raw.take(5)
        val adapter = adapters.firstOrNull { it.handlesZip(zip) }
        if (adapter == null) {
            emit(Finding("REGISTRY-nostate", ModuleId.REGISTRY, "No registry adapter for this ZIP", "Only states with an adapter can be searched (currently: ${adapters.joinToString { it.stateName }.ifEmpty { "none" }}).", Severity.INFO))
            return@flow
        }
        adapter.search(RegistryQuery(zip = zip)).collect { ev ->
            when (ev) {
                is RegistryEvent.Header -> {
                    ev.notes.forEachIndexed { i, n ->
                        emit(Finding("REGISTRY-note-$i", ModuleId.REGISTRY, "Part of the registry could not be searched", n, Severity.INFO))
                    }
                    if (ev.totalMatches == 0 && ev.listed == 0) {
                        emit(Finding("REGISTRY-none", ModuleId.REGISTRY, "No records for $zip", "The ${adapter.stateName} registry reports no matches for this ZIP.", Severity.CLEAN, ev.searchUrl))
                    } else {
                        emit(
                            Finding(
                                id = "REGISTRY-summary", module = ModuleId.REGISTRY,
                                title = "${ev.totalMatches} match${if (ev.totalMatches == 1) "" else "es"} in $zip",
                                detail = (if (ev.capped) "Showing ${ev.listed} of ${ev.totalMatches}; open the official ${adapter.stateName} registry for the rest. " else "") +
                                    "Matches are by location only. The registry warns that records may not relate to the person you have in mind; verify before relying on any of this.",
                                severity = Severity.INFO, url = ev.searchUrl,
                            ),
                        )
                    }
                }
                is RegistryEvent.Entry -> {
                    val e = ev.entry
                    emit(
                        Finding(
                            id = "REGISTRY-${adapter.stateCode}-${e.key}", module = ModuleId.REGISTRY, title = e.name, detail = e.summary,
                            severity = e.severity, url = e.sourceUrl, raw = "", confidence = Confidence.HIGH,
                            geo = e.geo, imageUrl = e.imageUrl, imageLargeUrl = e.imageLargeUrl, tags = e.tags, hotTags = e.hotTags,
                        ),
                    )
                }
            }
        }
    }

    /** Address search: the registry's own "map search" for the street address typed by the user, live and unstored. */
    private fun runAddress(input: ScanInput): Flow<Finding> = flow {
        val q = vip.ringling.recon.domain.AddressQuery.parse(input.raw)
        val adapter = adapters.firstOrNull { it.stateCode == "ID" }
        val events = if (q == null || adapter == null) null else adapter.searchAddress(q)
        if (q == null || adapter == null || events == null) {
            emit(Finding("REGISTRY-nostate", ModuleId.REGISTRY, "Address search unavailable", "Address search needs a street address and an Idaho city, and is currently available for Idaho only.", Severity.INFO))
            return@flow
        }
        events.collect { ev ->
            when (ev) {
                is RegistryEvent.Header -> {
                    ev.notes.forEachIndexed { i, n -> emit(Finding("REGISTRY-note-$i", ModuleId.REGISTRY, "Part of the registry could not be searched", n, Severity.INFO)) }
                    val where = "${q.miles} mile${if (q.miles == 1) "" else "s"} of ${q.street}, ${q.city}"
                    if (ev.totalMatches == 0 && ev.listed == 0) {
                        emit(Finding("REGISTRY-none", ModuleId.REGISTRY, "No registrants within $where", "The ${adapter.stateName} registry lists no registrant addresses in that radius.", Severity.CLEAN, ev.searchUrl))
                    } else {
                        emit(
                            Finding(
                                id = "REGISTRY-summary", module = ModuleId.REGISTRY,
                                title = "${ev.totalMatches} registrant${if (ev.totalMatches == 1) "" else "s"} within $where",
                                detail = (if (ev.capped) "Showing ${ev.listed} of ${ev.totalMatches}; open the official ${adapter.stateName} registry for the rest. " else "") +
                                    "Distances are from the address you typed, as mapped by the registry. The registry warns that its map locations can be approximate and that records may not relate to the person you have in mind; verify before relying on any of this.",
                                severity = Severity.INFO, url = ev.searchUrl,
                            ),
                        )
                    }
                }
                is RegistryEvent.Entry -> {
                    val e = ev.entry
                    emit(
                        Finding(
                            id = "REGISTRY-${adapter.stateCode}-${e.key}", module = ModuleId.REGISTRY, title = e.name, detail = e.summary,
                            severity = e.severity, url = e.sourceUrl, raw = "", confidence = Confidence.HIGH,
                            geo = e.geo, imageUrl = e.imageUrl, imageLargeUrl = e.imageLargeUrl, tags = e.tags, hotTags = e.hotTags,
                        ),
                    )
                }
            }
        }
    }
}
