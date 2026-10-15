package vip.ringling.recon.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import vip.ringling.recon.data.net.Http
import vip.ringling.recon.data.net.HttpException
import vip.ringling.recon.data.sources.registry.IdahoRegistryAdapter
import vip.ringling.recon.data.sources.registry.RegistryAdapter
import vip.ringling.recon.data.sources.registry.RegistryEntry
import vip.ringling.recon.data.sources.registry.RegistryQuery
import vip.ringling.recon.data.sources.registry.RegistryEvent
import vip.ringling.recon.data.sources.registry.RegistrySource
import vip.ringling.recon.domain.Finding
import vip.ringling.recon.domain.InputType
import vip.ringling.recon.domain.ModuleId
import vip.ringling.recon.domain.OsintSource
import vip.ringling.recon.domain.ScanInput
import vip.ringling.recon.domain.ScanRecord
import vip.ringling.recon.domain.ScanRunner
import vip.ringling.recon.domain.Severity

private fun html(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "text/html;charset=ISO-8859-1").setBody(body)

class IdahoParsingTest {
    @Test fun parsesResultRowsAndCounts() {
        val p = IdahoRegistryAdapter.parseResults(fixture("idaho_zip_results.html"))
        assertEquals(2, p.totalMatches); assertEquals(1, p.pages)
        val r = p.rows.first()
        assertEquals("90001", r.id); assertEquals("SAMPLE, ALEX Q", r.name); assertEquals("1000001", r.kno)
        assertEquals("100 FICTION AVE", r.address); assertEquals("BOISE", r.city); assertEquals("ADA", r.county)
        assertEquals("83702", r.zip); assertEquals("Compliant", r.status)
        assertEquals("../sorFiles/photos/thumbs/FAKE_90001_01-02-2024.jpg", r.photoHref)
        assertEquals("jsp/sor_map_display.jsp?idx=90001&regid=SX90001&kind=primary&which=1&sz=400", r.mapHref)
        assertEquals("Non Comp", p.rows[1].status)
    }

    @Test fun countWordingVariantsSeenLive() {
        assertEquals(2, IdahoRegistryAdapter.parseResults(fixture("idaho_zip_found_no_pages.html")).totalMatches)
        val one = IdahoRegistryAdapter.parseResults(fixture("idaho_juvenile_results.html"))
        assertEquals(1, one.totalMatches); assertEquals(1, one.rows.size)
        assertTrue(one.rows.single().href.startsWith("SOR?id="), one.rows.single().href)
    }

    @Test fun multipageAndEmpty() {
        val m = IdahoRegistryAdapter.parseResults(fixture("idaho_zip_multipage.html"))
        assertEquals(60, m.totalMatches); assertEquals(4, m.pages); assertEquals(2, m.rows.size, "only rows present are returned")
        val e = IdahoRegistryAdapter.parseResults(fixture("idaho_zip_empty.html"))
        assertEquals(0, e.totalMatches); assertTrue(e.rows.isEmpty())
        assertEquals(0, IdahoRegistryAdapter.parseResults("<html><body>garbage</body></html>").rows.size)
    }

    @Test fun parsesDetailOffensesAndStatus() {
        val d = IdahoRegistryAdapter.parseDetail(fixture("idaho_detail.html"))
        assertEquals("Compliant", d.regStatus)
        assertEquals("Mar 15, 2026", d.lastRegistered)
        assertEquals("../sorFiles/photos/FAKE_90001_01-02-2024.jpg", d.photoHref, "full photo, not the thumbnail")
        assertEquals(2, d.offenses.size)
        assertEquals("18-0000", d.offenses[0].statute); assertEquals("FICTIONAL OFFENSE ONE", d.offenses[0].description)
        assertEquals("Jun 01, 2010", d.offenses[0].date); assertEquals("ADA CO", d.offenses[0].place)
    }

    @Test fun parsesGeoFromTheRegistrysMapPage() {
        val g = IdahoRegistryAdapter.parseGeo(fixture("idaho_map.html"))!!
        assertEquals(43.615, g.lat, 1e-6); assertEquals(-116.202, g.lng, 1e-6)
        assertEquals(null, IdahoRegistryAdapter.parseGeo("<html>no map here</html>"))
        assertEquals(null, IdahoRegistryAdapter.parseGeo("new google.maps.LatLng(0.0, 0.0)"), "null island is a failed geocode")
        assertEquals(null, IdahoRegistryAdapter.parseGeo("new google.maps.LatLng(123.4, 10.0)"))
    }

