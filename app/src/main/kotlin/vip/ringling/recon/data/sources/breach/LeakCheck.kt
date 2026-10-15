package vip.ringling.recon.data.sources.breach

import java.net.URLEncoder
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import vip.ringling.recon.data.net.Http
import vip.ringling.recon.data.net.HttpException
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
    fun finding(r: LeakCheckResult): Finding {
        if (r.found == 0) {
            return Finding("BREACH-leakcheck", ModuleId.BREACH, "LeakCheck: no results", "Not found in LeakCheck's index.", Severity.CLEAN)
        }
        val names = r.sources.map { it.name }.filter { it.isNotBlank() }
        val creds = r.fields.any { it.equals("password", true) || it.equals("hash", true) }
        val shown = names.take(4).joinToString(", ") + if (names.size > 4) " +${names.size - 4} more" else ""
        val fields = if (r.fields.isEmpty()) "" else " Fields: ${r.fields.take(8).joinToString(", ")}${if (r.fields.size > 8) " +${r.fields.size - 8} more" else ""}."
        return Finding(
            id = "BREACH-leakcheck",
            module = ModuleId.BREACH,
            title = "LeakCheck · ${r.found} record${if (r.found == 1) "" else "s"}",
            detail = (if (shown.isBlank()) "Found in compiled leak data." else "Found in: $shown.") + fields,
            severity = if (creds) Severity.EXPOSED else Severity.INFO,
            url = "https://leakcheck.io",
            raw = Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), kotlinx.serialization.json.buildJsonObject {
                put("found", kotlinx.serialization.json.JsonPrimitive(r.found))
                put("public_endpoint", kotlinx.serialization.json.JsonPrimitive(r.viaPublic))
                put("sources", kotlinx.serialization.json.JsonArray(r.sources.map { kotlinx.serialization.json.JsonPrimitive(it.name + (it.date?.let { d -> " ($d)" } ?: "")) }))
                put("fields", kotlinx.serialization.json.JsonArray(r.fields.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            }),
        )
    }
}
