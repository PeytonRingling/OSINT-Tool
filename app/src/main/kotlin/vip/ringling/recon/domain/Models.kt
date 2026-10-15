package vip.ringling.recon.domain

import kotlinx.serialization.Serializable

@Serializable
enum class InputType(val label: String) {
    EMAIL("Email"),
    PHONE("Phone"),
    USERNAME("Username"),
    DOMAIN("Domain"),
    ZIP("ZIP"),
    /** Never auto-detected: raw is a content URI picked by the user. */
    IMAGE("Photo"),
    IP("IP"),
    WALLET("Wallet"),
}

@Serializable
enum class Severity(val label: String) {
    CLEAN("Clean"),
    INFO("Info"),
    EXPOSED("Exposed"),
}

@Serializable
enum class Confidence(val label: String) { LOW("Low"), MEDIUM("Medium"), HIGH("High") }

@Serializable
enum class ModuleId(val label: String, val tagline: String) {
    BREACH("Breach check", "HIBP · LeakCheck"),
    IDENTITY("Identity", "Accounts across sites"),
    PHONE("Phone intel", "Region · carrier · spam reports"),
    DOMAIN("Domain recon", "DNS · mail auth · RDAP"),
    REGISTRY("Offender registry", "Idaho · live lookup"),
    IMAGE("Photo metadata", "EXIF · GPS · camera · hashes"),
    NETWORK("Network intel", "RDAP · reverse DNS · ports · Tor"),
    CRYPTO("Crypto wallet", "On-chain activity · scam reports"),
    CODE("Code footprint", "GitHub profile · commit emails"),
    EMAILINTEL("Email intel", "Provider · disposable · pivots"),
    DORKS("Search dorks", "Ready-made operators"),
}

@Serializable
data class GeoPoint(val lat: Double, val lng: Double)

/** An external page to open in the browser (a search, a profile, a reverse-image lookup). */
@Serializable
data class Link(val label: String, val url: String)

/** A follow-up search the user can launch with one tap; [query] is typed into the search field and auto-detected. */
@Serializable
data class Pivot(val label: String, val query: String)

/** One labelled line in a detail screen's fact table (e.g. "Breach date" → "Oct 4, 2013"). */
@Serializable
data class Fact(val label: String, val value: String)

/** A lookup that costs real money. It is only ever offered, never run, until the user confirms the shown [cost]. */
@Serializable
data class PaidAction(val id: String, val label: String, val cost: String, val explanation: String)

/** Normalized result emitted by every source. Optional fields let any module attach a photo, a place or labels. */
@Serializable
data class Finding(
    val id: String,
    val module: ModuleId,
    val title: String,
    val detail: String,
    val severity: Severity,
    val url: String? = null,
    val raw: String = "",
    val confidence: Confidence? = null,
    val geo: GeoPoint? = null,
    val imageUrl: String? = null,
    val imageLargeUrl: String? = null,
    val tags: List<String> = emptyList(),
    /** Tags to draw in the warning colour. */
    val hotTags: Set<String> = emptySet(),
    val facts: List<Fact> = emptyList(),
    val links: List<Link> = emptyList(),
    val pivots: List<Pivot> = emptyList(),
    val paid: PaidAction? = null,
)

data class ScanInput(val raw: String, val type: InputType)

enum class ModuleStatus { QUEUED, RUNNING, DONE, FAILED }

/** Events a scan streams out as modules start, emit, and finish. */
sealed interface ScanEvent {
    data class Started(val module: ModuleId) : ScanEvent
    data class Found(val finding: Finding) : ScanEvent
    data class Finished(val module: ModuleId) : ScanEvent
    data class Failed(val module: ModuleId, val message: String) : ScanEvent
}

/** Snapshot of one scan: per-module status plus findings so far. */
data class ScanSession(
    val input: ScanInput,
    val statuses: Map<ModuleId, ModuleStatus> = emptyMap(),
    val errors: Map<ModuleId, String> = emptyMap(),
    val findings: List<Finding> = emptyList(),
) {
    val running: Boolean get() = statuses.values.any { it == ModuleStatus.RUNNING || it == ModuleStatus.QUEUED }
    val complete: Boolean get() = statuses.isNotEmpty() && !running
    fun byModule(): Map<ModuleId, List<Finding>> = findings.groupBy { it.module }
}

/** Past scan as shown in History. */
data class ScanRecord(
    val id: String,
    val input: ScanInput,
    val timestampMillis: Long,
    val findings: List<Finding>,
    /** [RiskScore.VERSION] the row was saved under. Older rows are re-scored on load and labelled in History. */
    val scoreVersion: Int = RiskScore.VERSION,
) {
    val risk: Int get() = RiskScore.compute(findings)
    val legacyScore: Boolean get() = scoreVersion < RiskScore.VERSION
    /** ZIP / offender-registry searches are live lookups, not risk assessments: they show a neutral result count instead. */
    val neutral: Boolean get() = input.type == InputType.ZIP
}

/**
 * Risk score, version [VERSION]. Deterministic: it depends ONLY on how many scorable findings are EXPOSED and how many are INFO.
 *
 *     score = min(100, 14 × exposed + 4 × info)          bands: ≥ 50 exposed, ≥ 14 info (one exposed finding is never "clean"), else clean
 *
 * Scorable = evidence only. Not counted: CLEAN findings, offender-registry records (never risk-scored), search dorks, and
 * housekeeping findings (summaries, "… unavailable" errors, link lists, manual-check lists, the identity roll-up, paid-lookup offers).
 * The same counts therefore always give the same score, whatever order results arrive in.
 */
object RiskScore {
    const val VERSION = 2
    const val EXPOSED_WEIGHT = 14
    const val INFO_WEIGHT = 4

    fun compute(exposed: Int, info: Int): Int = (exposed * EXPOSED_WEIGHT + info * INFO_WEIGHT).coerceIn(0, 100)

    fun compute(findings: List<Finding>): Int {
        val scorable = findings.filter { it.scorable }
        return compute(scorable.count { it.severity == Severity.EXPOSED }, scorable.count { it.severity == Severity.INFO })
    }

    fun band(score: Int): Severity = when {
        score >= 50 -> Severity.EXPOSED
        score >= 14 -> Severity.INFO
        else -> Severity.CLEAN
    }
}

private val NON_EVIDENCE_SUFFIXES = listOf("-error", "-summary", "-manual", "-links", "-profile", "-offer", "-pending", "-nostate", "-none")

/** True when the finding is evidence about the subject (see [RiskScore]). */
val Finding.scorable: Boolean
    get() = module != ModuleId.REGISTRY && module != ModuleId.DORKS && paid == null && NON_EVIDENCE_SUFFIXES.none { id.endsWith(it) }

/** True for findings that must be shown neutrally (no severity chip, no "Exposed" wording). */
val Finding.neutral: Boolean get() = module == ModuleId.REGISTRY

/** An actual registrant record (as opposed to the summary / notes / "none found" rows of the registry module). */
val Finding.isRegistryRecord: Boolean get() = module == ModuleId.REGISTRY && Regex("^REGISTRY-[A-Z]{2}-.+").matches(id)
