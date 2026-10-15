package vip.ringling.recon.domain

import kotlinx.serialization.Serializable

@Serializable
enum class InputType(val label: String) {
    EMAIL("Email"),
    PHONE("Phone"),
    USERNAME("Username"),
    DOMAIN("Domain"),
    ZIP("ZIP"),
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
) {
    val risk: Int get() = RiskScore.compute(findings)
}

object RiskScore {
    /** 0..100, higher is worse. Exposed findings dominate; info adds a little. */
    fun compute(findings: List<Finding>): Int {
        val exposed = findings.count { it.severity == Severity.EXPOSED }
        val info = findings.count { it.severity == Severity.INFO }
        return (exposed * 14 + info * 4).coerceAtMost(100)
    }

    fun band(score: Int): Severity = when {
        score >= 50 -> Severity.EXPOSED
        score >= 15 -> Severity.INFO
        else -> Severity.CLEAN
    }
}
