package vip.ringling.recon.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MailGradeTest {
    private fun d(key: String, sev: Severity) = Finding("DOMAIN-$key", ModuleId.DOMAIN, key, "x", sev)

    @Test fun strongSetupGetsAnA() {
        val g = MailGradeBuilder.compute(listOf(d("spf", Severity.CLEAN), d("dmarc", Severity.CLEAN), d("dkim", Severity.CLEAN), d("mtasts", Severity.CLEAN), d("bimi", Severity.INFO)))!!
        assertEquals(95, g.score); assertEquals("A", g.letter)
    }

    @Test fun missingDmarcAndOpenSpfFails() {
        val g = MailGradeBuilder.compute(listOf(d("spf", Severity.EXPOSED), d("dmarc", Severity.EXPOSED), d("dkim", Severity.INFO)))!!
        assertEquals(5, g.score); assertEquals("F", g.letter)
        assertEquals(CheckState.FAIL, g.checks.first { it.name == "DMARC" }.state)
    }

    @Test fun aFailedLookupIsNotAGrade() {
        assertNull(MailGradeBuilder.compute(listOf(d("spf", Severity.CLEAN))))
        assertNull(MailGradeBuilder.compute(listOf(d("spf", Severity.CLEAN), Finding("DOMAIN-dmarc-error", ModuleId.DOMAIN, "DMARC unavailable", "x", Severity.INFO))))
    }
}
