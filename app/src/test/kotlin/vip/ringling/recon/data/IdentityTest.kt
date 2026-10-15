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
        assertTrue(sites.size >= 30, "expected a curated list, got ${sites.size}")
        assertEquals(sites.size, sites.map { it.name }.distinct().size, "duplicate site names")
        sites.forEach { s ->
            assertTrue("{u}" in s.url, "${s.name} url has no {u}")
            assertTrue(s.url.startsWith("https://"), "${s.name} must be https")
            if (s.method == Method.message) assertTrue(s.errorMsg != null || s.foundMsg != null, "${s.name} needs errorMsg or foundMsg")
            if (s.method == Method.redirect) assertTrue(s.errorUrl != null, "${s.name} needs errorUrl")
            s.usernameRegex?.let { Regex(it) } // must compile
        }
    }

    @Test fun wmnDerivedListParses() {
        val sites = SiteList.parse(java.io.File("src/main/assets/wmn_sites.json").readText())
        assertTrue(sites.size >= 100, "got ${sites.size}")
        sites.forEach { s -> assertTrue("{u}" in s.url && s.url.startsWith("https://") && s.foundMsg != null, s.name) }
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

            val hits = f.filter { it.id != "IDENTITY-summary" && it.id != "IDENTITY-manual" }.associateBy { it.title }
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

    @Test fun foundMsgRedirectChallengeAndBrowserUserAgent() = runTest {
        val (srv, router) = server(
            "/hr/ghost" to { MockResponse().setResponseCode(200).setBody("<title>ghost | HackerRank</title>") },
            "/hr/nobody" to { MockResponse().setResponseCode(200).setBody("<title>Programming Problems :: HackerRank</title>") },
            "/wp/" to { MockResponse().setResponseCode(302).setHeader("Location", "/wordpress.com/typo?subdomain=x") },
            "/wordpress.com/typo" to { MockResponse().setResponseCode(200).setBody("create a site") },
            "/wall/" to { MockResponse().setResponseCode(200).setBody("<title>Just a moment...</title>") },
            "/cf/" to { MockResponse().setResponseCode(403).setBody("<title>Attention Required! | Cloudflare</title>") },
        )
        srv.use {
            val base = srv.url("/").toString().trimEnd('/')
            val list = listOf(
                Site("HR", "$base/hr/{u}", Method.message, foundMsg = "{u} | HackerRank"),
                Site("WP", "$base/wp/{u}", Method.redirect, errorUrl = "wordpress.com/typo"),
                Site("Wall", "$base/wall/{u}", Method.status),
                Site("CF", "$base/cf/{u}", Method.status),
            )
            val src = IdentitySource(Http(), srv.endpoints(), { list }).also { it.limiter = RateLimiter(4, 0) }
            val hits = src.collect(ScanInput("ghost", InputType.USERNAME))
            assertEquals(listOf("HR"), hits.filter { it.id != "IDENTITY-summary" && it.id != "IDENTITY-manual" }.map { it.title }, "only the foundMsg site matches")
            val s = hits.single { it.id == "IDENTITY-summary" }
            assertTrue("1 found, 1 not found" in s.detail && "2 inconclusive" in s.detail, s.detail)
            assertTrue("Wall (bot challenge)" in s.raw && "CF (bot challenge)" in s.raw, s.raw)
            assertTrue(router.seen.all { it.getHeader("User-Agent")!!.startsWith("Mozilla/5.0") }, "site probes use a browser UA")
            val none = src.collect(ScanInput("nobody", InputType.USERNAME))
            assertTrue(none.none { it.title == "HR" }, "page without the profile marker is absent")
        }
    }

    @Test fun networkErrorsAreInconclusiveNotAbsent() = runTest {
        val (srv, _) = server("/s/ok/" to { MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST) })
        srv.use {
            val base = srv.url("/").toString().trimEnd('/')
            val src = IdentitySource(Http(), srv.endpoints(), { listOf(Site("Flaky", "$base/s/ok/{u}")) }).also { it.limiter = RateLimiter(2, 0) }
            val s = src.collect(user).single { it.id == "IDENTITY-summary" }
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

    @Test fun profileEnrichmentCorrelatesAcrossSitesAndOffersPivots() = runTest {
        val (srv, _) = server(
            "/a/" to { MockResponse().setResponseCode(200).setBody("""{"name":"Jane Doe","location":"Boise","website_url":"https://jane.example/blog","github_username":"janed","profile_image":"https://img.example/a.png"}""") },
            "/b/" to { MockResponse().setResponseCode(200).setBody("""{"displayName":"Jane Doe","bio":"hi","them":[{"proofs_summary":{"g":[{"nametag":"jd_other"}]}}]}""") },
        )
        srv.use {
            val base = srv.url("/").toString().trimEnd('/')
            val list = listOf(
                Site("A", "$base/a/{u}", Method.status, Confidence.HIGH, extract = vip.ringling.recon.data.sources.identity.Extract(
                    facts = mapOf("Name" to "name", "Location" to "location", "Website" to "website_url"), avatar = "profile_image", handles = listOf("github_username"))),
                Site("B", "$base/b/{u}", Method.status, Confidence.HIGH, extract = vip.ringling.recon.data.sources.identity.Extract(
                    facts = mapOf("Name" to "displayName"), handles = listOf("them.0.proofs_summary.*.*.nametag"))),
            )
            val src = IdentitySource(Http(), srv.endpoints(), { list }).also { it.limiter = RateLimiter(2, 0) }
            val f = src.collect(user)
            val a = f.single { it.id == "IDENTITY-A" }
            assertEquals("https://img.example/a.png", a.imageUrl)
            assertTrue(a.pivots.any { it.query == "janed" })
            val profile = f.single { it.id == "IDENTITY-profile" }
            assertTrue(profile.facts.single { it.label == "Name" }.value.contains("Jane Doe (A, B)"), profile.facts.toString())
            assertTrue(profile.pivots.any { it.query == "jane.example" })
            assertTrue(profile.pivots.any { it.query == "jd_other" })
            assertTrue(profile.links.any { "lens.google.com" in it.url })
            assertTrue(f.single { it.id == "IDENTITY-manual" }.links.size >= 10)
        }
    }

    @Test fun extractorHandlesJsonXmlMetaAndGarbage() {
        val x = vip.ringling.recon.data.sources.identity.Extractor
        val j = x.extract(vip.ringling.recon.data.sources.identity.Extract(facts = mapOf("N" to "0.name", "D" to "0.at|date", "H" to "0.site|host")), """[{"name":"X","at":"2020-01-02T03:04","site":"https://www.a.io/p"}]""")!!
        assertEquals(listOf("N" to "X", "D" to "2020-01-02", "H" to "a.io"), j.facts)
        val xml = x.extract(vip.ringling.recon.data.sources.identity.Extract(kind = "xml", facts = mapOf("N" to "profile > realname"), avatar = "profile > avatarFull"), "<response><profile><realname>Al</realname><avatarFull>http://i/x.jpg</avatarFull></profile></response>")!!
        assertEquals(listOf("N" to "Al"), xml.facts); assertEquals("http://i/x.jpg", xml.avatar)
        val meta = x.extract(vip.ringling.recon.data.sources.identity.Extract(kind = "meta", facts = mapOf("N" to "og:title")), """<meta property="og:title" content="Telegram: View"/>""")!!
        assertEquals(listOf("N" to "Telegram: View"), meta.facts)
        assertEquals(null, x.extract(vip.ringling.recon.data.sources.identity.Extract(), "<<not json"))
    }
}
