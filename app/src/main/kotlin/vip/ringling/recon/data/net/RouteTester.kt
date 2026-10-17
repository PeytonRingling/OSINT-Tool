package vip.ringling.recon.data.net

import javax.inject.Inject
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request

/** Outcome of "Test connection": what the outside world sees when Recon makes a request right now. */
sealed interface RouteResult {
    /** Reached the check service through Tor. */
    data class Tor(val exitIp: String) : RouteResult
    /** Reached it, but not through Tor: either no proxy, or a custom proxy. */
    data class Other(val ip: String, val viaProxy: Boolean) : RouteResult
    /** The request failed (with a proxy on, this is the fail-closed case: nothing leaked). */
    /** The Tor check service failed, but a plain "what is my IP" service answered, so we only know the address, not whether it is Tor. */
    data class Unconfirmed(val ip: String, val reason: String) : RouteResult
    data class Failed(val message: String) : RouteResult
}

@Serializable private data class IpEcho(val ip: String = "")
@Serializable private data class TorCheck(val IsTor: Boolean = false, val IP: String = "")

/**
 * Asks check.torproject.org which address it sees. It uses the same HTTP client (and so the same proxy selector) as every lookup and as
 * the photo/tile loader, so the answer is what lookups really use. Only a plain GET is sent; nothing about your targets.
 */
class RouteTester(private val http: Http, private val base: String, private val fallbackBase: String = "https://api.ipify.org") {
    @Inject constructor(http: Http) : this(http, "https://check.torproject.org")

    private val json = Json { ignoreUnknownKeys = true }

    private fun describeError(e: Exception) = "${e.javaClass.simpleName}${e.message?.let { ": " + it.take(100) } ?: ""}"

    suspend fun test(): RouteResult {
        val first = try {
            val body = http.execute(Request.Builder().url("$base/api/ip").build()) { r ->
                if (!r.isSuccessful) throw HttpException(r.code, "The check service answered ${r.code}")
                r.body.string()
            }
            val c = json.decodeFromString(TorCheck.serializer(), body)
            return if (c.IsTor) RouteResult.Tor(c.IP) else RouteResult.Other(c.IP, ProxyState.proxy != null)
        } catch (e: Exception) { e }
        // The Tor check service failed (blocked, down, or the proxy dropped it). Try a plain IP echo so the user still learns something.
        return try {
            val body = http.execute(Request.Builder().url("$fallbackBase/?format=json").build()) { r ->
                if (!r.isSuccessful) throw HttpException(r.code, "answered ${r.code}")
                r.body.string()
            }
            val ip = json.decodeFromString(IpEcho.serializer(), body).ip
            if (ip.isBlank()) RouteResult.Failed(describeError(first)) else RouteResult.Unconfirmed(ip, describeError(first))
        } catch (e: Exception) { RouteResult.Failed(describeError(first)) }
    }

    companion object {
        /** Plain-language verdict for the screen, given the mode the user selected. */
        fun describe(mode: ProxyMode, r: RouteResult): Pair<String, Boolean> = when {
            r is RouteResult.Tor && mode == ProxyMode.TOR -> "Working. Requests leave through Tor (exit ${r.exitIp})." to true
            r is RouteResult.Tor -> "Requests leave through Tor (exit ${r.exitIp}), though Tor mode isn't selected." to true
            r is RouteResult.Other && mode == ProxyMode.TOR -> "Not going through Tor. The check saw ${r.ip}. Make sure Orbot is connected and its SOCKS port is 9050." to false
            r is RouteResult.Other && mode == ProxyMode.CUSTOM -> "Reached through your proxy. The check saw ${r.ip} (not a Tor exit)." to true
            r is RouteResult.Other -> "Direct connection. The check saw ${r.ip}, your real address." to true
            r is RouteResult.Unconfirmed && mode == ProxyMode.OFF -> "Direct connection. Your address is ${r.ip}. (The Tor check service couldn't be reached: ${r.reason}.)" to true
            r is RouteResult.Unconfirmed -> "Connected, and the outside world sees ${r.ip}, but the Tor check service couldn't be reached (${r.reason}), so Tor can't be confirmed. If ${r.ip} is not your real address, traffic is going through the proxy." to true
            r is RouteResult.Failed && mode != ProxyMode.OFF -> "Couldn't reach the proxy or the check service (${(r as RouteResult.Failed).message}). Lookups will fail until it works, but nothing leaks (fail-closed)." to false
            else -> "No connection (${(r as RouteResult.Failed).message})." to false
        }
    }
}
