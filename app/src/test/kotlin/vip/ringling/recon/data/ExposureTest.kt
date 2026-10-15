package vip.ringling.recon.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import vip.ringling.recon.data.sources.breach.HibpBreach
import vip.ringling.recon.data.sources.breach.HibpMapper
import vip.ringling.recon.domain.Exposure
import vip.ringling.recon.domain.Fact
import vip.ringling.recon.domain.Finding
import vip.ringling.recon.domain.ModuleId
import vip.ringling.recon.domain.Severity

class ExposureTest {
    private val breaches = Json { ignoreUnknownKeys = true }.decodeFromString<List<HibpBreach>>(fixture("hibp_samples.json")).map(HibpMapper::breachFinding)

    @Test fun nodesTimelineAndBreachRingFromRealBreaches() {
        val identity = Finding("IDENTITY-GitHub", ModuleId.IDENTITY, "GitHub", "d", Severity.INFO)
        val noise = Finding("IDENTITY-summary", ModuleId.IDENTITY, "s", "d", Severity.INFO)
        val all = breaches + identity + noise
        val nodes = Exposure.nodes(all, listOf(ModuleId.BREACH, ModuleId.IDENTITY, ModuleId.CODE, ModuleId.DORKS))
        assertEquals(listOf(ModuleId.BREACH, ModuleId.IDENTITY, ModuleId.CODE), nodes.map { it.module }, "dorks have no node; busiest first")
        val b = nodes.first()
        assertEquals(breaches.size, b.count); assertEquals(breaches.size, b.dots.size)
        assertEquals(Severity.EXPOSED, b.dots.first(), "exposed dots come first"); assertTrue(b.radius > nodes[1].radius)
        assertEquals(1, nodes[1].count, "the identity summary is not evidence"); assertEquals(9.0, nodes[2].radius, 1e-9)
        val t = Exposure.timeline(all)
        assertEquals(breaches.size, t.size); assertEquals(t.sortedBy { it.monthIndex }, t)
        assertTrue(t.any { it.year == 2025 && it.month == 11 && it.title.startsWith("Suno") }, t.toString())
    }

    @Test fun fixFirstOrdersStealerLogsThenPasswordsThenPasskeys() {
        val stealer = Finding("BREACH-hibp-S", ModuleId.BREACH, "Stealer Logs", "d", Severity.EXPOSED, facts = listOf(Fact("Breach date", "Jun 15, 2026"), Fact("Flags", "Stealer log — malware-harvested credentials")))
        val plain = Finding("BREACH-hibp-P", ModuleId.BREACH, "Plain", "d", Severity.INFO, facts = listOf(Fact("Breach date", "Apr 11, 2025")))
        val steps = Exposure.fixFirst(listOf(stealer, plain))
        assertEquals(listOf("Treat a device as compromised", "Rotate exposed passwords", "Move key accounts to passkeys"), steps.map { it.title })
        assertTrue(steps[0].urgent && !steps[1].urgent)
        assertEquals("1 finding flagged as exposed.", steps[1].detail)
        assertEquals(listOf("Move key accounts to passkeys"), Exposure.fixFirst(listOf(plain)).map { it.title })
        val dns = Finding("DOMAIN-spf", ModuleId.DOMAIN, "SPF missing", "No SPF record.\nMore", Severity.EXPOSED)
        assertEquals(listOf("SPF missing"), Exposure.fixFirst(listOf(dns)).map { it.title })
        assertEquals(emptyList(), Exposure.fixFirst(emptyList()))
    }

    @Test fun headlines() {
        assertEquals("Critical exposure", Exposure.headline(100)); assertEquals("Moderate exposure", Exposure.headline(20)); assertEquals("Low exposure", Exposure.headline(0))
    }
}
