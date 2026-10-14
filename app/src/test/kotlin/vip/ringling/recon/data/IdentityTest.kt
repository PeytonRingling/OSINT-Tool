package vip.ringling.recon.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import vip.ringling.recon.data.net.Http
import vip.ringling.recon.data.sources.identity.IdentitySource
import vip.ringling.recon.data.sources.identity.Method
import vip.ringling.recon.data.sources.identity.RateLimiter
import vip.ringling.recon.data.sources.identity.Site
import vip.ringling.recon.data.sources.identity.SiteList
import vip.ringling.recon.domain.Confidence
import vip.ringling.recon.domain.InputType
import vip.ringling.recon.domain.ScanInput
import vip.ringling.recon.domain.Severity

class IdentityTest {
    private val user = ScanInput("ghost_in_shell", InputType.USERNAME)

    @Test fun bundledSiteListParsesAndIsSane() {
        val text = checkNotNull(javaClass.getResourceAsStream("/../../../../../src/main/assets/sites.json") ?: java.io.File("src/main/assets/sites.json").takeIf { it.exists() }?.inputStream())
            .bufferedReader().readText()
        val sites = SiteList.parse(text)
        assertTrue(sites.size >= 35, "expected a curated list, got ${sites.size}")
        assertEquals(sites.size, sites.map { it.name }.distinct().size, "duplicate site names")
        sites.forEach { s ->
            assertTrue("{u}" in s.url, "${s.name} url has no {u}")
            assertTrue(s.url.startsWith("https://"), "${s.name} must be https")
            if (s.method == Method.message) assertTrue(s.errorMsg != null, "${s.name} needs errorMsg")
            s.usernameRegex?.let { Regex(it) } // must compile
        }
    }

    private fun sites(base: String) = listOf(
        Site("StatusOk", "$base/s/ok/{u}", Method.status, Confidence.HIGH),
        Site("StatusGone", "$base/s/gone/{u}", Method.status, Confidence.HIGH),
        Site("MsgGone", "$base/m/gone/{u}", Method.message, Confidence.MEDIUM, errorMsg = "no such user"),
        Site("MsgOk", "$base/m/ok/{u}", Method.message, Confidence.MEDIUM, errorMsg = "no such user"),
        Site("Blocked", "$base/blocked/{u}", Method.status),
        Site("Limited", "$base/limited/{u}", Method.status),
        Site("Api400", "$base/api400/{u}", Method.status, absentCodes = listOf(400)),
        Site("Redir", "$base/redir/{u}", Method.redirect, errorUrl = "/signup"),
        Site("StrictName", "$base/strict/{u}", Method.status, usernameRegex = "^[a-z]{1,5}$"),
    )

    @Test fun detectionMethodsAndConfidence() = runTest {
        val (srv, router) = server(
            "/s/ok/" to { MockResponse().setResponseCode(200).setBody("profile") },
            "/s/gone/" to { MockResponse().setResponseCode(404) },
            "/m/gone/" to { MockResponse().setResponseCode(200).setBody("<h1>No such user</h1>") },
            "/m/ok/" to { MockResponse().setResponseCode(200).setBody("welcome ghost") },
            "/blocked/" to { MockResponse().setResponseCode(403) },
            "/limited/" to { MockResponse().setResponseCode(429) },
            "/api400/" to { MockResponse().setResponseCode(400) },
            "/redir/" to { MockResponse().setResponseCode(302).setHeader("Location", "/signup?from=x") },
            "/signup" to { MockResponse().setResponseCode(200).setBody("sign up") },
        )
        srv.use {
            val base = srv.url("/").toString().trimEnd('/')
            val src = IdentitySource(Http(), srv.endpoints(), { sites(base) }).also { it.limiter = RateLimiter(4, 0) }
            val f = src.collect(user)

            val hits = f.filter { it.id != "IDENTITY-summary" }.associateBy { it.title }
            assertEquals(setOf("StatusOk", "MsgOk"), hits.keys, "only real hits are emitted")
            assertEquals(Confidence.HIGH, hits.getValue("StatusOk").confidence)
            assertEquals(Confidence.MEDIUM, hits.getValue("MsgOk").confidence)
            assertTrue(hits.values.all { it.severity == Severity.INFO })

            val summary = f.single { it.id == "IDENTITY-summary" }
            assertTrue("Checked 8 sites: 2 found, 4 not found" in summary.detail, summary.detail)
            assertTrue("2 inconclusive" in summary.detail, summary.detail)
            assertTrue("1 skipped" in summary.detail, summary.detail)
            assertTrue("Blocked (blocked)" in summary.raw && "Limited (rate-limited)" in summary.raw, summary.raw)
            assertTrue(router.seen.none { it.path!!.startsWith("/strict") }, "invalid username for site must not be probed")
        }
    }

