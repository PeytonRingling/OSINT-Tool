package vip.ringling.recon.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import vip.ringling.recon.data.net.Http
import vip.ringling.recon.data.sources.breach.PwnedPasswords

class PwnedPasswordsTest {
    @Test fun sendsOnlyTheHashPrefixAndFindsTheSuffix() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("0018A45C4D1DEF81644B54AB7F969B88D65:0\r\n1E4C9B93F3F0682250B6CF8331B7EE68FD8:9545824\r\n011053FD0102E94D6AE2F8B83D76FAF94F6:1"))
        server.start()
        try {
            val n = PwnedPasswords(Http(), server.url("").toString().trimEnd('/')).count("password")
            assertEquals(9545824L, n)
            val req = server.takeRequest()
            assertEquals("/range/5BAA6", req.path)
            assertTrue(!req.path!!.contains("password", ignoreCase = true) && req.path!!.length == "/range/".length + 5)
            assertEquals("true", req.getHeader("Add-Padding"))
        } finally { server.shutdown() }
    }

    @Test fun paddingRowsAndMissingSuffixMeanNotFound() {
        assertEquals(0L, PwnedPasswords.parse("ABC:0\nDEF:3", "ABC"))
        assertEquals(0L, PwnedPasswords.parse("DEF:3", "ABC"))
        assertEquals("5BAA61E4C9B93F3F0682250B6CF8331B7EE68FD8", PwnedPasswords.sha1("password"))
    }
}
