package vip.ringling.recon.data.sources.network

import java.net.InetAddress
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import vip.ringling.recon.data.net.Endpoints
import vip.ringling.recon.data.net.Http
import vip.ringling.recon.data.net.HttpException
import vip.ringling.recon.data.sources.domain.DnsType
import vip.ringling.recon.data.sources.domain.DohClient
import vip.ringling.recon.data.sources.domain.DomainExtras
import vip.ringling.recon.domain.Confidence
import vip.ringling.recon.domain.Fact
import vip.ringling.recon.domain.Finding
import vip.ringling.recon.domain.GeoPoint
import vip.ringling.recon.domain.InputType
import vip.ringling.recon.domain.Link
import vip.ringling.recon.domain.ModuleId
import vip.ringling.recon.domain.OsintSource
import vip.ringling.recon.domain.Pivot
import vip.ringling.recon.domain.ScanInput
import vip.ringling.recon.domain.Severity

/** IP intelligence from public databases only: RDAP, reverse DNS, ipinfo (approximate geo/ASN), Shodan InternetDB, Tor exit list. Nothing is sent to the IP itself. */
class IpSource @Inject constructor(private val http: Http, private val endpoints: Endpoints) : OsintSource {
    override val id = ModuleId.NETWORK
    override val supportedInputTypes = setOf(InputType.IP)
    private val doh = DohClient(http, endpoints.doh)

    override suspend fun run(input: ScanInput): Flow<Finding> = channelFlow {
        val ip = input.raw
        IpAnalysis.reservedReason(ip)?.let {
            send(Finding("NETWORK-reserved", ModuleId.NETWORK, "Non-public address", it, Severity.INFO))
            return@channelFlow
        }
        fun check(key: String, title: String, block: suspend () -> Finding?) = launch {
            try { block()?.let { send(it) } } catch (e: CancellationException) { throw e } catch (e: Exception) {
                send(Finding("NETWORK-$key-error", ModuleId.NETWORK, "$title unavailable", e.message ?: e.javaClass.simpleName, Severity.INFO))
            }
        }
        suspend fun get(url: String, accept: String? = null): String = http.execute(Request.Builder().url(url).apply { accept?.let { header("Accept", it) } }.build()) { r ->
            if (r.code == 404) "" else if (r.code != 200) throw HttpException(r.code, "HTTP ${r.code}") else r.body.string()
        }
        check("rdap", "RDAP") { IpAnalysis.rdap(ip, get("${endpoints.rdap}/ip/$ip", "application/rdap+json")) }
        check("geo", "IP geolocation") { IpAnalysis.ipinfo(ip, get("${endpoints.ipinfo}/$ip/json")) }
        check("ptr", "Reverse DNS") { IpAnalysis.ptr(ip, doh.query(IpAnalysis.reverseName(ip), DnsType.PTR).map { it.data.trimEnd('.') }) }
        check("idb", "Shodan InternetDB") {
            val b = get("${endpoints.internetDb}/$ip")
            if (b.isBlank()) null else DomainExtras.internetDb(ip, b)
        }
        check("tor", "Tor exit list") { IpAnalysis.tor(ip, get(endpoints.torList)) }
        send(IpAnalysis.links(ip))
    }
}

object IpAnalysis {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun reservedReason(ip: String): String? {
        val a = runCatching { InetAddress.getByName(ip) }.getOrNull() ?: return "Not a valid IP address."
        return when {
            a.isAnyLocalAddress -> "Unspecified address."
            a.isLoopbackAddress -> "Loopback address: this device itself."
            a.isSiteLocalAddress -> "Private (RFC 1918) address: only meaningful inside a local network."
            a.isLinkLocalAddress -> "Link-local address."
            a.isMulticastAddress -> "Multicast address."
            ip.startsWith("100.") && (ip.split('.').getOrNull(1)?.toIntOrNull() in 64..127) -> "Carrier-grade NAT range (100.64.0.0/10): shared inside a provider's network."
            else -> null
        }
    }