    @Test fun findsThePagerTemplate() {
        assertEquals("SOR?form=5&zip=83702&rad=A&srt=1&sz=400&page=2", IdahoRegistryAdapter.parsePagerTemplate(fixture("idaho_page1.html")))
        assertEquals(null, IdahoRegistryAdapter.parsePagerTemplate(fixture("idaho_zip_results.html")), "single page: no pager")
    }

    @Test fun detailToleratesMissingSections() {
        val d = IdahoRegistryAdapter.parseDetail("<html><body>nothing</body></html>")
        assertEquals(null, d.regStatus); assertTrue(d.offenses.isEmpty())
    }
}

class IdahoAdapterTest {
    private fun adapter(srv: okhttp3.mockwebserver.MockWebServer, configure: IdahoRegistryAdapter.() -> Unit = {}) =
        IdahoRegistryAdapter(Http(), srv.endpoints()).apply(configure)

    private fun routes(adult: String, juvenile: String = fixture("idaho_zip_empty.html"), page2: () -> MockResponse = { html(fixture("idaho_page2.html")) }) = arrayOf<Pair<String, (okhttp3.mockwebserver.RecordedRequest) -> MockResponse>>(
        "/idaho/SOR?form=5" to { page2() },
        "/idaho/SOR?id=" to { html(fixture("idaho_detail.html")) },
        "/idaho/jsp/sor_map_display.jsp" to { html(fixture("idaho_map.html")) },
        "/idaho/SOR" to { req -> if ("rad=J" in req.body.clone().readUtf8()) html(juvenile) else html(adult) },
    )

    @Test fun zipRange() {
        val a = IdahoRegistryAdapter(Http(), vip.ringling.recon.data.net.Endpoints())
        assertTrue(a.handlesZip("83702") && a.handlesZip("83201") && a.handlesZip("83877"))
        assertTrue(!a.handlesZip("90210") && !a.handlesZip("83100") && !a.handlesZip("83900"))
    }

    @Test fun followsEveryPageOfTheQueryAndBothDatabases() = runTest {
        val (srv, router) = server(*routes(fixture("idaho_page1.html"), fixture("idaho_juvenile_results.html")))
        srv.use {
            val events = adapter(srv).search(RegistryQuery(zip = "83702")).toList()
            val header = events.first() as RegistryEvent.Header
            assertEquals(4, header.totalMatches, "3 adult over two pages + 1 juvenile"); assertEquals(4, header.listed); assertTrue(!header.capped)
            val entries = events.filterIsInstance<RegistryEvent.Entry>().map { it.entry }
            assertEquals(setOf("SAMPLE, ALEX Q", "EXAMPLE, BEN", "THIRD, PERSON C", "SAMPLE, JAMIE R"), entries.map { it.name }.toSet())
            assertEquals(entries.size, entries.map { it.key }.distinct().size, "no record listed twice")
            val posts = router.seen.filter { it.method == "POST" }.map { it.body.clone().readUtf8() }
            assertEquals(2, posts.size); assertTrue(posts.any { "rad=A" in it && "form=5" in it && "zip=83702" in it } && posts.any { "rad=J" in it })
            val pageGets = router.seen.filter { it.method == "GET" && "page=" in it.path!! }.map { it.path!! }
            assertEquals(1, pageGets.size, pageGets.toString()); assertTrue(pageGets.single().endsWith("page=2") && "rad=A" in pageGets.single())
        }
    }

    @Test fun entriesCarryPhotoCoordinatesTagsAndOffenses() = runTest {
        val (srv, router) = server(*routes(fixture("idaho_page1.html"), fixture("idaho_juvenile_results.html")))
        srv.use {
            val entries = adapter(srv).search(RegistryQuery(zip = "83702")).toList().filterIsInstance<RegistryEvent.Entry>().map { it.entry }
            val adult = entries.first { it.name == "SAMPLE, ALEX Q" }
            val lines = adult.summary.lines()
            assertEquals("100 FICTION AVE, BOISE, ADA County, 83702", lines[0])
            assertEquals("Status: Compliant · Last registered: Mar 15, 2026", lines[1])
            assertTrue("Offenses (2):" in lines && "• 18-0000 – FICTIONAL OFFENSE ONE – Jun 01, 2010 – ADA CO" in lines, adult.summary)
            assertTrue(lines.any { it.startsWith("Convictions on 2 separate dates") && "does not state" in it }, "repeat is inferred, and says so")
            assertEquals(listOf("Adult registry", "Multiple convictions"), adult.tags)
            assertEquals(Severity.INFO, adult.severity, "registry records are never labelled Exposed")
            assertEquals(43.615, adult.geo!!.lat, 1e-6)
            assertTrue(adult.imageUrl!!.endsWith("/sorFiles/photos/thumbs/FAKE_90001_01-02-2024.jpg"), adult.imageUrl)
            assertTrue(adult.imageLargeUrl!!.endsWith("/sorFiles/photos/FAKE_90001_01-02-2024.jpg"), adult.imageLargeUrl)
            assertEquals("Juvenile registry", entries.first { it.name == "SAMPLE, JAMIE R" }.tags.first())
            assertTrue(router.seen.any { it.path!!.contains("sor_map_display.jsp?idx=90001") }, "coordinates come from the registry's own map page")
        }
    }

