package vip.ringling.recon.data

import kotlin.test.Test
import kotlin.test.assertEquals
import vip.ringling.recon.domain.Evidence
import vip.ringling.recon.domain.Finding
import vip.ringling.recon.domain.IncomingText
import vip.ringling.recon.domain.ModuleId
import vip.ringling.recon.domain.Severity

class IncomingTextTest {
    @Test fun mailtoAndTelLinksBecomeQueries() {
        assertEquals("ann@b.com", IncomingText.fromUri("mailto", "ann@b.com?subject=hi&body=x"))
        assertEquals("ann@b.com", IncomingText.fromUri("MAILTO", "ann@b.com,other@b.com"))
        assertEquals("+1 208 555 0100", IncomingText.fromUri("tel", "+1 208 555 0100"))
        assertEquals(null, IncomingText.fromUri("https", "//example.com")); assertEquals(null, IncomingText.fromUri("tel", null))
    }

    @Test fun sharedTextUsesTheFirstNonBlankLineCapped() {
        assertEquals("first", IncomingText.clean("\n  first  \nsecond")); assertEquals(null, IncomingText.clean("  \n "))
        assertEquals(200, IncomingText.clean("x".repeat(500))!!.length)
    }

    @Test fun evidenceHashCoversTheContent() {
        val f = Finding("X-1", ModuleId.BREACH, "Adobe · 2013", "152M accounts", Severity.EXPOSED, url = "https://haveibeenpwned.com/x")
        val md = Evidence.markdown(f, 1_700_000_000_000)
        assertEquals(true, "Content SHA-256: ${Evidence.hash(f)}" in md && "2023-11-14" in md && "does not verify" in md, md)
        assertEquals(Evidence.hash(f), Evidence.hash(f.copy())); assertEquals(false, Evidence.hash(f) == Evidence.hash(f.copy(detail = "changed")))
    }

    @Test fun cappedReadStopsAtTheLimitWithoutReadNBytes() {
        val data = ByteArray(300_000) { (it % 251).toByte() }
        assertEquals(1000, vip.ringling.recon.data.sources.image.ImageSource.readCapped(data.inputStream(), 1000).size)
        assertEquals(300_000, vip.ringling.recon.data.sources.image.ImageSource.readCapped(data.inputStream(), 1_000_000).size)
        assertEquals(0, vip.ringling.recon.data.sources.image.ImageSource.readCapped(ByteArray(0).inputStream(), 10).size)
    }
}
