package vip.ringling.recon.data.sources.phone

import java.net.URLEncoder
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import vip.ringling.recon.data.net.BROWSER_USER_AGENT
import vip.ringling.recon.data.net.Endpoints
import vip.ringling.recon.data.net.Http
import vip.ringling.recon.data.net.HttpException
import vip.ringling.recon.data.secret.ApiKeys
import vip.ringling.recon.domain.ApiKeyId
import vip.ringling.recon.domain.Fact
import vip.ringling.recon.domain.Finding
import vip.ringling.recon.domain.InputType
import vip.ringling.recon.domain.Link
import vip.ringling.recon.domain.ModuleId
import vip.ringling.recon.domain.OsintSource
import vip.ringling.recon.domain.ScanInput
import vip.ringling.recon.domain.Severity

/**
 * Reverse-phone intelligence from FREE sources only:
 *  1. offline analysis (libphonenumber): country, region, original carrier, line type, time zones
 *  2. 800notes community caller reports (page 1)
 *  3. optional Veriphone (free-tier key): current carrier and VoIP/landline/mobile
 *  4. one-tap lookup links (search engines, caller-ID sites, messaging apps)
 * No free database can name a number's owner; owner lookups are commercial people-search brokers, deliberately not used.
 */
class PhoneSource @Inject constructor(
    private val http: Http,
    private val endpoints: Endpoints,
    private val keys: ApiKeys,
) : OsintSource {
    override val id = ModuleId.PHONE
    override val supportedInputTypes = setOf(InputType.PHONE)

    internal var defaultRegion: () -> String = { Locale.getDefault().country.ifBlank { "US" } }

    override suspend fun run(input: ScanInput): Flow<Finding> = channelFlow {
        when (val r = PhoneAnalyzer.analyze(input.raw, defaultRegion())) {
            is PhoneAnalyzer.Result.Unparseable -> send(Finding("PHONE-analysis", ModuleId.PHONE, "Can't read this number", r.reason, Severity.INFO))
            is PhoneAnalyzer.Result.Ok -> {
                val info = r.info
                send(analysisFinding(info))
                send(linksFinding(info))
                launch { catching("notes", "800notes") { notesFinding(info)?.let { send(it) } } }
                val sid = keys.get(ApiKeyId.TWILIO_SID); val token = keys.get(ApiKeyId.TWILIO_TOKEN)
                if (sid != null && token != null) launch { catching("twilio", "Twilio") { send(twilio(info, sid, token)) } }
                keys.get(ApiKeyId.VERIPHONE)?.let { key -> launch { catching("veriphone", "Veriphone") { send(veriphone(info, key)) } } }
            }
        }
    }

    private suspend fun kotlinx.coroutines.channels.ProducerScope<Finding>.catching(key: String, name: String, block: suspend () -> Unit) {
        try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) {
            send(Finding("PHONE-$key-error", ModuleId.PHONE, "$name unavailable", e.message ?: e.javaClass.simpleName, Severity.INFO))
        }
    }

    internal fun analysisFinding(i: PhoneInfo): Finding {
        val where = listOf(i.place, i.countryName).filter { it.isNotBlank() }.distinct().joinToString(", ")
        val detail = buildList {
            add(if (i.valid) "Valid ${i.countryName} number · ${i.lineType}" else "Not a valid ${i.countryName.ifBlank { "" }} number (${i.possibleReason.lowercase()})".replace("  ", " "))
            if (where.isNotBlank() && i.valid) add("Registered area: $where")
            if (i.valid && i.carrier.isNotBlank()) add("Original carrier: ${i.carrier}")
            if (i.valid) add("This comes from the numbering plan, offline. Numbers can be ported between carriers, so the carrier and even the line type may have changed.")
            if (i.valid && i.nanp && i.carrier.isBlank()) add("US and Canadian numbers are portable, so there is no carrier in the numbering data; a Veriphone key (free tier) can look up the current one.")
        }.joinToString("\n")
        return Finding(
            id = "PHONE-analysis", module = ModuleId.PHONE, title = i.national, detail = detail,
            severity = Severity.INFO, url = null, raw = "",
            tags = listOfNotNull(i.lineType.substringBefore(" ("), i.regionCode, "Invalid".takeIf { !i.valid }),
            hotTags = if (i.valid) emptySet() else setOf("Invalid"),
            facts = buildList {
                add(Fact("E.164", i.e164)); add(Fact("International", i.international)); add(Fact("National", i.national))
                add(Fact("Country", "${i.countryName} (+${i.countryCallingCode})"))
                if (i.place.isNotBlank()) add(Fact("Area", i.place))
                if (i.carrier.isNotBlank()) add(Fact("Original carrier", i.carrier))
                add(Fact("Line type", i.lineType)); add(Fact("Valid number", if (i.valid) "Yes" else "No"))
                if (i.timeZones.isNotEmpty()) add(Fact("Time zone", i.timeZones.joinToString(", ")))
            },
        )
    }

    internal fun linksFinding(i: PhoneInfo): Finding {
        val digits = i.e164.filter(Char::isDigit)
        val national = i.national
        val dashed = if (i.nanp && digits.length == 11) "${digits.substring(1, 4)}-${digits.substring(4, 7)}-${digits.substring(7)}" else null
        val variants = listOfNotNull("\"${i.international}\"", "\"$national\"", dashed?.let { "\"$it\"" }, "\"${i.e164}\"").distinct()
        val q = URLEncoder.encode(variants.joinToString(" OR "), "UTF-8")
        val region = i.regionCode.lowercase()
        val links = buildList {
            add(Link("Google: every format of this number", "https://www.google.com/search?q=$q"))
            add(Link("Bing", "https://www.bing.com/search?q=$q"))
            add(Link("DuckDuckGo", "https://duckduckgo.com/?q=$q"))
            add(Link("Yandex", "https://yandex.com/search/?text=$q"))
            if (i.nanp) {
                Notes800.urlFor("https://800notes.com", i.e164)?.let { add(Link("800notes: full community comments", it)) }
                add(Link("WhoCallsMe", "https://whocallsme.com/Phone-Number.aspx/${digits.removePrefix("1")}"))
                add(Link("ShouldIAnswer", "https://www.shouldianswer.com/phone-number/${digits.removePrefix("1")}"))
            }
            add(Link("Truecaller (needs a free sign-in)", "https://www.truecaller.com/search/$region/${digits.removePrefix(i.countryCallingCode.toString())}"))
            add(Link("Telegram: is it registered?", "https://t.me/${i.e164}"))
            add(Link("WhatsApp: is it registered?", "https://wa.me/$digits"))
        }
        return Finding(
            id = "PHONE-links", module = ModuleId.PHONE, title = "Look it up elsewhere",
            detail = "One-tap searches for this number in other databases. These open in your browser; Recon doesn't scrape them.\nNo free database names the owner of a number. Search engines and community caller-ID sites are the best free signal.",
            severity = Severity.INFO, links = links,
        )
    }

    internal suspend fun notesFinding(i: PhoneInfo): Finding? {
        val url = Notes800.urlFor(endpoints.notes800, i.e164) ?: return null
        val html = http.execute(Request.Builder().url(url).header("User-Agent", BROWSER_USER_AGENT).build()) { r ->
            if (r.code == 404) "" else if (r.code != 200) throw HttpException(r.code, "800notes returned HTTP ${r.code}") else r.body.string()
        }
        return notesToFinding(Notes800.parse(html), url)
    }

    internal fun notesToFinding(n: NotesResult, url: String): Finding {
        if (n.reports.isEmpty()) {
            return Finding("PHONE-notes", ModuleId.PHONE, "No spam reports", "Nobody has left a comment about this number on 800notes. That is a good sign, but not proof it's trustworthy.", Severity.CLEAN, url)
        }
        val approx = if (n.pages > 1) "${(n.pages - 1) * 20 + n.reports.size}+" else n.reports.size.toString()
        val callers = n.reports.flatMap { it.callers }.map { it.trim() }.groupingBy { it.lowercase() }.eachCount().entries.sortedByDescending { it.value }
        val display = n.reports.flatMap { it.callers }.groupBy { it.lowercase() }.mapValues { it.value.first() }
        val joined = n.reports.joinToString(" ") { it.text + " " + it.callers.joinToString(" ") }.lowercase()
        val spamHits = Regex("scam|fraud|robocall|spam|telemarket|phish|harass|fake").findAll(joined).count()
        val likelySpam = spamHits >= 3 || n.pages >= 2
        val detail = buildList {
            add("About $approx community report${if (approx == "1") "" else "s"} on 800notes.")
            if (callers.isNotEmpty()) add("Claimed callers: " + callers.take(4).joinToString(", ") { (k, c) -> "${display[k] ?: k}${if (c > 1) " (${c}×)" else ""}" } + ".")
            if (likelySpam) add("Reports mention scam, spam or telemarketing. Treat calls from this number with caution.")
            add("Comments are written by the public and unverified; scammers often spoof real numbers, so the number's owner may be innocent.")
        }.joinToString("\n")
        val dates = n.reports.mapNotNull { it.date }.sorted()
        return Finding(
            id = "PHONE-notes", module = ModuleId.PHONE, title = "$approx community report${if (approx == "1") "" else "s"}",
            detail = detail, severity = if (likelySpam) Severity.EXPOSED else Severity.INFO, url = url, raw = "",
            tags = listOfNotNull("Spam/scam reports".takeIf { likelySpam }, *callers.take(3).map { display[it.key] ?: it.key }.toTypedArray()),
            hotTags = setOf("Spam/scam reports"),
            facts = buildList {
                add(Fact("Comment pages", n.pages.toString()))
                if (dates.isNotEmpty()) add(Fact("Reports shown span", "${dates.first()} → ${dates.last()}"))
                n.reports.take(6).forEach { add(Fact(it.date ?: "—", it.text.take(160) + if (it.text.length > 160) "…" else "")) }
            },
            links = listOf(Link("Read all comments on 800notes", url)),
        )
    }

    /** Veriphone v2 (free tier, needs a key). Shape from its docs; not verifiable without a key — all fields optional. */
    internal suspend fun veriphone(i: PhoneInfo, key: String): Finding {
        val url = "${endpoints.veriphone}/verify?phone=${URLEncoder.encode(i.e164, "UTF-8")}&key=${URLEncoder.encode(key, "UTF-8")}"
        val body = http.execute(Request.Builder().url(url).build()) { r ->
            if (r.code == 401 || r.code == 403) throw HttpException(r.code, "Veriphone rejected the API key")
            if (r.code == 429) throw HttpException(429, "Veriphone quota or rate limit reached")
            if (r.code != 200) throw HttpException(r.code, "Veriphone returned HTTP ${r.code}")
            r.body.string()
        }
        val o = Json.parseToJsonElement(body) as? JsonObject ?: throw HttpException(502, "Veriphone sent an unexpected response")
        fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        if (s("status").equals("error", true)) throw HttpException(400, "Veriphone: ${s("message") ?: "error"}")
        val valid = o["phone_valid"]?.jsonPrimitive?.booleanOrNull
        val type = s("phone_type"); val carrier = s("carrier"); val region = s("phone_region")
        val voip = type.equals("voip", true)
        return Finding(
            id = "PHONE-veriphone", module = ModuleId.PHONE, title = "Veriphone: ${carrier ?: "carrier unknown"}",
            detail = buildList {
                add(listOfNotNull(if (valid == true) "Valid" else if (valid == false) "Not valid" else null, type?.replaceFirstChar { it.uppercase() }, carrier).joinToString(" · "))
                if (voip) add("VoIP numbers are cheap to create and easy to hide behind; treat unexpected calls with extra care.")
                add("Current carrier according to Veriphone's live data (unlike the offline numbering plan, this follows number porting).")
            }.filter { it.isNotBlank() }.joinToString("\n"),
            severity = if (voip) Severity.EXPOSED else Severity.INFO,
            tags = listOfNotNull(type, "VoIP".takeIf { voip }), hotTags = setOf("VoIP"),
            facts = listOfNotNull(
                carrier?.let { Fact("Current carrier", it) }, type?.let { Fact("Line type", it) }, region?.let { Fact("Region", it) },
                s("country")?.let { Fact("Country", it) }, s("local_number")?.let { Fact("Local format", it) },
            ),
            url = "https://veriphone.io",
        )
    }

    /**
     * Twilio Lookup v2 with caller_name (CNAM) + line_type_intelligence. This is the legitimate way to get the name
     * a carrier lists for a number. Paid per lookup, billed to the user's own Twilio account. Shape from Twilio's docs;
     * not verifiable without credentials, so every field is optional.
     */
    internal suspend fun twilio(i: PhoneInfo, sid: String, token: String): Finding {
        val url = "${endpoints.twilio}/PhoneNumbers/${URLEncoder.encode(i.e164, "UTF-8")}?Fields=caller_name,line_type_intelligence"
        val auth = "Basic " + java.util.Base64.getEncoder().encodeToString("$sid:$token".toByteArray())
        val body = http.execute(Request.Builder().url(url).header("Authorization", auth).build()) { r ->
            when (r.code) {
                200 -> r.body.string()
                401, 403 -> throw HttpException(r.code, "Twilio rejected the Account SID / Auth Token")
                404 -> throw HttpException(404, "Twilio doesn't recognize this number")
                429 -> throw HttpException(429, "Twilio rate limit — try again shortly")
                else -> throw HttpException(r.code, "Twilio returned HTTP ${r.code}")
            }
        }
        val o = Json.parseToJsonElement(body) as? JsonObject ?: throw HttpException(502, "Twilio sent an unexpected response")
        val cn = o["caller_name"] as? JsonObject
        val name = cn?.get("caller_name")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val kind = cn?.get("caller_type")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val cnErr = cn?.get("error_code")?.jsonPrimitive?.contentOrNull
        val lt = o["line_type_intelligence"] as? JsonObject
        val type = lt?.get("type")?.jsonPrimitive?.contentOrNull; val carrier = lt?.get("carrier_name")?.jsonPrimitive?.contentOrNull
        val voip = type.equals("nonFixedVoip", true) || type.equals("fixedVoip", true) || type.equals("voip", true)
        return Finding(
            id = "PHONE-twilio", module = ModuleId.PHONE,
            title = name ?: "No caller-ID name on file",
            detail = buildList {
                add(if (name != null) "Caller-ID name registered for this number: $name${kind?.let { " (${it.lowercase()})" } ?: ""}." else "The carrier lists no caller-ID name for this number.")
                if (carrier != null || type != null) add(listOfNotNull(type?.let { "Line type: $it" }, carrier?.let { "Current carrier: $it" }).joinToString(" · "))
                if (voip) add("VoIP numbers are cheap to create and easy to hide behind.")
                add("This is the name the carrier publishes (CNAM). For many mobile numbers it is blank or just a city; it can also be outdated or a business name rather than a person.")
                if (cnErr != null) add("Twilio reported a caller-name error ($cnErr).")
            }.joinToString("\n"),
            severity = if (voip) Severity.EXPOSED else Severity.INFO, url = null, raw = "",
            tags = listOfNotNull(kind?.lowercase()?.replaceFirstChar { it.uppercase() }, type, "VoIP".takeIf { voip }), hotTags = setOf("VoIP"),
            facts = listOfNotNull(name?.let { Fact("Caller-ID name", it) }, kind?.let { Fact("Caller type", it) }, type?.let { Fact("Line type", it) }, carrier?.let { Fact("Current carrier", it) }),
        )
    }
}
