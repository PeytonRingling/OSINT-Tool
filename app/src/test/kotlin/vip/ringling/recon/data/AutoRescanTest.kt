package vip.ringling.recon.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import vip.ringling.recon.data.watch.AutoRescanLogic
import vip.ringling.recon.domain.InputType
import vip.ringling.recon.domain.ScanDiff
import vip.ringling.recon.domain.ScanInput

class AutoRescanTest {
    private fun d(raw: String, before: Int?, after: Int, new: List<String> = emptyList(), failed: Boolean = false) = ScanDiff(ScanInput(raw, InputType.EMAIL), before, after, new, 0, failed)

    @Test fun onlyWorseOrNewIsAlertedAndFirstScansAreSilent() {
        val alerts = AutoRescanLogic.alerts(listOf(d("worse@x.com", 10, 50), d("better@x.com", 50, 10), d("same@x.com", 10, 10), d("new@x.com", null, 80), d("lead@x.com", 10, 10, listOf("Adobe")), d("fail@x.com", 10, 0, failed = true)))
        assertEquals(listOf("worse@x.com", "lead@x.com"), alerts.map { it.input.raw })
    }

    @Test fun summaryNamesAtMostThreeTargets() {
        assertNull(AutoRescanLogic.summary(emptyList()))
        val s = AutoRescanLogic.summary(listOf("a", "b", "c", "d", "e").map { d("$it@x.com", 1, 9) })!!
        assertEquals("5 targets changed: a@x.com, b@x.com, c@x.com +2", s)
        assertEquals("1 target changed: a@x.com", AutoRescanLogic.summary(listOf(d("a@x.com", 1, 9))))
    }
}
