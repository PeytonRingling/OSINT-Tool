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

class LeakCheckV2Test {
    private val client = vip.ringling.recon.data.sources.breach.LeakCheckClient(vip.ringling.recon.data.net.Http(), "http://x", "http://y")

    @Test fun mergesEntriesPerLeakAndReadsFlagsFieldsQuota() {
        val r = client.parseV2(fixture("leakcheck_v2.json"))
        assertEquals(3, r.found); assertEquals(395, r.quota); assertTrue(!r.viaPublic)
        assertEquals(2, r.leaks.size, "two entries from the same leak merge")
        val c1 = r.leaks.first { it.name == "Collection #1" }
        assertEquals("2019-01", c1.date); assertEquals(listOf("email", "password", "username"), c1.fields); assertTrue(c1.compilation && !c1.unverified)
        val naz = r.leaks.first { it.name == "Naz.API" }
        assertTrue(naz.unverified && naz.passwordless && naz.date == null)
        assertEquals(listOf("email", "password", "username", "ip"), r.fields)
    }

    @Test fun leakedValuesAreNeverExtractedOrShown() {
        val r = client.parseV2(fixture("leakcheck_v2.json"))
        val f = LeakCheckMapper.finding(r)
        val everything = f.detail + f.raw + f.facts.joinToString { it.label + it.value } + f.tags.joinToString()
        for (secret in listOf("hunter2", "another-FAKE", "a@b.com", "203.0.113.7", "neo")) assertTrue(secret !in everything, "$secret must not appear")
        assertTrue("\"password\"" in f.raw && "<text" in f.raw, "raw keeps the shape with masked values: ${f.raw}")
    }

    @Test fun mapperShowsPerLeakFieldsAndFlags() {
        val f = LeakCheckMapper.finding(client.parseV2(fixture("leakcheck_v2.json")))
        assertEquals(Severity.EXPOSED, f.severity)
        assertTrue("1 of 2 leaks include a password" in f.detail, f.detail)
        assertTrue("marked unverified" in f.detail && "combo-list" in f.detail, f.detail)
        assertEquals("2019-01 · email, password, username · combo list", f.facts.first { it.label == "Collection #1" }.value)
        assertEquals("date unknown · email, ip · unverified, no password", f.facts.first { it.label == "Naz.API" }.value)
        assertEquals("395", f.facts.first { it.label == "Queries left" }.value)
        assertEquals("Keyed API", f.facts.first { it.label == "Endpoint" }.value)
    }

    @Test fun toleratesVariantShapes() {
        // source as a bare string, no "fields" → field names inferred from which keys have values (names only)
        val r = client.parseV2("""{"success":true,"found":1,"result":[{"source":"Plain.com","email":"x@y.z","name":"Q","dob":null}]}""")
        assertEquals("Plain.com", r.leaks.single().name)
        assertEquals(listOf("email", "name"), r.leaks.single().fields, "null-valued keys are skipped")
        assertEquals(null, r.quota)
        assertEquals(1, client.parseV2("""{"success":true,"result":[{"source":{"name":"A"}}]}""").found, "found falls back to result size")
    }

    @Test fun notFoundAndBadResponses() {
        assertEquals(0, client.parseV2("""{"success":false,"error":"Not found"}""").found)
        val limit = runCatching { client.parseV2("""{"success":false,"error":"Limit reached"}""") }
        assertTrue(limit.exceptionOrNull()?.message?.contains("Limit reached") == true, "limit: $limit")
        val junk = runCatching { client.parseV2("<html>") }
        assertTrue(junk.exceptionOrNull()?.message?.startsWith("LeakCheck sent") == true, "junk: $junk")
    }

    @Test fun redactTruncatesArraysAndMasksStrings() {
        val shape = client.redact(Json.parseToJsonElement("""{"a":"secret","n":5,"l":[{"k":"v"},{"k":"v"},{"k":"v"},{"k":"v"}]}""")).toString()
        assertTrue("secret" !in shape && "<text, 6 chars>" in shape && "\"n\":5" in shape && "2 more" in shape, shape)
    }
}
