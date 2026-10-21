package vip.ringling.recon.data.mock

import vip.ringling.recon.domain.Confidence
import vip.ringling.recon.domain.Link
import vip.ringling.recon.domain.Finding
import vip.ringling.recon.domain.GeoPoint
import vip.ringling.recon.domain.InputType
import vip.ringling.recon.domain.ModuleId
import vip.ringling.recon.domain.ScanInput
import vip.ringling.recon.domain.RiskScore
import vip.ringling.recon.domain.ScanRecord
import vip.ringling.recon.domain.Severity

/** Phase 1 only: realistic-looking canned results. All identities are fictional. */
object MockData {
    private fun f(
        key: String, module: ModuleId, title: String, detail: String, sev: Severity,
        url: String? = null, raw: String = "", conf: Confidence? = null,
    ) = Finding("$module-$key", module, title, detail, sev, url, raw.ifEmpty { """{"source":"mock","key":"$key"}""" }, conf)

    fun breach(input: ScanInput): List<Finding> = when (input.type) {
        InputType.EMAIL -> listOf(
            f("adobe", ModuleId.BREACH, "Adobe · 2013", "152M accounts breached · Oct 4, 2013\nExposed: email addresses, password hints, passwords, usernames.\nIn late 2013 a large account database was stolen. It held encrypted passwords and unencrypted hints.\nWhat to do:\n• Change this account's password, and any other account where you reused it. Turn on two-factor authentication and use a password manager so every password is unique.", Severity.EXPOSED, "https://haveibeenpwned.com/PwnedWebsites#Adobe", """{"Name":"Adobe","BreachDate":"2013-10-04","PwnCount":152445165,"DataClasses":["Email addresses","Password hints","Passwords","Usernames"]}""")
                .copy(
                    tags = listOf("Email addresses", "Password hints", "Passwords", "Usernames"), hotTags = setOf("Passwords", "Password hints"),
                    facts = listOf(
                        vip.ringling.recon.domain.Fact("Breach date", "Oct 4, 2013"), vip.ringling.recon.domain.Fact("Added to HIBP", "Dec 4, 2013"),
                        vip.ringling.recon.domain.Fact("Accounts affected", "152,445,165"), vip.ringling.recon.domain.Fact("Website", "adobe.com"),
                        vip.ringling.recon.domain.Fact("Verified", "Yes"),
                    ),
                ),
            f("linkedin", ModuleId.BREACH, "LinkedIn · 2012", "164M accounts. Exposed: email, password (unsalted SHA-1).", Severity.EXPOSED, "https://haveibeenpwned.com/PwnedWebsites#LinkedIn", """{"Name":"LinkedIn","BreachDate":"2012-05-05","PwnCount":164611595,"DataClasses":["Email addresses","Passwords"]}"""),
            f("canva", ModuleId.BREACH, "Canva · 2019", "137M accounts. Exposed: email, name, city, bcrypt password.", Severity.EXPOSED, "https://haveibeenpwned.com/PwnedWebsites#Canva", """{"Name":"Canva","BreachDate":"2019-05-24","PwnCount":137272116}"""),
            f("dropbox", ModuleId.BREACH, "Dropbox · 2012", "68M accounts. Exposed: email, bcrypt password.", Severity.INFO, "https://haveibeenpwned.com/PwnedWebsites#Dropbox", """{"Name":"Dropbox","BreachDate":"2012-07-01","PwnCount":68648009}"""),
            f("leakcheck", ModuleId.BREACH, "LeakCheck · 3 sources", "Found in 3 compiled combo lists. Last seen 2023-08.", Severity.INFO, null, """{"success":true,"found":3,"sources":["Collection #1","Naz.API","Combolist 2023"]}"""),
            f("pastes", ModuleId.BREACH, "No public pastes", "Not seen in any paste in the HIBP paste index.", Severity.CLEAN),
        )
        InputType.PHONE -> listOf(
            f("lc-phone", ModuleId.BREACH, "LeakCheck · 1 source", "Number appears in a 2021 social-network scrape alongside a display name.", Severity.EXPOSED, null, """{"found":1,"sources":["Facebook 2021 scrape"]}"""),
        )
        InputType.USERNAME -> listOf(
            f("lc-user", ModuleId.BREACH, "LeakCheck · 2 sources", "Handle appears in 2 gaming-forum dumps (2016, 2019).", Severity.INFO, null, """{"found":2}"""),
        )
        else -> emptyList()
    }

