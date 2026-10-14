package vip.ringling.recon.data.sources.domain

import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import vip.ringling.recon.domain.Finding
import vip.ringling.recon.domain.ModuleId
import vip.ringling.recon.domain.Severity

/** Pure functions from DNS/HTTP data to findings, so every rule is unit-testable without a network. */
object DomainAnalysis {
    private fun f(key: String, title: String, detail: String, sev: Severity, url: String? = null, raw: String = "") =
        Finding("DOMAIN-$key", ModuleId.DOMAIN, title, detail, sev, url, raw)

    fun addresses(a: List<DnsRecord>, aaaa: List<DnsRecord>): Finding =
        if (a.isEmpty() && aaaa.isEmpty()) f("a", "A / AAAA", "No address records — the name does not resolve.", Severity.INFO)
        else f("a", "A / AAAA", (a + aaaa).joinToString(" · ") { it.data }, Severity.INFO, raw = (a + aaaa).joinToString(",") { it.data })

    fun mx(records: List<DnsRecord>): Finding {
        if (records.isEmpty()) return f("mx", "MX", "No mail exchangers — the domain does not receive mail.", Severity.INFO)
        val sorted = records.map { it.data.trim().split(" ", limit = 2) }.map { (it.getOrNull(0)?.toIntOrNull() ?: 0) to it.last().trimEnd('.') }.sortedBy { it.first }
        if (sorted.size == 1 && sorted[0].second.isEmpty()) return f("mx", "MX", "Null MX (RFC 7505): the domain explicitly accepts no mail.", Severity.CLEAN)
        val provider = when {
            sorted.any { it.second.endsWith("google.com") || it.second.endsWith("googlemail.com") } -> " — Google Workspace"
            sorted.any { it.second.endsWith("outlook.com") || it.second.endsWith("protection.outlook.com") } -> " — Microsoft 365"
            else -> ""
        }
        return f("mx", "MX", sorted.joinToString(" · ") { "${it.second} (${it.first})" } + provider, Severity.INFO)
    }

    fun ns(records: List<DnsRecord>): Finding =
        if (records.isEmpty()) f("ns", "NS", "No nameservers returned.", Severity.INFO)
        else f("ns", "NS", records.joinToString(" · ") { it.data.trimEnd('.') }, Severity.INFO)

    fun spf(txt: List<String>): Finding {
        val spf = txt.filter { it.startsWith("v=spf1", ignoreCase = true) }
        return when {
            spf.isEmpty() -> f("spf", "SPF", "No SPF record — anyone can send mail claiming to be this domain without SPF failing.", Severity.EXPOSED)
            spf.size > 1 -> f("spf", "SPF", "Multiple SPF records (${spf.size}); receivers treat this as a permanent error.", Severity.EXPOSED, raw = spf.joinToString("\n"))
            else -> {
                val rec = spf.single()
                val all = Regex("""(?:^|\s)([+\-~?]?)all(?:\s|$)""").find(rec)?.groupValues?.get(1)
                when {
                    all == "-" -> f("spf", "SPF", "$rec — hard fail: only listed senders are accepted.", Severity.CLEAN, raw = rec)
                    all == "~" -> f("spf", "SPF", "$rec — soft fail: unlisted senders are flagged, not rejected.", Severity.INFO, raw = rec)
                    all == "?" -> f("spf", "SPF", "$rec — neutral: SPF gives receivers no guidance.", Severity.EXPOSED, raw = rec)
                    // "+all" and a bare "all" (implicit +) both authorize every sender on the internet.
                    all == "+" || all == "" -> f("spf", "SPF", "$rec — 'all' without -/~ authorizes every sender on the internet.", Severity.EXPOSED, raw = rec)
                    else -> f("spf", "SPF", "$rec — no 'all' mechanism; policy is open-ended.", Severity.INFO, raw = rec)
                }
            }
        }
    }

    fun dmarc(txt: List<String>): Finding {
        val rec = txt.firstOrNull { it.startsWith("v=DMARC1", ignoreCase = true) }
            ?: return f("dmarc", "DMARC", "No DMARC record at _dmarc — spoofed mail is not rejected or reported.", Severity.EXPOSED)
        val tags = rec.split(";").map { it.trim() }.filter { "=" in it }.associate { it.substringBefore("=").trim().lowercase() to it.substringAfter("=").trim() }
        val p = tags["p"]?.lowercase()
        val rua = tags["rua"]
        return when (p) {
            "reject" -> f("dmarc", "DMARC", "$rec — spoofed mail is rejected.", Severity.CLEAN, raw = rec)
            "quarantine" -> f("dmarc", "DMARC", "$rec — spoofed mail goes to spam; consider p=reject.", Severity.CLEAN, raw = rec)
            "none" -> f("dmarc", "DMARC · p=none", "$rec — monitoring only, spoofed mail is still delivered." + if (rua == null) " No rua= reporting address either." else "", Severity.EXPOSED, raw = rec)
            else -> f("dmarc", "DMARC", "$rec — missing or invalid p= policy.", Severity.EXPOSED, raw = rec)
        }
    }

    val DKIM_SELECTORS = listOf("default", "google", "selector1", "selector2", "k1", "k2", "s1", "s2", "mail", "dkim", "smtp", "mandrill", "mxvout", "cm", "zendesk1", "fm1", "protonmail")

