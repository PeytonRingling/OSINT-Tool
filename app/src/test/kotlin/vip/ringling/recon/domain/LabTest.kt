package vip.ringling.recon.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LabTest {
    private val forged = """
        Received: from mail.example.net (mail.example.net [203.0.113.9])
         by mx.google.com with ESMTPS id abc; Mon, 5 Oct 2026 10:00:00 -0700
        Received: from localhost (10.0.0.5) by mail.example.net with SMTP; Mon, 5 Oct 2026 09:59:58 -0700
        Authentication-Results: mx.google.com; spf=fail smtp.mailfrom=bad.example; dkim=none; dmarc=fail header.from=paypal.com
        Return-Path: <x@bad.example>
        From: "PayPal Support <support@paypal.com>" <alert@paypal.com.secure-login.example>
        Reply-To: help@scam.example
        Subject: Verify your account
        Message-ID: <1@bad.example>

        body text that must be ignored: From: nobody@ignored.example
    """.trimIndent()

    @Test fun flagsAForgedMessageAndExtractsLeads() {
        val r = HeaderAnalyzer.analyze(forged)!!
        assertEquals(Verdict.FORGED, r.verdict); assertEquals("fail", r.spf); assertEquals("fail", r.dmarc)
        assertTrue(r.flags.any { it.severity == Severity.EXPOSED && "Replies go to a different domain" in it.text })
        assertTrue(r.flags.any { "display name" in it.text })
        assertEquals("203.0.113.9", r.hops.first().ip ?: r.hops.last().ip)          // public hop found; the private 10.x hop is not a lead
        assertTrue(r.leads.any { it.query == "203.0.113.9" } && r.leads.any { it.query == "scam.example" })
        assertTrue(r.leads.none { it.query.contains("ignored") })
    }

    @Test fun aCleanMessagePassesAndGarbageIsRejected() {
        val ok = HeaderAnalyzer.analyze("Authentication-Results: mx; spf=pass; dkim=pass; dmarc=pass\nReturn-Path: <a@example.com>\nFrom: Alice <a@mail.example.com>\nMessage-ID: <1@example.com>\n")!!
        assertEquals(Verdict.CLEAN, ok.verdict)
        assertNull(HeaderAnalyzer.analyze("just some words"))
    }

    @Test fun linkTricksAreFlagged() {
        val f = LinkAnalysis.analyze(listOf("http://bit.ly/x", "http://203.0.113.7/login"))
        assertTrue(f.any { "shortener" in it.text } && f.any { it.severity == Severity.EXPOSED && "bare IP" in it.text } && f.any { "not encrypted" in it.text })
        assertTrue(LinkAnalysis.analyze(listOf("https://xn--pypal-4ve.com/")).any { "xn--" in it.text })
        assertTrue(LinkAnalysis.analyze(listOf("https://example.com/")).single().severity == Severity.CLEAN)
        assertTrue(LinkAnalysis.analyze(listOf("https://paypal.com@evil.example/")).any { "user@host" in it.text })
    }
}
