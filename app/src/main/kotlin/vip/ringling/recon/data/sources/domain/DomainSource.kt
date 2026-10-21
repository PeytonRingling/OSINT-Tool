package vip.ringling.recon.data.sources.domain

import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import okhttp3.Request
import vip.ringling.recon.data.net.Endpoints
import vip.ringling.recon.data.net.Http
import vip.ringling.recon.data.net.HttpException
import vip.ringling.recon.domain.Finding
import vip.ringling.recon.domain.InputType
import vip.ringling.recon.domain.ModuleId
import vip.ringling.recon.domain.OsintSource
import vip.ringling.recon.domain.ScanInput
import vip.ringling.recon.domain.Severity

/** Passive domain recon: DNS-over-HTTPS, mail-auth records, certificate transparency, RDAP. No port scanning. */
class DomainSource @Inject constructor(
    private val http: Http,
    private val endpoints: Endpoints,
    private val keys: vip.ringling.recon.data.secret.ApiKeys,
) : OsintSource {
    override val id = ModuleId.DOMAIN
    override val supportedInputTypes = setOf(InputType.DOMAIN)

    private val doh = DohClient(http, endpoints.doh)

    override suspend fun run(input: ScanInput): Flow<Finding> = channelFlow {
        val d = input.raw.lowercase()
        send(LookalikeLookup.offer(d))
        check("dns", "DNS records") {
            coroutineScope {
                val a = async { doh.query(d, DnsType.A) }
                val aaaa = async { doh.query(d, DnsType.AAAA) }
                send(DomainAnalysis.addresses(a.await(), aaaa.await()))
            }
        }
        check("wayback", "Wayback Machine") {
            suspend fun snap(ts: String?): DomainExtras.Snapshot? {
                val q = if (ts == null) "" else "&timestamp=$ts"
                val req = Request.Builder().url("${endpoints.archive}/wayback/available?url=$d$q").build()
                val body = http.execute(req) { r -> if (r.code != 200) throw HttpException(r.code, "Wayback returned HTTP ${r.code}") else r.body.string() }
                return DomainExtras.snapshot(body)
            }
            coroutineScope {
                val first = async { snap("19960101") }
                val latest = async { snap(null) }
                send(DomainExtras.wayback(d, first.await(), latest.await()))
            }
        }
        check("urlscan", "urlscan.io") {
            val req = Request.Builder().url("${endpoints.urlscan}/api/v1/search/?q=domain:$d&size=20").build()
            val body = http.execute(req) { r -> if (r.code != 200) throw HttpException(r.code, "urlscan.io returned HTTP ${r.code}") else r.body.string() }
            DomainExtras.urlscan(d, body)?.let { send(it) }
        }
        check("idb", "Shodan InternetDB") {
            val ips = doh.query(d, DnsType.A).map { it.data }.filter { Regex("""\d+\.\d+\.\d+\.\d+""").matches(it) }.distinct().take(3)
            coroutineScope {
                ips.map { ip -> async {
                    val req = Request.Builder().url("${endpoints.internetDb}/$ip").build()
                    val body = http.execute(req) { r -> if (r.code == 404) "{\"detail\":\"none\"}" else if (r.code != 200) throw HttpException(r.code, "InternetDB returned HTTP ${r.code}") else r.body.string() }
                    DomainExtras.internetDb(ip, body)
                } }.awaitAll().filterNotNull().forEach { send(it) }
            }
        }
        // With a VirusTotal key: what the security engines say about the domain (only the domain is sent).
        if (keys.get(vip.ringling.recon.domain.ApiKeyId.VIRUSTOTAL) != null) check("vt", "VirusTotal") {
            val r = vip.ringling.recon.data.lab.VirusTotal(http, keys, endpoints).domain(d)
            if (r is vip.ringling.recon.data.lab.VtResult.Failed) throw HttpException(0, r.message)
            vip.ringling.recon.data.lab.VirusTotal.flags(r, "domain").firstOrNull()?.let { f ->
                send(Finding("DOMAIN-vt", ModuleId.DOMAIN, "VirusTotal", f.text, f.severity, vip.ringling.recon.data.lab.VirusTotal.domainPage(d)))
            }
        }
        check("mx", "MX") { send(DomainAnalysis.mx(doh.query(d, DnsType.MX))) }
        check("ns", "NS") { send(DomainAnalysis.ns(doh.query(d, DnsType.NS))) }
        check("spf", "SPF") { send(DomainAnalysis.spf(doh.txt(d))) }
        check("dmarc", "DMARC") { send(DomainAnalysis.dmarc(doh.txt("_dmarc.$d"))) }
        check("dkim", "DKIM") {
            // coroutineScope contains a failing lookup so it surfaces here, not as a cancellation of the whole scan.
            val hits = coroutineScope {
                DomainAnalysis.DKIM_SELECTORS.map { sel -> async { sel to doh.txt("$sel._domainkey.$d") } }.awaitAll()
            }.filter { (_, txt) -> txt.any(DomainAnalysis::isActiveDkim) }.map { it.first }
            send(DomainAnalysis.dkim(hits))
        }
        check("bimi", "BIMI") { send(DomainAnalysis.bimi(doh.txt("default._bimi.$d"))) }
        check("mtasts", "MTA-STS") { send(DomainAnalysis.mtaSts(doh.txt("_mta-sts.$d"))) }
        check("subs", "Subdomains (crt.sh)") {
            val req = Request.Builder().url("${endpoints.crtSh}/?q=%25.$d&output=json").build()
            val body = http.execute(req) { r ->
                if (r.code != 200) throw HttpException(r.code, "crt.sh returned HTTP ${r.code}")
                r.body.string()
            }
            send(DomainAnalysis.subdomains(DomainAnalysis.parseCrtNames(body.ifBlank { "[]" }, d), d))
        }
        check("rdap", "RDAP") {
            val req = Request.Builder().url("${endpoints.rdap}/domain/$d").header("Accept", "application/rdap+json").build()
            val body = http.execute(req) { r ->
                when (r.code) {
                    200 -> r.body.string()
                    404 -> throw HttpException(404, "No RDAP record (unsupported TLD or unregistered)")
                    else -> throw HttpException(r.code, "RDAP returned HTTP ${r.code}")
                }
            }
            send(DomainAnalysis.rdap(body, d))
        }
    }

    /** Runs one check concurrently; a failure becomes an info finding instead of failing the whole module. */
    private fun ProducerScope<Finding>.check(key: String, title: String, block: suspend ProducerScope<Finding>.() -> Unit) {
        launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                send(Finding("DOMAIN-$key-error", ModuleId.DOMAIN, "$title unavailable", e.message ?: e.javaClass.simpleName, Severity.INFO))
            }
        }
    }
}
