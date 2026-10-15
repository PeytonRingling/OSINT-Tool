package vip.ringling.recon.data.sources.registry

import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.jsoup.Jsoup
import vip.ringling.recon.data.net.Endpoints
import vip.ringling.recon.data.net.Http
import vip.ringling.recon.data.net.HttpException
import vip.ringling.recon.data.sources.identity.RateLimiter
import vip.ringling.recon.domain.GeoPoint
import vip.ringling.recon.domain.Severity

/** Parsed search-result row. [href], [photoHref], [mapHref] are relative to the /sor_id/ page that produced them. */
data class IdahoRow(
    val id: String, val href: String, val name: String, val kno: String, val address: String,
    val city: String, val county: String, val zip: String, val status: String,
    val photoHref: String? = null, val mapHref: String? = null,
)
data class IdahoPage(val totalMatches: Int, val pages: Int, val rows: List<IdahoRow>)
data class IdahoOffense(val statute: String, val description: String, val date: String, val place: String)
data class IdahoDetail(val regStatus: String?, val lastRegistered: String?, val offenses: List<IdahoOffense>, val photoHref: String? = null)

/**
 * apps.isp.idaho.gov/sor_id — server-rendered HTML. Confirmed against the live site:
 *  - ZIP search: POST SOR  form=5 & zip=NNNNN & rad=A|J & sz=400   (form=3 cty=…, form=4 cnt=… for city/county)
 *  - results: 15 rows/page, "N matches found in M pages", cells td#nam_field/kno_field/adr_field/cty_field×2/zip_field/stat_field,
 *    a thumbnail at td#off_img img and a map link at td#map_field a
 *  - detail: GET SOR?id=N&sz=400 (labels td#sor_detail_lead, offenses table, full photo img)
 *  - map page: jsp/sor_map_display.jsp?… embeds the registry's own geocoded coordinates as google.maps.LatLng(lat, lng);
 *    we read those numbers only, so no address is ever sent to a geocoder.
 * Both adult (rad=A) and juvenile (rad=J) databases are queried for the query the user typed; every results page of THAT query is
 * followed (pager links, `&page=N`), bounded by maxPagesPerDatabase/maxEnriched. Nothing is cached or stored. Date of birth, physical description and aliases are deliberately not read.
 */
