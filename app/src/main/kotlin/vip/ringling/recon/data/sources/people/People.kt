package vip.ringling.recon.data.sources.people

import java.net.URLEncoder
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import vip.ringling.recon.data.net.Endpoints
import vip.ringling.recon.data.net.Http
import vip.ringling.recon.data.net.HttpException
import vip.ringling.recon.data.secret.ApiKeys
import vip.ringling.recon.domain.ApiKeyId
import vip.ringling.recon.domain.Confidence
import vip.ringling.recon.domain.Fact
import vip.ringling.recon.domain.Finding
import vip.ringling.recon.domain.InputType
import vip.ringling.recon.domain.Link
import vip.ringling.recon.domain.ModuleId
import vip.ringling.recon.domain.OsintSource
import vip.ringling.recon.domain.ScanInput
import vip.ringling.recon.domain.Severity

/** The caveat every name match carries. */
const val BY_NAME_ONLY = "Matched by name only: common names return other people, so confirm it's the right person before relying on it."

/** Parsers for each public source. Pure (text in, findings out), so they are tested against saved responses. */
object PeopleParsers {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
    private fun JsonObject.s(k: String) = this[k].str()
    private fun hit(id: String, title: String, detail: String, url: String?, tag: String, facts: List<Fact> = emptyList()) =
        Finding("PEOPLE-hit-$id", ModuleId.PEOPLE, title, detail + "\n" + BY_NAME_ONLY, Severity.INFO, url = url, confidence = Confidence.LOW, tags = listOf(tag), facts = facts)

    /** Wikidata search restricted to humans (`haswbstatement:P31=Q5`); the snippet is the entity's one-line description. */
    fun wikidata(body: String, name: String): List<Finding> =
        json.parseToJsonElement(body).jsonObject["query"]?.jsonObject?.get("search")?.jsonArray.orEmpty().mapNotNull { e ->
            val o = e.jsonObject; val id = o.s("title") ?: return@mapNotNull null
            hit("wikidata-$id", name, o.s("snippet") ?: "Person entry on Wikidata", "https://www.wikidata.org/wiki/$id", "Wikidata (public figures)")
        }.take(5)

    /** ORCID public researcher profiles. */
    fun orcid(body: String): List<Finding> =
        json.parseToJsonElement(body).jsonObject["expanded-result"]?.jsonArray.orEmpty().mapNotNull { e ->
            val o = e.jsonObject; val id = o.s("orcid-id") ?: return@mapNotNull null
            val name = listOfNotNull(o.s("given-names"), o.s("family-names")).joinToString(" ").ifBlank { o.s("credit-name") ?: id }
            val orgs = (o["institution-name"] as? JsonArray).orEmpty().mapNotNull { it.str() }
            hit("orcid-$id", name, if (orgs.isEmpty()) "ORCID researcher profile (no institution listed)" else "ORCID researcher profile · " + orgs.take(3).joinToString(", "), "https://orcid.org/$id", "ORCID (researchers)")
        }.take(5)

    /** CourtListener (RECAP) docket search results. */
    fun courts(body: String, base: String): List<Finding> =
        json.parseToJsonElement(body).jsonObject["results"]?.jsonArray.orEmpty().mapNotNull { e ->
            val o = e.jsonObject; val caption = o.s("caseName") ?: o.s("case_name") ?: return@mapNotNull null
            val docketId = o.s("docket_id") ?: o.s("docketNumber") ?: caption.hashCode().toString()
            val bits = listOfNotNull(o.s("court"), o.s("dateFiled")?.let { "filed ${it.take(10)}" }, o.s("docketNumber")?.let { "docket $it" })
            hit("court-$docketId", caption, bits.joinToString(" · ").ifBlank { "Federal court docket" }, o.s("docket_absolute_url")?.let { base.trimEnd('/') + it }, "Court dockets")
        }.take(6)

