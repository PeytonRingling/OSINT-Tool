package vip.ringling.recon.data.sources.breach

import java.net.URLEncoder
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import vip.ringling.recon.data.net.Http
import vip.ringling.recon.data.net.HttpException
import vip.ringling.recon.domain.Fact
import vip.ringling.recon.domain.Finding
import vip.ringling.recon.domain.ModuleId
import vip.ringling.recon.domain.Severity

@Serializable
data class HibpBreach(
    @SerialName("Name") val name: String,
    @SerialName("Title") val title: String = "",
    @SerialName("Domain") val domain: String = "",
    @SerialName("BreachDate") val breachDate: String = "",
    @SerialName("PwnCount") val pwnCount: Long = 0,
    @SerialName("DataClasses") val dataClasses: List<String> = emptyList(),
    @SerialName("IsVerified") val isVerified: Boolean = true,
    @SerialName("IsSensitive") val isSensitive: Boolean = false,
    @SerialName("AddedDate") val addedDate: String = "",
    @SerialName("ModifiedDate") val modifiedDate: String = "",
    @SerialName("Description") val description: String = "",
    @SerialName("LogoPath") val logoPath: String = "",
    @SerialName("IsFabricated") val isFabricated: Boolean = false,
    @SerialName("IsRetired") val isRetired: Boolean = false,
    @SerialName("IsSpamList") val isSpamList: Boolean = false,
    @SerialName("IsMalware") val isMalware: Boolean = false,
    @SerialName("IsStealerLog") val isStealerLog: Boolean = false,
    @SerialName("Attribution") val attribution: String? = null,
    @SerialName("DisclosureUrl") val disclosureUrl: String? = null,
)

@Serializable
data class HibpPaste(
    @SerialName("Source") val source: String = "",
    @SerialName("Id") val id: String = "",
    @SerialName("Title") val title: String? = null,
    @SerialName("Date") val date: String? = null,
    @SerialName("EmailCount") val emailCount: Int = 0,
)

class HibpClient(
    private val http: Http,
    private val baseUrl: String,
    /** Longest Retry-After we'll sit through before giving up (ms). */
    private val maxWaitMs: Long = 30_000,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun breaches(email: String, apiKey: String): List<HibpBreach> =
        get("/breachedaccount/${enc(email)}?truncateResponse=false", apiKey)?.let { json.decodeFromString<List<HibpBreach>>(it) }.orEmpty()

    suspend fun pastes(email: String, apiKey: String): List<HibpPaste> =
        get("/pasteaccount/${enc(email)}", apiKey)?.let { json.decodeFromString<List<HibpPaste>>(it) }.orEmpty()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** null = 404 (nothing found). Honors 429 Retry-After once per request, then fails. */
    private suspend fun get(path: String, apiKey: String): String? {
        var retried = false
        while (true) {
            val req = Request.Builder().url(baseUrl + path)
                .header("hibp-api-key", apiKey)
                .header("Accept", "application/json")
                .build()
            val result = http.execute(req) { r ->
                when (r.code) {
                    200 -> Result.Body(r.body.string())
                    404 -> Result.NotFound
                    401 -> throw HttpException(401, "HIBP rejected the API key")
                    403 -> throw HttpException(403, "HIBP refused the request (user-agent or key)")
                    429 -> Result.Retry((r.header("Retry-After")?.trim()?.toLongOrNull() ?: 2L) * 1000)
                    else -> throw HttpException(r.code, "HIBP returned HTTP ${r.code}")
                }
            }
            when (result) {
                is Result.Body -> return result.text
                Result.NotFound -> return null
                is Result.Retry -> {
                    if (retried || result.ms > maxWaitMs) {
                        throw HttpException(429, "HIBP rate limit — retry in ${result.ms / 1000}s")
                    }
                    retried = true
                    sleep(result.ms)
                }
            }
        }
    }

    private sealed interface Result {
        data class Body(val text: String) : Result
        data object NotFound : Result
        data class Retry(val ms: Long) : Result
    }
}

object HibpMapper {
    private val sensitiveClasses = setOf("Passwords", "Password hints", "Security questions and answers", "Auth tokens", "Private messages")

