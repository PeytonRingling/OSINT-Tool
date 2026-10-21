package vip.ringling.recon.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import vip.ringling.recon.data.lab.VirusTotal
import vip.ringling.recon.data.lab.VtResult
import vip.ringling.recon.data.net.Endpoints
import vip.ringling.recon.data.net.Http
import vip.ringling.recon.data.secret.InMemoryApiKeys
import vip.ringling.recon.domain.ApiKeyId
import vip.ringling.recon.domain.Severity

class VirusTotalTest {
    private val report = """{"data":{"attributes":{"reputation":-12,"last_analysis_date":1760000000,
        "last_analysis_stats":{"malicious":3,"suspicious":1,"harmless":60,"undetected":8,"timeout":0},
        "last_analysis_results":{"EngineB":{"category":"malicious","result":"phishing"},"EngineA":{"category":"malicious","result":"malware"},"EngineC":{"category":"harmless","result":"clean"},"EngineD":{"category":"suspicious","result":"suspicious"}}}}}"""

    private fun vt(base: String, key: String? = "k") = VirusTotal(Http(), InMemoryApiKeys(if (key == null) emptyMap() else mapOf(ApiKeyId.VIRUSTOTAL to key)), Endpoints(virusTotal = "$base/vt"))

    @Test fun parsesTheVerdictAndSendsTheKeyHeaderToTheDomainEndpoint() = runTest {
        val (srv, router) = server("/vt/domains/bad.example" to { json(report) })
        srv.use {
            val r = vt(srv.url("/").toString().trimEnd('/')).domain("Bad.Example") as VtResult.Found
            assertEquals(3, r.verdict.malicious); assertEquals(72, r.verdict.total); assertEquals(listOf("EngineA", "EngineB", "EngineD"), r.verdict.flaggedBy)
            assertEquals("k", router.seen.single().getHeader("x-apikey"))
            val f = VirusTotal.flags(r, "domain").single()
            assertEquals(Severity.EXPOSED, f.severity); assertTrue("3 of 72 engines" in f.text && "EngineA" in f.text, f.text)
        }
    }

    @Test fun urlLookupUsesTheBase64UrlIdAndNotFoundIsNotAnAccusation() = runTest {
        val (srv, router) = server("/vt/urls/" to { MockResponse().setResponseCode(404).setBody("{}") })
        srv.use {
            val r = vt(srv.url("/").toString().trimEnd('/')).url("https://example.com/a?b=1")
            assertTrue(r is VtResult.Unknown)
            assertEquals("/vt/urls/aHR0cHM6Ly9leGFtcGxlLmNvbS9hP2I9MQ", router.seen.single().path)       // base64url, no padding
            assertEquals(Severity.INFO, VirusTotal.flags(r, "link").single().severity)
        }
    }

    @Test fun noKeyMeansNoRequestAndErrorsAreReadable() = runTest {
        val (srv, router) = server("/vt/" to { MockResponse().setResponseCode(429).setBody("{}") })
        srv.use {
            val base = srv.url("/").toString().trimEnd('/')
            assertEquals(VtResult.NoKey, vt(base, null).domain("a.example")); assertEquals(0, router.seen.size)
            val f = vt(base).domain("a.example") as VtResult.Failed
            assertTrue("rate limit" in f.message)
        }
        assertEquals(64, VirusTotal.urlPage("https://example.com").substringAfterLast('/').length)
    }
}
