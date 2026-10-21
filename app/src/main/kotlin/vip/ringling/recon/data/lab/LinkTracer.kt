package vip.ringling.recon.data.lab

import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.Request
import vip.ringling.recon.data.net.BROWSER_USER_AGENT
import vip.ringling.recon.data.net.Http
import vip.ringling.recon.data.net.await

data class TraceHop(val url: String, val code: Int?)
data class TraceResult(val hops: List<TraceHop>, val error: String? = null) { val chain: List<String> get() = hops.map { it.url } }

/**
 * Follows a link's redirects one hop at a time WITHOUT downloading any page content (the body is never read), going through the same client,
 * proxy and Tor settings as every other lookup. Only http/https hops are followed; at most [maxHops].
 */
@Singleton
class LinkTracer @Inject constructor(private val http: Http) {
    suspend fun trace(start: String, maxHops: Int = 10): TraceResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val client = http.client.newBuilder().followRedirects(false).followSslRedirects(false).build()
        val hops = mutableListOf<TraceHop>()
        var url = normalize(start) ?: return@withContext TraceResult(emptyList(), "That doesn't look like a web link.")
        repeat(maxHops + 1) {
            val req = Request.Builder().url(url).header("User-Agent", BROWSER_USER_AGENT).header("Range", "bytes=0-0").build()
            val code: Int; val loc: String?
            try { client.newCall(req).await().use { r -> code = r.code; loc = r.header("Location") } }
            catch (e: Exception) { hops += TraceHop(url, null); return@withContext TraceResult(hops, e.message ?: e.javaClass.simpleName) }
            hops += TraceHop(url, code)
            if (code !in 300..399 || loc == null) return@withContext TraceResult(hops)
            val next = runCatching { URI(url).resolve(loc).toString() }.getOrNull()
            if (next == null || !(next.startsWith("http://", true) || next.startsWith("https://", true))) return@withContext TraceResult(hops, "Redirects to something that isn't a web page.")
            url = next
        }
        TraceResult(hops, "Stopped after $maxHops redirects.")
    }

    companion object {
        fun normalize(raw: String): String? {
            val t = raw.trim().takeIf { it.isNotEmpty() && !it.contains(' ') } ?: return null
            val withScheme = if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(t)) t else "https://$t"
            if (!(withScheme.startsWith("http://", true) || withScheme.startsWith("https://", true))) return null
            return runCatching { URI(withScheme).takeIf { it.host != null }?.toString() }.getOrNull()
        }
    }
}
