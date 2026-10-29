package vip.ringling.recon.data

import kotlin.test.Test
import kotlin.test.assertNotNull
import vip.ringling.recon.domain.ScanRunner
import vip.ringling.recon.domain.ModuleId
import vip.ringling.recon.domain.Finding
import vip.ringling.recon.domain.PaidLookups
import vip.ringling.recon.domain.PaidAction
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import vip.ringling.recon.data.net.Http
import vip.ringling.recon.data.secret.InMemoryApiKeys
import vip.ringling.recon.data.sources.phone.Notes800
import vip.ringling.recon.data.sources.phone.PhoneAnalyzer
import vip.ringling.recon.data.sources.phone.PhoneSource
import vip.ringling.recon.domain.ApiKeyId
import vip.ringling.recon.domain.InputType
import vip.ringling.recon.domain.ScanInput
import vip.ringling.recon.domain.Severity

class PhoneAnalyzerTest {
    private fun ok(raw: String, region: String = "US") = (PhoneAnalyzer.analyze(raw, region) as PhoneAnalyzer.Result.Ok).info

    @Test fun usNumberIsAnalyzedOffline() {
        val i = ok("+1 650 253 0000")
        assertTrue(i.valid && i.nanp); assertEquals("+16502530000", i.e164); assertEquals("US", i.regionCode); assertEquals("United States", i.countryName)
        assertEquals("(650) 253-0000", i.national); assertEquals("+1 650-253-0000", i.international)
        assertTrue(i.lineType.startsWith("Landline or mobile"), i.lineType)
        assertTrue(i.place.contains("Mountain View") || i.place.contains("CA"), "geocoder: '${i.place}'")
        assertTrue(i.timeZones.any { it.startsWith("America/") }, i.timeZones.toString())
        assertEquals("", i.carrier, "US numbers are portable: the numbering plan has no carrier")
    }

    @Test fun nationalFormatUsesTheDefaultRegion() {
        assertEquals("+16502530000", ok("(650) 253-0000", "US").e164)
        val uk = ok("07400 123456", "GB")
        assertEquals("GB", uk.regionCode); assertEquals("Mobile", uk.lineType); assertEquals("+447400123456", uk.e164); assertEquals("United Kingdom", uk.countryName)
    }

    @Test fun nonUsNumbersGetCountryDetail() {
        val uk = ok("+44 20 7946 0958")
        assertEquals("GB", uk.regionCode); assertTrue(uk.valid); assertTrue(!uk.nanp)
        assertTrue(uk.place.contains("London"), uk.place)
    }

    @Test fun invalidAndUnparseableNumbers() {
        val bad = ok("+1 123 456 7890")
        assertTrue(!bad.valid); assertEquals("", bad.place); assertEquals("", bad.carrier)
        assertTrue(PhoneAnalyzer.analyze("abc", "US") is PhoneAnalyzer.Result.Unparseable)
        assertTrue((PhoneAnalyzer.analyze("+999 123 4567", "US") as PhoneAnalyzer.Result.Unparseable).reason.contains("country code"))
        assertTrue((PhoneAnalyzer.analyze("+1 2", "US") as? PhoneAnalyzer.Result.Unparseable) != null)
    }
}

class Notes800Test {
    @Test fun parsesReportsCallersDatesAndPageCount() {
        val n = Notes800.parse(fixture("800notes_reports.html"))
        assertEquals(19, n.pages); assertEquals(3, n.reports.size, "ad rows are skipped")
        assertEquals(listOf("Bank Alert"), n.reports[0].callers); assertEquals("2024-03-02", n.reports[0].date)
        assertTrue(n.reports[0].text.startsWith("Automated robocall"))
        assertEquals(listOf("Warranty Dept"), n.reports[2].callers, "only 'Caller:' lines count as callers")
    }

    @Test fun noCommentsMeansNoReports() {
        val n = Notes800.parse(fixture("800notes_none.html")); assertEquals(0, n.pages); assertTrue(n.reports.isEmpty())
    }

