package vip.ringling.recon.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import vip.ringling.recon.data.net.Http
import vip.ringling.recon.data.net.ProxyMode
import vip.ringling.recon.data.net.RouteResult
import vip.ringling.recon.data.net.RouteTester

class RouteTesterTest {
    private fun server(body: String, code: Int = 200) = MockWebServer().apply { enqueue(MockResponse().setResponseCode(code).setBody(body)); start() }

    @Test fun recognisesATorExit() = runTest {
        val s = server("""{"IsTor":true,"IP":"185.220.101.4"}""")
        try {
            val r = RouteTester(Http(), s.url("").toString().trimEnd('/')).test()
            assertEquals(RouteResult.Tor("185.220.101.4"), r)
            assertTrue(RouteTester.describe(ProxyMode.TOR, r).let { it.second && "185.220.101.4" in it.first })
            assertEquals("/api/ip", s.takeRequest().path)
        } finally { s.shutdown() }
    }

    @Test fun warnsWhenTorModeIsNotActuallyTor() = runTest {
        val s = server("""{"IsTor":false,"IP":"203.0.113.9"}""")
        try {
            val r = RouteTester(Http(), s.url("").toString().trimEnd('/')).test()
            val (text, ok) = RouteTester.describe(ProxyMode.TOR, r)
            assertTrue(!ok && "Not going through Tor" in text && "Orbot" in text, text)
            assertTrue(RouteTester.describe(ProxyMode.OFF, r).first.contains("real address"))
        } finally { s.shutdown() }
    }

    @Test fun failuresAreReportedAsFailClosedWhenAProxyIsOn() = runTest {
        val s = server("nope", 503)
        try {
            val r = RouteTester(Http(), s.url("").toString().trimEnd('/')).test()
            assertTrue(r is RouteResult.Failed)
            assertTrue(RouteTester.describe(ProxyMode.TOR, r).first.contains("fail-closed"))
            assertTrue(!RouteTester.describe(ProxyMode.OFF, r).first.contains("fail-closed"))
        } finally { s.shutdown() }
    }
}
