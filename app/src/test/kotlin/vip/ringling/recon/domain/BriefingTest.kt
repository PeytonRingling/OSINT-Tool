package vip.ringling.recon.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BriefingTest {
    private fun breach(id: String, sev: Severity, date: String) = Finding("BREACH-hibp-$id", ModuleId.BREACH, "$id · 2020", "d", sev, facts = listOf(Fact("Breach date", date)), tags = listOf("Passwords"))

    @Test fun summarisesBreachesAndPicksAFirstStep() {
        val b = BriefingBuilder.build(InputType.EMAIL, listOf(breach("Adobe", Severity.EXPOSED, "Oct 4, 2013"), breach("Zynga", Severity.INFO, "Sep 12, 2019")), setOf(ModuleId.BREACH))
        assertTrue("1 exposed finding" in b.headline, b.headline)
        assertTrue(b.points.first().contains("2 data breaches from 2013 to 2019"), b.points.first())
        assertTrue(b.points.any { it.startsWith("Most recent: Zynga") })
        assertTrue(b.firstStep != null)
    }

    @Test fun cleanScansSaySoAndFailuresAreAdmitted() {
        val clean = BriefingBuilder.build(InputType.DOMAIN, emptyList(), setOf(ModuleId.DOMAIN))
        assertTrue(clean.headline.startsWith("Nothing exposed"))
        val failed = BriefingBuilder.build(InputType.EMAIL, listOf(Finding("BREACH-hibp-error", ModuleId.BREACH, "x unavailable", "d", Severity.INFO)), setOf(ModuleId.BREACH))
        assertTrue(failed.points.any { "couldn't be reached" in it })
        assertEquals(null, clean.firstStep)
    }
}
