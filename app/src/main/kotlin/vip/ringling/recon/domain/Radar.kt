package vip.ringling.recon.domain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** One scanned target as shown on the radar: the latest scan of each distinct input. */
data class RadarTarget(
    val input: ScanInput,
    val risk: Int,
    /** Offender-registry record count for ZIP targets (counts only, never records); null when unknown. */
    val records: Int?,
    val lastScanMillis: Long,
    val scans: Int,
    /** History record to open (null for ZIP: registry results are never stored, so they are re-run live). */
    val recordId: String?,
) {
    val key: String get() = "${input.type.name}:${input.raw}"
    val isArea: Boolean get() = input.type.countsOnly
    val critical: Boolean get() = !isArea && risk >= 50
    val clean: Boolean get() = if (isArea) records == 0 else risk == 0
}

data class Blip(val target: RadarTarget, val x: Double, val y: Double)

/** Geometry shared by the radar canvas and its tests, in the design's 390 × 420 space (centre 195,210; outer ring 192). */
object RadarMath {
    const val CX = 195.0
    const val CY = 210.0
    const val OUTER = 192.0

    /** radius = 34 + (100 − risk) × 1.55, so risk 100 sits near the centre and risk 0 near the outer ring. */
    fun radiusFor(risk: Int): Double = 34.0 + (100 - risk.coerceIn(0, 100)) * 1.55

    /** Areas have no risk score: place them on the middle ring when they hold records, near the rim when empty. */
    fun effectiveRisk(t: RadarTarget): Int = if (t.isArea) (if ((t.records ?: 1) > 0) 45 else 5) else t.risk

    /** Stable angle in degrees (0 = 12 o'clock, clockwise) derived from the target key only. */
    fun angleFor(key: String): Double {
        var h = 1125899906842597L
        key.forEach { c -> h = 31 * h + c.code }
        return abs(h % 36000) / 100.0
    }

    /** Places every target by risk and stable angle, nudging the angle in small steps when two blips would overlap. */
    fun place(targets: List<RadarTarget>, minGap: Double = 44.0): List<Blip> {
        val placed = mutableListOf<Blip>()
        for (t in targets.sortedWith(compareByDescending<RadarTarget> { effectiveRisk(it) }.thenBy { it.key })) {
            val r0 = radiusFor(effectiveRisk(t))
            val base = angleFor(t.key)
            var best: Blip? = null
            // Prefer the exact radius; when crowded, walk the angle first, then step the radius slightly in and out.
            loop@ for (dr in doubleArrayOf(0.0, 12.0, -12.0, 24.0, -24.0)) {
                val r = (r0 + dr).coerceIn(22.0, OUTER - 12.0)
                for (step in 0..36) {
                    val delta = if (step == 0) 0.0 else ((step + 1) / 2) * 9.0 * (if (step % 2 == 1) 1 else -1)
                    val a = Math.toRadians(base + delta - 90.0)
                    val b = Blip(t, CX + r * cos(a), CY + r * sin(a))
                    if (placed.none { hypot(it.x - b.x, it.y - b.y) < minGap }) { best = b; break@loop }
                    if (best == null) best = b
                }
            }
            placed += best!!
        }
        return placed
    }

    /** Latest scan per distinct input, newest first. */
    fun targets(records: List<ScanRecord>, recordCounts: Map<String, Int> = emptyMap()): List<RadarTarget> =
        records.groupBy { it.input.type to it.input.raw }.map { (_, rs) ->
            val latest = rs.maxBy { it.timestampMillis }
            val zip = latest.input.type.countsOnly
            RadarTarget(latest.input, if (zip) 0 else latest.risk, if (zip) recordCounts["${latest.input.type.name}:${latest.input.raw}"] else null, latest.timestampMillis, rs.size, if (zip) null else latest.id)
        }.sortedByDescending { it.lastScanMillis }
}

/** Same data as the Log: latest snapshot per distinct target, newest first (so the radar and the Log can never disagree). */
fun RadarMath.targetsFromSnapshots(snapshots: List<ScanSnapshot>): List<RadarTarget> =
    snapshots.groupBy { it.key }.values.map { l ->
        val latest = l.maxBy { it.ts }
        val zip = latest.isArea
        RadarTarget(ScanInput(latest.value, latest.type), if (zip) 0 else latest.risk, if (zip) latest.records else null, latest.ts, l.size, if (zip) null else latest.id)
    }.sortedByDescending { it.lastScanMillis }
