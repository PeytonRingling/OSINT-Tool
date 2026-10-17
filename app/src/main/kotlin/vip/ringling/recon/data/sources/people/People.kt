package vip.ringling.recon.data.sources.people

import java.net.URLEncoder
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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

    /** Employment history and publications from an ORCID record, as facts (read in the app, no extra step). */
    fun orcidDetail(employments: String?, works: String?): Pair<List<Fact>, String?> {
        val facts = mutableListOf<Fact>()
        var current: String? = null
        employments?.let { body ->
            runCatching {
                json.parseToJsonElement(body).jsonObject["affiliation-group"]?.jsonArray.orEmpty().mapNotNull { g ->
                    g.jsonObject["summaries"]?.jsonArray?.firstOrNull()?.jsonObject?.get("employment-summary")?.jsonObject
                }.take(5).forEach { s ->
                    val org = (s["organization"] as? JsonObject)?.s("name") ?: return@forEach
                    val role = listOfNotNull(s.s("role-title"), s.s("department-name")).joinToString(", ")
                    val from = (s["start-date"] as? JsonObject)?.get("year").let { it as? JsonObject }?.s("value"); val to = (s["end-date"] as? JsonObject)?.get("year").let { it as? JsonObject }?.s("value")
                    val years = if (from == null) "" else " ($from–${to ?: "present"})"
                    if (to == null && current == null) current = listOfNotNull(s.s("role-title"), org).joinToString(" at ")
                    facts += Fact(if (to == null) "Current position" else "Past position", listOfNotNull(role.ifBlank { null }, org).joinToString(" · ") + years)
                }
            }
        }
        works?.let { body ->
            runCatching {
                val titles = json.parseToJsonElement(body).jsonObject["group"]?.jsonArray.orEmpty().mapNotNull { g ->
                    g.jsonObject["work-summary"]?.jsonArray?.firstOrNull()?.jsonObject?.get("title")?.jsonObject?.get("title")?.jsonObject?.s("value")
                }
                if (titles.isNotEmpty()) facts += Fact("Publications", "${titles.size} listed")
                titles.take(3).forEach { facts += Fact("Work", it) }
            }
        }
        return facts to current
    }

    private fun titleCase(s: String) = s.lowercase().split(' ').joinToString(" ") { w -> w.split('-').joinToString("-") { p -> p.replaceFirstChar { it.uppercase() } } }

    /** CMS NPI registry (US healthcare providers). Only name, credential, specialty and practice city/state are read: never street addresses or phone numbers. */
    fun npi(body: String): List<Finding> =
        json.parseToJsonElement(body).jsonObject["results"]?.jsonArray.orEmpty().mapNotNull { e ->
            val o = e.jsonObject; val number = o.s("number") ?: return@mapNotNull null
            val basic = o["basic"]?.jsonObject ?: return@mapNotNull null
            val name = listOfNotNull(basic.s("first_name"), basic.s("last_name")).joinToString(" ").let(::titleCase).ifBlank { return@mapNotNull null }
            val credential = basic.s("credential")
            val specialty = (o["taxonomies"] as? JsonArray).orEmpty().map { it.jsonObject }.let { t -> (t.firstOrNull { it["primary"].str() == "true" } ?: t.firstOrNull())?.s("desc") }
            val addr = (o["addresses"] as? JsonArray).orEmpty().map { it.jsonObject }.let { a -> a.firstOrNull { it.s("address_purpose") == "LOCATION" } ?: a.firstOrNull() }
            val where = listOfNotNull(addr?.s("city")?.let(::titleCase), addr?.s("state")).joinToString(", ")
            hit("npi-$number", name + (credential?.let { ", $it" } ?: ""), listOfNotNull("Licensed healthcare provider (NPI $number)", specialty, where.ifBlank { null }).joinToString(" · "), "https://npiregistry.cms.hhs.gov/provider-view/$number", "NPI (healthcare providers)")
        }.take(5)

    /** OpenAlex author records (publications). */
    fun openAlex(body: String): List<Finding> =
        json.parseToJsonElement(body).jsonObject["results"]?.jsonArray.orEmpty().mapNotNull { e ->
            val o = e.jsonObject; val id = o.s("id") ?: return@mapNotNull null; val name = o.s("display_name") ?: return@mapNotNull null
            val inst = (o["last_known_institutions"] as? JsonArray).orEmpty().mapNotNull { it.jsonObject.s("display_name") }.firstOrNull()
            val works = (o["works_count"] as? JsonPrimitive)?.contentOrNull; val cites = (o["cited_by_count"] as? JsonPrimitive)?.contentOrNull
            hit("openalex-" + id.substringAfterLast('/'), name, listOfNotNull(works?.let { "$it publications" }, cites?.let { "$it citations" }, inst).joinToString(" · ").ifBlank { "Author record" }, id, "OpenAlex (publications)")
        }.take(5)

    /**
     * Commercial people-finder sites, as plain links the user opens in their own browser. Recon never requests them, scrapes them or sends them anything.
     * They aggregate public records, but their data is often old or wrong and may include home addresses and phone numbers.
     */
    fun finders(name: String): Finding {
        val parts = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val first = parts.firstOrNull().orEmpty(); val last = parts.lastOrNull().orEmpty()
        fun e(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        val dash = "$first-$last"
        val links = listOf(
            Link("TruePeopleSearch", "https://www.truepeoplesearch.com/results?name=${e(name)}"),
            Link("FastPeopleSearch", "https://www.fastpeoplesearch.com/name/${e(dash.lowercase())}"),
            Link("Whitepages", "https://www.whitepages.com/name/${e(dash)}"),
            Link("Spokeo", "https://www.spokeo.com/${e(dash)}"),
            Link("That's Them", "https://thatsthem.com/name/${e(dash)}"),
            Link("Radaris", "https://radaris.com/p/${e(first)}/${e(last)}/"),
            Link("PeekYou", "https://www.peekyou.com/${e("${first}_$last".lowercase())}"),
            Link("ZabaSearch", "https://www.zabasearch.com/people/${e("$first $last".lowercase()).replace("%20", "+")}/"),
            Link("FamilyTreeNow", "https://www.familytreenow.com/search/genealogy/results?first=${e(first)}&last=${e(last)}"),
            Link("Intelius", "https://www.intelius.com/people-search/${e(dash)}"),
            Link("BeenVerified", "https://www.beenverified.com/people/${e(dash.lowercase())}/"),
            Link("Nuwber", "https://nuwber.com/search?name=${e(name)}"),
            Link("Facebook people search", "https://www.facebook.com/public/${e(dash)}"),
            Link("LinkedIn people search", "https://www.linkedin.com/pub/dir?firstName=${e(first)}&lastName=${e(last)}"),
        )
        return Finding(
            "PEOPLE-finders", ModuleId.PEOPLE, "People-finder sites",
            "Commercial sites that aggregate public records. Recon only opens them in your browser. Their data is often outdated or about someone else with the same name, can include home addresses and phone numbers, and some charge for the full report.",
            Severity.INFO, confidence = Confidence.LOW, links = links,
        )
    }

    /** Official and public portals with the name filled in where the portal supports it. (Commercial people-finder sites are in [finders].) */
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
            "Open these to search by name yourself. They are official or public sources; Recon doesn't query them. State court, property and voter records are searched on each state's or county's own site.",
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
        launch { source("npi", "NPI healthcare providers") { npi(parts.first(), parts.last()) } }
        launch { source("openalex", "OpenAlex publications") { openAlex(name) } }
        // Your own free api.data.gov key lifts the FEC limit from 40/hour (the shared demo key) to 1,000/hour.
        val fecKey = keys.get(ApiKeyId.FEC) ?: "DEMO_KEY"
        launch { source("fec", "FEC donations") { fec(name, fecKey) } }
        send(PeopleParsers.links(name))
        send(PeopleParsers.finders(name))
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
            if (r.code == 429) throw HttpException(429, "Rate-limited; try again later (for FEC, add your own free key in System)")
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
        val hits = PeopleParsers.orcid(get(url, "Accept" to "application/json"))
        // Read each profile's employment and publications now, so the match is useful without leaving the app. A failed detail request just leaves the basics.
        return coroutineScope {
            hits.mapIndexed { i, h ->
                async {
                    if (i >= 3) return@async h
                    val id = h.url!!.substringAfterLast('/')
                    val emp = runCatching { get("${endpoints.orcid}/v3.0/$id/employments".toHttpUrl(), "Accept" to "application/json") }.getOrNull()
                    val works = runCatching { get("${endpoints.orcid}/v3.0/$id/works".toHttpUrl(), "Accept" to "application/json") }.getOrNull()
                    val (facts, current) = PeopleParsers.orcidDetail(emp, works)
                    if (facts.isEmpty()) h else h.copy(detail = (current?.let { "$it\n" } ?: "") + h.detail, facts = facts)
                }
            }.map { it.await() }
        }
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

    private suspend fun npi(first: String, last: String): List<Finding> {
        if (first == last) return emptyList()
        val url = "${endpoints.npi}/api/".toHttpUrl().newBuilder().addQueryParameter("version", "2.1").addQueryParameter("first_name", first).addQueryParameter("last_name", last).addQueryParameter("limit", "5").build()
        return PeopleParsers.npi(get(url))
    }

    private suspend fun openAlex(name: String): List<Finding> {
        val url = "${endpoints.openAlex}/authors".toHttpUrl().newBuilder().addQueryParameter("search", name).addQueryParameter("per-page", "5").build()
        return PeopleParsers.openAlex(get(url, "Accept" to "application/json"))
    }
}
