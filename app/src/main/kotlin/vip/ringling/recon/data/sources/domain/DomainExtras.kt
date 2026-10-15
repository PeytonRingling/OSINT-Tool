package vip.ringling.recon.data.sources.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import vip.ringling.recon.domain.Confidence
import vip.ringling.recon.domain.Fact
import vip.ringling.recon.domain.Finding
import vip.ringling.recon.domain.Link
import vip.ringling.recon.domain.ModuleId
import vip.ringling.recon.domain.Pivot
import vip.ringling.recon.domain.Severity

/** Wayback Machine availability and Shodan InternetDB lookups: both are third-party databases, we send nothing to the target. */
object DomainExtras {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class Snapshot(val timestamp: String, val url: String) {
        val date get() = "${timestamp.take(4)}-${timestamp.drop(4).take(2)}-${timestamp.drop(6).take(2)}"
    }

    /** Parses archive.org/wayback/available; null when nothing is archived. */
    fun snapshot(body: String): Snapshot? = runCatching {
        val c = json.parseToJsonElement(body).jsonObject["archived_snapshots"]?.jsonObject?.get("closest")?.jsonObject ?: return null
        if (c["available"]?.jsonPrimitive?.contentOrNull != "true") return null
        val ts = c["timestamp"]?.jsonPrimitive?.contentOrNull ?: return null
        val url = c["url"]?.jsonPrimitive?.contentOrNull ?: return null
        Snapshot(ts, url.replace("http://", "https://"))
    }.getOrNull()

    fun wayback(domain: String, first: Snapshot?, latest: Snapshot?): Finding {
        val calendar = Link("Wayback calendar", "https://web.archive.org/web/*/$domain")
        if (first == null && latest == null) {
            return Finding("DOMAIN-wayback", ModuleId.DOMAIN, "No Wayback snapshots", "The Internet Archive holds no capture of $domain.", Severity.INFO, links = listOf(calendar))
        }
        val facts = buildList {
            first?.let { add(Fact("Earliest capture", "${it.date}\n${it.url}")) }
            latest?.let { add(Fact("Latest capture", "${it.date}\n${it.url}")) }
        }
        return Finding(
            "DOMAIN-wayback", ModuleId.DOMAIN, "Wayback Machine · first seen ${(first ?: latest)!!.date}",
            "Archived history can reveal old pages, owners, contact details and removed content.",
            Severity.INFO, url = (latest ?: first)!!.url, confidence = Confidence.HIGH, facts = facts,
            links = listOfNotNull(first?.let { Link("Earliest snapshot", it.url) }, latest?.let { Link("Latest snapshot", it.url) }, calendar),
        )
    }

    /** urlscan.io public scan history for a domain: how often it was scanned, from where it was hosted, what it was titled. */
    fun urlscan(domain: String, body: String): Finding? = runCatching {
        val root = json.parseToJsonElement(body).jsonObject
        val total = root["total"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        val results = root["results"]?.jsonArray?.mapNotNull { it as? JsonObject }.orEmpty()
        if (results.isEmpty()) return null
        fun page(r: JsonObject, k: String) = r["page"]?.jsonObject?.get(k)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val ips = results.mapNotNull { page(it, "ip") }.distinct(); val countries = results.mapNotNull { page(it, "country") }.distinct()
        val asns = results.mapNotNull { page(it, "asnname") }.distinct(); val titles = results.mapNotNull { page(it, "title") }.distinct()
        val times = results.mapNotNull { it["task"]?.jsonObject?.get("time")?.jsonPrimitive?.contentOrNull?.take(10) }.sorted()
        Finding(
            "DOMAIN-urlscan", ModuleId.DOMAIN, "urlscan.io · $total public scan${if (total == 1) "" else "s"}",
            "Other people have scanned this domain; the scans show where it was hosted and what it served.", Severity.INFO, confidence = Confidence.MEDIUM,
            url = "https://urlscan.io/search/#domain:$domain",
            facts = listOfNotNull(
                times.lastOrNull()?.let { Fact("Most recent scan", it) }, ips.takeIf { it.isNotEmpty() }?.let { Fact("IPs seen", it.joinToString("\n")) },
                asns.takeIf { it.isNotEmpty() }?.let { Fact("Hosting networks", it.joinToString("\n")) }, countries.takeIf { it.isNotEmpty() }?.let { Fact("Server countries", it.joinToString(", ")) },
                titles.takeIf { it.isNotEmpty() }?.let { Fact("Page titles", it.take(5).joinToString("\n")) },
            ),
            pivots = ips.take(4).map { Pivot("IP intel $it", it) },
            links = listOf(Link("urlscan.io results", "https://urlscan.io/search/#domain:$domain")) + results.take(3).mapNotNull { r -> r["result"]?.jsonPrimitive?.contentOrNull?.let { Link("Scan ${r["task"]?.jsonObject?.get("time")?.jsonPrimitive?.contentOrNull?.take(10).orEmpty()}", it.replace("/api/v1/result/", "/result/")) } },
        )
    }.getOrNull()

    /** Shodan InternetDB entry for one IP. Returns null when the IP is unknown to the index. */
    fun internetDb(ip: String, body: String): Finding? = runCatching {
        val o: JsonObject = json.parseToJsonElement(body).jsonObject
        if (o["detail"] != null) return null
        fun list(k: String) = o[k]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
        val ports = list("ports"); val vulns = list("vulns"); val hosts = list("hostnames"); val tags = list("tags"); val cpes = list("cpes")
        val facts = buildList {
            if (ports.isNotEmpty()) add(Fact("Open ports (per Shodan)", ports.joinToString(", ")))
            if (vulns.isNotEmpty()) add(Fact("Known CVEs", vulns.joinToString(", ")))
            if (hosts.isNotEmpty()) add(Fact("Hostnames", hosts.joinToString("\n")))
            if (cpes.isNotEmpty()) add(Fact("Detected software", cpes.joinToString("\n")))
        }
        Finding(
            "DOMAIN-idb-$ip", ModuleId.DOMAIN, "Shodan InternetDB · $ip",
            if (vulns.isEmpty()) "${ports.size} port${if (ports.size == 1) "" else "s"} indexed, no known CVEs." else "${vulns.size} known CVE${if (vulns.size == 1) "" else "s"} on this host.",
            if (vulns.isNotEmpty()) Severity.EXPOSED else Severity.INFO, url = "https://www.shodan.io/host/$ip", confidence = Confidence.MEDIUM,
            tags = tags, facts = facts,
            pivots = hosts.take(6).map { Pivot("Domain intel $it", it) },
            links = listOf(Link("Shodan host page", "https://www.shodan.io/host/$ip")),
        )
    }.getOrNull()
}
