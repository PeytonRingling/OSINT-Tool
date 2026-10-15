package vip.ringling.recon.domain

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.asin
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min

object Geo {
    private const val EARTH_M = 6_371_008.8
    private const val METERS_PER_MILE = 1609.344
    private const val TILE = 256.0

    fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLng / 2).pow(2)
        return 2 * EARTH_M * asin(min(1.0, sqrt(h)))
    }

    /** Points farther than [maxKm] from the median position, or null when there are too few to judge ([minPoints]). The registry's own geocoder occasionally lands an address in the wrong city. */
    fun outliers(points: List<GeoPoint>, maxKm: Double = 100.0, minPoints: Int = 4): Set<GeoPoint> {
        if (points.size < minPoints) return emptySet()
        fun median(v: List<Double>) = v.sorted().let { if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2 }
        val m = GeoPoint(median(points.map { it.lat }), median(points.map { it.lng }))
        return points.filter { distanceMeters(m, it) > maxKm * 1000 }.toSet()
    }

    /** US-style: feet under a tenth of a mile, then tenths, then whole miles. */
    fun formatDistance(meters: Double): String {
        val miles = meters / METERS_PER_MILE
        return when {
            miles < 0.1 -> "${(meters * 3.28084 / 10).toInt() * 10} ft"
            miles < 10 -> "%.1f mi".format(miles)
            else -> "${miles.toInt()} mi"
        }
    }

    // ---- Web Mercator (the OSM tile scheme) ----

    /** World pixel X/Y at (possibly fractional) zoom [z]; the world is 256·2^z pixels wide. */
    fun worldX(lng: Double, z: Double) = (lng + 180.0) / 360.0 * TILE * 2.0.pow(z)
    fun worldY(lat: Double, z: Double): Double {
        val l = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
        return (1.0 - ln(tan(l) + 1.0 / cos(l)) / PI) / 2.0 * TILE * 2.0.pow(z)
    }
    fun lngAt(worldX: Double, z: Double) = worldX / (TILE * 2.0.pow(z)) * 360.0 - 180.0
    fun latAt(worldY: Double, z: Double): Double {
        val n = PI - 2.0 * PI * worldY / (TILE * 2.0.pow(z))
        return Math.toDegrees(atan(sinh(n)))
    }

    /** Camera that shows every point with ~15% padding, within [minZoom], [maxZoom]. */
    fun fit(points: List<GeoPoint>, widthPx: Double, heightPx: Double, minZoom: Double = 9.0, maxZoom: Double = 17.0): Pair<GeoPoint, Double> {
        require(points.isNotEmpty())
        val minLat = points.minOf { it.lat }; val maxLat = points.maxOf { it.lat }
        val minLng = points.minOf { it.lng }; val maxLng = points.maxOf { it.lng }
        val center = GeoPoint((minLat + maxLat) / 2, (minLng + maxLng) / 2)
        if (points.size == 1 || (maxLat == minLat && maxLng == minLng)) return center to min(maxZoom, 15.0).coerceAtLeast(minZoom)
        val spanX = (worldX(maxLng, 0.0) - worldX(minLng, 0.0)).coerceAtLeast(1e-9)
        val spanY = (worldY(minLat, 0.0) - worldY(maxLat, 0.0)).coerceAtLeast(1e-9)
        val zx = log2(widthPx * 0.85 / spanX)
        val zy = log2(heightPx * 0.85 / spanY)
        return center to min(zx, zy).coerceIn(minZoom, maxZoom)
    }

    fun tileCount(z: Int) = 1 shl z

    fun tileRange(centerWorld: Double, halfExtentPx: Double, tileSizePx: Double, max: Int): IntRange {
        val lo = floor((centerWorld - halfExtentPx) / tileSizePx).toInt()
        val hi = floor((centerWorld + halfExtentPx) / tileSizePx).toInt()
        return max(0, lo)..min(max - 1, hi)
    }
}