    @Test fun urlOnlyForNanpNumbers() {
        assertEquals("https://800notes.com/Phone.aspx/1-208-555-0142", Notes800.urlFor("https://800notes.com", "+12085550142"))
        assertNull(Notes800.urlFor("https://800notes.com", "+447400123456"))
    }
}

class PhoneSourceTest {
    private val phone = ScanInput("+16502530000", InputType.PHONE)
    private fun source(srv: okhttp3.mockwebserver.MockWebServer, keys: Map<ApiKeyId, String> = emptyMap()) =
        PhoneSource(Http(), srv.endpoints(), InMemoryApiKeys(keys)).also { it.defaultRegion = { "US" } }

    @Test fun offlineAnalysisAndLinksWorkWithNoKeysAndNoNetwork() = runTest {
        val (srv, _) = server("/notes/" to { MockResponse().setResponseCode(503) })
        srv.use {
            val f = source(srv).collect(phone)
            val a = f.first { it.id == "PHONE-analysis" }
            assertEquals("(650) 253-0000", a.title); assertTrue("Valid United States number" in a.detail, a.detail)
            assertEquals("+16502530000", a.facts.first { it.label == "E.164" }.value)
            val links = f.first { it.id == "PHONE-links" }.links
            assertTrue(links.any { it.url.startsWith("https://www.google.com/search?q=") && "%22%28650%29+253-0000%22" in it.url })
            assertTrue(links.any { it.url == "https://wa.me/16502530000" } && links.any { it.url == "https://t.me/+16502530000" })
            assertTrue(links.none { listOf("spokeo", "whitepages", "beenverified", "intelius", "radaris").any { b -> b in it.url } }, "no people-search brokers")
            assertEquals("800notes unavailable", f.first { it.id == "PHONE-notes-error" }.title)
        }
    }

    @Test fun spamReportsAreSummarizedAndFlagged() = runTest {
        val (srv, router) = server("/notes/Phone.aspx/1-650-253-0000" to { MockResponse().setResponseCode(200).setBody(fixture("800notes_reports.html")) })
        srv.use {
            val n = source(srv).collect(phone).first { it.id == "PHONE-notes" }
            assertEquals(Severity.EXPOSED, n.severity); assertEquals("363+ community reports", n.title)
            assertTrue("Claimed callers: Bank Alert (2×)" in n.detail && "scam, spam or telemarketing" in n.detail && "unverified" in n.detail, n.detail)
            assertTrue("Spam/scam reports" in n.tags && "Spam/scam reports" in n.hotTags)
            assertEquals("2024-03-02 → 2024-03-09", n.facts.first { it.label == "Reports shown span" }.value)
            assertTrue(router.seen.single().getHeader("User-Agent")!!.startsWith("Mozilla/5.0"))
        }
    }

    @Test fun nobodyHasReportedItIsClean() = runTest {
        val (srv, _) = server("/notes/" to { MockResponse().setResponseCode(200).setBody(fixture("800notes_none.html")) })
        srv.use { assertEquals(Severity.CLEAN, source(srv).collect(phone).first { it.id == "PHONE-notes" }.severity) }
    }

    @Test fun veriphoneFlagsVoip() = runTest {
        val (srv, router) = server(
            "/notes/" to { MockResponse().setResponseCode(404) },
            "/veriphone/verify" to { json("""{"status":"success","phone_valid":true,"phone_type":"voip","carrier":"Bandwidth","phone_region":"California","country":"United States","local_number":"(650) 253-0000"}""") },
        )
        srv.use {
            val v = source(srv, mapOf(ApiKeyId.VERIPHONE to "vk")).collect(phone).first { it.id == "PHONE-veriphone" }
            assertEquals(Severity.EXPOSED, v.severity); assertTrue("VoIP" in v.tags && "Bandwidth" in v.title)
            assertEquals("Bandwidth", v.facts.first { it.label == "Current carrier" }.value)
            assertTrue(router.seen.any { "key=vk" in it.path!! && "phone=%2B16502530000" in it.path!! })
        }
    }

    private val twilioOk = """{"phone_number":"+16502530000","valid":true,"caller_name":{"caller_name":"ACME CORP","caller_type":"BUSINESS","error_code":null},"line_type_intelligence":{"type":"landline","carrier_name":"Example Telecom"}}"""