    fun identity(input: ScanInput): List<Finding> = when (input.type) {
        InputType.USERNAME -> listOf(
            f("github", ModuleId.IDENTITY, "GitHub", "Profile exists · 23 public repos · joined 2017.", Severity.EXPOSED, "https://github.com/${input.raw}", conf = Confidence.HIGH),
            f("reddit", ModuleId.IDENTITY, "Reddit", "u/${input.raw} · 4,212 karma · active in r/netsec, r/hiking.", Severity.EXPOSED, "https://reddit.com/user/${input.raw}", conf = Confidence.HIGH),
            f("steam", ModuleId.IDENTITY, "Steam", "Public profile · 112 games · last online 3 days ago.", Severity.INFO, "https://steamcommunity.com/id/${input.raw}", conf = Confidence.MEDIUM),
            f("twitch", ModuleId.IDENTITY, "Twitch", "Channel exists · 38 followers.", Severity.INFO, "https://twitch.tv/${input.raw}", conf = Confidence.MEDIUM),
            f("medium", ModuleId.IDENTITY, "Medium", "Possible match — response size consistent with a profile page.", Severity.INFO, "https://medium.com/@${input.raw}", conf = Confidence.LOW),
            f("none", ModuleId.IDENTITY, "38 sites with no account", "Checked 42 curated sites; no registration found on the rest.", Severity.CLEAN),
        )
        InputType.EMAIL -> listOf(
            f("spotify", ModuleId.IDENTITY, "Spotify", "Email is registered (signup flow reports 'already in use').", Severity.INFO, null, conf = Confidence.HIGH),
            f("adobe-reg", ModuleId.IDENTITY, "Adobe", "Account exists for this address.", Severity.INFO, null, conf = Confidence.MEDIUM),
            f("pinterest", ModuleId.IDENTITY, "Pinterest", "Reset flow suggests a registered account.", Severity.INFO, null, conf = Confidence.LOW),
            f("rest", ModuleId.IDENTITY, "6 checks negative", "No registration found on the remaining curated sites.", Severity.CLEAN),
        )
        InputType.PHONE -> listOf(
            f("whatsapp", ModuleId.IDENTITY, "WhatsApp", "Number is registered.", Severity.INFO, null, conf = Confidence.HIGH),
            f("telegram", ModuleId.IDENTITY, "Telegram", "Number is registered.", Severity.INFO, null, conf = Confidence.MEDIUM),
            f("rest-phone", ModuleId.IDENTITY, "3 checks negative", "No registration found on the remaining curated sites.", Severity.CLEAN),
        )
        else -> emptyList()
    }

    fun domain(input: ScanInput): List<Finding> = listOf(
        f("a", ModuleId.DOMAIN, "A / AAAA", "104.21.44.9 · 172.67.152.30 · 2606:4700:3030::6815:2c09", Severity.INFO, null, """{"A":["104.21.44.9","172.67.152.30"],"AAAA":["2606:4700:3030::6815:2c09"]}"""),
        f("mx", ModuleId.DOMAIN, "MX", "aspmx.l.google.com (1) · alt1.aspmx.l.google.com (5) — Google Workspace.", Severity.INFO),
        f("ns", ModuleId.DOMAIN, "NS", "gina.ns.cloudflare.com · max.ns.cloudflare.com", Severity.INFO),
        f("spf", ModuleId.DOMAIN, "SPF", "v=spf1 include:_spf.google.com ~all — soft-fail policy.", Severity.CLEAN),
        f("dmarc", ModuleId.DOMAIN, "DMARC", "v=DMARC1; p=none; rua=mailto:d@${input.raw} — monitoring only, spoofed mail is not rejected.", Severity.EXPOSED, null, """{"record":"v=DMARC1; p=none","policy":"none"}"""),
        f("dkim", ModuleId.DOMAIN, "DKIM", "Selector 'google' found. 'default', 's1', 'selector1' not found.", Severity.CLEAN),
        f("bimi", ModuleId.DOMAIN, "BIMI", "No default._bimi record.", Severity.INFO),
        f("mta", ModuleId.DOMAIN, "MTA-STS", "No _mta-sts record; inbound TLS is opportunistic.", Severity.INFO),
        f("subs", ModuleId.DOMAIN, "14 subdomains (crt.sh)", "api, staging, vpn, grafana, jira, … Staging and grafana look internal.", Severity.EXPOSED, "https://crt.sh/?q=${input.raw}", """{"count":14,"names":["api","staging","vpn","grafana","jira"]}"""),
        f("rdap", ModuleId.DOMAIN, "RDAP", "Registrar: Cloudflare, Inc. · created 2019-03-11 · expires 2027-03-11 · privacy-redacted.", Severity.CLEAN, "https://rdap.org/domain/${input.raw}"),
    )

