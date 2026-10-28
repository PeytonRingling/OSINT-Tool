package vip.ringling.recon.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import vip.ringling.recon.data.net.Http
import vip.ringling.recon.data.net.HttpException
import vip.ringling.recon.data.secret.InMemoryApiKeys
import vip.ringling.recon.data.sources.breach.BreachSource
import vip.ringling.recon.data.sources.breach.HibpClient
import vip.ringling.recon.domain.ApiKeyId
import vip.ringling.recon.domain.InputType
import vip.ringling.recon.domain.ScanInput
import vip.ringling.recon.domain.Severity

class BreachSourceTest {
    private val email = ScanInput("a@b.com", InputType.EMAIL)

    @Test fun emailWithBothKeysMapsBreachesPastesAndLeakCheck() = runTest {
        val (srv, router) = server(
            "/hibp/breachedaccount/" to { json(fixture("hibp_breaches.json")) },
            "/hibp/pasteaccount/" to { json(fixture("hibp_pastes.json")) },
            "/lc/v2/query/" to { json(fixture("leakcheck_v2.json")) },
        )
        srv.use {
            val src = BreachSource(Http(), srv.endpoints(), InMemoryApiKeys(mapOf(ApiKeyId.HIBP to "hk", ApiKeyId.LEAKCHECK to "lk")))
            val f = src.collect(email)

            val adobe = f.first { it.title.startsWith("Adobe") }
            assertEquals("Adobe · 2013", adobe.title)
            assertEquals(Severity.EXPOSED, adobe.severity, "passwords exposed")
            assertTrue("153M accounts" in adobe.detail || "152M accounts" in adobe.detail, adobe.detail)
            assertEquals(Severity.INFO, f.first { it.title.startsWith("Dropbox") }.severity, "email-only breach is info")
            assertEquals(Severity.EXPOSED, f.first { it.id.endsWith("hibp-pastes") }.severity)
            val leak = f.first { it.id == "BREACH-leakcheck" }
            assertEquals(Severity.EXPOSED, leak.severity, "password field present")
            assertTrue("Collection #1" in leak.detail)

            val hibpReq = router.seen.first { it.path!!.startsWith("/hibp/breachedaccount/a%40b.com") }
            assertEquals("hk", hibpReq.getHeader("hibp-api-key"))
            assertNotNull(hibpReq.getHeader("User-Agent")).let { assertTrue(it.startsWith("Recon-Android") && "github" !in it, it) }
            assertEquals("lk", router.seen.first { it.path!!.startsWith("/lc/v2/") }.getHeader("X-API-Key"))
        }
    }

    @Test fun hibp404MeansCleanNotError() = runTest {
        val (srv, _) = server(
            "/hibp/breachedaccount/" to { MockResponse().setResponseCode(404) },
            "/hibp/pasteaccount/" to { MockResponse().setResponseCode(404) },
            "/lc/public" to { json(fixture("leakcheck_public_none.json")) },
        )
        srv.use {
            val f = BreachSource(Http(), srv.endpoints(), InMemoryApiKeys(mapOf(ApiKeyId.HIBP to "hk"))).collect(email)
            assertTrue(f.any { it.title == "No known breaches" && it.severity == Severity.CLEAN })
            assertTrue(f.any { it.title == "No public pastes" })
            assertTrue(f.none { it.title.contains("unavailable") })
        }
    }

    @Test fun rateLimitHonorsRetryAfterThenSucceeds() = runTest {
        var calls = 0
        val (srv, _) = server("/hibp/breachedaccount/" to {
            if (calls++ == 0) MockResponse().setResponseCode(429).setHeader("Retry-After", "3") else json("[]")
        })
        srv.use {
            val slept = mutableListOf<Long>()
            val client = HibpClient(Http(), srv.endpoints().hibp, sleep = { slept += it })
            assertEquals(emptyList(), client.breaches("a@b.com", "k"))
            assertEquals(listOf(3000L), slept)
            assertEquals(2, calls)
        }
    }

    @Test fun rateLimitBeyondCapOrSecondStrikeFails() = runTest {
        val (srv, _) = server("/hibp/breachedaccount/" to { MockResponse().setResponseCode(429).setHeader("Retry-After", "600") })
        srv.use {
            val slept = mutableListOf<Long>()
            val ex = assertFailsWith<HttpException> { HibpClient(Http(), srv.endpoints().hibp, sleep = { slept += it }).breaches("a@b.com", "k") }
            assertEquals(429, ex.code)
            assertTrue(slept.isEmpty(), "must not sleep for 10 minutes")
        }
        var n = 0
        val (srv2, _) = server("/hibp/breachedaccount/" to { n++; MockResponse().setResponseCode(429).setHeader("Retry-After", "1") })
        srv2.use {
            assertFailsWith<HttpException> { HibpClient(Http(), srv2.endpoints().hibp, sleep = {}).breaches("a@b.com", "k") }
            assertEquals(2, n, "one retry only")
        }
    }

