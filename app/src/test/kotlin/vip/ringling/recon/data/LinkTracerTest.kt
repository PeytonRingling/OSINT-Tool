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
            val r = LinkTracer(Http(), allowPrivate = true).trace(s.url("/one").toString())
            assertEquals(listOf(301, 302, 200), r.hops.map { it.code }); assertEquals(null, r.error)
            assertEquals(s.url("/three").toString(), r.hops.last().url)
        } finally { s.shutdown() }
    }

    @Test fun neverTouchesPrivateOrLocalAddresses() = runTest {
        val s = MockWebServer().apply { enqueue(MockResponse().setBody("secret admin page")); start() }
        try {
            val r = LinkTracer(Http()).trace(s.url("/").toString())            // default: private addresses refused
            assertEquals(true, r.error!!.startsWith("Refused")); assertEquals(0, s.requestCount)
        } finally { s.shutdown() }
        listOf("http://localhost/x", "http://192.168.1.1/", "http://169.254.169.254/latest/meta-data", "http://[::1]/", "http://100.64.0.1/", "http://printer.local/").forEach {
            assertEquals(true, LinkTracer.blockedHost(java.net.URI(it).host), it)
        }
        assertEquals(false, LinkTracer.blockedHost("example.com"))
    }

    @Test fun neverConnectsOverPlainHttp() = runTest {
        val r = LinkTracer(Http()).trace("http://example.com/login")
        assertEquals(true, r.error!!.startsWith("Not contacted")); assertEquals(listOf(null), r.hops.map { it.code })
    }

    @Test fun stopsAtTheHopLimitAndRejectsNonWebLinks() = runTest {
        val s = MockWebServer().apply { repeat(5) { enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/loop")) }; start() }
        try { assertEquals("Stopped after 3 redirects.", LinkTracer(Http(), allowPrivate = true).trace(s.url("/loop").toString(), maxHops = 3).error) } finally { s.shutdown() }
        assertNull(LinkTracer.normalize("javascript:alert(1)")); assertNull(LinkTracer.normalize("two words"))
        assertEquals("https://example.com", LinkTracer.normalize("example.com"))
    }
}
