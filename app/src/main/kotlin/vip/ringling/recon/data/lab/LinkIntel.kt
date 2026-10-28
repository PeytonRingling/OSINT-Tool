package vip.ringling.recon.data.lab

import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import vip.ringling.recon.data.net.Endpoints
import vip.ringling.recon.data.net.Http
import vip.ringling.recon.data.net.HttpException
import vip.ringling.recon.data.sources.domain.DnsType
import vip.ringling.recon.data.sources.domain.DohClient
import vip.ringling.recon.domain.Flag
import vip.ringling.recon.domain.Severity

/**
 * Checks a link's DOMAIN without ever contacting the site: is it on Cloudflare's malware-blocking DNS list, does it resolve at all, and how
 * new is its registration (phishing domains are usually days old). Only DNS and RDAP lookups are made, so the site never hears from the user.
 */
@Singleton
class LinkIntel(private val http: Http, private val doh: DohClient, private val securityDoh: DohClient, private val rdapBase: String) {
    @Inject constructor(http: Http, endpoints: Endpoints) : this(http, DohClient(http, endpoints.doh), DohClient(http, endpoints.dohSecurity), endpoints.rdap)

    suspend fun check(host: String): List<Flag> {
        val h = host.lowercase().trim('.')
        val out = mutableListOf<Flag>()
        out += runCatching { dnsFlag(h) }.getOrElse { Flag(Severity.INFO, "Couldn't ask the DNS reputation list (${it.message ?: it.javaClass.simpleName}).") }
        out += runCatching { ageFlag(h) }.getOrElse { Flag(Severity.INFO, "Couldn't look up when this domain was registered.") }
        return out
    }

    private suspend fun dnsFlag(h: String): Flag {
        val normal = doh.query(h, DnsType.A).map { it.data }
        val secure = securityDoh.query(h, DnsType.A).map { it.data }
        return when {
            normal.isNotEmpty() && secure.isNotEmpty() && secure.all { it == "0.0.0.0" } -> Flag(Severity.EXPOSED, "Blocked as malicious by Cloudflare's security DNS (known malware or phishing).")
            normal.isEmpty() && secure.isEmpty() -> Flag(Severity.INFO, "This name doesn't resolve: it may not exist, or was taken down.")
            else -> Flag(Severity.CLEAN, "Not on Cloudflare's malware/phishing block list (that list isn't complete).")
        }
    }

    private suspend fun ageFlag(h: String): Flag {
        val body = http.execute(Request.Builder().url("$rdapBase/domain/${registrable(h)}").header("Accept", "application/rdap+json").build()) { r ->
            if (r.code != 200) throw HttpException(r.code, "RDAP returned HTTP ${r.code}")
            r.body.string()
        }
        val reg = registrationDate(body) ?: return Flag(Severity.INFO, "No registration date is published for this domain.")
        return ageFlag(reg, Instant.now())
    }

    companion object {
        /** The registered domain ("login.example.co.uk" → "example.co.uk"), using the same two-part suffix list as the look-alike check. */
        fun registrable(host: String): String =
            vip.ringling.recon.data.sources.domain.LookalikeGenerator.split(host)?.let { (name, suffix) -> "$name.$suffix" } ?: host.split('.').takeLast(2).joinToString(".")

        fun registrationDate(rdapJson: String): Instant? = runCatching {
            ((Json.parseToJsonElement(rdapJson).jsonObject["events"] as? JsonArray).orEmpty()).firstNotNullOfOrNull { e ->
                val o = e as? JsonObject ?: return@firstNotNullOfOrNull null
                if (o["eventAction"]?.jsonPrimitive?.contentOrNull == "registration") o["eventDate"]?.jsonPrimitive?.contentOrNull else null
            }?.let { Instant.parse(it) }
        }.getOrNull()

        fun ageFlag(registered: Instant, now: Instant): Flag {
            val days = Duration.between(registered, now).toDays()
            return when {
                days < 0 -> Flag(Severity.INFO, "Registration date is in the future; ignore it.")
                days < 30 -> Flag(Severity.EXPOSED, "Registered only $days day${if (days == 1L) "" else "s"} ago. Brand-new domains are a common scam sign.")
                days < 180 -> Flag(Severity.INFO, "Registered ${days / 30} months ago: still quite new.")
                else -> Flag(Severity.CLEAN, "Registered ${days / 365} year${if (days / 365 == 1L) "" else "s"}${if (days >= 365) "" else " (${days / 30} months)"} ago.")
            }
        }
    }
}
