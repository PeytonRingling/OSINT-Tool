package vip.ringling.recon.security

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import java.net.InetSocketAddress
import java.net.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import vip.ringling.recon.data.directNetworkForTests
import vip.ringling.recon.data.lab.LinkTracer
import vip.ringling.recon.data.net.Http
import vip.ringling.recon.data.net.Opsec
import vip.ringling.recon.ui.LinkOpener
import vip.ringling.recon.ui.components.LocalOpsecImages
import vip.ringling.recon.ui.components.RemoteImage
import vip.ringling.recon.ui.theme.ReconTheme

class OpsecPolicyTest {
    private val tor = Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved("127.0.0.1", 9050))

    @Test fun directContactIsBlockedOnlyInOpsecWithoutAProxy() {
        assertNull(Opsec.blockReason(on = false, proxy = null))
        assertNotNull(Opsec.blockReason(on = true, proxy = null))
        assertNull(Opsec.blockReason(on = true, proxy = tor))
    }

    @Test fun linksOpenOnlyInTorBrowserOrAreCopied() {
        assertEquals(LinkOpener.Route.BROWSER, LinkOpener.route(opsec = false, torBrowserInstalled = false))
        assertEquals(LinkOpener.Route.TOR_BROWSER, LinkOpener.route(opsec = true, torBrowserInstalled = true))
        assertEquals(LinkOpener.Route.COPY, LinkOpener.route(opsec = true, torBrowserInstalled = false))
    }

    @Test fun theLinkInspectorDoesNotContactTheSiteInOpsecWithoutAProxy() {
        directNetworkForTests()
        val s = MockWebServer().apply { repeat(2) { enqueue(MockResponse().setResponseCode(200)) }; start() }
        try {
            val tracer = LinkTracer(Http(), allowPrivate = true)
            Opsec.on = true
            val blocked = runBlocking { tracer.trace(s.url("/x").toString()) }
            assertEquals(0, s.requestCount, "nothing sent")
            assertEquals(true, blocked.error?.contains("OPSEC"), blocked.error)
            Opsec.on = false
            assertEquals(200, runBlocking { tracer.trace(s.url("/x").toString()) }.hops.single().code)
        } finally { Opsec.on = false; s.shutdown() }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OpsecImageTest {
    @get:Rule val rule = createComposeRule()

    @Test fun photosWaitForATapInOpsecMode() {
        rule.setContent {
            ReconTheme { CompositionLocalProvider(LocalOpsecImages provides true) { RemoteImage("https://target.example/avatar.png", "Avatar", Modifier.size(96.dp)) } }
        }
        rule.onNodeWithText("Tap to load").assertExists()
        rule.onNodeWithContentDescription("Photo not loaded (OPSEC mode). Tap to load.").performClick()
        rule.onNodeWithText("Tap to load").assertDoesNotExist()
    }

    @Test fun photosLoadNormallyWithOpsecOff() {
        rule.setContent { ReconTheme { RemoteImage("https://target.example/avatar.png", "Avatar", Modifier.size(96.dp)) } }
        rule.onNodeWithText("Tap to load").assertDoesNotExist()
    }
}
