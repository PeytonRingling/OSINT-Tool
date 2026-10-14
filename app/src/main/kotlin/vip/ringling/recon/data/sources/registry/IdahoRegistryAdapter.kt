package vip.ringling.recon.data.sources.registry

import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.jsoup.Jsoup
import vip.ringling.recon.data.net.Endpoints
import vip.ringling.recon.data.net.Http
import vip.ringling.recon.data.net.HttpException
import vip.ringling.recon.data.sources.identity.RateLimiter

/** Parsed search-result row. */
data class IdahoRow(val id: String, val href: String, val name: String, val kno: String, val address: String, val city: String, val county: String, val zip: String, val status: String)
data class IdahoPage(val totalMatches: Int, val pages: Int, val rows: List<IdahoRow>)
data class IdahoOffense(val statute: String, val description: String, val date: String, val place: String)
data class IdahoDetail(val regStatus: String?, val lastRegistered: String?, val offenses: List<IdahoOffense>)

/**
 * apps.isp.idaho.gov/sor_id — server-rendered HTML. Confirmed against the live site:
 *  - ZIP search: POST SOR  form=5 & zip=NNNNN & rad=A & sz=400   (form=3 cty=…, form=4 cnt=… for city/county)
 *  - results: 15 rows per page, "N matches found in M pages", cells td#nam_field/kno_field/adr_field/cty_field×2/zip_field/stat_field
 *  - detail: GET SOR?id=N&sz=400, label cells td#sor_detail_lead, offenses table under "Offenses Requiring Registration"
 * Both the adult (rad=A) and juvenile (rad=J) databases are queried, first results page only, for the query the user typed.
 * Nothing is cached or stored.
 * Date of birth, physical description, aliases and photos are deliberately not read.
 */
class IdahoRegistryAdapter @Inject constructor(
    private val http: Http,
    private val endpoints: Endpoints,
) : RegistryAdapter {
    override val stateCode = "ID"
    override val stateName = "Idaho"
    override fun handlesZip(zip: String) = zip.take(3).toIntOrNull() in 832..838

    private val limiter = RateLimiter(maxConcurrent = 3, minIntervalMs = 150)

    override suspend fun search(query: RegistryQuery): RegistryResult = coroutineScope {
        // Adult ("A") and juvenile ("J") are separate databases on the same site; each gets one first-page query.
        val dbs = listOf("A" to "Adult", "J" to "Juvenile").map { (code, label) ->
            async { label to runCatching { searchDatabase(query, code, label) } }
        }.awaitAll()
        val ok = dbs.mapNotNull { (_, r) -> r.getOrNull() }
        if (ok.isEmpty()) throw dbs.first().second.exceptionOrNull() ?: AdapterUnavailable("Idaho registry unavailable")
        val notes = dbs.filter { it.second.isFailure }.map { (label, r) -> "$label registry unavailable: ${r.exceptionOrNull()?.message}" }
        RegistryResult(ok.sumOf { it.total }, ok.flatMap { it.entries }, "${endpoints.idahoSor}/search_zip.html", notes)
    }

    private class DbResult(val total: Int, val entries: List<RegistryEntry>)

    private suspend fun searchDatabase(query: RegistryQuery, rad: String, label: String): DbResult {
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
        val page = parseResults(html)
        val entries = coroutineScope {
            page.rows.map { row ->
                async {
                    val detail = try {
                        limiter.run { fetchDetail(row.href) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                    RegistryEntry(row.name, "$label registry · " + summarize(row, detail), "${endpoints.idahoSor}/${row.href}")
                }
            }.awaitAll()
        }
        return DbResult(page.totalMatches, entries)
    }

    /** [href] is the relative link taken from the results page, so adult and juvenile records both resolve. */
    private suspend fun fetchDetail(href: String): IdahoDetail {
        val html = http.execute(Request.Builder().url("${endpoints.idahoSor}/$href".toHttpUrl()).build()) { r ->
            if (r.code != 200) throw HttpException(r.code, "Idaho registry detail returned HTTP ${r.code}")
            r.body.string()
        }
        return parseDetail(html)
    }

    internal fun summarize(row: IdahoRow, d: IdahoDetail?): String = buildList {
        add(listOf(row.address, row.city, row.county.takeIf { it.isNotBlank() }?.let { "$it County" }, row.zip).filter { !it.isNullOrBlank() }.joinToString(", "))
        (d?.regStatus ?: row.status).takeIf { it.isNotBlank() }?.let { add("Status: $it") }
        d?.lastRegistered?.takeIf { it.isNotBlank() }?.let { add("Last registered: $it") }
        if (d == null) add("Offense details unavailable (open the official record).")
        else if (d.offenses.isNotEmpty()) add("Offenses: " + d.offenses.joinToString("; ") { o -> listOf(o.statute, o.description, o.date).filter { it.isNotBlank() }.joinToString(" – ") })
    }.joinToString(" · ")

    companion object {
        // Seen live: "60 matches found in 4 pages.", "4 matches found.", "1 match.", "0 matches found".
        private val matchRe = Regex("""(\d+)\s+match(?:es)?(?:\s+found)?(?:\s+in\s+(\d+)\s+pages?)?""", RegexOption.IGNORE_CASE)

        private fun clean(s: String?) = s.orEmpty().replace(' ', ' ').replace(Regex("\\s+"), " ").trim()

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
                )
            }.filter { it.id.isNotEmpty() }
            return IdahoPage(m?.groupValues?.get(1)?.toInt() ?: rows.size, m?.groupValues?.get(2)?.takeIf { it.isNotEmpty() }?.toInt() ?: 1, rows)
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
            return IdahoDetail(sideValue("Reg Status"), sideValue("Last Registered"), offenses)
        }
    }
}
