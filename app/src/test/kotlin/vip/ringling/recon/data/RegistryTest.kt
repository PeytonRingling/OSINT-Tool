package vip.ringling.recon.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
import vip.ringling.recon.data.net.HttpException
import vip.ringling.recon.data.sources.registry.IdahoRegistryAdapter
import vip.ringling.recon.data.sources.registry.RegistryAdapter
import vip.ringling.recon.data.sources.registry.RegistryEntry
import vip.ringling.recon.data.sources.registry.RegistryQuery
import vip.ringling.recon.data.sources.registry.RegistryResult
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
        assertEquals(2, d.offenses.size)
        assertEquals("18-0000", d.offenses[0].statute); assertEquals("FICTIONAL OFFENSE ONE", d.offenses[0].description)
        assertEquals("Jun 01, 2010", d.offenses[0].date); assertEquals("ADA CO", d.offenses[0].place)
    }

    @Test fun detailToleratesMissingSections() {
        val d = IdahoRegistryAdapter.parseDetail("<html><body>nothing</body></html>")
        assertEquals(null, d.regStatus); assertTrue(d.offenses.isEmpty())
    }
}

class IdahoAdapterTest {
    @Test fun zipRange() {
        val a = IdahoRegistryAdapter(Http(), vip.ringling.recon.data.net.Endpoints())
        assertTrue(a.handlesZip("83702") && a.handlesZip("83201") && a.handlesZip("83877"))
        assertTrue(!a.handlesZip("90210") && !a.handlesZip("83100") && !a.handlesZip("83900"))
    }

    /** Serves the adult database for rad=A and the given page for rad=J. */
    private fun bySearch(adult: String, juvenile: String, juvenileCode: Int = 200): (okhttp3.mockwebserver.RecordedRequest) -> MockResponse = { req ->
        if (req.method == "POST" && "rad=J" in req.body.clone().readUtf8()) html(juvenile, juvenileCode) else html(adult)
    }

    @Test fun queriesAdultAndJuvenileFirstPageOnly() = runTest {
        val (srv, router) = server(
            "/idaho/SOR?id=" to { html(fixture("idaho_detail.html")) },
            "/idaho/SOR" to bySearch(fixture("idaho_zip_multipage.html"), fixture("idaho_juvenile_results.html")),
        )
        srv.use {
            val f = RegistrySource(setOf(IdahoRegistryAdapter(Http(), srv.endpoints()))).collect(ScanInput("83702", InputType.ZIP))

            val posts = router.seen.filter { it.method == "POST" }.map { it.body.clone().readUtf8() }
            assertEquals(2, posts.size)
            assertTrue(posts.any { "rad=A" in it && "form=5" in it && "zip=83702" in it && "sz=400" in it }, posts.toString())
            assertTrue(posts.any { "rad=J" in it && "form=5" in it && "zip=83702" in it }, posts.toString())
            assertTrue(router.seen.none { it.path!!.contains("page=") }, "must never walk further pages")

            val summary = f.first { it.id == "REGISTRY-summary" }
            assertEquals("61 matches in 83702", summary.title)
            assertTrue("Showing the first 3" in summary.detail && "verify before relying" in summary.detail, summary.detail)
            val adult = f.first { it.title == "SAMPLE, ALEX Q" }
            assertTrue(adult.detail.startsWith("Adult registry · 100 FICTION AVE, BOISE, ADA County, 83702"), adult.detail)
            assertTrue("Last registered: Mar 15, 2026" in adult.detail && "18-0000 – FICTIONAL OFFENSE ONE" in adult.detail, adult.detail)
            assertEquals("", adult.raw)
            val juv = f.first { it.title == "SAMPLE, JAMIE R" }
            assertTrue(juv.detail.startsWith("Juvenile registry · 300 JUVENILE EXAMPLE LN"), juv.detail)
            assertTrue(juv.url!!.endsWith("/idaho/SOR?id=90101&sz=400"), juv.url)
        }
    }

