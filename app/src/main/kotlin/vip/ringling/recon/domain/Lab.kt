package vip.ringling.recon.domain

/** One observation from a Lab tool: [severity] EXPOSED = a red flag, INFO = worth a look, CLEAN = fine. */
data class Flag(val severity: Severity, val text: String)

enum class Verdict(val label: String) { CLEAN("No red flags"), CHECK("Worth a closer look"), SUSPICIOUS("Suspicious"), FORGED("Likely forged") }

data class HeaderHop(val from: String, val by: String, val ip: String?, val time: String?)

data class HeaderReport(
    val from: String?, val replyTo: String?, val returnPath: String?, val subject: String?, val date: String?,
    val spf: String?, val dkim: String?, val dmarc: String?,
    /** Oldest hop first: the first entry is where the message entered the mail system. */
    val hops: List<HeaderHop>, val flags: List<Flag>, val verdict: Verdict, val leads: List<Pivot>,
)

/** Reads raw e-mail headers and points out spoofing signs. Pure and offline: nothing leaves the phone. */
object HeaderAnalyzer {
    private val addr = Regex("""[A-Za-z0-9._%+'-]+@([A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+)""")
    private val ipv4 = Regex("""\b(?:(?:25[0-5]|2[0-4]\d|1?\d?\d)\.){3}(?:25[0-5]|2[0-4]\d|1?\d?\d)\b""")

    /** Unfolds continuation lines and returns (lowercase name, value) pairs in order. Stops at the first blank line (the body). */
    fun parse(raw: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, StringBuilder>>()
        for (line in raw.replace("\r\n", "\n").lines()) {
            if (line.isBlank()) { if (out.isNotEmpty()) break else continue }
            if (line[0] == ' ' || line[0] == '\t') out.lastOrNull()?.second?.append(' ')?.append(line.trim())
            else if (':' in line) out += line.substringBefore(':').trim().lowercase() to StringBuilder(line.substringAfter(':').trim())
        }
        return out.map { it.first to it.second.toString() }
    }

    /** The address that really sent it: the last <...> group, else the last address-looking text (a quoted display name can contain another address). */
    private fun domainOf(s: String?) = s?.let { addr.findAll(it).lastOrNull()?.groupValues?.get(1)?.lowercase() }
    private fun isPrivate(ip: String) = ip.startsWith("10.") || ip.startsWith("127.") || ip.startsWith("192.168.") || ip.startsWith("169.254.") || Regex("""^172\.(1[6-9]|2\d|3[01])\.""").containsMatchIn(ip)
    /** example.co.uk and mail.example.co.uk count as the same organisation; this keeps the last two labels (good enough for a heuristic). */
    private fun base(d: String?) = d?.split('.')?.takeLast(2)?.joinToString(".")

