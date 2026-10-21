package vip.ringling.recon.data

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import vip.ringling.recon.data.lab.LinkIntel
import vip.ringling.recon.data.net.Http
import vip.ringling.recon.data.sources.domain.DohClient
import vip.ringling.recon.domain.IncomingText
import vip.ringling.recon.domain.Severity

private fun dns(vararg ips: String) = MockResponse().setHeader("Content-Type", "application/dns-json").setBody("""{"Status":0,"Answer":[${ips.joinToString(",") { """{"name":"x.example.","type":1,"TTL":60,"data":"$it"}""" }}]}""")
private fun rdap(date: String) = MockResponse().setBody("""{"events":[{"eventAction":"registration","eventDate":"$date"}]}""")

class LinkIntelTest {
    private fun intel(base: String) = LinkIntel(Http(), DohClient(Http(), "$base/doh"), DohClient(Http(), "$base/sdoh"), "$base/rdap")

    @Test fun flagsAKnownBadDomainAndAVeryNewRegistrationWithoutTouchingTheSite() = runTest {
        val recent = Instant.now().minusSeconds(3 * 86400).toString()
        val (srv, router) = server("/doh" to { dns("203.0.113.5") }, "/sdoh" to { dns("0.0.0.0") }, "/rdap" to { rdap(recent) })
        srv.use {
            val f = intel(srv.url("/").toString().trimEnd('/')).check("login.paypa1-secure.example")
            assertTrue(f.any { it.severity == Severity.EXPOSED && "malicious" in it.text })
            assertTrue(f.any { it.severity == Severity.EXPOSED && "Registered only 3 days ago" in it.text }, f.toString())
            assertTrue(router.seen.all { it.path!!.startsWith("/doh") || it.path!!.startsWith("/sdoh") || it.path!!.startsWith("/rdap/domain/paypa1-secure.example") }, router.seen.map { it.path }.toString())
        }
    }

    @Test fun anOldCleanDomainIsReportedClean() = runTest {
        val (srv, _) = server("/doh" to { dns("203.0.113.5") }, "/sdoh" to { dns("203.0.113.5") }, "/rdap" to { rdap("2001-01-01T00:00:00Z") })
        srv.use { assertTrue(intel(srv.url("/").toString().trimEnd('/')).check("example.com").all { it.severity == Severity.CLEAN }) }
    }

    @Test fun agesAndSharedLinksAreClassified() {
        val now = Instant.parse("2026-10-21T00:00:00Z")
        assertEquals(Severity.INFO, LinkIntel.ageFlag(Instant.parse("2026-07-01T00:00:00Z"), now).severity)
        assertEquals("example.co", LinkIntel.registrable("a.b.example.co"))
        assertTrue(IncomingText.isLink("https://bit.ly/abc") && !IncomingText.isLink("see https://bit.ly/abc") && !IncomingText.isLink("example.com"))
    }
}
