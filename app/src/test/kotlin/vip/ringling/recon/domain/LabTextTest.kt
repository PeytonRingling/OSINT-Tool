package vip.ringling.recon.domain

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import vip.ringling.recon.data.lab.QrReader

class LabTextTest {
    @Test fun scamTextIsScoredAndCleanTextIsNot() {
        val s = ScamCheck.analyze("URGENT: Your PayPal account is suspended. Verify your account within 24 hours at http://paypa1-secure.top/login or call +1 800 555 0100")
        assertTrue(s.verdict == Verdict.FORGED || s.verdict == Verdict.SUSPICIOUS, s.verdict.name)
        assertTrue(s.flags.any { "Mentions paypal" in it.text }); assertEquals(listOf("http://paypa1-secure.top/login"), s.links)
        assertEquals(Verdict.CLEAN, ScamCheck.analyze("Lunch at noon? I'll bring the notes from Tuesday.").verdict)
    }

    @Test fun decoderHandlesBase64HexUrlJwtAndTimes() {
        assertEquals("hello world", LabDecoder.run("aGVsbG8gd29ybGQ=").first { it.label == "Base64 → text" }.value)
        assertEquals("hi!", LabDecoder.run("686921").first { it.label == "Hex → text" }.value)
        assertEquals("a b&c", LabDecoder.run("a%20b%26c").first { it.label == "URL-decoded" }.value)
        assertEquals("1970-01-01 00:00:00 UTC", LabDecoder.run("0000000000").first { it.label == "Unix time (seconds)" }.value)
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIiwiZXhwIjoxNzAwMDAwMDAwfQ.sig"
        val r = LabDecoder.run(jwt)
        assertTrue(r.any { it.label == "JWT payload" && "\"sub\"" in it.value } && r.any { it.label == "Expires" && it.value.startsWith("2023-11-14") } && r.any { it.label == "Note" })
    }

    @Test fun qrRoundTrip() {
        val m = QRCodeWriter().encode("https://example.com/a?b=1", BarcodeFormat.QR_CODE, 300, 300)
        val px = IntArray(300 * 300) { if (m.get(it % 300, it / 300)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        assertEquals("https://example.com/a?b=1", QrReader.decode(300, 300, px))
        assertEquals(null, QrReader.decode(10, 10, IntArray(100) { 0xFFFFFFFF.toInt() }))
    }
}
