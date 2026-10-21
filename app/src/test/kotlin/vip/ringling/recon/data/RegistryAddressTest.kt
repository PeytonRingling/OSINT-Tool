package vip.ringling.recon.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import vip.ringling.recon.data.net.Http
import vip.ringling.recon.data.sources.registry.AdapterUnavailable
import vip.ringling.recon.data.sources.registry.IdahoRegistryAdapter
import vip.ringling.recon.data.sources.registry.RegistryEvent
import vip.ringling.recon.data.sources.registry.RegistrySource
import vip.ringling.recon.domain.AddressQuery
import vip.ringling.recon.domain.InputType
import vip.ringling.recon.domain.ScanInput

private fun page(body: String) = MockResponse().setHeader("Content-Type", "text/html;charset=ISO-8859-1").setBody(body)

class RegistryAddressTest {
    @Test fun addressQueryRoundTripsAndValidates() {
        val q = AddressQuery.of("200 example st,", "boise", 3)!!
        assertEquals("200 EXAMPLE ST, BOISE (3 mi)", q.raw); assertEquals(q, AddressQuery.parse(q.raw))
        assertNull(AddressQuery.of("Main", "Boise", 3)); assertNull(AddressQuery.of("200 Example St", "Boise", 2)); assertNull(AddressQuery.of("200 Example St", "", 1)); assertNull(AddressQuery.parse("83702"))
    }

    @Test fun parsesSinglesAndSharedAddressGroupsWithCoordinatesAndDistances() {
        val r = IdahoRegistryAdapter.parseMapSearch(fixture("idaho_map_search.html"))
        assertEquals(listOf("90001", "90002", "90003"), r.map { it.id })
        val a = r[0]; val b = r[1]; val c = r[2]
        assertEquals("SAMPLE, ALEX Q", a.name); assertEquals("200 EXAMPLE ST", a.address); assertEquals("BOISE", a.city); assertEquals("83702", a.zip)
        assertEquals("18-0000 – FICTIONAL OFFENSE ONE", a.offense); assertEquals(0.552, a.miles); assertEquals("../sorFiles/photos/thumbs/FAKE_90001_01-02-2024.jpg", a.photoHref)
        assertEquals("SOR?id=90001&sz=400", a.href); assertEquals(43.62, a.geo!!.lat); assertEquals(-116.21, a.geo!!.lng)
        assertEquals("83702-1234", b.zip); assertEquals(1.25, b.miles)
        assertEquals(b.geo, c.geo, "registrants at one address share the group marker"); assertEquals(43.63, c.geo!!.lat)
        assertNull(c.photoHref, "a record without a photo parses fine")
    }

    @Test fun searchPostsTheMapFormAndStreamsNearestFirst() = runTest {
        val (srv, router) = server("/idaho/jsp/map_search_result.jsp" to { page(fixture("idaho_map_search.html")) }, "/idaho/SOR" to { page(fixture("idaho_detail.html")) })
        srv.use {
            val events = IdahoRegistryAdapter(Http(), srv.endpoints()).searchAddress(AddressQuery.of("200 Example St", "Boise", 3)!!).toList()
            val header = events.first() as RegistryEvent.Header
            assertEquals(3, header.totalMatches); assertTrue(!header.capped)
            val entries = events.filterIsInstance<RegistryEvent.Entry>().map { it.entry }
            assertEquals(setOf("SAMPLE, ALEX Q", "EXAMPLE, BEN", "THIRD, PERSON C"), entries.map { it.name }.toSet())
            assertTrue(entries.all { it.geo != null && it.miles != null } && entries.first { it.name.startsWith("SAMPLE") }.summary.contains("0.55 mi from the searched address"))
            val post = router.seen.first { it.method == "POST" }.body.clone().readUtf8()
            assertTrue("adr=200+EXAMPLE+ST" in post && "cty=BOISE" in post && "sta=IDAHO" in post && "rad=3" in post, post)
        }
    }

    @Test fun anUnmappableAddressIsReportedPlainly() = runTest {
        val (srv, _) = server("/idaho/jsp/map_search_result.jsp" to { page("<div style=\"color:red\">Unable to display a map for the requested address.</div>") })
        srv.use {
            val e = assertFailsWith<AdapterUnavailable> { IdahoRegistryAdapter(Http(), srv.endpoints()).searchAddress(AddressQuery.of("1 Nowhere Rd", "Boise", 1)!!).toList() }
            assertTrue("couldn't map that address" in e.message!!)
        }
    }

    @Test fun sourceSummarizesAnAddressSearchAndRejectsBadInput() = runTest {
        val (srv, _) = server("/idaho/jsp/map_search_result.jsp" to { page(fixture("idaho_map_search.html")) }, "/idaho/SOR" to { page(fixture("idaho_detail.html")) })
        srv.use {
            val src = RegistrySource(setOf(IdahoRegistryAdapter(Http(), srv.endpoints())))
            val fs = src.run(ScanInput("200 EXAMPLE ST, BOISE (3 mi)", InputType.ADDRESS)).toList()
            assertTrue(fs.any { it.id == "REGISTRY-summary" && it.title == "3 registrants within 3 miles of 200 EXAMPLE ST, BOISE" })
            assertEquals(3, fs.count { it.id.startsWith("REGISTRY-ID-") })
            assertEquals("REGISTRY-nostate", src.run(ScanInput("garbage", InputType.ADDRESS)).toList().single().id)
        }
    }
}
