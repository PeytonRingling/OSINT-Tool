package vip.ringling.recon.data

import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import vip.ringling.recon.domain.Finding
import vip.ringling.recon.domain.ModuleId
import vip.ringling.recon.domain.Severity

@RunWith(RobolectricTestRunner::class)
class PlaybookTest {
    private val f = Finding("BREACH-hibp-X", ModuleId.BREACH, "Adobe · 2013", "d", Severity.EXPOSED, tags = listOf("Passwords"))

    @Test fun finishingEveryStepMarksTheBreachHandledAndUntickingUndoesIt() = runBlocking {
        val repo = HandledRepository(ApplicationProvider.getApplicationContext())
        val k = HandledRepository.keyOf(f)
        repo.setStep(f, 0, true, 2)
        assertEquals(setOf("$k:0"), repo.doneSteps.first()); assertTrue(k !in repo.handled.first(), "one of two steps is not enough")
        repo.setStep(f, 1, true, 2)
        assertTrue(k in repo.handled.first(), "all steps done")
        repo.setStep(f, 0, false, 2)
        assertTrue(k !in repo.handled.first() && repo.doneSteps.first() == setOf("$k:1"))
        repo.clear(); assertTrue(repo.doneSteps.first().isEmpty())
    }
}