class IdahoRegistryAdapter @Inject constructor(
    private val http: Http,
    private val endpoints: Endpoints,
) : RegistryAdapter {
    override val stateCode = "ID"
    override val stateName = "Idaho"
    override fun handlesZip(zip: String) = zip.take(3).toIntOrNull() in 832..838

    private val limiter = RateLimiter(maxConcurrent = 4, minIntervalMs = 100)
    private val base: HttpUrl get() = "${endpoints.idahoSor}/".toHttpUrl()

    /** Bounds for very large searches (e.g. a whole county). A typical ZIP is well under both. */
    internal var maxPagesPerDatabase = 20
    internal var maxEnriched = 200

    private class Listing(val label: String, val rows: List<IdahoRow>, val total: Int, val pagesMissed: Int, val pageCapped: Boolean)

    override fun search(query: RegistryQuery): Flow<RegistryEvent> = channelFlow {
        // 1) First page of the adult and juvenile databases, concurrently.
        val first = coroutineScope {
            listOf("A" to "Adult", "J" to "Juvenile").map { (code, label) ->
                async { Triple(code, label, runCatching { postSearch(query, code) }) }
            }.awaitAll()
        }
        val ok = first.filter { it.third.isSuccess }
        if (ok.isEmpty()) throw first.first().third.exceptionOrNull() ?: AdapterUnavailable("Idaho registry unavailable")
        val notes = first.filter { it.third.isFailure }.map { (_, label, r) -> "$label registry unavailable: ${r.exceptionOrNull()?.message}" }.toMutableList()

        // 2) The remaining pages of each database — only the pages of this very query, up to the cap.
        val listings = coroutineScope {
            ok.map { (_, label, r) ->
                async {
                    val (page1, template) = r.getOrThrow()
                    val rows = page1.rows.toMutableList()
                    var missed = 0
                    val lastPage = minOf(page1.pages, maxPagesPerDatabase)
                    if (template != null) for (n in 2..lastPage) {
                        val page = optional { limiter.run { fetch(template.replace("page=2", "page=$n")) { parseResults(it) } } }
                        if (page == null) missed++ else rows += page.rows
                    }
                    Listing(label, rows.distinctBy { it.id }, page1.totalMatches, missed, page1.pages > maxPagesPerDatabase)
                }
            }.awaitAll()
        }
        val total = listings.sumOf { it.total }
        val rows = listings.flatMap { l -> l.rows.map { l.label to it } }
        listings.filter { it.pagesMissed > 0 }.forEach { notes += "${it.label} registry: ${it.pagesMissed} results page(s) could not be loaded." }
        send(RegistryEvent.Header(total, rows.size, capped = rows.size < total, searchUrl = "${endpoints.idahoSor}/search_zip.html", notes = notes))

        // 3) Details and coordinates, streamed as each record completes. Beyond the cap a row is shown without them.
        coroutineScope {
            rows.forEachIndexed { i, (label, row) ->
                launch {
                    val enrich = i < maxEnriched
                    val detail = if (enrich) optional { limiter.run { fetch(row.href) { parseDetail(it) } } } else null
                    val geo = if (enrich) row.mapHref?.let { href -> optional { limiter.run { fetch(href) { parseGeo(it) } } } } else null
                    send(RegistryEvent.Entry(toEntry(row, detail, geo, label)))
                }
            }
        }
    }

    /** First page of a database, plus the pager link template ("…&page=2") used to reach the rest. */
    private suspend fun postSearch(query: RegistryQuery, rad: String): Pair<IdahoPage, String?> {
        val form = FormBody.Builder().add("sz", "400").add("rad", rad).apply {
            when {
                query.zip != null -> { add("form", "5"); add("zip", query.zip) }
                query.city != null -> { add("form", "3"); add("cty", query.city.uppercase()) }
                query.county != null -> { add("form", "4"); add("cnt", query.county.uppercase()) }
                else -> throw IllegalArgumentException("zip, city or county required")
            }
        }.build()
        val req = Request.Builder().url("${endpoints.idahoSor}/SOR").post(form).build()
        val html = http.execute(req) { r ->
            if (r.code != 200) throw HttpException(r.code, "Idaho registry returned HTTP ${r.code}")
            r.body.string()
        }
        return parseResults(html) to parsePagerTemplate(html)
    }

    private suspend fun <T> optional(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** [href] comes from the results page (relative to /sor_id/), so adult and juvenile pages both resolve. */
    private suspend fun <T> fetch(href: String, parse: (String) -> T): T {
        val url = base.resolve(href) ?: throw HttpException(0, "bad registry link")
        val html = http.execute(Request.Builder().url(url).build()) { r ->
            if (r.code != 200) throw HttpException(r.code, "Idaho registry returned HTTP ${r.code}")
            r.body.string()
        }
        return parse(html)
    }

    internal fun toEntry(row: IdahoRow, d: IdahoDetail?, geo: GeoPoint?, label: String): RegistryEntry {
        val status = (d?.regStatus ?: row.status).takeIf { it.isNotBlank() }
        val nonCompliant = status?.contains("NON", ignoreCase = true) == true
        val dates = d?.offenses?.map { it.date }?.filter { it.isNotBlank() }?.distinct().orEmpty()
        val multiple = dates.size >= 2
        val tags = buildList {
            add("$label registry")
            if (nonCompliant) add("Non-compliant")
            if (multiple) add("Multiple convictions")
        }
        val place = listOf(row.address, row.city, row.county.takeIf { it.isNotBlank() }?.let { "$it County" }, row.zip).filter { !it.isNullOrBlank() }.joinToString(", ")
        val lines = buildList {
            add(place)
            add(listOfNotNull(status?.let { "Status: $it" }, d?.lastRegistered?.takeIf { it.isNotBlank() }?.let { "Last registered: $it" }).joinToString(" · "))
            when {
                d == null -> add("Offense details unavailable (open the official record).")
                d.offenses.isEmpty() -> add("No offenses listed.")
                else -> {
                    add("Offenses (${d.offenses.size}):")
                    d.offenses.forEach { o -> add("• " + listOf(o.statute, o.description, o.date, o.place).filter { it.isNotBlank() }.joinToString(" – ")) }
                    if (multiple) add("Convictions on ${dates.size} separate dates. The registry does not state whether this is a repeat offence; this is inferred from the dates.")
                }
            }
        }.filter { it.isNotBlank() }
        fun abs(href: String?) = href?.let { base.resolve(it)?.toString() }
        return RegistryEntry(
            key = "${label.first()}-${row.id}",
            name = row.name,
            summary = lines.joinToString("\n"),
            sourceUrl = abs(row.href) ?: "${endpoints.idahoSor}/",
            // Registry records are never risk-scored or labelled "Exposed": status/convictions are shown as tags instead.
            severity = Severity.INFO,
            geo = geo,
            imageUrl = abs(row.photoHref),
            imageLargeUrl = abs(d?.photoHref ?: row.photoHref),
            tags = tags,
            hotTags = setOf("Non-compliant", "Multiple convictions"),
        )
    }

    companion object {
        // Seen live: "60 matches found in 4 pages.", "4 matches found.", "1 match.", "0 matches found".
        private val matchRe = Regex("""(\d+)\s+match(?:es)?(?:\s+found)?(?:\s+in\s+(\d+)\s+pages?)?""", RegexOption.IGNORE_CASE)
        private val latLngRe = Regex("""google\.maps\.LatLng\(\s*(-?\d+\.\d+)\s*,\s*(-?\d+\.\d+)\s*\)""")

        private fun clean(s: String?) = s.orEmpty().replace(' ', ' ').replace(Regex("\\s+"), " ").trim()

        /** The pager's "page=2" link, used as a template for the other pages of the same search. */
        fun parsePagerTemplate(html: String): String? =
            Jsoup.parse(html).select("a[href*=page=]").map { it.attr("href") }.firstOrNull { Regex("""[?&]page=2\b""").containsMatchIn(it) }

        fun parseResults(html: String): IdahoPage {
            val doc = Jsoup.parse(html)
            val m = matchRe.find(clean(doc.body().text()))
            // Use each name cell's own row; tr:has(...) would also match the outer layout rows wrapping the data table.
            val rows = doc.select("td[id=nam_field]").mapNotNull { it.parent() }.map { tr ->
                val link = tr.selectFirst("td[id=nam_field] a")
                val cells = tr.select("td[id=cty_field]")
                IdahoRow(
                    id = Regex("""[?&]id=(\d+)""").find(link?.attr("href").orEmpty())?.groupValues?.get(1).orEmpty(),
                    href = link?.attr("href").orEmpty(),
                    name = clean(link?.text() ?: tr.selectFirst("td[id=nam_field]")?.text()),
                    kno = clean(tr.selectFirst("td[id=kno_field]")?.text()),
                    address = clean(tr.selectFirst("td[id=adr_field]")?.text()),
                    city = clean(cells.getOrNull(0)?.text()),
                    county = clean(cells.getOrNull(1)?.text()),
                    zip = clean(tr.selectFirst("td[id=zip_field]")?.text()),
                    status = clean(tr.selectFirst("td[id=stat_field]")?.text()),
                    photoHref = tr.selectFirst("td[id=off_img] img")?.attr("src")?.takeIf { it.contains("photos/") },
                    mapHref = tr.selectFirst("td[id=map_field] a")?.attr("href")?.takeIf { it.isNotBlank() },
                )
            }.filter { it.id.isNotEmpty() }
            return IdahoPage(m?.groupValues?.get(1)?.toInt() ?: rows.size, m?.groupValues?.get(2)?.takeIf { it.isNotEmpty() }?.toInt() ?: 1, rows)
        }

        /** The marker's LatLng (else the first one) on the registry's map page is the address. Out-of-range values are rejected. */
        private val markerRe = Regex("""Marker\(\s*\{[^}]*?google\.maps\.LatLng\(\s*(-?\d+\.\d+)\s*,\s*(-?\d+\.\d+)\s*\)""")

        fun parseGeo(html: String): GeoPoint? = (markerRe.find(html) ?: latLngRe.find(html))?.let { m ->
            val lat = m.groupValues[1].toDouble(); val lng = m.groupValues[2].toDouble()
            if (lat in -90.0..90.0 && lng in -180.0..180.0 && !(lat == 0.0 && lng == 0.0)) GeoPoint(lat, lng) else null
        }

        fun parseDetail(html: String): IdahoDetail {
            val doc = Jsoup.parse(html)
            // "Reg Status:" / "Last Registered:" are th labels whose value sits in the next cell.
            fun sideValue(label: String): String? = doc.select("th.sor_info_th").firstOrNull { clean(it.text()).startsWith(label) }
                ?.parent()?.selectFirst("td")?.text()?.let(::clean)?.takeIf { it.isNotBlank() }
            val offenseTable = doc.select("th").firstOrNull { clean(it.text()) == "Offenses Requiring Registration" }?.closest("table")
            val offenses = offenseTable?.select("tr")?.filter { tr -> tr.children().size == 4 && tr.children().all { it.tagName() == "td" } }
                ?.map { tr ->
                    val c = tr.children().map { clean(it.text()) }
                    IdahoOffense(c[0], c[1], c[2], c[3])
                }.orEmpty()
            val photo = doc.select("img[src]").map { it.attr("src") }.firstOrNull { it.contains("photos/") && !it.contains("thumbs/") }
            return IdahoDetail(sideValue("Reg Status"), sideValue("Last Registered"), offenses, photo)
        }
    }
}