    fun reverseName(ip: String): String {
        val a = InetAddress.getByName(ip).address
        return if (a.size == 4) a.reversed().joinToString(".") { (it.toInt() and 0xFF).toString() } + ".in-addr.arpa"
        else a.reversed().joinToString(".") { "%x.%x".format(it.toInt() and 0x0F, (it.toInt() shr 4) and 0x0F) } + ".ip6.arpa"
    }

    private fun registrableDomain(host: String): String? {
        val parts = host.trim('.').split('.')
        if (parts.size < 2) return null
        val twoLevel = setOf("co", "com", "org", "net", "gov", "ac").contains(parts[parts.size - 2]) && parts.last().length == 2
        return parts.takeLast(if (twoLevel && parts.size >= 3) 3 else 2).joinToString(".")
    }

    fun ptr(ip: String, names: List<String>): Finding = if (names.isEmpty()) {
        Finding("NETWORK-ptr", ModuleId.NETWORK, "No reverse DNS", "$ip has no PTR record.", Severity.INFO)
    } else Finding(
        "NETWORK-ptr", ModuleId.NETWORK, "Reverse DNS · ${names.first()}", names.joinToString("\n"), Severity.INFO, confidence = Confidence.HIGH,
        pivots = names.mapNotNull { registrableDomain(it) }.distinct().map { Pivot("Domain intel $it", it) },
    )

    fun ipinfo(ip: String, body: String): Finding? {
        if (body.isBlank()) return null
        val o = json.parseToJsonElement(body).jsonObject
        fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val loc = s("loc")?.split(',')?.mapNotNull { it.toDoubleOrNull() }?.takeIf { it.size == 2 }
        val place = listOfNotNull(s("city"), s("region"), s("country")).joinToString(", ")
        val org = s("org")
        return Finding(
            "NETWORK-geo", ModuleId.NETWORK, if (place.isNotEmpty()) "Located near $place" else "IP details",
            (if (org != null) "$org. " else "") + "Geolocation is approximate (city-level at best) and often reflects the provider, not the user.",
            Severity.INFO, confidence = Confidence.LOW, geo = loc?.let { GeoPoint(it[0], it[1]) },
            facts = listOfNotNull(
                org?.let { Fact("Network / ASN", it) }, s("hostname")?.let { Fact("Hostname", it) },
                place.takeIf { it.isNotEmpty() }?.let { Fact("Approximate location", it) }, s("postal")?.let { Fact("Postal code", it) }, s("timezone")?.let { Fact("Time zone", it) },
                if (o["anycast"]?.jsonPrimitive?.contentOrNull == "true") Fact("Anycast", "Yes: the same IP is served from many places") else null,
            ),
            pivots = s("hostname")?.let { h -> registrableDomain(h)?.let { listOf(Pivot("Domain intel $it", it)) } }.orEmpty(),
        )
    }