    @Test fun twilioIsOnlyOfferedWithItsCostAndNeverCalledByAScan() = runTest {
        val (srv, router) = server("/notes/" to { MockResponse().setResponseCode(404) }, "/twilio/PhoneNumbers/" to { json(twilioOk) })
        srv.use {
            val f = source(srv, mapOf(ApiKeyId.TWILIO_SID to "ACxxxx", ApiKeyId.TWILIO_TOKEN to "tok")).collect(phone)
            val offer = f.single { it.id == "PHONE-twilio-offer" }
            val paid = assertNotNull(offer.paid)
            assertTrue("0.018" in paid.cost && "0.010" in paid.cost && "0.008" in paid.cost, paid.cost)
            assertTrue("Nothing is sent until you confirm" in offer.detail)
            assertTrue(router.seen.none { it.path!!.startsWith("/twilio") }, "a scan must never contact Twilio on its own")
            assertTrue(f.none { it.id == "PHONE-twilio" })
        }
    }

    @Test fun twilioRunsOnlyAfterConfirmationAndUsesBasicAuth() = runTest {
        val (srv, router) = server("/notes/" to { MockResponse().setResponseCode(404) }, "/twilio/PhoneNumbers/" to { json(twilioOk) })
        srv.use {
            val t = source(srv, mapOf(ApiKeyId.TWILIO_SID to "ACxxxx", ApiKeyId.TWILIO_TOKEN to "tok")).runPaid(PhoneSource.TWILIO_ACTION, phone).single()
            assertEquals("ACME CORP", t.title)
            assertTrue("Caller-ID name registered for this number: ACME CORP (business)" in t.detail && "Current carrier: Example Telecom" in t.detail, t.detail)
            val req = router.seen.single { it.path!!.startsWith("/twilio/") }
            assertEquals("Basic " + java.util.Base64.getEncoder().encodeToString("ACxxxx:tok".toByteArray()), req.getHeader("Authorization"))
            assertTrue("Fields=caller_name,line_type_intelligence" in req.path!!)
        }
    }

    @Test fun twilioNeedsBothCredentialsAndExplainsErrors() = runTest {
        val (srv, router) = server("/notes/" to { MockResponse().setResponseCode(404) }, "/twilio/" to { MockResponse().setResponseCode(401) })
        srv.use {
            assertTrue(source(srv, mapOf(ApiKeyId.TWILIO_SID to "AC")).collect(phone).none { it.id.startsWith("PHONE-twilio") }, "half a credential offers nothing")
            val noKeys = source(srv, mapOf(ApiKeyId.TWILIO_SID to "AC")).runPaid(PhoneSource.TWILIO_ACTION, phone).single()
            assertEquals("Twilio unavailable", noKeys.title)
            assertTrue(router.seen.none { it.path!!.startsWith("/twilio") })
            val err = source(srv, mapOf(ApiKeyId.TWILIO_SID to "AC", ApiKeyId.TWILIO_TOKEN to "bad")).runPaid(PhoneSource.TWILIO_ACTION, phone).single()
            assertEquals("PHONE-twilio-error", err.id); assertTrue("rejected the Account SID" in err.detail, err.detail)
            assertTrue(source(srv).runPaid("something-else", phone).isEmpty(), "unknown actions do nothing")
        }
    }

