package vip.ringling.recon.data.sources.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import vip.ringling.recon.data.net.Http
import vip.ringling.recon.data.net.HttpException

@Serializable private data class DohResponse(val Status: Int = 0, val Answer: List<DohAnswer> = emptyList())
@Serializable private data class DohAnswer(val name: String = "", val type: Int = 0, val TTL: Int = 0, val data: String = "")

enum class DnsType(val code: Int) { A(1), NS(2), MX(15), TXT(16), AAAA(28) }

data class DnsRecord(val name: String, val type: Int, val data: String)

/** DNS-over-HTTPS (Cloudflare JSON API). NXDOMAIN / empty answers return an empty list. */
class DohClient(private val http: Http, private val baseUrl: String) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun query(name: String, type: DnsType): List<DnsRecord> {
        val url = baseUrl.toHttpUrl().newBuilder().addQueryParameter("name", name).addQueryParameter("type", type.name).build()
        val req = Request.Builder().url(url).header("Accept", "application/dns-json").build()
        val body = http.execute(req) { r ->
            if (r.code != 200) throw HttpException(r.code, "DNS-over-HTTPS returned HTTP ${r.code}")
            r.body.string()
        }
        val resp = json.decodeFromString<DohResponse>(body)
        // Status 0 = NOERROR, 3 = NXDOMAIN. Anything else (SERVFAIL=2, REFUSED=5) is a real failure.
        if (resp.Status != 0 && resp.Status != 3) throw HttpException(resp.Status, "DNS error (RCODE ${resp.Status}) for $name")
        return resp.Answer.filter { it.type == type.code }.map { DnsRecord(it.name.trimEnd('.'), it.type, it.data) }
    }

    /** TXT strings, with the quoting and 255-byte chunking that DNS imposes removed. */
    suspend fun txt(name: String): List<String> = query(name, DnsType.TXT).map { unquoteTxt(it.data) }

    companion object {
        fun unquoteTxt(data: String): String {
            val parts = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(data).map { it.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\") }.toList()
            return if (parts.isEmpty()) data else parts.joinToString("")
        }
    }
}