    /** FEC individual contributions. Street addresses are deliberately never read: only city/state, employer and the gift itself. */
    fun fec(body: String): List<Finding> =
        json.parseToJsonElement(body).jsonObject["results"]?.jsonArray.orEmpty().mapNotNull { e ->
            val o = e.jsonObject
            val amount = (o["contribution_receipt_amount"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            val committee = o["committee"]?.jsonObject?.s("name") ?: o.s("committee_name") ?: "a committee"
            val date = o.s("contribution_receipt_date")?.take(10).orEmpty()
            val where = listOfNotNull(o.s("contributor_city"), o.s("contributor_state")).joinToString(", ")
            val work = listOfNotNull(o.s("contributor_employer"), o.s("contributor_occupation")).joinToString(" · ")
            hit(
                "fec-" + (o.s("sub_id") ?: "$date$amount$committee".hashCode()), "$${"%,.0f".format(amount)} to $committee",
                listOf(date, where, work).filter { it.isNotBlank() }.joinToString(" · "), "https://www.fec.gov/data/receipts/individual-contributions/", "FEC donations",
                facts = listOfNotNull(o.s("contributor_name")?.let { Fact("Name on filing", it) }, if (where.isNotBlank()) Fact("Location", where) else null, if (work.isNotBlank()) Fact("Employer / occupation", work) else null),
            )
        }.take(10)

    /** Official and public portals with the name filled in where the portal supports it. No people-finder or data-broker sites. */
    fun links(name: String): Finding {
        fun q(s: String) = URLEncoder.encode(s, "UTF-8")
        val quoted = "\"$name\""
        val links = listOf(
            Link("Federal court cases (PACER Case Locator)", "https://pcl.uscourts.gov/pcl/pages/search/findParty.jsf"),
            Link("Court opinions and dockets (CourtListener)", "https://www.courtlistener.com/?q=${q(quoted)}"),
            Link("Sex-offender registries, all states (NSOPW)", "https://www.nsopw.gov/"),
            Link("SEC filings mentioning the name (EDGAR)", "https://efts.sec.gov/LATEST/search-index?q=${q(quoted)}"),
            Link("Financial professionals (FINRA BrokerCheck)", "https://brokercheck.finra.org/search/genericsearch/grid?query=${q(name)}&filter=active%3Dtrue%2Cinactive%3Dtrue&nrows=12&start=0&sort=bc_lastname_sort+asc"),
            Link("Company officers and directors (OpenCorporates)", "https://opencorporates.com/officers?q=${q(name)}"),
            Link("Academic publications (Google Scholar)", "https://scholar.google.com/scholar?q=${q("author:$quoted")}"),
            Link("Patents as inventor (Google Patents)", "https://patents.google.com/?inventor=${q(name)}"),
            Link("News mentions (Google News)", "https://news.google.com/search?q=${q(quoted)}"),
            Link("Professional licence lookups (web search)", "https://www.google.com/search?q=${q("$quoted license lookup")}"),
            Link("Court and legal sites (web search)", "https://www.google.com/search?q=${q("$quoted (site:justia.com OR site:casetext.com OR site:courtlistener.com OR site:unicourt.com)")}"),
            Link("Archived pages (Wayback Machine)", "https://web.archive.org/web/*/${q(name)}"),
        )
        return Finding(
            "PEOPLE-links", ModuleId.PEOPLE, "Official and public portals",
            "Open these to search by name yourself. They are official or public sources; Recon doesn't query them or scrape people-finder sites. State court, property and voter records are searched on each state's or county's own site.",
            Severity.INFO, confidence = Confidence.HIGH, links = links,
        )
    }
}

/**
 * Public-records lookup by name. Free, official or open sources only (Wikidata, ORCID, CourtListener, FEC with your own free key); no data brokers,
 * no home addresses or phone numbers. Every result is "matched by name only". Findings are shown live and never saved to History.
 */
class PeopleSource @Inject constructor(
    private val http: Http,
    private val endpoints: Endpoints,
    private val keys: ApiKeys,
) : OsintSource {
    override val id = ModuleId.PEOPLE
    override val supportedInputTypes = setOf(InputType.PERSON)

    override suspend fun run(input: ScanInput): Flow<Finding> = channelFlow {
        val name = input.raw.trim().replace(Regex("\\s+"), " ")
        val parts = name.split(' ')
        launch { source("wikidata", "Wikidata") { wikidata(name) } }
        launch { source("orcid", "ORCID") { orcid(parts.first(), parts.last()) } }
        launch { source("court", "Court dockets") { courts(name) } }
        val fecKey = keys.get(ApiKeyId.FEC)
        if (fecKey == null) send(Finding("PEOPLE-fec-nokey", ModuleId.PEOPLE, "Campaign donations skipped", "Add a free api.data.gov key in System to include public political donations (FEC).", Severity.INFO))
        else launch { source("fec", "FEC donations") { fec(name, fecKey) } }
        send(PeopleParsers.links(name))
    }

    private suspend fun ProducerScope<Finding>.source(key: String, label: String, block: suspend () -> List<Finding>) {
        try {
            val hits = block()
            if (hits.isEmpty()) send(Finding("PEOPLE-$key-none", ModuleId.PEOPLE, "$label: nothing found", "No $label entries matched this name.", Severity.CLEAN)) else hits.forEach { send(it) }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            send(Finding("PEOPLE-$key-error", ModuleId.PEOPLE, "$label unavailable", e.message ?: e.javaClass.simpleName, Severity.INFO))
        }
    }

    private suspend fun get(url: okhttp3.HttpUrl, vararg headers: Pair<String, String>): String =
        http.execute(Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()) { r ->
            if (r.code == 429) throw HttpException(429, "Rate-limited; try again in a minute")
            if (!r.isSuccessful) throw HttpException(r.code, "HTTP ${r.code}")
            r.body.string()
        }

    private suspend fun wikidata(name: String): List<Finding> {
        val url = "${endpoints.wikidata}/w/api.php".toHttpUrl().newBuilder().addQueryParameter("action", "query").addQueryParameter("list", "search")
            .addQueryParameter("srsearch", "$name haswbstatement:P31=Q5").addQueryParameter("srlimit", "5").addQueryParameter("srnamespace", "0").addQueryParameter("format", "json").build()
        return PeopleParsers.wikidata(get(url), name)
    }

    private suspend fun orcid(given: String, family: String): List<Finding> {
        if (given == family) return emptyList()
        val url = "${endpoints.orcid}/v3.0/expanded-search/".toHttpUrl().newBuilder().addQueryParameter("q", "given-names:$given AND family-name:$family").addQueryParameter("rows", "5").build()
        return PeopleParsers.orcid(get(url, "Accept" to "application/json"))
    }

    private suspend fun courts(name: String): List<Finding> {
        val url = "${endpoints.courtListener}/api/rest/v4/search/".toHttpUrl().newBuilder().addQueryParameter("q", "caseName:(\"$name\")").addQueryParameter("type", "r").addQueryParameter("page_size", "6").build()
        return PeopleParsers.courts(get(url, "Accept" to "application/json"), endpoints.courtListener)
    }

    private suspend fun fec(name: String, key: String): List<Finding> {
        val url = "${endpoints.fec}/v1/schedules/schedule_a/".toHttpUrl().newBuilder().addQueryParameter("api_key", key).addQueryParameter("contributor_name", name)
            .addQueryParameter("per_page", "10").addQueryParameter("sort", "-contribution_receipt_date").build()
        return PeopleParsers.fec(get(url))
    }
}
