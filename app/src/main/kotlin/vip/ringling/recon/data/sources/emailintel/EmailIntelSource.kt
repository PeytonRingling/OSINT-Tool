package vip.ringling.recon.data.sources.emailintel

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import vip.ringling.recon.data.net.Endpoints
import vip.ringling.recon.data.net.Http
import vip.ringling.recon.data.sources.domain.DnsType
import vip.ringling.recon.data.sources.domain.DohClient
import vip.ringling.recon.domain.Confidence
import vip.ringling.recon.domain.Fact
import vip.ringling.recon.domain.Finding
import vip.ringling.recon.domain.InputType
import vip.ringling.recon.domain.Link
import vip.ringling.recon.domain.ModuleId
import vip.ringling.recon.domain.OsintSource
import vip.ringling.recon.domain.Pivot
import vip.ringling.recon.domain.ScanInput
import vip.ringling.recon.domain.Severity

fun interface DisposableDomains { fun contains(domain: String): Boolean }

/** Bundled community blocklist (disposable-email-domains, CC0), loaded once. */
@Singleton
class AssetDisposableDomains @Inject constructor(@ApplicationContext private val context: Context) : DisposableDomains {
    private val set by lazy { runCatching { context.assets.open("disposable_domains.txt").bufferedReader().useLines { l -> l.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toHashSet() } }.getOrDefault(hashSetOf()) }
    override fun contains(domain: String) = domain.lowercase().let { d -> generateSequence(d) { it.substringAfter('.', "").takeIf { r -> '.' in r } }.any { it in set } }
}

/** Structure of the address itself: provider, disposable/role flags, +tag and Gmail-dot tricks, and the mail host behind the domain. */
class EmailIntelSource @Inject constructor(
    http: Http, endpoints: Endpoints, private val disposable: DisposableDomains,
) : OsintSource {
    override val id = ModuleId.EMAILINTEL
    override val supportedInputTypes = setOf(InputType.EMAIL)
    private val doh = DohClient(http, endpoints.doh)

    override suspend fun run(input: ScanInput): Flow<Finding> = flow {
        val mx = runCatching { doh.query(input.raw.substringAfter('@'), DnsType.MX).map { it.data.substringAfter(' ').trimEnd('.').lowercase() } }.getOrNull()
        emit(EmailAnalysis.analyze(input.raw, disposable.contains(input.raw.substringAfter('@')), mx))
    }
}

object EmailAnalysis {
    private val free = setOf("gmail.com", "googlemail.com", "outlook.com", "hotmail.com", "live.com", "msn.com", "yahoo.com", "ymail.com", "aol.com", "icloud.com", "me.com", "mac.com", "proton.me", "protonmail.com", "pm.me", "gmx.com", "gmx.net", "mail.com", "zoho.com", "yandex.com", "yandex.ru", "mail.ru", "fastmail.com", "hey.com", "tutanota.com", "tuta.com")
    private val roles = setOf("admin", "administrator", "info", "support", "sales", "contact", "help", "billing", "abuse", "postmaster", "webmaster", "hostmaster", "noreply", "no-reply", "security", "office", "hr", "jobs", "press", "marketing", "team", "hello")
    private val providers = listOf(
        "google.com" to "Google Workspace / Gmail", "googlemail.com" to "Google Workspace / Gmail", "outlook.com" to "Microsoft 365 / Outlook", "protection.outlook.com" to "Microsoft 365 / Outlook",
        "protonmail.ch" to "Proton Mail", "zoho." to "Zoho Mail", "icloud.com" to "Apple iCloud Mail", "fastmail.com" to "Fastmail", "messagingengine.com" to "Fastmail",
        "yahoodns.net" to "Yahoo Mail", "mimecast.com" to "Mimecast gateway", "pphosted.com" to "Proofpoint gateway", "mailgun.org" to "Mailgun", "secureserver.net" to "GoDaddy", "mxroute.com" to "MXroute", "improvmx.com" to "ImprovMX forwarder", "forwardemail.net" to "Forward Email",
    )

    fun provider(mx: List<String>): String? = mx.firstNotNullOfOrNull { h -> providers.firstOrNull { h.contains(it.first) }?.second }

    fun analyze(email: String, isDisposable: Boolean, mx: List<String>?): Finding {
        val local = email.substringBefore('@').lowercase(); val domain = email.substringAfter('@').lowercase()
        val base = local.substringBefore('+')
        val tag = local.substringAfter('+', "").takeIf { it.isNotEmpty() }
        val gmail = domain == "gmail.com" || domain == "googlemail.com"
        val canonical = if (gmail) base.replace(".", "") + "@gmail.com" else null
        val isFree = domain in free
        val isRole = base in roles
        val prov = mx?.let(::provider)
        val noMail = mx != null && mx.isEmpty()
        val flags = listOfNotNull(
            if (isDisposable) "Disposable domain" else null, if (isFree) "Free provider" else if (!isDisposable) "Custom domain" else null,
            if (isRole) "Role account" else null, if (tag != null) "+tag alias" else null, if (noMail) "No MX records" else null,
        )
        val detail = buildList {
            add(flags.joinToString(" · "))
            if (isDisposable) add("Throwaway addresses are used to dodge tracking, so absence from breach data means little.")
            if (noMail) add("The domain has no mail servers: this address cannot receive mail.")
            if (tag != null) add("Sub-address: mail to \"$base+$tag\" lands in \"$base@$domain\". The tag \"$tag\" often names the site that was given this address.")
            if (isRole) add("A shared role mailbox, not a person.")
            if (prov != null) add("Mail is handled by $prov.")
        }.joinToString("\n")
        val pivots = buildList {
            if (base.length >= 3 && !isRole && '@' !in base) add(Pivot("Search username $base", base))
            if (canonical != null && canonical != "$base@$domain") add(Pivot("Breach check $canonical", canonical))
            if (tag != null) add(Pivot("Breach check $base@$domain", "$base@$domain"))
            if (!isFree && !isDisposable) add(Pivot("Domain intel $domain", domain))
        }
        val enc = URLEncoder.encode(email, "UTF-8")
        return Finding(
            "EMAILINTEL-address", ModuleId.EMAILINTEL, if (isDisposable) "Disposable address" else "Address analysis",
            detail, if (isDisposable || noMail) Severity.EXPOSED else Severity.INFO, confidence = Confidence.HIGH, tags = flags,
            facts = listOfNotNull(
                Fact("Local part", base), Fact("Domain", domain), tag?.let { Fact("Sub-address tag", it) }, canonical?.let { Fact("Canonical Gmail address", it) },
                prov?.let { Fact("Mail provider", it) }, mx?.takeIf { it.isNotEmpty() }?.let { Fact("MX hosts", it.take(4).joinToString("\n")) },
            ),
            pivots = pivots,
            links = listOf(
                Link("Epieos (account lookup, opens their site)", "https://epieos.com/?q=$enc&t=email"), Link("Search the address", "https://www.google.com/search?q=%22$enc%22"),
                Link("Search in pastes", "https://www.google.com/search?q=site:pastebin.com+%22$enc%22"), Link("Hunter.io domain search", "https://hunter.io/search/$domain"),
            ),
        )
    }
}
