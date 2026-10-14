package vip.ringling.recon.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

class ScanRunnerTest {
    private fun finding(m: ModuleId, n: Int) = Finding("$m-$n", m, "t$n", "d", Severity.INFO)

    private class Fake(
        override val id: ModuleId,
        override val supportedInputTypes: Set<InputType>,
        private val body: suspend kotlinx.coroutines.flow.FlowCollector<Finding>.() -> Unit,
    ) : OsintSource {
        override suspend fun run(input: ScanInput): Flow<Finding> = flow { body() }
    }

    @Test fun runsOnlySupportingSourcesInParallelAndStreams() = runTest {
        val slow = Fake(ModuleId.BREACH, setOf(InputType.EMAIL)) { delay(1000); emit(finding(ModuleId.BREACH, 1)) }
        val fast = Fake(ModuleId.IDENTITY, setOf(InputType.EMAIL)) { delay(10); emit(finding(ModuleId.IDENTITY, 1)) }
        val other = Fake(ModuleId.DOMAIN, setOf(InputType.DOMAIN)) { emit(finding(ModuleId.DOMAIN, 1)) }
        val runner = ScanRunner(setOf(slow, fast, other))

        val events = runner.events(ScanInput("a@b.com", InputType.EMAIL)).toList()
        val found = events.filterIsInstance<ScanEvent.Found>().map { it.finding.module }
        assertEquals(listOf(ModuleId.IDENTITY, ModuleId.BREACH), found, "fast module streams first")
        assertEquals(1000L, testScheduler.currentTime, "parallel, not sequential")
        assertTrue(events.none { (it as? ScanEvent.Started)?.module == ModuleId.DOMAIN })
    }

    @Test fun failureIsIsolatedPerModule() = runTest {
        val bad = Fake(ModuleId.BREACH, setOf(InputType.EMAIL)) { error("boom") }
        val good = Fake(ModuleId.IDENTITY, setOf(InputType.EMAIL)) { emit(finding(ModuleId.IDENTITY, 1)) }
        val s = ScanRunner(setOf(bad, good)).session(ScanInput("a@b.com", InputType.EMAIL)).last()
        assertEquals(ModuleStatus.FAILED, s.statuses[ModuleId.BREACH])
        assertEquals("boom", s.errors[ModuleId.BREACH])
        assertEquals(ModuleStatus.DONE, s.statuses[ModuleId.IDENTITY])
        assertEquals(1, s.findings.size)
        assertTrue(s.complete)
    }

    @Test fun riskScoreBands() {
        assertEquals(0, RiskScore.compute(emptyList()))
        val f = List(10) { Finding("$it", ModuleId.BREACH, "", "", Severity.EXPOSED) }
        assertEquals(100, RiskScore.compute(f))
        assertEquals(Severity.EXPOSED, RiskScore.band(70))
        assertEquals(Severity.CLEAN, RiskScore.band(3))
    }
}
