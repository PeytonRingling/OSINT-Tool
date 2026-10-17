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

    @Test fun linksAreNormalisedAndOnlyWebLinksPass() {
        val n = vip.ringling.recon.ui.LinkOpener::normalize
        assertEquals("https://example.com/a?b=1", n(" https://example.com/a?b=1 ")); assertEquals("https://www.wikidata.org/wiki/Q1", n("//www.wikidata.org/wiki/Q1"))
        assertEquals("https://example.com/x", n("example.com/x")); assertEquals("HTTP://a.org", n("HTTP://a.org"))
        listOf("", "  ", "javascript:alert(1)", "intent://x#Intent;end", "tel:123", "file:///etc/passwd", "two words.com", "localhost").forEach { assertEquals(null, n(it), it) }
    }
}