    @Test fun veryLargeSearchesAreCapped() = runTest {
        val (srv, router) = server(*routes(fixture("idaho_page1.html")))
        srv.use {
            val events = adapter(srv) { maxPagesPerDatabase = 1; maxEnriched = 1 }.search(RegistryQuery(zip = "83702")).toList()
            val header = events.first() as RegistryEvent.Header
            assertEquals(3, header.totalMatches); assertEquals(2, header.listed); assertTrue(header.capped, "page 2 was not fetched")
            assertTrue(router.seen.none { it.method == "GET" && "page=" in it.path!! })
            val entries = events.filterIsInstance<RegistryEvent.Entry>().map { it.entry }
            assertEquals(1, entries.count { it.geo != null }, "only maxEnriched rows get coordinates")
            assertTrue(entries.count { "Offense details unavailable" in it.summary } == 1, "rows past the cap still appear, just without details")
        }
    }

    @Test fun aFailedPageIsReportedNotHidden() = runTest {
        val (srv, _) = server(*routes(fixture("idaho_page1.html"), page2 = { html("down", 500) }))
        srv.use {
            val header = adapter(srv).search(RegistryQuery(zip = "83702")).toList().first() as RegistryEvent.Header
            assertEquals(2, header.listed); assertTrue(header.capped)
            assertTrue(header.notes.any { "1 results page(s) could not be loaded" in it }, header.notes.toString())
        }
    }

    @Test fun detailFailureDegradesButKeepsRow() = runTest {
        val (srv, _) = server(
            "/idaho/SOR?id=" to { html("boom", 500) },
            "/idaho/SOR" to { req -> if ("rad=J" in req.body.clone().readUtf8()) html(fixture("idaho_zip_empty.html")) else html(fixture("idaho_zip_results.html")) },
        )
        srv.use {
            val entries = adapter(srv).search(RegistryQuery(zip = "83702")).toList().filterIsInstance<RegistryEvent.Entry>().map { it.entry }
            val rec = entries.first { it.name == "EXAMPLE, BEN" }
            assertTrue("Status: Non Comp" in rec.summary && "Offense details unavailable" in rec.summary, rec.summary)
            assertTrue("Non-compliant" in rec.tags && rec.severity == Severity.INFO)
            assertEquals(null, rec.geo, "map page unreachable → no coordinates, row still shown")
        }
    }

    @Test fun juvenileOutageStillReturnsAdultWithANote() = runTest {
        val (srv, _) = server("/idaho/SOR" to { _ -> html("placeholder") })
        srv.use {
            // juvenile endpoint answers 503
            srv.dispatcher = Router(listOf(
                "/idaho/SOR?id=" to { _: okhttp3.mockwebserver.RecordedRequest -> html(fixture("idaho_detail.html")) },
                "/idaho/jsp/" to { _ -> html(fixture("idaho_map.html")) },
                "/idaho/SOR" to { req -> if ("rad=J" in req.body.clone().readUtf8()) html("down", 503) else html(fixture("idaho_zip_results.html")) },
            ))
            val f = RegistrySource(setOf(adapter(srv))).collect(ScanInput("83702", InputType.ZIP))
            assertTrue(f.any { it.title == "SAMPLE, ALEX Q" })
            val note = f.first { it.id.startsWith("REGISTRY-note") }
            assertTrue("Juvenile registry unavailable" in note.detail && "503" in note.detail, note.detail)
        }
    }

