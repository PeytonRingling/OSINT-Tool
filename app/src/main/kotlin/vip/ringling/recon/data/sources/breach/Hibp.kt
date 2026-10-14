package vip.ringling.recon.data.sources.breach

import java.net.URLEncoder
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import vip.ringling.recon.data.net.Http
import vip.ringling.recon.data.net.HttpException
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
        val count = if (b.pwnCount > 0) "${compact(b.pwnCount)} accounts. " else ""
        val exposed = if (b.dataClasses.isEmpty()) "" else "Exposed: ${b.dataClasses.joinToString(", ").lowercase()}."
        return Finding(
            id = "BREACH-hibp-${b.name}",
            module = ModuleId.BREACH,
            title = "${b.title.ifBlank { b.name }} · $year",
            detail = (count + exposed).trim().ifEmpty { "Listed in a known breach." },
            severity = if (severe) Severity.EXPOSED else Severity.INFO,
            url = "https://haveibeenpwned.com/PwnedWebsites#${b.name}",
            raw = Json.encodeToString(HibpBreach.serializer(), b),
        )
    }

    fun pasteFinding(p: List<HibpPaste>): Finding = if (p.isEmpty()) {
        Finding("BREACH-hibp-pastes", ModuleId.BREACH, "No public pastes", "Not seen in any paste in the HIBP paste index.", Severity.CLEAN)
    } else {
        Finding(
            "BREACH-hibp-pastes", ModuleId.BREACH, "${p.size} public paste${if (p.size == 1) "" else "s"}",
            p.joinToString("; ") { "${it.source}: ${it.title ?: it.id}" }.take(240),
            Severity.EXPOSED, raw = Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(HibpPaste.serializer()), p),
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
