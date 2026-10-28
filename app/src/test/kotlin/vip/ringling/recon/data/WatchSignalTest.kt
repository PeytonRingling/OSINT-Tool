package vip.ringling.recon.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import vip.ringling.recon.data.db.HistoryDb
import vip.ringling.recon.data.sources.domain.DomainAnalysis
import vip.ringling.recon.data.watch.WatchChecker
import vip.ringling.recon.data.watch.WatchLogic
import vip.ringling.recon.data.watch.WatchRepository
import vip.ringling.recon.domain.Fact
import vip.ringling.recon.domain.Finding
import vip.ringling.recon.domain.InputType
import vip.ringling.recon.domain.ModuleId
import vip.ringling.recon.domain.OsintSource
import vip.ringling.recon.domain.ScanInput
import vip.ringling.recon.domain.ScanRunner
import vip.ringling.recon.domain.Severity

/** Fixture-driven: no network. Covers what a username / domain watch alerts on. */
class WatchSignalTest {
    private fun subs(vararg n: String) = Finding("DOMAIN-subs", ModuleId.DOMAIN, "Subdomains", "d", Severity.INFO, raw = n.joinToString("\n"))
    private fun rdap(registrar: String, expires: String) = Finding("DOMAIN-rdap", ModuleId.DOMAIN, "RDAP", "Registrar: $registrar · expires $expires (12d)", Severity.CLEAN,
        facts = listOf(Fact("Registrar", registrar), Fact("Expires", expires), Fact("Nameservers", "ns1.x.io")))
    private fun wayback(latest: String) = Finding("DOMAIN-wayback", ModuleId.DOMAIN, "Wayback", "d", Severity.INFO, facts = listOf(Fact("Earliest capture", "2001-01-01\nu"), Fact("Latest capture", "$latest\nhttps://web.archive.org/x")))

    @Test fun domainSignatureCoversSubdomainsWhoisAndWayback() {
        val sig = WatchLogic.signatureFor(InputType.DOMAIN, listOf(subs("api.x.io", "dev.x.io"), rdap("Gandi", "2027-01-01"), wayback("2026-10-01")))!!
        assertEquals(setOf("sub:api.x.io", "sub:dev.x.io", "whois:Registrar=Gandi|Expires=2027-01-01|Status=|Nameservers=ns1.x.io", "wayback:2026-10-01"), sig)
    }

    @Test fun aFailedCategoryKeepsItsBaselineInsteadOfAlertingOrForgetting() {
        val known = WatchLogic.signatureFor(InputType.DOMAIN, listOf(subs("api.x.io"), rdap("Gandi", "2027-01-01"), wayback("2026-10-01")))!!
        // crt.sh failed this run; RDAP and Wayback answered unchanged.
        val now = WatchLogic.signatureFor(InputType.DOMAIN, listOf(Finding("DOMAIN-subs-error", ModuleId.DOMAIN, "e", "d", Severity.INFO), rdap("Gandi", "2027-01-01"), wayback("2026-10-01")), known)!!
        assertEquals(known, now)
        assertEquals(emptySet(), WatchLogic.newItems(known, now))
        // A subdomain that comes back after an outage is not "new".
        assertEquals(emptySet(), WatchLogic.newItems(now, WatchLogic.signatureFor(InputType.DOMAIN, listOf(subs("api.x.io"), rdap("Gandi", "2027-01-01"), wayback("2026-10-01")), now)!!))
    }

    @Test fun rdapFactsAreStableAcrossDaysButMoveOnRealChanges() {
        val a = DomainAnalysis.rdap(fixture("rdap.json"), "northwind.io", now = java.time.Instant.parse("2026-10-14T00:00:00Z"))
        val b = DomainAnalysis.rdap(fixture("rdap.json"), "northwind.io", now = java.time.Instant.parse("2027-02-20T00:00:00Z"))
        assertTrue(a.facts.any { it.label == "Registrar" } && a.facts.any { it.label == "Expires" }, a.facts.toString())
        assertEquals(WatchLogic.signatureFor(InputType.DOMAIN, listOf(a)), WatchLogic.signatureFor(InputType.DOMAIN, listOf(b)), "the days-left countdown is not a change")
        val moved = WatchLogic.signatureFor(InputType.DOMAIN, listOf(rdap("Namecheap", "2027-01-01")), WatchLogic.signatureFor(InputType.DOMAIN, listOf(rdap("Gandi", "2027-01-01"))))!!
        assertEquals("Registration (WHOIS) changed", WatchLogic.describe(InputType.DOMAIN, WatchLogic.newItems(setOf("whois:Registrar=Gandi|Expires=2027-01-01|Status=|Nameservers=ns1.x.io"), moved)))
    }