    fun analyze(raw: String): HeaderReport? {
        val h = parse(raw)
        if (h.none { it.first == "from" || it.first == "received" }) return null
        fun first(n: String) = h.firstOrNull { it.first == n }?.second
        val from = first("from"); val replyTo = first("reply-to"); val returnPath = first("return-path")
        val authAll = h.filter { it.first == "authentication-results" }.joinToString(" ") { it.second }
        fun result(mech: String) = Regex("""\b$mech=(\w+)""", RegexOption.IGNORE_CASE).find(authAll)?.groupValues?.get(1)?.lowercase()
        val spf = result("spf") ?: first("received-spf")?.substringBefore(' ')?.lowercase()
        val dkim = result("dkim"); val dmarc = result("dmarc")

        val hops = h.filter { it.first == "received" }.map { (_, v) ->
            HeaderHop(
                Regex("""from\s+(\S+)""", RegexOption.IGNORE_CASE).find(v)?.groupValues?.get(1).orEmpty(), Regex("""\bby\s+(\S+)""", RegexOption.IGNORE_CASE).find(v)?.groupValues?.get(1).orEmpty(),
                Regex("""\[(\S+?)]""").findAll(v).map { it.groupValues[1] }.firstOrNull { ipv4.matches(it) || ':' in it }, v.substringAfterLast(';', "").trim().ifBlank { null },
            )
        }.reversed()

        val flags = mutableListOf<Flag>()
        fun authFlag(name: String, r: String?) = when (r) {
            null -> {}
            "pass" -> flags += Flag(Severity.CLEAN, "$name passed.")
            "fail", "permerror" -> flags += Flag(Severity.EXPOSED, "$name FAILED: the sending server isn't authorised for this domain.")
            else -> flags += Flag(Severity.INFO, "$name result: $r.")
        }
        authFlag("SPF", spf); authFlag("DKIM", dkim); authFlag("DMARC", dmarc)
        if (spf == null && dkim == null && dmarc == null) flags += Flag(Severity.INFO, "No authentication results in these headers (they may have been trimmed).")

        val fromDomain = domainOf(from)
        // "paypal@bank.com" shown as the name of a message actually sent from another domain.
        val shownAddr = from?.substringBeforeLast('<')?.let { addr.find(it)?.groupValues?.get(1)?.lowercase() }
        if (shownAddr != null && fromDomain != null && base(shownAddr) != base(fromDomain)) flags += Flag(Severity.EXPOSED, "The display name shows an address at $shownAddr but the real sender is $fromDomain.")
        val rp = domainOf(returnPath)
        if (rp != null && fromDomain != null && base(rp) != base(fromDomain)) flags += Flag(if (spf == "pass") Severity.INFO else Severity.EXPOSED, "Return-Path ($rp) differs from the From domain ($fromDomain).")
        val rt = domainOf(replyTo)
        if (rt != null && fromDomain != null && base(rt) != base(fromDomain)) flags += Flag(Severity.EXPOSED, "Replies go to a different domain ($rt) than the sender ($fromDomain).")
        val mid = domainOf(first("message-id"))
        if (mid != null && fromDomain != null && base(mid) != base(fromDomain)) flags += Flag(Severity.INFO, "Message-ID was created by $mid, not $fromDomain (normal for mail services).")

        val bad = flags.count { it.severity == Severity.EXPOSED }
        val verdict = when {
            dmarc == "fail" || bad >= 2 -> Verdict.FORGED
            bad == 1 -> Verdict.SUSPICIOUS
            flags.any { it.severity == Severity.INFO } -> Verdict.CHECK
            else -> Verdict.CLEAN
        }
        val leads = buildList {
            fromDomain?.let { add(Pivot("Sender domain $it", it)) }
            rt?.takeIf { it != fromDomain }?.let { add(Pivot("Reply-To domain $it", it)) }
            hops.firstNotNullOfOrNull { it.ip?.takeIf { ip -> ipv4.matches(ip) && !isPrivate(ip) } }?.let { add(Pivot("Origin IP $it", it)) }
        }
        return HeaderReport(from, replyTo, returnPath, first("subject"), first("date"), spf, dkim, dmarc, hops, flags, verdict, leads)
    }
}

/** Inspects a redirect chain for the usual link tricks. Pure; [LinkTracer] gathers the chain. */
object LinkAnalysis {
    private val shorteners = setOf("bit.ly", "t.co", "tinyurl.com", "goo.gl", "ow.ly", "is.gd", "buff.ly", "rebrand.ly", "cutt.ly", "shorturl.at", "t.ly", "rb.gy", "lnkd.in")
    private val ipHost = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

    fun host(url: String): String? = runCatching { java.net.URI(url.trim()).host?.lowercase() }.getOrNull()

    fun analyze(chain: List<String>, failed: Boolean = false): List<Flag> {
        if (chain.isEmpty()) return emptyList()
        val flags = mutableListOf<Flag>()
        val hosts = chain.mapNotNull(::host)
        val first = chain.first(); val last = chain.last(); val lastHost = hosts.lastOrNull()
        if (hosts.firstOrNull() in shorteners) flags += Flag(Severity.INFO, "Starts at a link shortener (${hosts.first()}): the real destination is hidden until followed.")
        if (chain.size - 1 >= 4) flags += Flag(Severity.INFO, "${chain.size - 1} redirects in a row: unusually long chain.")
        if (hosts.distinct().size > 2) flags += Flag(Severity.INFO, "Passes through ${hosts.distinct().size} different sites on the way.")
        if (lastHost != null && ipHost.matches(lastHost)) flags += Flag(Severity.EXPOSED, "Ends at a bare IP address, not a named site.")
        if (hosts.any { it.contains("xn--") }) flags += Flag(Severity.EXPOSED, "Uses an internationalised (xn--) domain, a common look-alike trick.")
        if (chain.any { runCatching { java.net.URI(it).userInfo != null }.getOrDefault(false) }) flags += Flag(Severity.EXPOSED, "Contains user@host trickery: the text before the @ is not the site.")
        if (last.startsWith("http://", true)) flags += Flag(Severity.INFO, "Final page is not encrypted (http, not https).")
        if (lastHost != null && lastHost.count { it == '.' } >= 4) flags += Flag(Severity.INFO, "Very long host name ($lastHost); the real domain is its last two parts.")
        if (failed) flags += Flag(Severity.INFO, "The chain stopped early (timeout or error), so the final destination is unknown.")
        if (flags.isEmpty()) flags += Flag(Severity.CLEAN, "No common link tricks found. That doesn't prove the destination is safe.")
        if (first.startsWith("http://", true) && flags.none { it.text.startsWith("Final page") }) flags += Flag(Severity.INFO, "The link you were given is not encrypted (http).")
        return flags
    }
}
