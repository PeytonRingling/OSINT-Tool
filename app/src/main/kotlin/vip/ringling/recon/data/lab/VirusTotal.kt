package vip.ringling.recon.data.lab

import java.security.MessageDigest
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Request
import vip.ringling.recon.data.net.Endpoints
import vip.ringling.recon.data.net.Http
import vip.ringling.recon.data.secret.ApiKeys
import vip.ringling.recon.domain.ApiKeyId
import vip.ringling.recon.domain.Flag
import vip.ringling.recon.domain.Severity

/** What VirusTotal's engines currently say about a domain or URL. [flaggedBy] names up to six engines that call it malicious or suspicious. */
data class VtVerdict(val malicious: Int, val suspicious: Int, val harmless: Int, val undetected: Int, val reputation: Int?, val analysedAtEpoch: Long?, val flaggedBy: List<String>, val permalink: String) {
    val total: Int get() = malicious + suspicious + harmless + undetected
}

sealed interface VtResult {
    data class Found(val verdict: VtVerdict) : VtResult
    /** VirusTotal has never seen it (for a URL this is common and says nothing bad). */
    data class Unknown(val permalink: String) : VtResult
    data object NoKey : VtResult
    data class Failed(val message: String) : VtResult
}

/**
 * VirusTotal v3 lookups with the user's own free API key. A domain lookup sends only the domain; a URL lookup sends the URL (encoded) and
 * only ever READS an existing report: nothing is submitted for scanning. Never called during a scan unless the key is set (domain scans) or
 * the user taps the lookup button (links).
 */
@Singleton
class VirusTotal @Inject constructor(private val http: Http, private val keys: ApiKeys, private val endpoints: Endpoints) {
    fun hasKey() = keys.get(ApiKeyId.VIRUSTOTAL) != null

    suspend fun domain(host: String): VtResult = lookup("/domains/${host.lowercase()}", domainPage(host))
    suspend fun url(url: String): VtResult = lookup("/urls/${Base64.getUrlEncoder().withoutPadding().encodeToString(url.toByteArray())}", urlPage(url))

    private suspend fun lookup(path: String, page: String): VtResult {
        val key = keys.get(ApiKeyId.VIRUSTOTAL) ?: return VtResult.NoKey
        return try {
            val (code, body) = http.execute(Request.Builder().url(endpoints.virusTotal + path).header("x-apikey", key).header("accept", "application/json").build()) { r -> r.code to r.body.string() }
            when (code) {
                200 -> parse(body, page)?.let { VtResult.Found(it) } ?: VtResult.Failed("VirusTotal returned an unexpected response.")
                404 -> VtResult.Unknown(page)
                401, 403 -> VtResult.Failed("VirusTotal rejected the API key. Check it under System → API keys.")
                429 -> VtResult.Failed("VirusTotal rate limit reached (free keys allow 4 lookups a minute). Try again shortly.")
                else -> VtResult.Failed("VirusTotal returned HTTP $code.")
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) { VtResult.Failed(e.message ?: e.javaClass.simpleName) }
    }

    companion object {
        fun domainPage(host: String) = "https://www.virustotal.com/gui/domain/${host.lowercase()}"
        /** VirusTotal's web page for a URL is addressed by the SHA-256 of the URL. */
        fun urlPage(url: String) = "https://www.virustotal.com/gui/url/" + MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }

        fun parse(body: String, permalink: String): VtVerdict? = runCatching {
            val attrs = Json.parseToJsonElement(body).jsonObject["data"]?.jsonObject?.get("attributes")?.jsonObject ?: return null
            val stats = attrs["last_analysis_stats"]?.jsonObject ?: return null
            fun n(k: String) = stats[k]?.jsonPrimitive?.intOrNull ?: 0
            val flagged = (attrs["last_analysis_results"] as? JsonObject)?.entries.orEmpty().mapNotNull { (engine, v) ->
                val cat = (v as? JsonObject)?.get("category")?.jsonPrimitive?.contentOrNull
                if (cat == "malicious" || cat == "suspicious") engine else null
            }.sorted().take(6)
            VtVerdict(n("malicious"), n("suspicious"), n("harmless"), n("undetected") + n("timeout"), attrs["reputation"]?.jsonPrimitive?.intOrNull, attrs["last_analysis_date"]?.jsonPrimitive?.longOrNull, flagged, permalink)
        }.getOrNull()

        fun flags(r: VtResult, what: String): List<Flag> = when (r) {
            is VtResult.Found -> {
                val v = r.verdict
                val who = if (v.flaggedBy.isEmpty()) "" else " (${v.flaggedBy.joinToString(", ")})"
                listOf(when {
                    v.malicious >= 2 -> Flag(Severity.EXPOSED, "VirusTotal: ${v.malicious} of ${v.total} engines flag this $what as malicious$who.")
                    v.malicious == 1 || v.suspicious > 0 -> Flag(Severity.INFO, "VirusTotal: ${v.malicious} malicious and ${v.suspicious} suspicious out of ${v.total} engines$who. One or two can be false alarms.")
                    else -> Flag(Severity.CLEAN, "VirusTotal: 0 of ${v.total} engines flag this $what. That doesn't prove it's safe.")
                })
            }
            is VtResult.Unknown -> listOf(Flag(Severity.INFO, "VirusTotal has no report for this $what yet."))
            VtResult.NoKey -> emptyList()
            is VtResult.Failed -> listOf(Flag(Severity.INFO, r.message))
        }
    }
}
