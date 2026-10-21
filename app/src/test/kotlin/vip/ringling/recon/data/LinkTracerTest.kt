package vip.ringling.recon.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import vip.ringling.recon.data.lab.LinkTracer
import vip.ringling.recon.data.net.Http

class LinkTracerTest {
    @Test fun followsRedirectsHopByHopWithoutReadingBodies() = runTest {
        val s = MockWebServer().apply {
            enqueue(MockResponse().setResponseCode(301).setHeader("Location", "/two"))
            enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/three"))
            enqueue(MockResponse().setResponseCode(200).setBody("page"))
            start()
        }
        try {
            val r = LinkTracer(Http()).trace(s.url("/one").toString())
            assertEquals(listOf(301, 302, 200), r.hops.map { it.code }); assertEquals(null, r.error)
            assertEquals(s.url("/three").toString(), r.hops.last().url)
        } finally { s.shutdown() }
    }

    @Test fun stopsAtTheHopLimitAndRejectsNonWebLinks() = runTest {
        val s = MockWebServer().apply { repeat(5) { enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/loop")) }; start() }
        try { assertEquals("Stopped after 3 redirects.", LinkTracer(Http()).trace(s.url("/loop").toString(), maxHops = 3).error) } finally { s.shutdown() }
        assertNull(LinkTracer.normalize("javascript:alert(1)")); assertNull(LinkTracer.normalize("two words"))
        assertEquals("https://example.com", LinkTracer.normalize("example.com"))
    }
}