    @Test fun noMatchesInEitherDatabaseIsClean() = runTest {
        val (srv, _) = server("/idaho/SOR" to { html(fixture("idaho_zip_empty.html")) })
        srv.use {
            val f = RegistrySource(setOf(adapter(srv))).collect(ScanInput("83702", InputType.ZIP))
            assertEquals(Severity.CLEAN, f.single().severity)
        }
    }

    @Test fun bothDatabasesDownFailsTheModuleLoudly() = runTest {
        val (srv, _) = server("/idaho/SOR" to { html("down", 503) })
        srv.use {
            val ex = assertFailsWith<HttpException> { RegistrySource(setOf(adapter(srv))).collect(ScanInput("83702", InputType.ZIP)) }
            assertEquals(503, ex.code)
        }
    }

    @Test fun cityAndCountyFormsUseConfirmedParams() = runTest {
        val (srv, router) = server("/idaho/SOR" to { html(fixture("idaho_zip_empty.html")) })
        srv.use {
            val a = adapter(srv)
            a.search(RegistryQuery(city = "Boise")).toList(); a.search(RegistryQuery(county = "Ada")).toList()
            val bodies = router.seen.map { it.body.clone().readUtf8() }
            assertTrue(bodies.any { "form=3" in it && "cty=BOISE" in it && "rad=A" in it }, bodies.toString())
            assertTrue(bodies.any { "form=3" in it && "rad=J" in it }, bodies.toString())
            assertTrue(bodies.any { "form=4" in it && "cnt=ADA" in it }, bodies.toString())
        }
    }

    @Test fun sourceSummarizesAndStreamsFindings() = runTest {
        val (srv, _) = server(*routes(fixture("idaho_page1.html"), fixture("idaho_juvenile_results.html")))
        srv.use {
            val f = RegistrySource(setOf(adapter(srv))).collect(ScanInput("83702", InputType.ZIP))
            val summary = f.first { it.id == "REGISTRY-summary" }
            assertEquals("4 matches in 83702", summary.title)
            assertTrue("Showing" !in summary.detail && "verify before relying" in summary.detail, summary.detail)
            assertEquals(4, f.count { it.id.startsWith("REGISTRY-ID-") })
            assertEquals("REGISTRY-summary", f.first().id, "summary arrives before the records")
        }
    }
}

private class FakeAdapter(private val events: List<RegistryEvent>) : RegistryAdapter {
    override val stateCode = "ID"; override val stateName = "Idaho"
    override fun handlesZip(zip: String) = zip.startsWith("83")
    override fun search(query: RegistryQuery): Flow<RegistryEvent> = flow { events.forEach { emit(it) } }
}

class RegistrySourceTest {
    private val zip = ScanInput("83702", InputType.ZIP)

    @Test fun noAdapterForOtherStates() = runTest {
        val f = RegistrySource(setOf(FakeAdapter(emptyList()))).collect(ScanInput("90210", InputType.ZIP))
        assertEquals("REGISTRY-nostate", f.single().id)
    }

    @Test fun cappedHeaderSaysHowManyAreShown() = runTest {
        val e = RegistryEntry("A-1", "X", "s", "u")
        val f = RegistrySource(setOf(FakeAdapter(listOf(RegistryEvent.Header(300, 150, true, "u"), RegistryEvent.Entry(e))))).collect(zip)
        assertTrue("Showing 150 of 300" in f.first { it.id == "REGISTRY-summary" }.detail)
    }

    @Test fun scanHistoryNeverStoresRegistryFindings() = runBlocking {
        val registry = object : OsintSource {
            override val id = ModuleId.REGISTRY
            override val supportedInputTypes = setOf(InputType.ZIP)
            override suspend fun run(input: ScanInput): Flow<Finding> = flow {
                emit(Finding("REGISTRY-x", ModuleId.REGISTRY, "SENSITIVE NAME", "d", Severity.INFO))
            }
        }
        val history = InMemoryHistoryRepository()
        val controller = ScanController(ScanRunner(setOf(registry)), history, SnapshotRepository(FakeSnapshotDao()), emptySet())
        val done = async(start = CoroutineStart.UNDISPATCHED) { controller.completed.first() }
        controller.start(zip)
        withTimeout(10_000) { done.await() }

        assertTrue(controller.session.value!!.findings.any { it.title == "SENSITIVE NAME" }, "visible live")
        val saved: ScanRecord = history.records.first().single()
        assertEquals("83702", saved.input.raw)
        assertTrue(saved.findings.isEmpty(), "…but never persisted")
    }
}
