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

    @Test fun fecDonationsNeverCarryAStreetAddress() {
        val fec = PeopleParsers.fec(fixture("fec_people.json"))
        assertEquals("\$250 to EXAMPLE PAC", fec[0].title); assertTrue(fec[1].title.endsWith("to SAMPLE COMMITTEE"), fec[1].title)
        val all = fec.joinToString("\n") { listOf(it.title, it.detail, it.raw, it.facts.joinToString { f -> f.value }).joinToString(" ") }
        assertTrue("SECRET STREET" !in all && "83702" !in all, all)
        assertTrue("BOISE, ID" in fec[0].detail && "PLACEHOLDER CO" in fec[0].detail)
    }

    @Test fun linksPointToOfficialPortalsAndNeverToPeopleFinders() {
        val f = PeopleParsers.links("Grace Hopper")
        val urls = f.links.joinToString("\n") { it.url }
        assertTrue("courtlistener.com" in urls && "nsopw.gov" in urls && "sec.gov" in urls && "brokercheck.finra.org" in urls && "Grace+Hopper" in urls)
        listOf("spokeo", "whitepages", "truthfinder", "beenverified", "intelius", "peoplefinder", "radaris", "mylife").forEach { assertTrue(it !in urls.lowercase(), it) }
        assertTrue(f.id == "PEOPLE-links" && !f.scorable)
    }

    @Test fun sourceRunsEverySourceAndOneFailureDoesNotHideTheOthers() = runTest {
        val (srv, router) = server(
            "/wd/" to { json(fixture("wikidata_people.json")) }, "/orcid/" to { json(fixture("orcid_people.json")) },
            "/cl/" to { MockResponse().setResponseCode(429).setBody("throttled") }, "/fec/" to { json(fixture("fec_people.json")) },
        )
        try {
            val keys = InMemoryApiKeys(mapOf(ApiKeyId.FEC to "k123"))
            val out = PeopleSource(Http(), srv.endpoints(), keys).collect(person)
            assertEquals(2 + 2 + 2, out.count { it.isPersonMatch }, out.map { it.id }.toString())
            assertTrue(out.any { it.id == "PEOPLE-court-error" && "Rate-limited" in it.detail } && out.any { it.id == "PEOPLE-links" })
            assertTrue(router.seen.any { it.path!!.startsWith("/fec/v1/schedules/schedule_a/") && "api_key=k123" in it.path!! && "contributor_name=Grace%20Hopper" in it.path!! }, router.seen.map { it.path }.toString())
            val wd = router.seen.first { it.path!!.startsWith("/wd/") }.path!!
            assertTrue("haswbstatement%3AP31%3DQ5" in wd || "haswbstatement:P31=Q5" in wd, wd)
            assertTrue(router.seen.first { it.path!!.startsWith("/orcid/") }.path!!.contains("given-names%3AGrace"), "ORCID query uses first and last name")
        } finally { srv.shutdown() }
    }

    @Test fun withoutAnFecKeyDonationsAreSkippedNotQueried() = runTest {
        val (srv, router) = server("/wd/" to { json("""{"query":{"search":[]}}""") }, "/orcid/" to { json("""{"expanded-result":null,"num-found":0}""") }, "/cl/" to { json("""{"results":[]}""") })
        try {
            val out = PeopleSource(Http(), srv.endpoints(), InMemoryApiKeys()).collect(person)
            assertTrue(out.any { it.id == "PEOPLE-fec-nokey" } && router.seen.none { it.path!!.startsWith("/fec/") })
            assertEquals(0, out.count { it.isPersonMatch }); assertTrue(out.any { it.id == "PEOPLE-wikidata-none" })
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
