package vip.ringling.recon.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import vip.ringling.recon.data.sources.registry.AdapterUnavailable
import vip.ringling.recon.data.sources.registry.IdahoRegistryAdapter
import vip.ringling.recon.data.sources.registry.RegistryAdapter
import vip.ringling.recon.data.sources.registry.RegistryEntry
import vip.ringling.recon.data.sources.registry.RegistryQuery
import vip.ringling.recon.data.sources.registry.RegistrySource
import vip.ringling.recon.domain.Finding
import vip.ringling.recon.domain.InputType
import vip.ringling.recon.domain.ModuleId
import vip.ringling.recon.domain.OsintSource
import vip.ringling.recon.domain.ScanInput
import vip.ringling.recon.domain.ScanRecord
import vip.ringling.recon.domain.ScanRunner
import vip.ringling.recon.domain.Severity

private class FakeAdapter(private val entries: List<RegistryEntry>) : RegistryAdapter {
    override val stateCode = "ID"
    override val stateName = "Idaho"
    var searches = 0
    override fun handlesZip(zip: String) = zip.startsWith("83")
    override suspend fun search(query: RegistryQuery): List<RegistryEntry> { searches++; return entries }
}

class RegistryTest {
    private val zip = ScanInput("83702", InputType.ZIP)

    @Test fun idahoZipRange() {
        val a = IdahoRegistryAdapter()
        assertTrue(a.handlesZip("83702") && a.handlesZip("83201") && a.handlesZip("83877"))
        assertTrue(!a.handlesZip("90210") && !a.handlesZip("83100") && !a.handlesZip("83900"))
    }

    @Test fun routesToAdapterAndMapsEntries() = runTest {
        val adapter = FakeAdapter(listOf(RegistryEntry("DOE, SAMPLE", "Boise · Tier I", "ref1", "https://example.test/1")))
        val f = RegistrySource(setOf(adapter)).collect(zip)
        assertEquals(1, f.size)
        assertEquals("DOE, SAMPLE", f.single().title)
        assertEquals("", f.single().raw, "no raw registry payload is retained")
        assertEquals(1, adapter.searches)
    }

    @Test fun noAdapterAndEmptyResults() = runTest {
        assertEquals("REGISTRY-nostate", RegistrySource(setOf(FakeAdapter(emptyList()))).collect(ScanInput("90210", InputType.ZIP)).single().id)
        val none = RegistrySource(setOf(FakeAdapter(emptyList()))).collect(zip).single()
        assertEquals(Severity.CLEAN, none.severity)
    }

    @Test fun unconfirmedIdahoAdapterFailsLoudlyNotWithFakeData() = runTest {
        val ex = runCatching { RegistrySource(setOf(IdahoRegistryAdapter())).collect(zip) }.exceptionOrNull()
        assertTrue(ex is AdapterUnavailable, "must surface as a module failure, not invent results")
    }

    @Test fun scanHistoryNeverStoresRegistryFindings() = runBlocking {
        val registry = object : OsintSource {
            override val id = ModuleId.REGISTRY
            override val supportedInputTypes = setOf(InputType.ZIP)
            override suspend fun run(input: ScanInput): Flow<Finding> = flow {
                emit(Finding("REGISTRY-x", ModuleId.REGISTRY, "SENSITIVE NAME", "d", Severity.INFO))
            }
        }
        val history = InMemoryHistoryRepository()
        val controller = ScanController(ScanRunner(setOf(registry)), history)
        // UNDISPATCHED: the collector is subscribed before the scan can complete.
        val done = async(start = CoroutineStart.UNDISPATCHED) { controller.completed.first() }
        controller.start(zip)
        withTimeout(10_000) { done.await() }

        assertTrue(controller.session.value!!.findings.any { it.title == "SENSITIVE NAME" }, "visible live")
        val saved: ScanRecord = history.records.first().single()
        assertEquals("83702", saved.input.raw)
        assertTrue(saved.findings.isEmpty(), "…but never persisted")
    }
}
