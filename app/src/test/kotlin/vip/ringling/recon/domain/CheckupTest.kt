package vip.ringling.recon.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CheckupTest {
    private fun breach(title: String, vararg tags: String) = Finding("BREACH-hibp-$title", ModuleId.BREACH, title, "d", Severity.EXPOSED, tags = tags.toList())
    private fun rec(raw: String, ts: Long, vararg f: Finding, type: InputType = InputType.EMAIL) = ScanRecord("$raw$ts", ScanInput(raw, type), ts, f.toList())

    @Test fun oneBreachAcrossTargetsBecomesOneFixRankedFirst() {
        val c = CheckupBuilder.build(listOf(
            rec("a@x.com", 1, breach("Adobe", "Passwords"), breach("Zynga")), rec("b@x.com", 1, breach("Adobe", "Passwords")),
            rec("a@x.com", 0, breach("Old")),                       // older scan of the same target is ignored
        ))!!
        assertEquals(2, c.targetCount)
        assertEquals("Change passwords from Adobe", c.fixes.first().title); assertEquals(listOf("a@x.com", "b@x.com"), c.fixes.first().targets)
        assertTrue(c.fixes.none { it.title.contains("Old") })
        assertTrue(c.fixes.any { it.title == "Move key accounts to passkeys" && it.targets.size == 2 })
    }

    @Test fun handledFindingsDropOutAndNothingScannedMeansNoCheckup() {
        val c = CheckupBuilder.build(listOf(rec("a@x.com", 1, breach("Adobe", "Passwords")))) { it.title == "Adobe" }!!
        assertTrue(c.fixes.isEmpty()); assertEquals(0, c.exposedTotal)
        assertNull(CheckupBuilder.build(emptyList()))
        assertNull(CheckupBuilder.build(listOf(rec("83646", 1, type = InputType.ZIP))))
    }

    @Test fun stealerLogsComeFirst() {
        val stealer = Finding("BREACH-hibp-S", ModuleId.BREACH, "Stealer", "d", Severity.EXPOSED, facts = listOf(Fact("Flags", "Stealer log")), tags = listOf("Passwords"))
        val c = CheckupBuilder.build(listOf(rec("a@x.com", 1, stealer), rec("b@x.com", 1, breach("Adobe", "Passwords")), rec("c@x.com", 1, breach("Adobe", "Passwords"))))!!
        assertEquals("Treat a device as compromised", c.fixes.first().title); assertTrue(c.fixes.first().urgent)
    }
}
