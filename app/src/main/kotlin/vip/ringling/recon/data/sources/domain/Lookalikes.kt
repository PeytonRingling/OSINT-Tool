package vip.ringling.recon.data.sources.domain

import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import vip.ringling.recon.data.net.Endpoints
import vip.ringling.recon.data.net.Http
import vip.ringling.recon.domain.Confidence
import vip.ringling.recon.domain.Fact
import vip.ringling.recon.domain.Finding
import vip.ringling.recon.domain.ModuleId
import vip.ringling.recon.domain.PaidAction
import vip.ringling.recon.domain.PaidLookups
import vip.ringling.recon.domain.Pivot
import vip.ringling.recon.domain.ScanInput
import vip.ringling.recon.domain.Severity

/** Generates look-alike (typosquat) variants of a domain. Pure and deterministic, so it is unit-tested. */
object LookalikeGenerator {
    private val tlds = listOf("com", "net", "org", "co", "io", "app", "xyz", "info", "biz", "online")
    private val twoPartSuffixes = setOf("co.uk", "org.uk", "com.au", "co.nz", "co.jp", "com.br", "co.in", "com.mx")
    private val homoglyphs = mapOf('o' to "0", 'l' to "1i", 'i' to "1l", 'a' to "4", 'e' to "3", 's' to "5", 'g' to "9q", 'b' to "6", 'm' to "rn", 'w' to "vv", 'd' to "cl")
    private val keyboard = mapOf(
        'q' to "wa", 'w' to "qes", 'e' to "wrd", 'r' to "etf", 't' to "ryg", 'y' to "tuh", 'u' to "yij", 'i' to "uok", 'o' to "ipl", 'p' to "o",
        'a' to "qsz", 's' to "awdx", 'd' to "sefc", 'f' to "drgv", 'g' to "fthb", 'h' to "gyjn", 'j' to "hukm", 'k' to "jil", 'l' to "ko",
        'z' to "ax", 'x' to "zsc", 'c' to "xdv", 'v' to "cfb", 'b' to "vgn", 'n' to "bhm", 'm' to "nj",
    )

    /** Splits "login.example.co.uk" into ("example", "co.uk") by keeping the registrable part. */
    fun split(domain: String): Pair<String, String>? {
        val labels = domain.lowercase().trim('.').split('.').filter { it.isNotEmpty() }
        if (labels.size < 2) return null
        val suffix2 = labels.takeLast(2).joinToString(".")
        return if (suffix2 in twoPartSuffixes && labels.size >= 3) labels[labels.size - 3] to suffix2 else labels[labels.size - 2] to labels.last()
    }

    private fun valid(label: String) = label.length in 1..63 && !label.startsWith("-") && !label.endsWith("-") && label.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }

    fun generate(domain: String, max: Int = 150): List<String> {
        val (sld, tld) = split(domain) ?: return emptyList()
        val original = "$sld.$tld"
        val out = LinkedHashSet<String>()
        fun add(label: String, t: String = tld) { if (valid(label) && "$label.$t" != original) out += "$label.$t" }
        tlds.filter { it != tld }.forEach { add(sld, it) }
        for (i in sld.indices) add(sld.removeRange(i, i + 1))                                           // omission
        for (i in 0 until sld.length - 1) if (sld[i] != sld[i + 1]) add(sld.substring(0, i) + sld[i + 1] + sld[i] + sld.substring(i + 2)) // transposition
        for (i in sld.indices) add(sld.substring(0, i + 1) + sld[i] + sld.substring(i + 1))             // repetition
        for (i in sld.indices) homoglyphs[sld[i]]?.let { subs -> subs.forEach { add(sld.substring(0, i) + it + sld.substring(i + 1)) } } // homoglyph
        for (i in 1 until sld.length) add(sld.substring(0, i) + "-" + sld.substring(i))                  // hyphenation
        for (i in sld.indices) if (sld[i] in "aeiou") "aeiou".filter { it != sld[i] }.forEach { add(sld.substring(0, i) + it + sld.substring(i + 1)) } // vowel swap
        for (i in sld.indices) keyboard[sld[i]]?.forEach { add(sld.substring(0, i) + it + sld.substring(i + 1)) } // adjacent key
        return out.take(max)
    }
}

/** Opt-in, free look-alike check: asks Cloudflare DNS (DoH) which generated variants resolve. Nothing is sent to the variants or the target. */
class LookalikeLookup @Inject constructor(http: Http, endpoints: Endpoints) : PaidLookups {
    private val doh = DohClient(http, endpoints.doh)

    override suspend fun runPaid(actionId: String, input: ScanInput): List<Finding> {
        if (actionId != ACTION) return emptyList()
        val variants = LookalikeGenerator.generate(input.raw)
        if (variants.isEmpty()) return listOf(Finding("DOMAIN-lookalikes-error", ModuleId.DOMAIN, "Look-alike check unavailable", "That doesn't look like a registrable domain.", Severity.INFO))
        val gate = Semaphore(8)
        val results = try {
            coroutineScope {
                variants.map { v ->
                    async { gate.withPermit { v to runCatching { doh.query(v, DnsType.A).map { it.data } }.getOrNull() } }
                }.awaitAll()
            }
        } catch (e: CancellationException) { throw e }
        val answered = results.filter { it.second != null }
        if (answered.size < results.size / 2) return listOf(Finding("DOMAIN-lookalikes-error", ModuleId.DOMAIN, "Look-alike check unavailable", "Most DNS lookups failed (${results.size - answered.size} of ${results.size}). Try again.", Severity.INFO))
        val live = answered.filter { it.second!!.isNotEmpty() }.map { it.first to it.second!! }
        return listOf(
            Finding(
                id = "DOMAIN-lookalikes", module = ModuleId.DOMAIN,
                title = if (live.isEmpty()) "No look-alike domains resolve" else "${live.size} look-alike domain${if (live.size == 1) "" else "s"} resolve",
                detail = (if (live.isEmpty()) "None of ${variants.size} generated variants of ${input.raw} resolve right now."
                else "${live.size} of ${variants.size} generated variants of ${input.raw} resolve. A resolving name is not proof of abuse: check each before drawing a conclusion.") +
                    "\nVariants are typo, homoglyph, TLD-swap and similar spellings. Only DNS was asked.",
                severity = if (live.isEmpty()) Severity.CLEAN else Severity.INFO, confidence = Confidence.MEDIUM,
                facts = live.take(40).map { (v, ips) -> Fact(v, ips.take(2).joinToString(", ")) },
                pivots = live.take(10).map { Pivot("Scan ${it.first}", it.first) },
            ),
        )
    }

    companion object {
        const val ACTION = "lookalikes"

        fun offer(domain: String): Finding = Finding(
            id = "DOMAIN-lookalikes-offer", module = ModuleId.DOMAIN, title = "Look-alike domains (optional)",
            detail = "Check which typo and look-alike versions of $domain exist. Free: about 100 DNS lookups through Cloudflare. Nothing is sent to those domains.",
            severity = Severity.INFO, tags = listOf("Optional"),
            paid = PaidAction(
                id = ACTION, label = "Check look-alike domains", cost = "Free (about 100 DNS lookups)",
                explanation = "Generates typo, homoglyph and TLD variants of $domain and asks Cloudflare's DNS-over-HTTPS service whether each one resolves. The variants and your query go to Cloudflare only; nothing is sent to the domains themselves.",
            ),
        )
    }
}
