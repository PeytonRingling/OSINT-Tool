package vip.ringling.recon.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SinceLastTest {
    private fun f(id: String, sev: Severity = Severity.EXPOSED) = Finding(id, ModuleId.BREACH, "T-$id", "d", sev)
    private fun rec(vararg fs: Finding) = ScanRecord("r", ScanInput("a@b.com", InputType.EMAIL), 1_000L, fs.toList())

    @Test fun reportsNewAndGoneAndRiskDelta() {
        val s = SinceLastBuilder.compute(rec(f("1"), f("2")), InputType.EMAIL, listOf(f("2"), f("3"), f("4", Severity.INFO)))!!
        assertEquals(listOf("T-3", "T-4"), s.newTitles); assertEquals(1, s.goneCount)
        assertEquals(28, s.before); assertEquals(32, s.after); assertTrue(s.changed)
        assertTrue(SinceLastBuilder.headline(s, "Oct 3").startsWith("Since Oct 3: risk 28 → 32 (▲ 4)"))
    }

    @Test fun nothingWithoutAPreviousScanOrForLiveLookups() {
        assertNull(SinceLastBuilder.compute(null, InputType.EMAIL, listOf(f("1"))))
        assertNull(SinceLastBuilder.compute(rec(f("1")), InputType.ZIP, listOf(f("1"))))
    }

    @Test fun aFailedSourceNeverLooksLikeAFix() {
        val s = SinceLastBuilder.compute(rec(f("1"), f("2")), InputType.EMAIL, listOf(f("1"), Finding("BREACH-hibp-error", ModuleId.BREACH, "x", "d", Severity.INFO)))!!
        assertEquals(0, s.goneCount); assertTrue(s.incomplete)
    }
}
