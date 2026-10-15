package vip.ringling.recon.data

import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import vip.ringling.recon.domain.Finding
import vip.ringling.recon.domain.InputType
import vip.ringling.recon.domain.ModuleId
import vip.ringling.recon.domain.RadarMath
import vip.ringling.recon.domain.RadarTarget
import vip.ringling.recon.domain.ScanInput
import vip.ringling.recon.domain.ScanRecord
import vip.ringling.recon.domain.ScanSnapshot
import vip.ringling.recon.domain.Severity

class RadarTest {
    private fun t(raw: String, type: InputType, risk: Int, records: Int? = null) = RadarTarget(ScanInput(raw, type), risk, records, 0, 1, "id")

    @Test fun radiusFollowsTheSpecFormula() {
        assertEquals(34.0, RadarMath.radiusFor(100), 1e-9)
        assertEquals(34.0 + 100 * 1.55, RadarMath.radiusFor(0), 1e-9)
        assertEquals(34.0 + 55 * 1.55, RadarMath.radiusFor(45), 1e-9)
        assertEquals(RadarMath.radiusFor(0), RadarMath.radiusFor(-5), "risk is clamped")
    }

    @Test fun anglesAreStableAndBlipsKeepTheirRadius() {
        assertEquals(RadarMath.angleFor("EMAIL:a@b.com"), RadarMath.angleFor("EMAIL:a@b.com"))
        assertTrue(RadarMath.angleFor("EMAIL:a@b.com") in 0.0..360.0)
        val targets = listOf(t("a@b.com", InputType.EMAIL, 100), t("jane", InputType.USERNAME, 10), t("83338", InputType.ZIP, 0, 68))
        val first = RadarMath.place(targets); val second = RadarMath.place(targets.reversed())
        assertEquals(first.associate { it.target.key to it.x to it.y }.size, 3)
        first.forEach { b ->
            val other = second.first { it.target.key == b.target.key }
            assertEquals(b.x, other.x, 1e-9, "placement must not depend on list order"); assertEquals(b.y, other.y, 1e-9)
            val r = hypot(b.x - RadarMath.CX, b.y - RadarMath.CY)
            assertEquals(RadarMath.radiusFor(RadarMath.effectiveRisk(b.target)), r, 1e-6)
        }
    }

    @Test fun crowdedBlipsAreNudgedApart() {
        val many = (1..12).map { t("user$it", InputType.USERNAME, 50) }
        val blips = RadarMath.place(many)
        val separated = blips.indices.count { i -> blips.indices.all { j -> i == j || hypot(blips[i].x - blips[j].x, blips[i].y - blips[j].y) >= 20 } }
        assertTrue(separated >= 10, "most blips should not overlap: $separated")
    }

    @Test fun latestScanPerTargetAndAreasHaveNoRisk() {
        val e = ScanInput("a@b.com", InputType.EMAIL); val z = ScanInput("83338", InputType.ZIP)
        val f = Finding("BREACH-hibp-X", ModuleId.BREACH, "X", "d", Severity.EXPOSED)
        val recs = listOf(ScanRecord("1", e, 10, emptyList()), ScanRecord("2", e, 20, listOf(f)), ScanRecord("3", z, 30, emptyList()))
        val targets = RadarMath.targets(recs, mapOf("ZIP:83338" to 68))
        assertEquals(2, targets.size)
        val email = targets.first { it.input.type == InputType.EMAIL }
        assertEquals(14, email.risk); assertEquals("2", email.recordId); assertEquals(2, email.scans)
        val zip = targets.first { it.isArea }
        assertEquals(0, zip.risk); assertEquals(68, zip.records); assertEquals(null, zip.recordId, "registry results are never stored, so a ZIP blip re-runs the lookup")
        assertEquals(45, RadarMath.effectiveRisk(zip)); assertEquals(5, RadarMath.effectiveRisk(zip.copy(records = 0)))
    }

    @Test fun snapshotsStoreCountsOnlyForAreas() {
        val reg = Finding("REGISTRY-ID-A-1", ModuleId.REGISTRY, "NAME", "d", Severity.INFO)
        val area = ScanSnapshot.of("s", ScanInput("83338", InputType.ZIP), listOf(reg, reg.copy(id = "REGISTRY-ID-A-2"), Finding("REGISTRY-summary", ModuleId.REGISTRY, "t", "d", Severity.INFO)), 5)
        assertEquals(2, area.records); assertEquals(0, area.risk); assertTrue(area.findingIds.isEmpty(), "nothing about registrants is stored")
        val email = ScanSnapshot.of("s2", ScanInput("a@b.com", InputType.EMAIL), listOf(Finding("BREACH-hibp-A", ModuleId.BREACH, "A", "d", Severity.EXPOSED), Finding("IDENTITY-summary", ModuleId.IDENTITY, "s", "d", Severity.INFO)), 6)
        assertEquals(14, email.risk); assertEquals(1, email.exposed); assertEquals(0, email.info); assertEquals(listOf("BREACH-hibp-A"), email.findingIds)
        assertEquals(null, ScanSnapshot.fromRecord(ScanRecord("z", ScanInput("83301", InputType.ZIP), 1, emptyList())).records, "older rows have no count")
    }
}
