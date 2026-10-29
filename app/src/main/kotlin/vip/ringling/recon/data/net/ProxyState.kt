package vip.ringling.recon.data.net

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

enum class ProxyMode(val label: String) { OFF("Off"), TOR("Tor (Orbot)"), CUSTOM("Custom SOCKS5") }

/**
 * Process-wide outbound proxy for every lookup. Fail-closed: if the proxy is unreachable, requests fail instead of
 * silently going out directly. Hostnames are handed to the SOCKS proxy unresolved, so DNS does not leak locally.
 */
object ProxyState {
    /** Where traffic goes while the saved proxy setting is still unknown (process start): a dead local port, so nothing leaves directly. */
    internal val UNSET: Proxy = socks("127.0.0.1", 9)

    @Volatile var proxy: Proxy? = UNSET
        private set

    /** False until [apply] has run with the saved setting. */
    val ready: Boolean get() = proxy !== UNSET

    /** Back to the process-start state (tests). */
    internal fun reset() { proxy = UNSET }

    fun apply(mode: ProxyMode, host: String, port: Int) {
        proxy = when (mode) {
            ProxyMode.OFF -> null
            ProxyMode.TOR -> socks("127.0.0.1", 9050)
            ProxyMode.CUSTOM -> if (host.isNotBlank() && port in 1..65535) socks(host.trim(), port) else socks("127.0.0.1", 9) // invalid: fail closed (a fresh instance, so [ready] stays true)
        }
    }

    private fun socks(host: String, port: Int) = Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved(host, port))

    val selector: ProxySelector = object : ProxySelector() {
        override fun select(uri: URI?): List<Proxy> = listOf(proxy ?: Proxy.NO_PROXY)
        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {}
    }
}