    fun dkim(found: List<String>): Finding =
        if (found.isEmpty()) f("dkim", "DKIM", "None of ${DKIM_SELECTORS.size} common selectors found. The domain may use a custom selector, or not sign mail.", Severity.INFO)
        else f("dkim", "DKIM", "Selector${if (found.size == 1) "" else "s"} found: ${found.joinToString(", ")}.", Severity.CLEAN, raw = found.joinToString(","))

    fun bimi(txt: List<String>): Finding {
        val rec = txt.firstOrNull { it.startsWith("v=BIMI1", ignoreCase = true) }
        return if (rec == null) f("bimi", "BIMI", "No default._bimi record.", Severity.INFO) else f("bimi", "BIMI", rec, Severity.CLEAN, raw = rec)
    }

    fun mtaSts(txt: List<String>): Finding {
        val rec = txt.firstOrNull { it.startsWith("v=STSv1", ignoreCase = true) }
        return if (rec == null) f("mtasts", "MTA-STS", "No _mta-sts record; inbound mail TLS is opportunistic and can be downgraded.", Severity.INFO)
        else f("mtasts", "MTA-STS", "$rec — SMTP TLS policy is published.", Severity.CLEAN, raw = rec)
    }

    private val internalHints = setOf("staging", "stage", "dev", "test", "qa", "uat", "vpn", "admin", "internal", "intranet", "jira", "grafana", "jenkins", "git", "gitlab", "sso", "kibana", "prometheus", "ci", "vault", "backup", "old")

    /** crt.sh JSON → distinct subdomain names (wildcards and the apex dropped). */
    fun parseCrtNames(body: String, domain: String): List<String> {
        val arr = Json.parseToJsonElement(body).jsonArray
        return arr.flatMap { (it as? JsonObject)?.get("name_value")?.jsonPrimitive?.contentOrNull?.split("\n").orEmpty() }
            .map { it.trim().lowercase().removePrefix("*.") }
            .filter { it.endsWith(".$domain") }
            .distinct().sorted()
    }

    fun subdomains(names: List<String>, domain: String): Finding {
        if (names.isEmpty()) return f("subs", "Subdomains (crt.sh)", "No subdomains in certificate transparency logs.", Severity.CLEAN, "https://crt.sh/?q=%25.$domain")
        val labels = names.map { it.removeSuffix(".$domain") }
        val risky = labels.filter { l -> l.split(".", "-").any { it in internalHints } }
        val sample = names.take(6).joinToString(", ") { it.removeSuffix(".$domain") } + if (names.size > 6) ", …" else ""
        val detail = "$sample." + if (risky.isNotEmpty()) " Internal-looking: ${risky.take(5).joinToString(", ")}." else ""
        return f(
            "subs", "${names.size} subdomain${if (names.size == 1) "" else "s"} (crt.sh)", detail,
            if (risky.isNotEmpty()) Severity.EXPOSED else Severity.INFO, "https://crt.sh/?q=%25.$domain", names.joinToString("\n"),
        )
    }

    fun rdap(body: String, domain: String, now: Instant = Instant.now()): Finding {
        val o = Json.parseToJsonElement(body).jsonObject
        val events = (o["events"] as? JsonArray).orEmpty().mapNotNull { e ->
            val eo = e as? JsonObject ?: return@mapNotNull null
            eo["eventAction"]?.jsonPrimitive?.contentOrNull to eo["eventDate"]?.jsonPrimitive?.contentOrNull
        }.toMap()
        val registrar = (o["entities"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .firstOrNull { e -> (e["roles"] as? JsonArray).orEmpty().any { (it as? JsonPrimitive)?.contentOrNull == "registrar" } }
            ?.let { e ->
                (e["vcardArray"] as? JsonArray)?.getOrNull(1)?.let { it as? JsonArray }
                    ?.mapNotNull { it as? JsonArray }?.firstOrNull { it.getOrNull(0)?.jsonPrimitive?.contentOrNull == "fn" }
                    ?.getOrNull(3)?.jsonPrimitive?.contentOrNull
            }
        val created = events["registration"]?.take(10)
        val expires = events["expiration"]?.take(10)
        val status = (o["status"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

        val daysLeft = expires?.let { runCatching { ChronoUnit.DAYS.between(now, Instant.parse(events["expiration"])) }.getOrNull() }
        val sev = when {
            daysLeft != null && daysLeft < 0 -> Severity.EXPOSED
            daysLeft != null && daysLeft < 30 -> Severity.EXPOSED
            else -> Severity.CLEAN
        }
        val bits = buildList {
            registrar?.let { add("Registrar: $it") }
            created?.let { add("created $it") }
            expires?.let { add(if (daysLeft != null && daysLeft < 0) "EXPIRED $it" else "expires $it" + if (daysLeft != null && daysLeft < 30) " (${daysLeft}d)" else "") }
            if (status.isNotEmpty()) add("status: ${status.take(3).joinToString(", ")}")
        }
        return f("rdap", "RDAP", bits.joinToString(" · ").ifEmpty { "Registration data is redacted." }, sev, "https://rdap.org/domain/$domain", body.take(4000))
    }
}
