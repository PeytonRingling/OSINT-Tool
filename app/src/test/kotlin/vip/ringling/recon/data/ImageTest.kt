package vip.ringling.recon.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import vip.ringling.recon.data.sources.image.ImageAnalyzer
import vip.ringling.recon.domain.Severity

class ImageTest {
    private fun bytes(name: String) = checkNotNull(javaClass.getResourceAsStream("/fixtures/$name")).readBytes()
    private fun analyze(name: String) = bytes(name).let { ImageAnalyzer.analyze(name, it.inputStream(), it) }

    @Test fun exifWithGpsIsFlaggedAndGeolocated() {
        val f = analyze("exif_gps.jpg")
        assertEquals(Severity.EXPOSED, f.severity)
        val geo = assertNotNull(f.geo)
        assertEquals(43.608, geo.lat, 0.001); assertEquals(-116.2, geo.lng, 0.001)
        fun fact(l: String) = f.facts.first { it.label == l }.value
        assertEquals("Fictional Cameras FC-100", fact("Camera")); assertEquals("Jane Example", fact("Owner / artist"))
        assertEquals("SN123456", fact("Camera serial")); assertTrue(fact("Taken").startsWith("2024-05-17"))
        assertEquals(64, fact("SHA-256").length)
        assertTrue("GPS" in f.tags && f.links.size >= 4)
    }

    @Test fun strippedImageHasHashesButNoLocation() {
        val f = analyze("exif_none.jpg")
        assertEquals(null, f.geo); assertEquals(Severity.INFO, f.severity)
        assertTrue(f.detail.startsWith("No EXIF"), f.detail)
        assertTrue(f.facts.any { it.label == "SHA-256" })
    }
}
