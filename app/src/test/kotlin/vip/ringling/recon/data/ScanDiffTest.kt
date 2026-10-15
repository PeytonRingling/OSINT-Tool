package vip.ringling.recon.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import vip.ringling.recon.domain.Finding
import vip.ringling.recon.domain.InputType
import vip.ringling.recon.domain.ModuleId
import vip.ringling.recon.domain.ScanDiffs
import vip.ringling.recon.domain.ScanInput
import vip.ringling.recon.domain.ScanSnapshot
import vip.ringling.recon.domain.Severity

class ScanDiffTest {
    private fun f(id: String, sev: Severity = Severity.EXPOSED) = Finding(id, ModuleId.BREACH, "T-$id", "d", sev)
    private val input = ScanInput("a@b.com", InputType.EMAIL)

    @Test fun reportsNewAndGoneFindingsAndScoreChange() {
        val prev = ScanSnapshot.of("1", input, listOf(f("BREACH-hibp-A"), f("BREACH-hibp-B", Severity.INFO)), 1)
        val findings = listOf(f("BREACH-hibp-A"), f("BREACH-hibp-C"))
        val now = ScanSnapshot.of("2", input, findings, 2)
        val d = ScanDiffs.compare(prev, now, findings)
        assertEquals(listOf("T-BREACH-hibp-C"), d.newTitles); assertEquals(1, d.goneCount)
        assertEquals(now.risk - prev.risk, d.delta); assertTrue(d.changed)
    }

    @Test fun identicalScansAreNotChanges() {
        val findings = listOf(f("BREACH-hibp-A"))
        val a = ScanSnapshot.of("1", input, findings, 1); val b = ScanSnapshot.of("2", input, findings, 2)
        assertTrue(!ScanDiffs.compare(a, b, findings).changed)
    }

    @Test fun firstEverScanIsNewNotAChange() {
        val findings = listOf(f("BREACH-hibp-A"))
        val d = ScanDiffs.compare(null, ScanSnapshot.of("1", input, findings, 1), findings)
        assertEquals(null, d.before); assertEquals(null, d.delta); assertTrue(d.changed)
    }

    @Test fun batchSkipsRegistryAndPhotosAndDuplicates() {
        val list = listOf(input, input, ScanInput("83338", InputType.ZIP), ScanInput("content://x", InputType.IMAGE), ScanInput("jane", InputType.USERNAME))
        assertEquals(listOf("a@b.com", "jane"), ScanDiffs.batchable(list).map { it.raw })
    }
}