    @Test fun controllerReplacesTheOfferWithPendingThenTheResult() = runTest {
        val offer = Finding("PHONE-twilio-offer", ModuleId.PHONE, "Paid", "d", Severity.INFO, paid = PaidAction("twilio", "Look up", "about \$0.018", "x"))
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val lookups = object : PaidLookups {
            override suspend fun runPaid(actionId: String, input: ScanInput): List<Finding> { gate.await(); return listOf(Finding("PHONE-twilio", ModuleId.PHONE, "ACME", "d", Severity.INFO)) }
        }
        val controller = ScanController(ScanRunner(emptySet()), InMemoryHistoryRepository(), SnapshotRepository(FakeSnapshotDao()), setOf(lookups))
        val field = ScanController::class.java.getDeclaredField("_session").also { it.isAccessible = true }
        @Suppress("UNCHECKED_CAST") val state = field.get(controller) as kotlinx.coroutines.flow.MutableStateFlow<vip.ringling.recon.domain.ScanSession?>
        state.value = vip.ringling.recon.domain.ScanSession(phone, findings = listOf(offer))
        controller.runPaid(offer)
        assertEquals(listOf("PHONE-twilio-pending"), state.value!!.findings.map { it.id })
        gate.complete(Unit)
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { kotlinx.coroutines.withTimeout(5_000) { while (state.value!!.findings.singleOrNull()?.id != "PHONE-twilio") kotlinx.coroutines.delay(10) } }
    }

    @Test fun unreadableNumbersAreReportedNotCrashed() = runTest {
        val (srv, _) = server("/notes/" to { MockResponse().setResponseCode(404) })
        srv.use {
            val f = source(srv).collect(ScanInput("+999", InputType.PHONE))
            assertEquals("Can't read this number", f.single().title)
        }
    }

    @Test fun pivotRequestsAreHeldUntilConsumed() = runTest {
        val controller = ScanController(vip.ringling.recon.domain.ScanRunner(emptySet()), InMemoryHistoryRepository(), SnapshotRepository(FakeSnapshotDao()), emptySet())
        assertNull(controller.pendingQuery.value)
        controller.requestSearch("someone@example.com"); assertEquals("someone@example.com", controller.pendingQuery.value)
        controller.consumePending(); assertNull(controller.pendingQuery.value)
    }
}

class ProxyStateTest {
    @Test fun modesMapToSocksAndFailClosed() {
        val s = vip.ringling.recon.data.net.ProxyState
        val m = vip.ringling.recon.data.net.ProxyMode.entries.associateBy { it.name }
        s.apply(m.getValue("OFF"), "", 0); assertEquals(java.net.Proxy.NO_PROXY, s.selector.select(null).single())
        s.apply(m.getValue("TOR"), "", 0); val tor = s.selector.select(null).single()
        assertEquals(java.net.Proxy.Type.SOCKS, tor.type()); assertEquals(9050, (tor.address() as java.net.InetSocketAddress).port)
        s.apply(m.getValue("CUSTOM"), "10.0.0.2", 1080); assertEquals("10.0.0.2", (s.selector.select(null).single().address() as java.net.InetSocketAddress).hostString)
        s.apply(m.getValue("CUSTOM"), "", 0); assertEquals(java.net.Proxy.Type.SOCKS, s.selector.select(null).single().type(), "invalid custom proxy must fail closed")
        s.apply(m.getValue("OFF"), "", 0)
    }

    @Test fun nothingLeavesBeforeTheSavedSettingIsApplied() {
        val s = vip.ringling.recon.data.net.ProxyState
        val server = okhttp3.mockwebserver.MockWebServer().apply { enqueue(okhttp3.mockwebserver.MockResponse().setBody("hi")); start() }
        try {
            s.reset()
            assertEquals(false, s.ready)
            val p = s.selector.select(java.net.URI("https://example.com")).single()
            assertEquals(java.net.Proxy.Type.SOCKS, p.type(), "unknown setting must not mean direct")
            val http = vip.ringling.recon.data.net.Http()
            val sent = kotlinx.coroutines.runBlocking { runCatching { http.execute(okhttp3.Request.Builder().url(server.url("/")).build()) { it.code } } }
            assertEquals(true, sent.isFailure, "request must fail while the proxy setting is unknown")
            assertEquals(0, server.requestCount, "nothing reached the network directly")
            s.apply(vip.ringling.recon.data.net.ProxyMode.OFF, "", 0)
            assertEquals(true, s.ready)
            assertEquals(200, kotlinx.coroutines.runBlocking { http.execute(okhttp3.Request.Builder().url(server.url("/")).build()) { it.code } })
        } finally { s.apply(vip.ringling.recon.data.net.ProxyMode.OFF, "", 0); server.shutdown() }
    }
}