    @Test fun networkErrorsAreInconclusiveNotAbsent() = runTest {
        val (srv, _) = server("/s/ok/" to { MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST) })
        srv.use {
            val base = srv.url("/").toString().trimEnd('/')
            val src = IdentitySource(Http(), srv.endpoints(), { listOf(Site("Flaky", "$base/s/ok/{u}")) }).also { it.limiter = RateLimiter(2, 0) }
            val s = src.collect(user).single()
            assertTrue("1 inconclusive" in s.detail, s.detail)
            assertTrue(s.severity != Severity.CLEAN, "can't claim clean when nothing was verified")
        }
    }

    @Test fun usernameIsUrlEncoded() = runTest {
        val (srv, router) = server("/s/ok/" to { MockResponse().setResponseCode(404) })
        srv.use {
            val base = srv.url("/").toString().trimEnd('/')
            IdentitySource(Http(), srv.endpoints(), { listOf(Site("A", "$base/s/ok/{u}")) }).also { it.limiter = RateLimiter(1, 0) }
                .collect(ScanInput("a b/c", InputType.USERNAME))
            assertEquals("/s/ok/a+b%2Fc", router.seen.single().path)
        }
    }

    @Test fun emailChecksGravatarAndSpotify() = runTest {
        val md5 = java.security.MessageDigest.getInstance("MD5").digest("a@b.com".toByteArray()).joinToString("") { "%02x".format(it) }
        val (srv, router) = server(
            "/gravatar/$md5.json" to { MockResponse().setResponseCode(200).setBody("{}") },
            "/spotify/signup/public/v1/account" to { json("""{"status":20,"errors":{"email":"That email is already registered."}}""") },
        )
        srv.use {
            val f = IdentitySource(Http(), srv.endpoints(), { emptyList() }).also { it.limiter = RateLimiter(2, 0) }
                .collect(ScanInput("A@B.com", InputType.EMAIL).let { it.copy(raw = "a@b.com") })
            assertEquals(Confidence.HIGH, f.first { it.title == "Gravatar" }.confidence)
            assertEquals(Confidence.MEDIUM, f.first { it.title == "Spotify" }.confidence)
            assertTrue(router.seen.any { it.path!!.contains("email=a%40b.com") })
        }
    }

    @Test fun emailAvailableAndOddResponses() = runTest {
        val (srv, _) = server(
            "/gravatar/" to { MockResponse().setResponseCode(404) },
            "/spotify/" to { json("""{"status":999}""") },
        )
        srv.use {
            val f = IdentitySource(Http(), srv.endpoints(), { emptyList() }).also { it.limiter = RateLimiter(2, 0) }
                .collect(ScanInput("a@b.com", InputType.EMAIL))
            val s = f.single()
            assertEquals("IDENTITY-email-summary", s.id)
            assertTrue("Not registered on: Gravatar" in s.detail && "Spotify (unexpected response)" in s.detail, s.detail)
        }
    }

    @Test fun rateLimiterCapsConcurrency() = runTest {
        val limiter = RateLimiter(2, 0)
        var active = 0
        var peak = 0
        kotlinx.coroutines.coroutineScope {
            repeat(8) {
                launch {
                    limiter.run { active++; peak = maxOf(peak, active); kotlinx.coroutines.delay(10); active-- }
                }
            }
        }
        assertEquals(2, peak)
    }
}
