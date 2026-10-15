package vip.ringling.recon.data.sources.breach

import java.net.URLEncoder
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import vip.ringling.recon.data.net.Http
import vip.ringling.recon.data.net.HttpException
import vip.ringling.recon.domain.Fact
import vip.ringling.recon.domain.Finding
import vip.ringling.recon.domain.InputType
import vip.ringling.recon.domain.ModuleId
import vip.ringling.recon.domain.Severity

/** One leak LeakCheck found the query in. [fields] are the data types in THAT leak (keyed API only). */
data class LeakDetail(
    val name: String, val date: String?, val fields: List<String>,
    val unverified: Boolean = false, val compilation: Boolean = false, val passwordless: Boolean = false,
)

/** Normalized across the keyed v2 API and the keyless public endpoint. */
data class LeakCheckResult(
    val found: Int,
    val sources: List<LeakSource>,
    val fields: List<String>,
    val viaPublic: Boolean,
    val leaks: List<LeakDetail> = emptyList(),
    /** Remaining paid queries, when the API says. */
    val quota: Int? = null,
    /** The response with every string value masked, safe to show or share when a shape needs debugging. */
    val shape: String = "",
)
data class LeakSource(val name: String, val date: String?)

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

    /**
     * Tolerant parse of the keyed v2 response (shape from LeakCheck's docs; not verifiable without a key, so every
     * field is optional): {success, quota, found, result:[{source:{name,breach_date,unverified,…}, fields:[…], <values>}]}.
     * Only source names, dates, flags and FIELD NAMES are read. The leaked values themselves are never extracted.
     */
    internal fun parseV2(body: String): LeakCheckResult {
        val root = (try { Json.parseToJsonElement(body) } catch (e: Exception) { throw HttpException(502, "LeakCheck sent something that isn't JSON") }) as? JsonObject
            ?: throw HttpException(502, "LeakCheck sent an unexpected response")
        if (root["success"]?.jsonPrimitive?.booleanOrNull != true) return emptyOrThrow(root["error"]?.jsonPrimitive?.contentOrNull)
        val entries = (root["result"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        class Acc(var date: String?, val fields: LinkedHashSet<String> = linkedSetOf(), var unverified: Boolean = false, var compilation: Boolean = false, var passwordless: Boolean = false)
        val byName = linkedMapOf<String, Acc>()
        entries.forEach { e ->
            val src = e["source"]
            val srcObj = src as? JsonObject
            val name = srcObj?.get("name")?.jsonPrimitive?.contentOrNull ?: (src as? JsonPrimitive)?.contentOrNull ?: "Unknown source"
            val acc = byName.getOrPut(name) { Acc(srcObj?.get("breach_date")?.jsonPrimitive?.contentOrNull ?: srcObj?.get("date")?.jsonPrimitive?.contentOrNull) }
            val listed = (e["fields"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            // If the API omits "fields", fall back to which keys carry a value — names only, never the values.
            acc.fields += listed ?: e.filter { (k, v) -> k != "source" && k != "fields" && v !is JsonNull }.keys
            if (srcObj != null) {
                acc.unverified = acc.unverified || truthy(srcObj["unverified"])
                acc.compilation = acc.compilation || truthy(srcObj["compilation"])
                acc.passwordless = acc.passwordless || truthy(srcObj["passwordless"])
            }
        }
        val leaks = byName.map { (n, a) -> LeakDetail(n, a.date, a.fields.toList(), a.unverified, a.compilation, a.passwordless) }
        return LeakCheckResult(
            found = root["found"]?.jsonPrimitive?.intOrNull ?: entries.size,
            sources = leaks.map { LeakSource(it.name, it.date) },
            fields = leaks.flatMap { it.fields }.distinct(),
            viaPublic = false,
            leaks = leaks,
            quota = root["quota"]?.jsonPrimitive?.intOrNull,
            shape = Json.encodeToString(JsonElement.serializer(), redact(root)),
        )
    }

    private fun truthy(e: JsonElement?): Boolean = (e as? JsonPrimitive)?.let { it.booleanOrNull == true || it.intOrNull == 1 || it.contentOrNull == "1" } == true

    /** Keys, numbers and booleans stay; every string becomes a length placeholder; arrays are cut to two samples. */
    internal fun redact(e: JsonElement): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.mapValues { (_, v) -> redact(v) })
        is JsonArray -> JsonArray(e.take(2).map { redact(it) } + if (e.size > 2) listOf(JsonPrimitive("… ${e.size - 2} more")) else emptyList())
        is JsonPrimitive -> if (e.isString) JsonPrimitive("<text, ${e.content.length} chars>") else e
        JsonNull -> e
    }

    internal fun parsePublic(body: String): LeakCheckResult {
        val r = json.decodeFromString<PublicResponse>(body)
        if (!r.success) return emptyOrThrow(r.error)
        return LeakCheckResult(r.found, r.sources.map { LeakSource(it.name, it.date) }, r.fields, viaPublic = true, leaks = r.sources.map { LeakDetail(it.name, it.date, emptyList()) })
    }
}

object LeakCheckMapper {
    private val credentialFields = setOf("password", "hash", "pass", "passhash", "salt")
    private fun isCred(f: String) = f.lowercase() in credentialFields

    fun finding(r: LeakCheckResult): Finding {
        if (r.found == 0) {
            return Finding("BREACH-leakcheck", ModuleId.BREACH, "LeakCheck: no results", "Not found in LeakCheck's index.", Severity.CLEAN)
        }
        val perLeak = r.leaks.any { it.fields.isNotEmpty() }
        val leaks = r.leaks.ifEmpty { r.sources.map { LeakDetail(it.name, it.date, emptyList()) } }.filter { it.name.isNotBlank() }
            .sortedWith(compareByDescending<LeakDetail> { it.date.orEmpty() }.thenBy { it.name })
        val creds = r.fields.any(::isCred)
        val fields = r.fields.map { it.replace('_', ' ') }
        val withPassword = leaks.count { l -> l.fields.any(::isCred) }
        val shown = leaks.take(4).joinToString(", ") { it.name } + if (leaks.size > 4) " +${leaks.size - 4} more" else ""
        val detail = buildList {
            add("Found in ${r.found} record${if (r.found == 1) "" else "s"} across ${leaks.size.coerceAtLeast(1)} compiled leak${if (leaks.size == 1) "" else "s"}.")
            if (shown.isNotBlank()) add("Sources: $shown.")
            if (fields.isNotEmpty()) add("Data exposed: ${fields.joinToString(", ")}.")
            if (perLeak && withPassword > 0) add("$withPassword of ${leaks.size} leaks include a password or password hash.")
            else if (creds) add("A password or password hash is in this data.")
            if (creds) add("Change it anywhere you used it, and turn on two-factor authentication.")
            if (leaks.any { it.unverified }) add("${leaks.count { it.unverified }} leak${if (leaks.count { it.unverified } == 1) " is" else "s are"} marked unverified (lower confidence).")
            if (leaks.any { it.compilation }) add("${leaks.count { it.compilation }} are combo-list compilations: re-packaged old leaks, so the same exposure can repeat.")
            if (r.viaPublic) add("This lookup used the free public endpoint, which is rate-limited and gives no per-leak detail. Add a LeakCheck key in Settings for more.")
        }.joinToString("\n")
        return Finding(
            id = "BREACH-leakcheck",
            module = ModuleId.BREACH,
            title = "LeakCheck · ${r.found} record${if (r.found == 1) "" else "s"}",
            detail = detail,
            severity = if (creds) Severity.EXPOSED else Severity.INFO,
            url = "https://leakcheck.io",
            raw = r.shape.ifBlank {
                Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), kotlinx.serialization.json.buildJsonObject {
                    put("found", kotlinx.serialization.json.JsonPrimitive(r.found))
                    put("public_endpoint", kotlinx.serialization.json.JsonPrimitive(r.viaPublic))
                    put("sources", kotlinx.serialization.json.JsonArray(r.sources.map { kotlinx.serialization.json.JsonPrimitive(it.name + (it.date?.let { d -> " ($d)" } ?: "")) }))
                    put("fields", kotlinx.serialization.json.JsonArray(r.fields.map { kotlinx.serialization.json.JsonPrimitive(it) }))
                })
            },
            tags = fields,
            hotTags = r.fields.filter(::isCred).map { it.replace('_', ' ') }.toSet(),
            facts = buildList {
                add(Fact("Records found", r.found.toString()))
                add(Fact("Leaks listed", leaks.size.toString()))
                add(Fact("Endpoint", if (r.viaPublic) "Public (limited)" else "Keyed API"))
                r.quota?.let { add(Fact("Queries left", it.toString())) }
                leaks.take(60).forEach { l ->
                    val flags = listOfNotNull("unverified".takeIf { l.unverified }, "combo list".takeIf { l.compilation }, "no password".takeIf { l.passwordless })
                    val what = if (perLeak) l.fields.joinToString(", ") { it.replace('_', ' ') }.ifEmpty { "fields not listed" } else null
                    add(Fact(l.name, listOfNotNull(l.date?.takeIf { d -> d.isNotBlank() } ?: "date unknown", what, flags.joinToString(", ").takeIf { it.isNotEmpty() }).joinToString(" · ")))
                }
                if (leaks.size > 60) add(Fact("…", "${leaks.size - 60} more not shown"))
            },
        )
    }
}