    @Test fun inconclusiveSitesKeepKnownAccounts() {
        val summary = { inc: String -> Finding("IDENTITY-summary", ModuleId.IDENTITY, "s", "d", Severity.INFO, raw = inc) }
        val gh = Finding("IDENTITY-GitHub", ModuleId.IDENTITY, "GitHub", "d", Severity.INFO)
        val known = WatchLogic.signatureFor(InputType.USERNAME, listOf(gh, Finding("IDENTITY-Reddit", ModuleId.IDENTITY, "Reddit", "d", Severity.INFO), summary("")))!!
        val blocked = WatchLogic.signatureFor(InputType.USERNAME, listOf(gh, summary("inconclusive: Reddit (HTTP 429)")), known)!!
        assertEquals(known, blocked, "a rate-limited site is not a deleted account")
        assertNull(WatchLogic.signatureFor(InputType.USERNAME, listOf(gh)), "no summary = the sweep did not finish")
    }

    @Test fun legacyBaselinesAreReRecordedSilently() {
        assertNull(WatchLogic.usableBaseline(InputType.DOMAIN, setOf("DOMAIN-subs", "DOMAIN-rdap")))
        assertEquals(setOf("hibp-Adobe"), WatchLogic.usableBaseline(InputType.EMAIL, setOf("hibp-Adobe")))
        assertEquals(setOf("sub:a.x.io"), WatchLogic.usableBaseline(InputType.DOMAIN, setOf("sub:a.x.io")))
    }

    @Test fun describeSaysWhatChanged() {
        assertEquals("2 new subdomains (api.x.io, dev.x.io) · new Wayback Machine capture", WatchLogic.describe(InputType.DOMAIN, setOf("sub:dev.x.io", "sub:api.x.io", "wayback:2026-10-20")))
        assertEquals("New account on GitHub", WatchLogic.describe(InputType.USERNAME, setOf("account:GitHub")))
        assertEquals("2 new breaches", WatchLogic.describe(InputType.EMAIL, setOf("hibp-A", "hibp-B")))
    }
}

@RunWith(RobolectricTestRunner::class)
class WatchCheckerModulesTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private class Fake(override val id: ModuleId, override val supportedInputTypes: Set<InputType>, var out: () -> List<Finding>) : OsintSource {
        var runs = 0
        override suspend fun run(input: ScanInput): Flow<Finding> { runs++; return flowOf(*out().toTypedArray()) }
    }

    @Test fun domainWatchRunsOnlyTheDomainModuleAndAlertsOnNewSubdomain() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ctx, HistoryDb::class.java).allowMainThreadQueries().build()
        val repo = WatchRepository(db.watches())
        var names = listOf("api.x.io")
        val domain = Fake(ModuleId.DOMAIN, setOf(InputType.DOMAIN)) { listOf(Finding("DOMAIN-subs", ModuleId.DOMAIN, "s", "d", Severity.INFO, raw = names.joinToString("\n"))) }
        val identity = Fake(ModuleId.IDENTITY, setOf(InputType.DOMAIN, InputType.USERNAME)) { emptyList() }
        val breach = Fake(ModuleId.BREACH, setOf(InputType.EMAIL)) { emptyList() }
        val checker = WatchChecker(repo, breach, ScanRunner(setOf(domain, identity)))
        repo.add("x.io", type = InputType.DOMAIN)
        assertEquals(emptyMap(), checker.run(10), "baseline")
        names = listOf("api.x.io", "vpn.x.io")
        assertEquals(mapOf("x.io" to setOf("sub:vpn.x.io")), checker.run(20))
        assertEquals(0, identity.runs, "other modules don't run for a watch")
        db.close()
    }
}
