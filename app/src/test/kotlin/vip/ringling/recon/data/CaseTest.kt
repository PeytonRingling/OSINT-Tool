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

    @Test fun casesPersistTargetsNotesAndRenameDelete() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ctx, HistoryDb::class.java).allowMainThreadQueries().build()
        val repo = vip.ringling.recon.data.CaseFileRepository(db.caseFiles())
        val id = repo.create("  My footprint ", vip.ringling.recon.domain.CaseTarget(vip.ringling.recon.domain.InputType.EMAIL, "a@b.com", 1), now = 5)
        repo.addTarget(id, vip.ringling.recon.domain.InputType.USERNAME, "ab"); repo.addTarget(id, vip.ringling.recon.domain.InputType.USERNAME, "ab")
        repo.addTarget(id, vip.ringling.recon.domain.InputType.IMAGE, "content://photo/1")
        repo.addNote(id, "  met at the expo "); repo.addNote(id, "   ")
        var c = repo.cases.first().single()
        assertEquals("My footprint", c.name); assertEquals(1, c.number)
        assertEquals(listOf("a@b.com", "ab"), c.targets.map { it.value }, "duplicates ignored; photos are not durable targets")
        assertEquals(listOf("met at the expo"), c.notes.map { it.text })
        repo.removeTarget(id, vip.ringling.recon.domain.InputType.USERNAME, "ab"); repo.rename(id, "Renamed"); repo.deleteNote(c.notes.single().id)
        c = repo.cases.first().single()
        assertEquals(listOf("a@b.com"), c.targets.map { it.value }); assertEquals("Renamed", c.name); assertTrue(c.notes.isEmpty())
        val second = repo.create("Jerome area", vip.ringling.recon.domain.CaseTarget(vip.ringling.recon.domain.InputType.ZIP, "83338", 1))
        assertEquals(listOf(1, 2), repo.cases.first().map { it.number })
        repo.delete(id)
        assertEquals(listOf(second), repo.cases.first().map { it.id }); assertTrue(db.caseFiles().targets().first().none { it.caseId == id })
        db.close()
    }

    @Test fun backupRoundTripsAndNeverContainsKeys() = runTest {
        fun mgr(db: HistoryDb): Pair<vip.ringling.recon.data.BackupManager, vip.ringling.recon.data.CaseFileRepository> {
            val repo = vip.ringling.recon.data.CaseFileRepository(db.caseFiles())
            return vip.ringling.recon.data.BackupManager(db.scans(), db.snapshots(), db.caseFiles(), vip.ringling.recon.data.watch.WatchRepository(db.watches()), repo) to repo
        }
        val a = Room.inMemoryDatabaseBuilder(ctx, HistoryDb::class.java).allowMainThreadQueries().build()
        val (ma, ra) = mgr(a)
        a.scans().insert(vip.ringling.recon.data.db.ScanEntity("s1", "ann@b.com", "EMAIL", 5, "[]", 2))
        a.snapshots().insert(vip.ringling.recon.data.db.SnapshotEntity("s1", "EMAIL", "ann@b.com", 5, 40, 1, 2, "x,y"))
        val id = ra.create("Case", vip.ringling.recon.domain.CaseTarget(vip.ringling.recon.domain.InputType.EMAIL, "ann@b.com", 1)); ra.addNote(id, "hello")
        vip.ringling.recon.data.watch.WatchRepository(a.watches()).add("ann@b.com")
        val text = ma.export()
        assertTrue("hibp" !in text.lowercase() && "api" !in text.lowercase().replace("apikey", ""), "no key material in the file")
        val b = Room.inMemoryDatabaseBuilder(ctx, HistoryDb::class.java).allowMainThreadQueries().build()
        val (mb, rb) = mgr(b)
        val sum = mb.import(text).getOrThrow(); mb.import(text).getOrThrow()
        assertEquals(1, sum.scans); assertEquals(1, sum.cases)
        assertEquals("ann@b.com", b.scans().find("s1")!!.query); assertEquals(40, b.snapshots().snapshot().single().risk)
        val c = rb.cases.first().single(); assertEquals("Case", c.name); assertEquals(listOf("hello"), c.notes.map { it.text }, "second import must not duplicate notes")
        assertEquals(listOf("ann@b.com"), b.watches().snapshot().map { it.raw })
        assertTrue(mb.import("{\"format\":\"other\",\"createdAt\":1,\"scans\":[],\"snapshots\":[],\"cases\":[],\"watch\":[]}").isFailure)
        assertTrue(mb.import("not json").isFailure)
        a.close(); b.close()
    }

    @Test fun ageBasedDeleteRemovesOnlyOldScansAndKeepsCases() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ctx, HistoryDb::class.java).allowMainThreadQueries().build()
        db.scans().insert(vip.ringling.recon.data.db.ScanEntity("old", "a", "USERNAME", 1_000, "[]", 2)); db.scans().insert(vip.ringling.recon.data.db.ScanEntity("new", "b", "USERNAME", 9_000_000_000_000, "[]", 2))
        db.snapshots().insert(vip.ringling.recon.data.db.SnapshotEntity("old", "USERNAME", "a", 1_000, 0, 0, 0, "")); db.snapshots().insert(vip.ringling.recon.data.db.SnapshotEntity("new", "USERNAME", "b", 9_000_000_000_000, 0, 0, 0, ""))
        vip.ringling.recon.data.CaseFileRepository(db.caseFiles()).create("keep me")
        db.scans().deleteBefore(5_000); db.snapshots().deleteBefore(5_000)
        assertEquals(listOf("new"), db.scans().all().first().map { it.id }); assertEquals(listOf("new"), db.snapshots().snapshot().map { it.id })
        assertEquals(1, db.caseFiles().cases().first().size)
        db.clearAllTables()
        assertEquals(0, db.scans().all().first().size); assertEquals(0, db.caseFiles().cases().first().size)
        db.close()
    }

    @Test fun caseReportCarriesTargetsChangesPivotsAndNotes() {
        val file = vip.ringling.recon.domain.CaseFile("c", "My footprint", 1, 1, listOf(vip.ringling.recon.domain.CaseTarget(vip.ringling.recon.domain.InputType.EMAIL, "ann@b.com", 1), vip.ringling.recon.domain.CaseTarget(vip.ringling.recon.domain.InputType.USERNAME, "ann", 1)), listOf(vip.ringling.recon.domain.CaseNote(1, "check the old gmail", 1_700_000_000_000)))
        val s1 = vip.ringling.recon.domain.ScanSnapshot("1", vip.ringling.recon.domain.InputType.EMAIL, "ann@b.com", 1_700_000_000_000, 34, 3, 5, emptyList())
        val s2 = s1.copy(id = "2", ts = 1_700_000_100_000, risk = 100, exposed = 12)
        val states = listOf(vip.ringling.recon.domain.CaseTargetState(file.targets[0], s2, listOf(s1, s2)), vip.ringling.recon.domain.CaseTargetState(file.targets[1], null, emptyList()))
        val md = vip.ringling.recon.domain.CaseReport.markdown(file, states, vip.ringling.recon.domain.CaseChanges.events(states), listOf(vip.ringling.recon.domain.CasePivot("pal@work.io", "Breach check", "Found in Code footprint.")), "2026-10-15")
        assertTrue("# My footprint" in md && "- Case exposure: 100/100 (Critical exposure)" in md && "Since first scan: ▲ 66" in md, md)
        assertTrue("**ann@b.com** (Email): risk 100, 12 exposed" in md && "**ann** (Username): not scanned yet" in md && "same handle" in md, md)
        assertTrue("▲ 66 — Risk 34 → 100." in md && "pal@work.io" in md && "check the old gmail" in md && "never stored or included" in md, md)
    }

    @Test fun migrationFromV1KeepsHistoryAndCreatesNewTables() = runTest {
        val name = "mig-test.db"
        ctx.deleteDatabase(name)
        val raw = ctx.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
        raw.execSQL("CREATE TABLE IF NOT EXISTS `scans` (`id` TEXT NOT NULL, `query` TEXT NOT NULL, `type` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `findingsJson` TEXT NOT NULL, PRIMARY KEY(`id`))")
        raw.execSQL("INSERT INTO scans VALUES('s1','jane','USERNAME',5,'[]')")
        raw.version = 1
        raw.close()
        val db = Room.databaseBuilder(ctx, HistoryDb::class.java, name).addMigrations(HistoryDb.MIGRATION_1_2, HistoryDb.MIGRATION_2_3, HistoryDb.MIGRATION_3_4).allowMainThreadQueries().build()
        assertEquals("jane", db.scans().find("s1")!!.query)
        assertEquals(0, db.scans().find("s1")!!.scoreVersion, "rows from before versioning are legacy (0)")
        assertEquals(0, db.cases().all().first().size); assertEquals(0, db.watches().all().first().size)
        assertEquals(0, db.snapshots().all().first().size); assertEquals(0, db.caseFiles().cases().first().size)
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
        val hits = listOf(Finding("IDENTITY-github", ModuleId.IDENTITY, "GitHub", "d", Severity.INFO), Finding("IDENTITY-summary", ModuleId.IDENTITY, "s", "d", Severity.INFO), Finding("IDENTITY-gitlab", ModuleId.IDENTITY, "GitLab", "d", Severity.INFO))
        assertEquals(setOf("IDENTITY-github", "IDENTITY-gitlab"), vip.ringling.recon.data.watch.WatchLogic.signatureFor(vip.ringling.recon.domain.InputType.USERNAME, hits), "housekeeping rows are not findings")
        assertEquals(null, vip.ringling.recon.data.watch.WatchLogic.signatureFor(vip.ringling.recon.domain.InputType.DOMAIN, hits + Finding("DOMAIN-x-error", ModuleId.DOMAIN, "x", "d", Severity.INFO)), "a failed run never alerts")
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
