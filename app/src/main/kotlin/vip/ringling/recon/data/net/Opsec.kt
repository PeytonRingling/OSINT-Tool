package vip.ringling.recon.data.net

/**
 * OPSEC mode: nothing Recon does may reveal this phone's own address to a host the target could control. Scan modules only ask third-party
 * services, so this governs the three things that can reach a target's own server: following a link (the Lab's inspector), loading remote
 * photos (profile avatars are hosted wherever the profile says), and opening links in a browser (which doesn't use Recon's proxy).
 */
object Opsec {
    @Volatile var on: Boolean = false

    /** Why Recon must not contact a target-controlled host right now, or null when it may (OPSEC off, or traffic goes through a proxy). */
    fun blockReason(on: Boolean = this.on, proxy: java.net.Proxy? = ProxyState.proxy): String? =
        if (on && proxy == null) "OPSEC mode is on and Recon isn't using Tor or a proxy, so it won't contact this site from your own address. Turn on Tor (System → Privacy & network) or check the domain only." else null
}
