package vip.ringling.recon.data

import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import vip.ringling.recon.data.net.Http
import vip.ringling.recon.data.net.Metered
import vip.ringling.recon.data.net.QuotaInterceptor
import vip.ringling.recon.data.net.QuotaMeter
import vip.ringling.recon.data.net.buildHttpClient
import vip.ringling.recon.data.sources.dorks.Variants
import vip.ringling.recon.domain.InputType
import vip.ringling.recon.domain.ScanInput

class QuotaTest {
    private fun t(s: String) = Instant.parse(s).toEpochMilli()
    private fun meter() = QuotaMeter(zone = { ZoneOffset.UTC })

    @Test fun hostsMapToTheirService() {
        assertEquals(Metered.VERIPHONE, Metered.of("api.veriphone.io"))
        assertEquals(Metered.HIBP, Metered.of("haveibeenpwned.com"))
        assertNull(Metered.of("crt.sh"), "keyless sources aren't metered")
        assertNull(Metered.of("evilhaveibeenpwned.com"))
    }

    @Test fun countsRollOverByDayAndMonth() {
        val m = meter()
        m.record(Metered.VERIPHONE, t("2026-10-30T10:00:00Z"), 200)
        m.record(Metered.VERIPHONE, t("2026-10-30T11:00:00Z"), 200)
        m.record(Metered.VERIPHONE, t("2026-10-31T09:00:00Z"), 200)
        assertEquals("1 today · 3 of 1,000 this month", m.describe(Metered.VERIPHONE, m.usage.value[Metered.VERIPHONE], t("2026-10-31T12:00:00Z")))
        assertEquals("0 today · 0 of 1,000 this month", m.describe(Metered.VERIPHONE, m.usage.value[Metered.VERIPHONE], t("2026-11-01T12:00:00Z")))
    }

    @Test fun rateLimitAndNearLimitAreFlagged() {
        val m = meter()
        repeat(400) { m.record(Metered.VIRUSTOTAL, t("2026-10-30T10:00:00Z"), 200) }
        m.record(Metered.VIRUSTOTAL, t("2026-10-30T10:30:00Z"), 429)
        val line = m.describe(Metered.VIRUSTOTAL, m.usage.value[Metered.VIRUSTOTAL], t("2026-10-30T12:40:00Z"))
        assertEquals("401 of 500 today · rate-limited 2 h ago · near the free limit", line)
        m.reportRemaining(Metered.LEAKCHECK, 37)
        assertTrue("37 left" in m.describe(Metered.LEAKCHECK, m.usage.value[Metered.LEAKCHECK], t("2026-10-30T12:40:00Z")))
    }

    @Test fun persistsAndRestores() {
        var saved: String? = null
        val a = meter().apply { attach(null) { saved = it } }
        a.record(Metered.FEC, t("2026-10-30T10:00:00Z"), 200, remaining = 998)
        assertTrue(saved!!.contains("998") && !saved!!.contains("http"), "only counts and codes are stored")
        val b = meter().apply { attach(saved) {} }
        assertEquals(a.usage.value, b.usage.value)
        meter().attach("not json") {} // a corrupt file is ignored, not fatal
    }

    @Test fun interceptorCountsResponsesAndReadsRemainingHeader() = runTest {
        var n = 0
        val (server, _) = server("/x" to { _ -> if (n++ == 0) MockResponse().setResponseCode(200).addHeader("X-RateLimit-Remaining", "41").setBody("{}") else MockResponse().setResponseCode(429) })
        val m = meter()
        val client = buildHttpClient(meter = QuotaMeter()).newBuilder().addInterceptor(QuotaInterceptor(m, resolve = { Metered.FEC })).build()
        val http = Http(client)
        repeat(2) { http.execute(Request.Builder().url(server.url("/x")).build()) { it.code } }
        val u = m.usage.value[Metered.FEC]!!
        assertEquals(2, u.today); assertEquals(41, u.remaining); assertEquals(429, u.lastCode)
        server.shutdown()
    }
}

class NamePermutationTest {
    @Test fun buildsCommonHandlesFromAName() {
        val v = Variants.fromName("José Q. Álvarez")
        assertEquals(listOf("josealvarez", "jose.alvarez", "jose_alvarez", "jalvarez", "j.alvarez", "alvarezjose", "alvarez.jose", "alvarezj", "josea", "jose.a", "joseqalvarez", "jqalvarez", "jqa", "josealvarez1"), v)
        assertTrue(Variants.fromName("Cher").isEmpty(), "one word gives nothing to permute")
    }

    @Test fun personSearchGetsUsernamePivots() {
        val f = Variants.finding(ScanInput("Jane Doe", InputType.PERSON))!!
        assertTrue(f.pivots.map { it.query }.containsAll(listOf("janedoe", "jdoe", "jane.doe")))
        assertTrue("guesses" in f.detail)
    }
}
