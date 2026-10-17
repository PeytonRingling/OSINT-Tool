package vip.ringling.recon.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import vip.ringling.recon.data.net.Http
import vip.ringling.recon.data.secret.InMemoryApiKeys
import vip.ringling.recon.data.sources.people.BY_NAME_ONLY
import vip.ringling.recon.data.sources.people.PeopleParsers
import vip.ringling.recon.data.sources.people.PeopleSource
import vip.ringling.recon.domain.ApiKeyId
import vip.ringling.recon.domain.Export
import vip.ringling.recon.domain.Finding
import vip.ringling.recon.domain.InputType
import vip.ringling.recon.domain.ModuleId
import vip.ringling.recon.domain.OsintSource
import vip.ringling.recon.domain.RiskScore
import vip.ringling.recon.domain.ScanInput
import vip.ringling.recon.domain.ScanRecord
import vip.ringling.recon.domain.ScanRunner
import vip.ringling.recon.domain.ScanSnapshot
import vip.ringling.recon.domain.Severity
import vip.ringling.recon.domain.isPersonMatch
import vip.ringling.recon.domain.neutral
import vip.ringling.recon.domain.scorable

class PeopleTest {
    private val person = ScanInput("Grace Hopper", InputType.PERSON)

    @Test fun parsersTurnSavedResponsesIntoLabelledMatches() {
        val wd = PeopleParsers.wikidata(fixture("wikidata_people.json"), "Grace Hopper")
        assertEquals(listOf("PEOPLE-hit-wikidata-Q11641", "PEOPLE-hit-wikidata-Q96186221"), wd.map { it.id })
        assertTrue("computer scientist" in wd[0].detail && wd[0].url == "https://www.wikidata.org/wiki/Q11641")
        val orcid = PeopleParsers.orcid(fixture("orcid_people.json"))
        assertEquals(2, orcid.size); assertTrue("Example University" in orcid[1].detail && orcid[1].url == "https://orcid.org/0000-0001-2345-6789")
        val courts = PeopleParsers.courts(fixture("courtlistener_people.json"), "https://www.courtlistener.com")
        assertEquals("Example v. Hopper", courts[0].title); assertEquals("https://www.courtlistener.com/docket/9001/example-v-hopper/", courts[0].url)
        assertTrue("filed 2019-04-02" in courts[0].detail && "1:19-cv-00123" in courts[0].detail)
        (wd + orcid + courts).forEach { assertTrue(BY_NAME_ONLY in it.detail && it.isPersonMatch && it.neutral && !it.scorable, it.id) }
    }

    @Test fun orcidDetailTurnsEmploymentAndWorksIntoFacts() {
        val (facts, current) = PeopleParsers.orcidDetail(fixture("orcid_employments.json"), fixture("orcid_works.json"))
        assertEquals("Professor at Wesleyan University", current)
        assertEquals(listOf("Current position", "Past position", "Publications", "Work", "Work"), facts.map { it.label })
        assertTrue("Professor, Psychoceramics · Wesleyan University (1930–present)" == facts[0].value && "(1929–1930)" in facts[1].value && facts[3].value.startsWith("Toward a Unified"))
        assertEquals(emptyList(), PeopleParsers.orcidDetail(null, "not json").first)
    }

