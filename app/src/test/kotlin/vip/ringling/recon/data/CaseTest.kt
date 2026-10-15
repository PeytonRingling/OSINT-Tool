package vip.ringling.recon.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import vip.ringling.recon.data.db.HistoryDb
import vip.ringling.recon.domain.CaseReport
import vip.ringling.recon.domain.Fact
import vip.ringling.recon.domain.Finding
import vip.ringling.recon.domain.Link
import vip.ringling.recon.domain.ModuleId
import vip.ringling.recon.domain.Pivot
import vip.ringling.recon.domain.Severity

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CaseTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private fun f(id: String, module: ModuleId = ModuleId.IDENTITY, pivots: List<Pivot> = emptyList()) =
        Finding(id, module, "T-$id", "detail $id", Severity.INFO, url = "https://x/$id", facts = listOf(Fact("Name", "Jane\nDoe")), links = listOf(Link("L", "https://l")), pivots = pivots)

    @Test fun addNoteRemoveAndRegistryRefusal() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ctx, HistoryDb::class.java).allowMainThreadQueries().build()
        val repo = CaseRepository(db.cases())
        assertTrue(repo.add(f("a"))); assertFalse(repo.add(f("r", ModuleId.REGISTRY)), "registry data must never be stored")
        val key = repo.items.first().single().key
        repo.setNote(key, "met her at the expo")
        assertEquals("met her at the expo", repo.items.first().single().note)
        assertTrue(repo.add(f("a"))); assertEquals(1, repo.items.first().size, "same finding is not duplicated")
        repo.remove(key); assertEquals(0, repo.items.first().size)
        db.close()
    }

    @Test fun entitiesCountAcrossFindingsAndReportIsComplete() {
        val items = listOf(
            CaseItem("1", f("a", pivots = listOf(Pivot("p", "Jane@x.com"), Pivot("p2", "janed"))), "n1", 1),
            CaseItem("2", f("b", ModuleId.DOMAIN, listOf(Pivot("p", "jane@x.com"))), "", 2),
        )
        assertEquals(listOf("jane@x.com" to 2, "janed" to 1), CaseReport.entities(items))
        val md = CaseReport.markdown(items, "2026-10-15")
        assertTrue("# Recon case report" in md && "## Identity" in md && "## Domain recon" in md && "_Note:_ n1" in md && "- **Name:** Jane; Doe" in md && "jane@x.com (×2)" in md, md)
    }

    @Test fun migrationFromV1KeepsHistoryAndCreatesNewTables() = runTest {
        val name = "mig-test.db"
        ctx.deleteDatabase(name)
        val raw = ctx.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
        raw.execSQL("CREATE TABLE IF NOT EXISTS `scans` (`id` TEXT NOT NULL, `query` TEXT NOT NULL, `type` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `findingsJson` TEXT NOT NULL, PRIMARY KEY(`id`))")
        raw.execSQL("INSERT INTO scans VALUES('s1','jane','USERNAME',5,'[]')")
        raw.version = 1
        raw.close()
        val db = Room.databaseBuilder(ctx, HistoryDb::class.java, name).addMigrations(HistoryDb.MIGRATION_1_2).allowMainThreadQueries().build()
        assertEquals("jane", db.scans().find("s1")!!.query)
        assertEquals(0, db.cases().all().first().size); assertEquals(0, db.watches().all().first().size)
        db.close()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WatchTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private fun hibp(name: String) = Finding("BREACH-hibp-$name", ModuleId.BREACH, name, "d", Severity.INFO)

    @Test fun signatureIgnoresPastesAndFailsOnErrors() {
        val ok = listOf(hibp("Adobe"), hibp("LinkedIn"), Finding("BREACH-hibp-pastes", ModuleId.BREACH, "p", "d", Severity.INFO),
            Finding("BREACH-leakcheck", ModuleId.BREACH, "L", "d", Severity.INFO, facts = listOf(Fact("Records found", "2"), Fact("Endpoint", "x"), Fact("Combo Foo", "2020"))))
        assertEquals(setOf("hibp-Adobe", "hibp-LinkedIn", "leakcheck-combo foo"), vip.ringling.recon.data.watch.WatchLogic.signature(ok))
        assertEquals(null, vip.ringling.recon.data.watch.WatchLogic.signature(ok + Finding("BREACH-hibp-error", ModuleId.BREACH, "x", "d", Severity.INFO)))
        assertEquals(emptySet(), vip.ringling.recon.data.watch.WatchLogic.newItems(null, setOf("a")), "first run is a silent baseline")
        assertEquals(setOf("b"), vip.ringling.recon.data.watch.WatchLogic.newItems(setOf("a"), setOf("a", "b")))
    }

    @Test fun checkerAlertsOnlyOnNewBreaches() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ctx, HistoryDb::class.java).allowMainThreadQueries().build()
        val repo = vip.ringling.recon.data.watch.WatchRepository(db.watches())
        var current = listOf(hibp("Adobe"))
        val source = object : vip.ringling.recon.domain.OsintSource {
            override val id = ModuleId.BREACH
            override val supportedInputTypes = setOf(vip.ringling.recon.domain.InputType.EMAIL)
            override suspend fun run(input: vip.ringling.recon.domain.ScanInput) = kotlinx.coroutines.flow.flowOf(*current.toTypedArray())
        }
        val checker = vip.ringling.recon.data.watch.WatchChecker(repo, source)
        repo.add("Jane@X.com")
        assertEquals(emptyMap(), checker.run(10), "baseline is silent")
        assertEquals(emptyMap(), checker.run(20), "unchanged")
        current = listOf(hibp("Adobe"), hibp("Dropbox"))
        assertEquals(mapOf("jane@x.com" to setOf("hibp-Dropbox")), checker.run(30))
        assertEquals(emptyMap(), checker.run(40), "already reported")
        db.close()
    }
}
