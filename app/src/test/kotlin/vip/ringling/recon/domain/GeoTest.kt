package vip.ringling.recon.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeoTest {
    private val boise = GeoPoint(43.6150, -116.2023)
    private val meridian = GeoPoint(43.6121, -116.3915)

    @Test fun haversine() {
        assertEquals(0.0, Geo.distanceMeters(boise, boise), 1e-6)
        val km = Geo.distanceMeters(boise, meridian) / 1000
        assertTrue(km in 15.0..16.5, "Boise→Meridian ≈ 15.4 km, got $km")
        // NYC → LA ≈ 3936 km
        val d = Geo.distanceMeters(GeoPoint(40.7128, -74.0060), GeoPoint(34.0522, -118.2437)) / 1000
        assertTrue(d in 3900.0..3970.0, "$d")
        assertEquals(Geo.distanceMeters(boise, meridian), Geo.distanceMeters(meridian, boise), 1e-6)
    }

    @Test fun distanceFormatting() {
        assertEquals("150 ft", Geo.formatDistance(46.0))
        assertEquals("0.5 mi", Geo.formatDistance(805.0))
        assertEquals("3.1 mi", Geo.formatDistance(5000.0))
        assertEquals("12 mi", Geo.formatDistance(19_500.0))
    }

    @Test fun mercatorRoundTrip() {
        for (z in listOf(3.0, 12.0, 15.5)) {
            val x = Geo.worldX(boise.lng, z); val y = Geo.worldY(boise.lat, z)
            assertEquals(boise.lng, Geo.lngAt(x, z), 1e-9)
            assertEquals(boise.lat, Geo.latAt(y, z), 1e-9)
        }
        // Known OSM tile: zoom 0 is a single 256px tile; the equator/prime meridian is its center.
        assertEquals(128.0, Geo.worldX(0.0, 0.0), 1e-9); assertEquals(128.0, Geo.worldY(0.0, 0.0), 1e-9)
        // Boise at z=10 falls in tile 181/373 on the standard slippy-map formula.
        assertEquals(181, (Geo.worldX(boise.lng, 10.0) / 256).toInt()); assertEquals(373, (Geo.worldY(boise.lat, 10.0) / 256).toInt())
    }

    @Test fun fitCoversPointsAndClampsZoom() {
        val (c, z) = Geo.fit(listOf(boise, meridian), 800.0, 600.0)
        assertTrue(c.lng in meridian.lng..boise.lng && c.lat in 43.60..43.62)
        // Everything must be inside the viewport at the chosen zoom.
        for (p in listOf(boise, meridian)) {
            val dx = kotlin.math.abs(Geo.worldX(p.lng, z) - Geo.worldX(c.lng, z)); val dy = kotlin.math.abs(Geo.worldY(p.lat, z) - Geo.worldY(c.lat, z))
            assertTrue(dx <= 400 && dy <= 300, "dx=$dx dy=$dy z=$z")
        }
        assertEquals(15.0, Geo.fit(listOf(boise), 800.0, 600.0).second)
        assertEquals(9.0, Geo.fit(listOf(GeoPoint(30.0, -120.0), GeoPoint(48.0, -80.0)), 800.0, 600.0).second, "clamped to minZoom")
    }

    @Test fun tileRangeIsClamped() {
        assertEquals(0..1, Geo.tileRange(300.0, 300.0, 256.0, 2))
        assertEquals(2..4, Geo.tileRange(900.0, 200.0, 256.0, 100))
    }
}