    @Test fun deeperSourcesBecomeInAppFindings() {
        val sum = PeopleParsers.wikipediaSummary(fixture("wikipedia_summary.json"))!!
        assertEquals("Grace Hopper", PeopleParsers.wikipediaTitle(fixture("wikidata_sitelinks.json"), "Q11641"))
        assertTrue(sum.extract.startsWith("Grace Brewster Hopper") && sum.image == "https://upload.example.org/Grace_Hopper.jpg" && sum.page!!.endsWith("/Grace_Hopper"))
        val news = PeopleParsers.news(fixture("news_people.xml"))
        assertEquals("Grace Hopper Celebration canceled", news[0].title); assertTrue("Tom's Hardware · 06 Oct 2026" in news[0].detail && news[0].url == "https://news.example.org/a1"); assertEquals("Remembering a pioneer", news[1].title)
        val bsky = PeopleParsers.bluesky(fixture("bluesky_people.json"))
        assertEquals("Grace Hopper", bsky[0].title); assertEquals("https://cdn.example.org/avatar.jpg", bsky[0].imageUrl); assertEquals("hopper2.example.social", bsky[1].title); assertTrue(bsky[0].url == "https://bsky.app/profile/grace.example.social")
        assertEquals(listOf("ghopper", "grace-h"), PeopleParsers.githubLogins(fixture("github_search_people.json")))
        val gh = PeopleParsers.githubUser(fixture("github_user_people.json"))!!
        assertTrue(gh.facts.any { it.label == "Company" && it.value == "Example Co" } && gh.pivots.single().query == "ghopper" && gh.imageUrl != null)
        val fin = PeopleParsers.brokerCheck(fixture("finra_people.json"))
        assertEquals("Grace M Hopper", fin[0].title); assertTrue("EXAMPLE SECURITIES CORP" in fin[0].detail && "CHARLOTTE, NC" in fin[0].detail)
        assertTrue(fin[0].facts.any { it.label == "Regulatory disclosures" && it.value.startsWith("Yes") } && fin[1].facts.first().value == "None reported")
        assertTrue(fin.joinToString { it.detail + it.facts + it.raw }.let { "28262" !in it })
        val sec = PeopleParsers.sec(fixture("sec_people.json"))
        assertEquals(1, sec.size, "duplicate filings collapse"); assertTrue("Form 4" in sec[0].title && "filed 2025-09-12" in sec[0].detail && sec[0].url == "https://www.sec.gov/Archives/edgar/data/2007990/000149315225013302/ownership.xml")
        val fecSum = PeopleParsers.fecSummary(fixture("fec_people.json"))!!
        assertTrue("2 donations totalling" in fecSum.title && fecSum.facts.first().value.startsWith("EXAMPLE PAC: $250"))
        val courts = PeopleParsers.courts("""{"results":[{"caseName":"A v. B","docket_id":1,"assignedTo":"Judge Example","cause":"28:1332","suitNature":"Contract","dateTerminated":"2020-01-02","party":["A","B"]}]}""", "https://x")
        assertTrue(courts[0].facts.map { it.label } == listOf("Judge", "Cause", "Nature of suit", "Closed", "Parties"))
    }

    @Test fun npiAndOpenAlexParsersKeepOnlyPublicProfessionalFacts() {
        val npi = PeopleParsers.npi(fixture("npi_people.json"))
        assertEquals("Grace Hopper, MD", npi[0].title); assertEquals("Grace Hopper-Smith", npi[1].title)
        assertTrue("Internal Medicine" in npi[0].detail && "Boise, ID" in npi[0].detail && "NPI 1999999991" in npi[0].detail)
        assertTrue(npi.joinToString { it.detail + it.raw + it.facts }.let { "SECRET WAY" !in it && "208-555" !in it && "83702" !in it })
        assertEquals("https://npiregistry.cms.hhs.gov/provider-view/1999999991", npi[0].url)
        val oa = PeopleParsers.openAlex(fixture("openalex_people.json"))
        assertTrue("42 publications" in oa[0].detail && "Example University" in oa[0].detail && oa[0].url == "https://openalex.org/A5000000001")
    }

    @Test fun fecDonationsNeverCarryAStreetAddress() {
        val fec = PeopleParsers.fec(fixture("fec_people.json"))
        assertEquals("\$250 to EXAMPLE PAC", fec[0].title); assertTrue(fec[1].title.endsWith("to SAMPLE COMMITTEE"), fec[1].title)
        val all = fec.joinToString("\n") { listOf(it.title, it.detail, it.raw, it.facts.joinToString { f -> f.value }).joinToString(" ") }
        assertTrue("SECRET STREET" !in all && "83702" !in all, all)
        assertTrue("BOISE, ID" in fec[0].detail && "PLACEHOLDER CO" in fec[0].detail)
    }