    @Test fun detailFailureDegradesButKeepsRow() = runTest {
        val (srv, _) = server(
            "/idaho/SOR?id=" to { html("boom", 500) },
            "/idaho/SOR" to bySearch(fixture("idaho_zip_results.html"), fixture("idaho_zip_empty.html")),
        )
        srv.use {
            val f = RegistrySource(setOf(IdahoRegistryAdapter(Http(), srv.endpoints()))).collect(ScanInput("83702", InputType.ZIP))
            val rec = f.first { it.title == "EXAMPLE, BEN" }
            assertTrue("Status: Non Comp" in rec.detail && "Offense details unavailable" in rec.detail, rec.detail)
            assertTrue("Showing the first" !in f.first { it.id == "REGISTRY-summary" }.detail, "complete result is not partial")
        }
    }

    @Test fun juvenileOutageStillReturnsAdultWithANote() = runTest {
        val (srv, _) = server(
            "/idaho/SOR?id=" to { html(fixture("idaho_detail.html")) },
            "/idaho/SOR" to bySearch(fixture("idaho_zip_results.html"), "down", juvenileCode = 503),
        )
        srv.use {
            val f = RegistrySource(setOf(IdahoRegistryAdapter(Http(), srv.endpoints()))).collect(ScanInput("83702", InputType.ZIP))
            assertTrue(f.any { it.title == "SAMPLE, ALEX Q" })
            val note = f.first { it.id.startsWith("REGISTRY-note") }
            assertTrue("Juvenile registry unavailable" in note.detail && "503" in note.detail, note.detail)
        }
    }

    @Test fun noMatchesInEitherDatabaseIsClean() = runTest {
        val (srv, _) = server("/idaho/SOR" to { html(fixture("idaho_zip_empty.html")) })
        srv.use {
            val f = RegistrySource(setOf(IdahoRegistryAdapter(Http(), srv.endpoints()))).collect(ScanInput("83702", InputType.ZIP))
            assertEquals(Severity.CLEAN, f.single().severity)
        }
    }

    @Test fun bothDatabasesDownFailsTheModuleLoudly() = runTest {
        val (srv, _) = server("/idaho/SOR" to { html("down", 503) })
        srv.use {
            val ex = assertFailsWith<HttpException> {
                RegistrySource(setOf(IdahoRegistryAdapter(Http(), srv.endpoints()))).collect(ScanInput("83702", InputType.ZIP))
            }
            assertEquals(503, ex.code)
        }
    }

    @Test fun cityAndCountyFormsUseConfirmedParams() = runTest {
        val (srv, router) = server("/idaho/SOR" to { html(fixture("idaho_zip_empty.html")) })
        srv.use {
            val a = IdahoRegistryAdapter(Http(), srv.endpoints())
            a.search(RegistryQuery(city = "Boise")); a.search(RegistryQuery(county = "Ada"))
            val bodies = router.seen.map { it.body.clone().readUtf8() }
            assertTrue(bodies.any { "form=3" in it && "cty=BOISE" in it && "rad=A" in it }, bodies.toString())
            assertTrue(bodies.any { "form=3" in it && "rad=J" in it }, bodies.toString())
            assertTrue(bodies.any { "form=4" in it && "cnt=ADA" in it }, bodies.toString())
        }
    }
}

private class FakeAdapter(private val result: RegistryResult) : RegistryAdapter {
    override val stateCode = "ID"; override val stateName = "Idaho"
    override fun handlesZip(zip: String) = zip.startsWith("83")
    override suspend fun search(query: RegistryQuery) = result
}

class RegistrySourceTest {
    private val zip = ScanInput("83702", InputType.ZIP)

    @Test fun noAdapterForOtherStates() = runTest {
        val f = RegistrySource(setOf(FakeAdapter(RegistryResult(0, emptyList(), "u")))).collect(ScanInput("90210", InputType.ZIP))
        assertEquals("REGISTRY-nostate", f.single().id)
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
        val controller = ScanController(ScanRunner(setOf(registry)), history)
        val done = async(start = CoroutineStart.UNDISPATCHED) { controller.completed.first() }
        controller.start(zip)
        withTimeout(10_000) { done.await() }

        assertTrue(controller.session.value!!.findings.any { it.title == "SENSITIVE NAME" }, "visible live")
        val saved: ScanRecord = history.records.first().single()
        assertEquals("83702", saved.input.raw)
        assertTrue(saved.findings.isEmpty(), "…but never persisted")
    }
}
