package vip.ringling.recon.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import vip.ringling.recon.data.sources.breach.BreachAdvice
import vip.ringling.recon.data.sources.breach.HibpBreach
import vip.ringling.recon.data.sources.breach.HibpMapper
import vip.ringling.recon.data.sources.breach.HibpPaste
import vip.ringling.recon.data.sources.breach.LeakCheckMapper
import vip.ringling.recon.data.sources.breach.LeakCheckResult
import vip.ringling.recon.data.sources.breach.LeakSource
import vip.ringling.recon.domain.Severity

class BreachDetailTest {
    private val breaches = Json { ignoreUnknownKeys = true }.decodeFromString<List<HibpBreach>>(fixture("hibp_breaches.json"))

    @Test fun hibpBreachCarriesEverythingTheDetailScreenShows() {
        val f = HibpMapper.breachFinding(breaches.first { it.name == "Adobe" })
        val lines = f.detail.lines()
        assertEquals("152M accounts breached · Oct 4, 2013", lines[0])
        assertEquals("Exposed: email addresses, password hints, passwords, usernames.", lines[1])
        assertTrue(lines[2].startsWith("In late 2013, a large account database was stolen"), "HTML stripped: ${lines[2]}")
        assertTrue("<" !in f.detail, "no markup leaks into the UI")
        assertTrue("What to do:" in lines && lines.any { it.startsWith("• Change this account's password") }, f.detail)
        val facts = f.facts.associate { it.label to it.value }
        assertEquals("Oct 4, 2013", facts["Breach date"]); assertEquals("Dec 4, 2013", facts["Added to HIBP"])
        assertEquals("152,445,165", facts["Accounts affected"]); assertEquals("adobe.com", facts["Website"]); assertEquals("Yes", facts["Verified"])
        assertEquals(listOf("Email addresses", "Password hints", "Passwords", "Usernames"), f.tags)
        assertEquals(setOf("Passwords", "Password hints"), f.hotTags, "only credential-type data is flagged")
        assertEquals("https://haveibeenpwned.com/Content/Images/PwnedLogos/Adobe.png", f.imageUrl)
        assertEquals(Severity.EXPOSED, f.severity)
    }

    @Test fun emailOnlyBreachIsInfoWithPhishingAdvice() {
        val f = HibpMapper.breachFinding(breaches.first { it.name == "Dropbox" })
        assertEquals(Severity.INFO, f.severity); assertTrue(f.hotTags.isEmpty())
        assertTrue(f.detail.lines().any { it.contains("phishing") }, f.detail)
        assertTrue(f.detail.lines().none { it.contains("Change this account's password") })
        assertEquals("https://haveibeenpwned.com/Content/Images/PwnedLogos/Dropbox.png", f.imageUrl, "falls back to the standard logo path")
    }

    @Test fun flagsAreSurfaced() {
        val f = HibpMapper.breachFinding(HibpBreach(name = "X", isVerified = false, isSpamList = true, isSensitive = true, isStealerLog = true, attribution = "A researcher", disclosureUrl = "https://example.test/notice", dataClasses = listOf("Email addresses")))
        val flags = f.facts.first { it.label == "Flags" }.value
        assertTrue("Unverified" in flags && "Spam list" in flags && "Sensitive" in flags && "Stealer log" in flags, flags)
        assertEquals("A researcher", f.facts.first { it.label == "Attributed to" }.value)
        assertEquals("example.test/notice", f.facts.first { it.label == "Company disclosure" }.value)
        assertEquals("No", f.facts.first { it.label == "Verified" }.value)
    }

    @Test fun relativeLogoPathBecomesAbsolute() {
        val f = HibpMapper.breachFinding(HibpBreach(name = "Z", logoPath = "/Content/Images/PwnedLogos/Z.png"))
        assertEquals("https://haveibeenpwned.com/Content/Images/PwnedLogos/Z.png", f.imageUrl)
    }

    @Test fun pastesListEachOneWithDateAndAddressCount() {
        val f = HibpMapper.pasteFinding(listOf(
            HibpPaste("Pastebin", "8Q0BvKD8", "syslog", "2014-03-04T19:14:54Z", 139),
            HibpPaste("Pastie", "p2", null, "2016-01-01T00:00:00Z", 0),
        ))
        assertEquals(Severity.EXPOSED, f.severity)
        assertEquals("Pastie · 2016-01-01", f.facts[0].label, "newest first")
        assertEquals("Pastebin · 2014-03-04", f.facts[1].label)
        assertEquals("syslog — 139 addresses in this paste", f.facts[1].value)
        assertEquals("p2", f.facts[0].value, "no title or count → fall back to the paste id")
        assertEquals("https://pastebin.com/8Q0BvKD8", f.url)
    }

    @Test fun leakCheckListsSourcesAndFieldsButNeverValues() {
        val f = LeakCheckMapper.finding(LeakCheckResult(
            2, listOf(LeakSource("Old.com", "2016-05"), LeakSource("New.com", "2024-01"), LeakSource("Nodate.com", null)),
            listOf("email", "password", "ip_address"), viaPublic = false,
        ))
        assertEquals(Severity.EXPOSED, f.severity)
        assertTrue(f.detail.lines()[0].startsWith("Found in 2 records across 3 compiled leaks"), f.detail)
        assertTrue("Data exposed: email, password, ip address." in f.detail && "password or password hash" in f.detail, f.detail)
        val sources = f.facts.filter { it.label.endsWith(".com") }
        assertEquals(listOf("New.com", "Old.com", "Nodate.com"), sources.map { it.label }, "newest first, undated last")
        assertEquals("date unknown", sources.last().value)
        assertEquals(setOf("password"), f.hotTags); assertEquals(listOf("email", "password", "ip address"), f.tags)
        assertEquals("Keyed API", f.facts.first { it.label == "Endpoint" }.value)
    }

    @Test fun publicEndpointIsLabelledAsLimited() {
        val f = LeakCheckMapper.finding(LeakCheckResult(1, listOf(LeakSource("A", "2020-01")), listOf("email"), viaPublic = true))
        assertEquals(Severity.INFO, f.severity)
        assertTrue("free public endpoint" in f.detail && f.facts.first { it.label == "Endpoint" }.value.startsWith("Public"))
    }

    @Test fun adviceMapsDataTypesToActions() {
        assertTrue(BreachAdvice.forClasses(listOf("Passwords")).single().startsWith("Change this account's password"))
        assertTrue(BreachAdvice.forClasses(listOf("Credit cards")).any { "statements" in it })
        assertTrue(BreachAdvice.forClasses(listOf("Social security numbers")).any { "credit" in it })
        assertTrue(BreachAdvice.forClasses(listOf("Phone numbers")).any { "SIM-swap" in it })
        assertTrue(BreachAdvice.forClasses(emptyList()).single().startsWith("No password or financial data"))
    }
}