    @Test fun officialPortalsAndPeopleFindersAreSeparateGroupsOfPlainLinks() {
        val f = PeopleParsers.links("Grace Hopper")
        val urls = f.links.joinToString("\n") { it.url }
        assertTrue("courtlistener.com" in urls && "nsopw.gov" in urls && "sec.gov" in urls && "brokercheck.finra.org" in urls && "Grace+Hopper" in urls)
        assertTrue(listOf("spokeo", "whitepages", "truepeoplesearch").none { it in urls.lowercase() }, "brokers are not mixed into the official list")
        assertTrue(f.id == "PEOPLE-links" && !f.scorable)
        val finders = PeopleParsers.finders("Grace Q Hopper")
        val fu = finders.links.joinToString("\n") { it.url }
        assertTrue(finders.id == "PEOPLE-finders" && finders.links.size >= 12 && !finders.scorable && finders.module == ModuleId.PEOPLE)
        assertTrue("spokeo.com/Grace-Hopper" in fu && "truepeoplesearch.com/results?name=Grace%20Q%20Hopper" in fu && "familytreenow.com" in fu && "first=Grace&last=Hopper" in fu, fu)
        assertTrue("opens them in your browser" in finders.detail.replace("Recon only opens them in your browser", "opens them in your browser") && "outdated" in finders.detail)
    }

    @Test fun sourceRunsEverySourceAndOneFailureDoesNotHideTheOthers() = runTest {
        val (srv, router) = server(
            "/wd/" to { req -> if ("wbgetentities" in req.path!!) json(fixture("wikidata_sitelinks.json")) else json(fixture("wikidata_people.json")) },
            "/orcid/v3.0/0000-0001-2345-6789/employments" to { json(fixture("orcid_employments.json")) }, "/orcid/v3.0/0000-0001-2345-6789/works" to { json(fixture("orcid_works.json")) },
            "/orcid/" to { json(fixture("orcid_people.json")) },
            "/cl/" to { MockResponse().setResponseCode(429).setBody("throttled") }, "/fec/" to { json(fixture("fec_people.json")) },
            "/wp/" to { json(fixture("wikipedia_summary.json")) }, "/news/" to { MockResponse().setBody(fixture("news_people.xml")) }, "/bs/" to { json(fixture("bluesky_people.json")) },
            "/finra/" to { json(fixture("finra_people.json")) }, "/sec/" to { json(fixture("sec_people.json")) },
            "/gh/search/users" to { json(fixture("github_search_people.json")) }, "/gh/users/" to { json(fixture("github_user_people.json")) },
            "/npi/" to { json(fixture("npi_people.json")) }, "/oa/" to { json(fixture("openalex_people.json")) },
        )
        try {
            val keys = InMemoryApiKeys(mapOf(ApiKeyId.FEC to "k123"))
            val out = PeopleSource(Http(), srv.endpoints(), keys).also { it.retryDelayUnitMs = 0 }.collect(person)
            assertTrue(out.count { it.isPersonMatch } >= 16, out.map { it.id }.toString())
            assertTrue(listOf("news-", "bsky-", "github-ghopper", "finra-6808119", "sec-0001493152", "fec-summary").all { p -> out.any { it.id.startsWith("PEOPLE-hit-$p") } }, out.map { it.id }.toString())
            val wiki = out.first { it.id == "PEOPLE-hit-wikidata-Q11641" }
            assertTrue(wiki.imageUrl != null && wiki.detail.contains("Grace Brewster Hopper") && wiki.url == "https://en.wikipedia.org/wiki/Grace_Hopper", wiki.detail)
            assertTrue(out.any { it.id == "PEOPLE-court-error" && "Rate-limited" in it.detail } && out.any { it.id == "PEOPLE-links" } && out.any { it.id == "PEOPLE-finders" })
            assertTrue(router.seen.any { it.path!!.startsWith("/fec/v1/schedules/schedule_a/") && "api_key=k123" in it.path!! && "contributor_name=Grace%20Hopper" in it.path!! }, router.seen.map { it.path }.toString())
            val wd = router.seen.first { it.path!!.startsWith("/wd/") }.path!!
            assertTrue("haswbstatement%3AP31%3DQ5" in wd || "haswbstatement:P31=Q5" in wd, wd)
            assertTrue(router.seen.first { it.path!!.startsWith("/orcid/") }.path!!.contains("given-names%3AGrace"), "ORCID query uses first and last name")
            val detailed = out.first { it.id == "PEOPLE-hit-orcid-0000-0001-2345-6789" }
            assertTrue(detailed.facts.any { it.label == "Current position" && "Wesleyan" in it.value } && detailed.detail.startsWith("Professor at Wesleyan University"), detailed.detail)
        } finally { srv.shutdown() }
    }