    /** Fictional placeholder records (no real people); real registry data is only ever fetched live. */
    fun registry(input: ScanInput): List<Finding> {
        fun r(i: Int, name: String, addr: String, status: String, offenses: List<String>, tags: List<String>, sev: Severity, lat: Double, lng: Double, note: String? = null) =
            Finding(
                id = "REGISTRY-ID-$i", module = ModuleId.REGISTRY, title = name,
                detail = buildList {
                    add("$addr, BOISE, ADA County, ${input.raw}")
                    add("Status: $status · Last registered: Mar 15, 2026")
                    add("Offenses (${offenses.size}):")
                    offenses.forEach { add("• $it") }
                    note?.let { add(it) }
                }.joinToString("\n"),
                severity = Severity.INFO, hotTags = setOf("Non-compliant", "Multiple convictions"), url = "https://apps.isp.idaho.gov/sor_id/", raw = "", confidence = Confidence.HIGH,
                geo = GeoPoint(lat, lng), tags = tags,
            )
        return listOf(
            Finding("REGISTRY-summary", ModuleId.REGISTRY, "12 matches in ${input.raw}", "Matches are by location only. The registry warns that records may not relate to the person you have in mind; verify before relying on any of this.", Severity.INFO, "https://apps.isp.idaho.gov/sor_id/search_zip.html"),
            r(0, "SAMPLE, RECORD A", "100 FICTION AVE", "Compliant", listOf("18-0000 – FICTIONAL OFFENSE ONE – Jun 01, 2010 – ADA CO", "18-0001 – FICTIONAL OFFENSE TWO – Aug 12, 2014 – ADA CO"), listOf("Adult registry", "Multiple convictions"), Severity.EXPOSED, 43.6150, -116.2020, "Convictions on 2 separate dates. The registry does not state whether this is a repeat offence; this is inferred from the dates."),
            r(1, "EXAMPLE, RECORD B", "200 PLACEHOLDER ST", "Non Comp", listOf("18-0002 – FICTIONAL OFFENSE THREE – Feb 03, 2009 – ADA CO"), listOf("Adult registry", "Non-compliant"), Severity.EXPOSED, 43.6090, -116.2200),
            r(2, "PLACEHOLDER, RECORD C", "300 SAMPLE BLVD", "Compliant", listOf("18-0003 – FICTIONAL OFFENSE FOUR – Nov 20, 2016 – ADA CO"), listOf("Adult registry"), Severity.INFO, 43.6260, -116.1900),
            r(3, "FICTIONAL, RECORD D", "400 EXAMPLE CT", "Compliant", listOf("18-0004 – FICTIONAL OFFENSE FIVE – May 09, 2018 – ADA CO"), listOf("Juvenile registry"), Severity.INFO, 43.5900, -116.2500),
        ) + (4..11).map { i ->
            r(i, "SAMPLE, RECORD ${('A' + i)}", "${100 * i} MOCK ST", "Compliant", listOf("18-00${i.toString().padStart(2, '0')} – FICTIONAL OFFENSE – Jan 0${i % 9 + 1}, 20${10 + i} – ADA CO"), listOf("Adult registry"), Severity.INFO, 43.58 + i * 0.006, -116.30 + (i % 5) * 0.035)
        }
    }

