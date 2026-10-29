package vip.ringling.recon.security

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test
import vip.ringling.recon.data.directNetworkForTests
import vip.ringling.recon.data.net.BROWSER_USER_AGENT
import vip.ringling.recon.data.net.Http
import vip.ringling.recon.domain.Incoming
import vip.ringling.recon.domain.IncomingText

class IncomingTextRouteTest {
    private fun route(action: String, text: String? = null, type: String? = "text/plain", scheme: String? = null, ssp: String? = null) =
        IncomingText.route(action, type, text, scheme, ssp)

    @Test fun everyOutsideInputOnlyPrefills() {
        assertEquals(Incoming.Prefill("ann@example.com"), route("android.intent.action.SEND", "ann@example.com"))
        assertEquals(Incoming.Prefill("torvalds"), route("android.intent.action.PROCESS_TEXT", "  torvalds \nsecond line"))
        assertEquals(Incoming.Prefill("ann@example.com"), route("android.intent.action.VIEW", type = null, scheme = "mailto", ssp = "ann@example.com?subject=hi"))
        assertEquals(Incoming.Prefill("+1 (208) 555-0100"), route("android.intent.action.DIAL", type = null, scheme = "tel", ssp = "+1 (208) 555-0100"))
        assertEquals(Incoming.Link("https://example.com/x"), route("android.intent.action.SEND", "https://example.com/x"))
        assertEquals(Incoming.ClipboardOnFocus, route(IncomingText.ACTION_SCAN_CLIPBOARD, type = null))
    }

    @Test fun ignoresWhatItCannotUse() {
        assertNull(route("android.intent.action.SEND", "x", type = "image/png"))
        assertNull(route("android.intent.action.SEND", "   "))
        assertNull(route("android.intent.action.VIEW", type = null, scheme = "https", ssp = "//example.com"))
        assertNull(route("vip.ringling.recon.SOMETHING_ELSE", "x"))
    }

    @Test fun prefillNeverStartsASearch() {
        val controller = vip.ringling.recon.data.ScanController(vip.ringling.recon.domain.ScanRunner(emptySet()), vip.ringling.recon.data.InMemoryHistoryRepository(), vip.ringling.recon.data.SnapshotRepository(vip.ringling.recon.data.FakeSnapshotDao()), emptySet())
        controller.requestPrefill("someone@example.com")
        assertEquals("someone@example.com", controller.prefill.value, "held until the UI takes it (cold start)")
        assertNull(controller.pendingQuery.value, "outside input must not queue a search")
        controller.consumePrefill(); assertNull(controller.prefill.value)
    }
}

class UserAgentTest {
    @Test fun requestsDoNotNameTheToolOrItsRepo() {
        directNetworkForTests()
        val s = MockWebServer().apply { enqueue(MockResponse().setBody("ok")); start() }
        try {
            runBlocking { Http().execute(Request.Builder().url(s.url("/")).build()) { it.code } }
            val ua = s.takeRequest().getHeader("User-Agent").orEmpty()
            assertEquals(BROWSER_USER_AGENT, ua)
            assertFalse(ua.contains("recon", ignoreCase = true) || ua.contains("github", ignoreCase = true), ua)
        } finally { s.shutdown() }
    }
}