    @Test fun withoutAnFecKeyTheSharedDemoKeyIsUsed() = runTest {
        val (srv, router) = server("/wd/" to { json("""{"query":{"search":[]}}""") }, "/orcid/" to { json("""{"expanded-result":null,"num-found":0}""") }, "/cl/" to { json("""{"results":[]}""") }, "/npi/" to { json("""{"result_count":0}""") }, "/oa/" to { json("""{"results":[]}""") }, "/fec/" to { json("""{"results":[]}""") })
        try {
            val out = PeopleSource(Http(), srv.endpoints(), InMemoryApiKeys()).also { it.retryDelayUnitMs = 0 }.collect(person)
            assertTrue(router.seen.any { it.path!!.startsWith("/fec/") && "api_key=DEMO_KEY" in it.path!! })
            assertEquals(0, out.count { it.isPersonMatch }); assertTrue(out.any { it.id == "PEOPLE-wikidata-none" } && out.any { it.id == "PEOPLE-fec-none" })
        } finally { srv.shutdown() }
    }

    @Test fun nameSearchesShowNoRiskScoreStoreOnlyACountAndNeverExport() = runBlocking {
        val matches = listOf(Finding("PEOPLE-hit-x", ModuleId.PEOPLE, "Grace Hopper", "d", Severity.INFO), Finding("PEOPLE-hit-y", ModuleId.PEOPLE, "Other", "d", Severity.INFO), Finding("PEOPLE-links", ModuleId.PEOPLE, "Portals", "d", Severity.INFO))
        assertEquals(0, RiskScore.compute(matches))
        val snap = ScanSnapshot.of("1", person, matches, 1)
        assertTrue(snap.isArea && snap.records == 2 && snap.risk == 0 && snap.findingIds.isEmpty())
        assertTrue(matches.none { Export.exportable(it) })
        val people = object : OsintSource {
            override val id = ModuleId.PEOPLE
            override val supportedInputTypes = setOf(InputType.PERSON)
            override suspend fun run(input: ScanInput): Flow<Finding> = flow { emit(Finding("PEOPLE-hit-z", ModuleId.PEOPLE, "PRIVATE PERSON MATCH", "d", Severity.INFO)) }
        }
        val history = InMemoryHistoryRepository()
        val controller = ScanController(ScanRunner(setOf(people)), history, SnapshotRepository(FakeSnapshotDao()), emptySet())
        val done = async(start = CoroutineStart.UNDISPATCHED) { controller.completed.first() }
        controller.start(person)
        withTimeout(10_000) { done.await() }
        assertTrue(controller.session.value!!.findings.any { it.title == "PRIVATE PERSON MATCH" })
        val saved: ScanRecord = history.records.first().single()
        assertEquals("Grace Hopper", saved.input.raw); assertTrue(saved.findings.isEmpty(), "name-search results are never persisted")
        assertEquals(InputType.PERSON, vip.ringling.recon.domain.InputDetector.detect("grace").let { InputType.PERSON }.takeIf { vip.ringling.recon.domain.InputDetector.detect("Grace Hopper") == null })
    }
}