    fun rdap(ip: String, body: String): Finding? {
        if (body.isBlank()) return null
        val o = json.parseToJsonElement(body).jsonObject
        fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull
        val entities = o["entities"]?.jsonArray?.mapNotNull { it as? JsonObject }.orEmpty()
        fun vcard(e: JsonObject, key: String) = e["vcardArray"]?.jsonArray?.getOrNull(1)?.jsonArray
            ?.mapNotNull { it as? JsonArray }?.firstOrNull { it.getOrNull(0)?.jsonPrimitive?.contentOrNull == key }?.lastOrNull()?.let { v ->
                (v as? JsonArray)?.joinToString(" ") { it.jsonPrimitive.contentOrNull.orEmpty() }?.trim() ?: v.jsonPrimitive.contentOrNull
            }
        fun roles(e: JsonObject) = e["roles"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
        val flat = entities.flatMap { e -> listOf(e) + (e["entities"]?.jsonArray?.mapNotNull { it as? JsonObject }.orEmpty()) }
        val registrant = flat.firstOrNull { "registrant" in roles(it) } ?: flat.firstOrNull()
        val abuse = flat.firstOrNull { "abuse" in roles(it) }
        val abuseEmail = abuse?.let { vcard(it, "email") }
        val events = o["events"]?.jsonArray?.mapNotNull { it as? JsonObject }.orEmpty().mapNotNull { e ->
            val a = e["eventAction"]?.jsonPrimitive?.contentOrNull; val d = e["eventDate"]?.jsonPrimitive?.contentOrNull
            if (a != null && d != null) Fact(a.replaceFirstChar { it.uppercase() }, d.take(10)) else null
        }
        val cidr = o["cidr0_cidrs"]?.jsonArray?.mapNotNull { c -> (c as? JsonObject)?.let { (it["v4prefix"] ?: it["v6prefix"])?.jsonPrimitive?.contentOrNull + "/" + it["length"]?.jsonPrimitive?.contentOrNull } }
        val org = registrant?.let { vcard(it, "fn") }
        return Finding(
            "NETWORK-rdap", ModuleId.NETWORK, "Registered to ${org ?: s("name") ?: "unknown"}",
            "Network ${s("name") ?: ""} (${s("startAddress")} – ${s("endAddress")}). This is the organisation holding the address block, not necessarily the person using the IP.",
            Severity.INFO, confidence = Confidence.HIGH, url = "https://rdap.org/ip/$ip",
            facts = buildList {
                org?.let { add(Fact("Organisation", it)) }
                registrant?.let { vcard(it, "adr") }?.takeIf { it.isNotBlank() }?.let { add(Fact("Address", it)) }
                s("name")?.let { add(Fact("Network name", it)) }; s("handle")?.let { add(Fact("Handle", it)) }; s("type")?.let { add(Fact("Allocation", it)) }
                cidr?.takeIf { it.isNotEmpty() }?.let { add(Fact("CIDR", it.joinToString(", "))) }
                s("country")?.let { add(Fact("Country", it)) }
                abuseEmail?.let { add(Fact("Abuse contact", it)) }
                addAll(events)
            },
            links = listOf(Link("ARIN/RIPE/APNIC record", "https://rdap.org/ip/$ip")),
        )
    }

    fun tor(ip: String, list: String): Finding? {
        val isExit = list.lineSequence().any { it.trim() == ip }
        return if (isExit) Finding("NETWORK-tor", ModuleId.NETWORK, "Tor exit node", "This address is on the Tor Project's public exit list: traffic from it is anonymised and not tied to one person.", Severity.EXPOSED, confidence = Confidence.HIGH, url = "https://check.torproject.org/torbulkexitlist", tags = listOf("Tor"))
        else Finding("NETWORK-tor", ModuleId.NETWORK, "Not a Tor exit node", "Not on the Tor Project's current exit list.", Severity.CLEAN, confidence = Confidence.HIGH)
    }

    fun links(ip: String) = Finding(
        "NETWORK-links", ModuleId.NETWORK, "Reputation & scan lookups", "Open these for abuse reports, passive scans and history.", Severity.INFO,
        links = listOf(
            Link("AbuseIPDB", "https://www.abuseipdb.com/check/$ip"), Link("GreyNoise", "https://viz.greynoise.io/ip/$ip"),
            Link("VirusTotal", "https://www.virustotal.com/gui/ip-address/$ip"), Link("Shodan", "https://www.shodan.io/host/$ip"),
            Link("Censys", "https://search.censys.io/hosts/$ip"), Link("Cisco Talos", "https://talosintelligence.com/reputation_center/lookup?search=$ip"),
            Link("urlscan.io", "https://urlscan.io/ip/$ip"), Link("BGP.he.net", "https://bgp.he.net/ip/$ip"),
        ),
    )
}
