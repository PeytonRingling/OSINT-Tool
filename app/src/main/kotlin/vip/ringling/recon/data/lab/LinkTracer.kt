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
class LinkTracer(private val http: Http, private val allowPrivate: Boolean) {
    @Inject constructor(http: Http) : this(http, false)

    suspend fun trace(start: String, maxHops: Int = 10): TraceResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        // No redirects followed automatically, no cookies kept (OkHttp has no cookie jar by default), and never toward this phone's own network:
        // a link that points at localhost, a router or a cloud-metadata address is refused, including when a public name resolves to one.
        val client = http.client.newBuilder().followRedirects(false).followSslRedirects(false)
            .dns(object : okhttp3.Dns { override fun lookup(hostname: String) = okhttp3.Dns.SYSTEM.lookup(hostname).also { l -> if (!allowPrivate && l.any { isPrivate(it) }) throw java.net.UnknownHostException("blocked: $hostname points at a private address") } }).build()
        val hops = mutableListOf<TraceHop>()
        var url = normalize(start) ?: return@withContext TraceResult(emptyList(), "That doesn't look like a web link.")
        repeat(maxHops + 1) {
            if (!allowPrivate && blockedHost(URI(url).host)) { hops += TraceHop(url, null); return@withContext TraceResult(hops, "Refused: that address is on a private or local network, not the public internet.") }
            // Recon connects over https only: an http hop is reported but not requested (it could be read or altered on the network).
            if (!allowPrivate && url.startsWith("http://", ignoreCase = true)) { hops += TraceHop(url, null); return@withContext TraceResult(hops, "Not contacted: this address is unencrypted (http). Recon only connects over https, so what is there is unknown.") }
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
        fun isPrivate(a: java.net.InetAddress) = a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isMulticastAddress ||
            (a.address.size == 4 && (a.address[0].toInt() and 0xFF == 100 && a.address[1].toInt() and 0xC0 == 64)) || // CGNAT 100.64.0.0/10
            (a.address.size == 16 && (a.address[0].toInt() and 0xFE) == 0xFC) // IPv6 unique-local fc00::/7

        /** Literal IPs and obvious local names are refused before any request is made. */
        fun blockedHost(host: String?): Boolean {
            val h = host?.lowercase()?.trim('[', ']') ?: return true
            if (h == "localhost" || h.endsWith(".localhost") || h.endsWith(".local") || h.endsWith(".internal") || h.endsWith(".lan") || h.endsWith(".home.arpa")) return true
            val literal = Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(h) || ':' in h
            return literal && runCatching { isPrivate(java.net.InetAddress.getByName(h)) }.getOrDefault(true)
        }

        fun normalize(raw: String): String? {
            val t = raw.trim().takeIf { it.isNotEmpty() && !it.contains(' ') } ?: return null
            val withScheme = if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(t)) t else "https://$t"
            if (!(withScheme.startsWith("http://", true) || withScheme.startsWith("https://", true))) return null
            return runCatching { URI(withScheme).takeIf { it.host != null }?.toString() }.getOrNull()
        }
    }
}
