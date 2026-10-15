package vip.ringling.recon.data.sources.breach

import java.net.URLEncoder
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import vip.ringling.recon.data.net.Http
import vip.ringling.recon.data.net.HttpException
import vip.ringling.recon.domain.Fact
import vip.ringling.recon.domain.Finding
import vip.ringling.recon.domain.InputType
import vip.ringling.recon.domain.ModuleId
import vip.ringling.recon.domain.Severity

/** Normalized across the keyed v2 API and the keyless public endpoint. */
data class LeakCheckResult(val found: Int, val sources: List<LeakSource>, val fields: List<String>, val viaPublic: Boolean)
data class LeakSource(val name: String, val date: String?)

@Serializable private data class V2Response(
    val success: Boolean = false,
    val error: String? = null,
    val found: Int = 0,
    val result: List<V2Entry> = emptyList(),
)

@Serializable private data class V2Entry(val source: V2Source? = null, val fields: List<String> = emptyList())
@Serializable private data class V2Source(val name: String = "", @SerialName("breach_date") val breachDate: String? = null)

@Serializable private data class PublicResponse(
    val success: Boolean = false,
    val error: String? = null,
    val found: Int = 0,
    val fields: List<String> = emptyList(),
    val sources: List<PublicSource> = emptyList(),
)

@Serializable private data class PublicSource(val name: String = "", val date: String? = null)

class LeakCheckClient(private val http: Http, private val apiUrl: String, private val publicUrl: String) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Keyed v2 API when [apiKey] is set, otherwise the limited public endpoint (email only). */
    suspend fun lookup(query: String, type: InputType, apiKey: String?): LeakCheckResult {
        return if (apiKey != null) {
            val q = URLEncoder.encode(query, "UTF-8")
            val kind = when (type) { InputType.EMAIL -> "email"; InputType.PHONE -> "phone"; else -> "username" }
            val req = Request.Builder().url("$apiUrl/query/$q?type=$kind").header("X-API-Key", apiKey).header("Accept", "application/json").build()
            parseV2(fetch(req))
        } else {
            // The public endpoint auto-detects the type; it rejects "+" so phones go digits-only.
            val q = URLEncoder.encode(if (type == InputType.PHONE) query.filter(Char::isDigit) else query, "UTF-8")
            val req = Request.Builder().url("$publicUrl?check=$q").header("Accept", "application/json").build()
            parsePublic(fetch(req))
        }
    }

    private suspend fun fetch(req: Request): String = http.execute(req) { r ->
        val body = r.body.string()
        when (r.code) {
            200 -> body
            // LeakCheck reports most API errors as HTTP 400/401/403 with {"success":false,"error":"…"}.
            400, 401, 403 -> throw HttpException(r.code, "LeakCheck: " + (runCatching { json.decodeFromString<PublicResponse>(body).error }.getOrNull() ?: "request rejected"))
            429 -> throw HttpException(429, "LeakCheck rate limit — try again shortly")
            else -> throw HttpException(r.code, "LeakCheck returned HTTP ${r.code}")
        }
    }

    /** Live behaviour: no results arrive as {"success":false,"error":"Not found"}, which is an answer, not a failure. */
    private fun emptyOrThrow(error: String?): LeakCheckResult =
        if (error.equals("Not found", ignoreCase = true)) LeakCheckResult(0, emptyList(), emptyList(), viaPublic = false)
        else throw HttpException(400, "LeakCheck: ${error ?: "unknown error"}")

    internal fun parseV2(body: String): LeakCheckResult {
        val r = json.decodeFromString<V2Response>(body)
        if (!r.success) return emptyOrThrow(r.error)
        val sources = r.result.mapNotNull { e -> e.source?.let { LeakSource(it.name, it.breachDate) } }.distinct()
        return LeakCheckResult(r.found, sources, r.result.flatMap { it.fields }.distinct(), viaPublic = false)
    }

    internal fun parsePublic(body: String): LeakCheckResult {
        val r = json.decodeFromString<PublicResponse>(body)
        if (!r.success) return emptyOrThrow(r.error)
        return LeakCheckResult(r.found, r.sources.map { LeakSource(it.name, it.date) }, r.fields, viaPublic = true)
    }
}

object LeakCheckMapper {
    private val credentialFields = setOf("password", "hash", "pass", "passhash", "salt")

    fun finding(r: LeakCheckResult): Finding {
        if (r.found == 0) {
            return Finding("BREACH-leakcheck", ModuleId.BREACH, "LeakCheck: no results", "Not found in LeakCheck's index.", Severity.CLEAN)
        }
        val creds = r.fields.any { it.lowercase() in credentialFields }
        val sources = r.sources.filter { it.name.isNotBlank() }.sortedWith(compareByDescending<LeakSource> { it.date.orEmpty() }.thenBy { it.name })
        val fields = r.fields.map { it.replace('_', ' ') }
        val shown = sources.take(4).joinToString(", ") { it.name } + if (sources.size > 4) " +${sources.size - 4} more" else ""
        val detail = buildList {
            add("Found in ${r.found} record${if (r.found == 1) "" else "s"} across ${sources.size.coerceAtLeast(1)} compiled leak${if (sources.size == 1) "" else "s"}.")
            if (shown.isNotBlank()) add("Sources: $shown.")
            if (fields.isNotEmpty()) add("Data exposed: ${fields.joinToString(", ")}.")
            if (creds) add("A password or password hash is in this data. Change it anywhere you used it, and turn on two-factor authentication.")
            add("LeakCheck aggregates third-party leaks; the same exposure can appear in several compilations. ${if (r.viaPublic) "This lookup used the free public endpoint, which is rate-limited and less detailed than the keyed API." else ""}".trim())
        }.joinToString("\n")
        return Finding(
            id = "BREACH-leakcheck",
            module = ModuleId.BREACH,
            title = "LeakCheck · ${r.found} record${if (r.found == 1) "" else "s"}",
            detail = detail,
            severity = if (creds) Severity.EXPOSED else Severity.INFO,
            url = "https://leakcheck.io",
            raw = Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), kotlinx.serialization.json.buildJsonObject {
                put("found", kotlinx.serialization.json.JsonPrimitive(r.found))
                put("public_endpoint", kotlinx.serialization.json.JsonPrimitive(r.viaPublic))
                put("sources", kotlinx.serialization.json.JsonArray(r.sources.map { kotlinx.serialization.json.JsonPrimitive(it.name + (it.date?.let { d -> " ($d)" } ?: "")) }))
                put("fields", kotlinx.serialization.json.JsonArray(r.fields.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            }),
            tags = fields,
            hotTags = r.fields.filter { it.lowercase() in credentialFields }.map { it.replace('_', ' ') }.toSet(),
            facts = buildList {
                add(Fact("Records found", r.found.toString()))
                add(Fact("Leaks listed", sources.size.toString()))
                add(Fact("Endpoint", if (r.viaPublic) "Public (limited)" else "Keyed API"))
                sources.take(60).forEach { add(Fact(it.name, it.date?.takeIf { d -> d.isNotBlank() } ?: "date unknown")) }
                if (sources.size > 60) add(Fact("…", "${sources.size - 60} more not shown"))
            },
        )
    }
}