    /** Clearly fictional public-record matches for screenshots only. */
    fun people(input: ScanInput): List<Finding> {
        if (input.type != InputType.PERSON) return emptyList()
        fun hit(id: String, title: String, detail: String, tag: String) =
            Finding("PEOPLE-hit-$id", ModuleId.PEOPLE, title, detail + "\nMatched by name only: common names return other people, so confirm it's the right person before relying on it.", Severity.INFO, url = "https://example.org/$id", confidence = Confidence.LOW, tags = listOf(tag))
        return listOf(
            hit("wd-1", input.raw, "Fictional scientist and writer (1950–2020)", "Wikidata (public figures)"),
            hit("orcid-1", input.raw, "ORCID researcher profile · Example University", "ORCID (researchers)"),
            hit("court-1", "Example v. ${input.raw.substringAfterLast(' ')}", "U.S. District Court, D. Idaho · filed 2019-04-02 · docket 1:19-cv-00123", "Court dockets"),
            hit("court-2", "${input.raw.substringAfterLast(' ')} v. Placeholder Corp.", "U.S. District Court, D. Idaho · filed 2021-09-14 · docket 1:21-cv-00456", "Court dockets"),
            hit("fec-1", "\$250 to EXAMPLE PAC", "2022-10-03 · Boise, ID · Placeholder Co. · Engineer", "FEC donations"),
            hit("npi-1", "${input.raw}, MD", "Licensed healthcare provider (NPI 1999999991) · Internal Medicine · Boise, ID", "NPI (healthcare providers)"),
            Finding("PEOPLE-links", ModuleId.PEOPLE, "Official and public portals", "Open these to search by name yourself.", Severity.INFO, links = listOf(Link("Federal court cases (PACER Case Locator)", "https://pcl.uscourts.gov/"))),
            vip.ringling.recon.data.sources.people.PeopleParsers.finders(input.raw),
        )
    }

    fun forModule(module: ModuleId, input: ScanInput): List<Finding> = when (module) {
        ModuleId.BREACH -> breach(input)
        ModuleId.IDENTITY -> identity(input)
        ModuleId.PHONE -> emptyList()
        ModuleId.IMAGE, ModuleId.NETWORK, ModuleId.CRYPTO, ModuleId.CODE, ModuleId.EMAILINTEL, ModuleId.DORKS -> emptyList()
        ModuleId.DOMAIN -> domain(input)
        ModuleId.REGISTRY -> registry(input)
        ModuleId.PEOPLE -> people(input)
    }

    fun allFor(input: ScanInput): List<Finding> = ModuleId.entries.flatMap { forModule(it, input) }

    fun history(now: Long): List<ScanRecord> {
        val h = 3_600_000L
        fun rec(id: String, raw: String, type: InputType, ago: Long, legacy: Boolean = false): ScanRecord {
            val input = ScanInput(raw, type)
            return ScanRecord(id, input, now - ago, allFor(input).filter { it.module in modulesFor(type) }, scoreVersion = if (legacy) 0 else RiskScore.VERSION)
        }
        return listOf(
            rec("h1", "alex.moreno@proton.me", InputType.EMAIL, 2 * h),
            rec("h2", "northwind-labs.io", InputType.DOMAIN, 20 * h),
            rec("h3", "ghost_in_shell", InputType.USERNAME, 27 * h),
            rec("h4", "83702", InputType.ZIP, 52 * h),
            rec("h5", "+1 208 555 0142", InputType.PHONE, 96 * h),
            rec("h6", "sam.okafor@northwind-labs.io", InputType.EMAIL, 170 * h, legacy = true),
            rec("h7", "kestrel_9", InputType.USERNAME, 340 * h),
        )
    }

    fun modulesFor(type: InputType): Set<ModuleId> = when (type) {
        InputType.EMAIL -> setOf(ModuleId.BREACH, ModuleId.IDENTITY)
        InputType.PHONE -> setOf(ModuleId.BREACH, ModuleId.PHONE)
        InputType.USERNAME -> setOf(ModuleId.BREACH, ModuleId.IDENTITY)
        InputType.DOMAIN -> setOf(ModuleId.DOMAIN)
        InputType.ZIP -> setOf(ModuleId.REGISTRY)
        InputType.IMAGE -> setOf(ModuleId.IMAGE)
        InputType.IP -> setOf(ModuleId.NETWORK)
        InputType.WALLET -> setOf(ModuleId.CRYPTO)
        InputType.PERSON -> setOf(ModuleId.PEOPLE)
        InputType.ADDRESS -> setOf(ModuleId.REGISTRY)
    }
}