    @Test fun badHibpKeyDegradesToNoteAndLeakCheckStillRuns() = runTest {
        val (srv, _) = server(
            "/hibp/" to { MockResponse().setResponseCode(401) },
            "/lc/public" to { json(fixture("leakcheck_public.json")) },
        )
        srv.use {
            val f = BreachSource(Http(), srv.endpoints(), InMemoryApiKeys(mapOf(ApiKeyId.HIBP to "bad"))).collect(email)
            val err = f.first { it.id == "BREACH-hibp-error" }
            assertTrue("rejected the API key" in err.detail, err.detail)
            val leak = f.first { it.id == "BREACH-leakcheck" }
            assertEquals(Severity.INFO, leak.severity, "no password field")
            assertTrue(leak.raw.contains("\"public_endpoint\":true"))
        }
    }

    @Test fun noKeysEmailUsesPublicLeakCheckAndExplainsHibpSkip() = runTest {
        val (srv, router) = server("/lc/public" to { json(fixture("leakcheck_public.json")) })
        srv.use {
            val f = BreachSource(Http(), srv.endpoints(), InMemoryApiKeys()).collect(email)
            assertTrue(f.any { it.id == "BREACH-hibp-nokey" })
            assertTrue(f.any { it.id == "BREACH-leakcheck" })
            assertTrue(router.seen.none { it.path!!.startsWith("/hibp") }, "no HIBP call without a key")
            assertTrue(router.seen.single().path!!.contains("check=a%40b.com"))
        }
    }

    @Test fun phoneAndUsernameWorkKeylessViaPublicEndpointAndKeyedViaV2() = runTest {
        val (srv, router) = server(
            "/lc/public" to { json(fixture("leakcheck_public.json")) },
            "/lc/v2/query/" to { json(fixture("leakcheck_v2.json")) },
        )
        srv.use {
            val keyless = BreachSource(Http(), srv.endpoints(), InMemoryApiKeys())
            val phone = keyless.collect(ScanInput("+1 (208) 555-0142".filter { it.isDigit() || it == '+' }, InputType.PHONE))
            assertEquals("BREACH-leakcheck", phone.single().id)
            assertTrue(router.seen.single().path!!.endsWith("check=12085550142"), "public endpoint rejects '+', so digits only: ${router.seen.single().path}")
            keyless.collect(ScanInput("ghost", InputType.USERNAME))
            assertTrue(router.seen.last().path!!.endsWith("check=ghost"))

            val keyed = BreachSource(Http(), srv.endpoints(), InMemoryApiKeys(mapOf(ApiKeyId.LEAKCHECK to "lk")))
            keyed.collect(ScanInput("ghost", InputType.USERNAME))
            assertTrue(router.seen.last().path!!.contains("type=username"))
        }
    }

    @Test fun leakCheckNotFoundIsCleanNotAnError() = runTest {
        val (srv, _) = server("/lc/public" to { json(fixture("leakcheck_notfound.json")) }, "/lc/v2/query/" to { json(fixture("leakcheck_notfound.json")) })
        srv.use {
            val pub = BreachSource(Http(), srv.endpoints(), InMemoryApiKeys()).collect(ScanInput("ghost", InputType.USERNAME)).single()
            assertEquals(Severity.CLEAN, pub.severity); assertEquals("LeakCheck: no results", pub.title)
            val keyed = BreachSource(Http(), srv.endpoints(), InMemoryApiKeys(mapOf(ApiKeyId.LEAKCHECK to "k"))).collect(ScanInput("ghost", InputType.USERNAME)).single()
            assertEquals(Severity.CLEAN, keyed.severity)
        }
    }

    @Test fun leakCheckApiErrorBecomesUnavailableFinding() = runTest {
        val (srv, _) = server("/lc/v2/query/" to { json(fixture("leakcheck_error.json"), 401) })
        srv.use {
            val f = BreachSource(Http(), srv.endpoints(), InMemoryApiKeys(mapOf(ApiKeyId.LEAKCHECK to "x"))).collect(ScanInput("ghost", InputType.USERNAME))
            val err = f.single()
            assertEquals("BREACH-leakcheck-error", err.id)
            assertTrue("Invalid X-API-Key" in err.detail, err.detail)
        }
    }

    @Test fun malformedJsonIsContained() = runTest {
        val (srv, _) = server("/lc/public" to { json("<html>oops</html>") })
        srv.use {
            val f = BreachSource(Http(), srv.endpoints(), InMemoryApiKeys()).collect(email)
            assertTrue(f.any { it.id == "BREACH-leakcheck-error" })
        }
    }
}