    fun breachFinding(b: HibpBreach): Finding {
        val year = b.breachDate.take(4).ifEmpty { "?" }
        val severe = b.dataClasses.any { it in sensitiveClasses }
        val count = if (b.pwnCount > 0) "${compact(b.pwnCount)} accounts breached" else "Listed in a known breach"
        val line1 = listOf(count, prettyDate(b.breachDate)).filter { it.isNotBlank() }.joinToString(" · ")
        val line2 = if (b.dataClasses.isEmpty()) "" else "Exposed: ${b.dataClasses.joinToString(", ").lowercase()}."
        val story = plain(b.description)
        val advice = BreachAdvice.forClasses(b.dataClasses)
        val detail = buildList {
            add(line1)
            if (line2.isNotEmpty()) add(line2)
            if (story.isNotEmpty()) add(story)
            add("What to do:")
            advice.forEach { add("• $it") }
        }.joinToString("\n")
        val flags = buildList {
            if (!b.isVerified) add("Unverified")
            if (b.isSensitive) add("Sensitive — hidden from public search")
            if (b.isSpamList) add("Spam list")
            if (b.isMalware) add("Malware-sourced")
            if (b.isStealerLog) add("Stealer log — malware-harvested credentials")
            if (b.isRetired) add("Retired")
            if (b.isFabricated) add("Fabricated")
        }
        val facts = buildList {
            add(Fact("Breach date", prettyDate(b.breachDate).ifEmpty { "Unknown" }))
            if (b.addedDate.isNotBlank()) add(Fact("Added to HIBP", prettyDate(b.addedDate)))
            if (b.pwnCount > 0) add(Fact("Accounts affected", "%,d".format(b.pwnCount)))
            if (b.domain.isNotBlank()) add(Fact("Website", b.domain))
            add(Fact("Verified", if (b.isVerified) "Yes" else "No"))
            b.attribution?.takeIf { it.isNotBlank() }?.let { add(Fact("Attributed to", it)) }
            b.disclosureUrl?.takeIf { it.isNotBlank() }?.let { add(Fact("Company disclosure", it.removePrefix("https://").removePrefix("http://"))) }
            if (flags.isNotEmpty()) add(Fact("Flags", flags.joinToString(", ")))
        }
        return Finding(
            id = "BREACH-hibp-${b.name}",
            module = ModuleId.BREACH,
            title = "${b.title.ifBlank { b.name }} · $year",
            detail = detail,
            severity = if (severe) Severity.EXPOSED else Severity.INFO,
            url = "https://haveibeenpwned.com/PwnedWebsites#${b.name}",
            raw = Json.encodeToString(HibpBreach.serializer(), b),
            imageUrl = logoUrl(b),
            tags = b.dataClasses,
            hotTags = b.dataClasses.filter { it in sensitiveClasses }.toSet(),
            facts = facts,
        )
    }

    fun pasteFinding(p: List<HibpPaste>): Finding = if (p.isEmpty()) {
        Finding("BREACH-hibp-pastes", ModuleId.BREACH, "No public pastes", "Not seen in any paste in the HIBP paste index.", Severity.CLEAN)
    } else {
        Finding(
            "BREACH-hibp-pastes", ModuleId.BREACH, "${p.size} public paste${if (p.size == 1) "" else "s"}",
            buildList {
                add("This address appeared in ${p.size} paste${if (p.size == 1) "" else "s"} on public paste sites.")
                add("Pastes are often raw credential or contact dumps. Treat any password you used with this address as exposed.")
            }.joinToString("\n"),
            Severity.EXPOSED, raw = Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(HibpPaste.serializer()), p),
            facts = p.sortedByDescending { it.date.orEmpty() }.map { x ->
                Fact(
                    listOf(x.source, x.date?.take(10)).filter { !it.isNullOrBlank() }.joinToString(" · "),
                    listOfNotNull(x.title?.takeIf { it.isNotBlank() }, x.emailCount.takeIf { it > 0 }?.let { "$it addresses in this paste" }).joinToString(" — ").ifEmpty { x.id },
                )
            },
            url = p.firstOrNull { it.source.equals("Pastebin", true) && it.id.isNotBlank() }?.let { "https://pastebin.com/${it.id}" },
        )
    }

    fun noBreaches() = Finding(
        "BREACH-hibp-none", ModuleId.BREACH, "No known breaches", "Not found in any breach in Have I Been Pwned.", Severity.CLEAN,
    )

    private fun compact(n: Long) = when {
        n >= 1_000_000_000 -> "%.1fB".format(n / 1e9)
        n >= 1_000_000 -> "${n / 1_000_000}M"
        n >= 1_000 -> "${n / 1_000}K"
        else -> n.toString()
    }
}

private fun logoUrl(b: HibpBreach): String? = when {
    b.logoPath.startsWith("http") -> b.logoPath
    b.logoPath.startsWith("/") -> "https://haveibeenpwned.com${b.logoPath}"
    b.name.isNotBlank() -> "https://haveibeenpwned.com/Content/Images/PwnedLogos/${b.name}.png"
    else -> null
}

/** HIBP descriptions are small HTML snippets (links, emphasis); show them as plain text. */
private fun plain(html: String): String = org.jsoup.Jsoup.parse(html).text().trim()

private fun prettyDate(iso: String): String {
    val d = iso.take(10)
    val m = Regex("""(\d{4})-(\d{2})-(\d{2})""").matchEntire(d) ?: return iso
    val names = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    val month = names.getOrNull(m.groupValues[2].toInt() - 1) ?: return d
    return "$month ${m.groupValues[3].toInt()}, ${m.groupValues[1]}"
}
