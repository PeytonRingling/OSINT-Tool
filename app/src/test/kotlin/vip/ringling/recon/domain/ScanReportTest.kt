package vip.ringling.recon.domain

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScanReportTest {
    @Test fun reportListsFindingsAndNeverRegistryOrPeopleRecords() {
        val fs = listOf(
            Finding("BREACH-hibp-A", ModuleId.BREACH, "Adobe · 2013", "152M accounts", Severity.EXPOSED, url = "https://haveibeenpwned.com/PwnedWebsites#Adobe", tags = listOf("Passwords")),
            Finding("REGISTRY-1", ModuleId.REGISTRY, "SOME PERSON", "addr", Severity.INFO), Finding("PEOPLE-hit-1", ModuleId.PEOPLE, "Another Person", "d", Severity.INFO),
        )
        val md = ScanReport.markdown(ScanInput("a@b.com", InputType.EMAIL), fs, 0L)
        assertTrue("# Email: a@b.com" in md && "### Adobe · 2013 [Exposed]" in md && "Risk: 14/100" in md)
        assertFalse("SOME PERSON" in md || "Another Person" in md)
        assertTrue(ScanReport.fileName(ScanInput("A@b.com", InputType.EMAIL), 0L) == "recon-report-email-a-b-com-1970-01-01.pdf")
    }
}
