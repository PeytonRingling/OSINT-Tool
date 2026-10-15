package vip.ringling.recon.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import vip.ringling.recon.data.db.HistoryDb
import vip.ringling.recon.data.db.ScanEntity
import vip.ringling.recon.data.db.toRecord
import vip.ringling.recon.data.sources.breach.HibpBreach
import vip.ringling.recon.data.sources.breach.HibpMapper
import vip.ringling.recon.domain.BreachSeverity
import vip.ringling.recon.domain.Finding
import vip.ringling.recon.domain.InputType
import vip.ringling.recon.domain.ModuleId
import vip.ringling.recon.domain.RiskScore
import vip.ringling.recon.domain.ScanInput
import vip.ringling.recon.domain.ScanRecord
import vip.ringling.recon.domain.Severity
import vip.ringling.recon.domain.isRegistryRecord
import vip.ringling.recon.domain.neutral
import vip.ringling.recon.domain.scorable

class RiskScoreTest {
    private fun f(id: String, sev: Severity, module: ModuleId = ModuleId.IDENTITY) = Finding(id, module, id, "d", sev)

    @Test fun formulaIsDocumentedAndCapped() {
        assertEquals(0, RiskScore.compute(0, 0))
        assertEquals(14 * 2 + 4 * 3, RiskScore.compute(2, 3))
        assertEquals(100, RiskScore.compute(10, 10))
        assertEquals(Severity.CLEAN, RiskScore.band(13)); assertEquals(Severity.INFO, RiskScore.band(14)); assertEquals(Severity.EXPOSED, RiskScore.band(50))
        assertNotEquals(Severity.CLEAN, RiskScore.band(RiskScore.compute(1, 0)), "a single exposed finding must not read as clean")
    }

    @Test fun identicalCountsAlwaysGiveTheSameScoreWhateverTheOrderOrContent() {
        val a = listOf(f("a", Severity.EXPOSED), f("b", Severity.INFO), f("c", Severity.INFO), f("d", Severity.CLEAN))
        val b = listOf(f("x", Severity.INFO, ModuleId.BREACH), f("y", Severity.CLEAN, ModuleId.PHONE), f("z", Severity.EXPOSED, ModuleId.DOMAIN), f("w", Severity.INFO, ModuleId.NETWORK))
        assertEquals(RiskScore.compute(a), RiskScore.compute(b))
        assertEquals(RiskScore.compute(a), RiskScore.compute(a.reversed()))
        assertEquals(RiskScore.compute(a), RiskScore.compute(a.shuffled(java.util.Random(7))))
        assertEquals(22, RiskScore.compute(a))
    }

    @Test fun housekeepingFindingsNeverMoveTheScore() {
        val base = listOf(f("e", Severity.EXPOSED), f("i", Severity.INFO))
        val noise = listOf(
            f("IDENTITY-summary", Severity.INFO), f("DOMAIN-dkim-error", Severity.INFO), f("PHONE-links", Severity.INFO), f("IDENTITY-manual", Severity.INFO),
            f("IDENTITY-profile", Severity.INFO), f("PHONE-twilio-offer", Severity.INFO), f("DORKS-all", Severity.INFO, ModuleId.DORKS), f("REGISTRY-ID-A-1", Severity.INFO, ModuleId.REGISTRY),
        )
        noise.forEach { assertFalse(it.scorable, it.id) }
        assertEquals(RiskScore.compute(base), RiskScore.compute(base + noise))
        assertEquals(18, RiskScore.compute(base + noise))
    }

    @Test fun registryRecordsAreNeutralAndCountedByRegexNotSeverity() {
        val rec = f("REGISTRY-ID-A-42", Severity.INFO, ModuleId.REGISTRY)
        assertTrue(rec.neutral && rec.isRegistryRecord)
        assertFalse(f("REGISTRY-summary", Severity.INFO, ModuleId.REGISTRY).isRegistryRecord)
        assertFalse(f("REGISTRY-note-0", Severity.INFO, ModuleId.REGISTRY).isRegistryRecord)
        assertEquals(0, RiskScore.compute(listOf(rec, rec.copy(id = "REGISTRY-ID-J-7"))))
        assertTrue(ScanRecord("1", ScanInput("83646", InputType.ZIP), 0, listOf(rec)).neutral)
        assertFalse(ScanRecord("1", ScanInput("a@b.com", InputType.EMAIL), 0, emptyList()).neutral)
    }
}

/** Data classes are real HIBP `DataClasses` values (haveibeenpwned.com/api/v3/breaches, fetched 2026-10-15). */
class BreachSeverityTest {
    private val samples = Json { ignoreUnknownKeys = true }.decodeFromString<List<HibpBreach>>(fixture("hibp_samples.json")).associateBy { it.name }

    @Test fun suno2025IsExposedBecauseOfCardDataAddressAndPhone() {
        val suno = samples.getValue("Suno")
        assertEquals(listOf("Email addresses", "Names", "Partial credit card data", "Phone numbers", "Physical addresses", "Purchases"), suno.dataClasses)
        assertEquals(Severity.EXPOSED, BreachSeverity.of(suno.dataClasses))
        val f = HibpMapper.breachFinding(suno)
        assertEquals(Severity.EXPOSED, f.severity)
        assertEquals(setOf("Partial credit card data", "Phone numbers", "Physical addresses"), f.hotTags)
    }

    @Test fun realBreachesMapAsSpecified() {
        fun sev(name: String) = HibpMapper.breachFinding(samples.getValue(name)).severity
        assertEquals(Severity.EXPOSED, sev("Adobe"), "passwords + password hints")
        assertEquals(Severity.EXPOSED, sev("LinkedIn"), "email + passwords")
        assertEquals(Severity.EXPOSED, sev("Facebook"), "phone numbers")
        assertEquals(Severity.INFO, sev("Cocospy"), "email only")
        assertEquals(Severity.INFO, sev("Twitter200M"), "email, names, profiles, usernames")
    }

    @Test fun eachExposedCategoryAloneIsEnoughAndEverythingElseIsInfo() {
        val email = "Email addresses"
        listOf(
            "Passwords", "Historical passwords", "Password hints", "Security questions and answers", "Auth tokens",
            "Credit cards", "Partial credit card data", "Bank account numbers", "Financial transactions", "Payment histories", "Payment methods", "Financial investments",
            "Government issued IDs", "Passport numbers", "Social security numbers", "Driver's licenses",
            "Physical addresses", "Phone numbers",
        ).forEach { assertEquals(Severity.EXPOSED, BreachSeverity.of(listOf(email, it)), it) }
        // From HIBP's real class list: none of these exposes anything on its own.
        listOf(
            listOf(email), listOf(email, "Names"), listOf("Names"), listOf(email, "Names", "Usernames"), listOf(email, "IP addresses", "Usernames"),
            listOf(email, "Dates of birth", "Genders"), listOf(email, "Geographic locations", "Job titles", "Employers"), listOf(email, "Purchases", "Website activity", "Device information"),
            listOf(email, "Social media profiles", "Bios", "Profile photos"),
        ).forEach { assertEquals(Severity.INFO, BreachSeverity.of(it), it.toString()) }
        assertEquals(Severity.EXPOSED, BreachSeverity.of(listOf("phone numbers")), "case-insensitive")
        assertEquals(Severity.INFO, BreachSeverity.of(emptyList()))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LegacyHistoryTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val json = Json

    private fun oldBreach(name: String, classes: List<String>) = Finding("BREACH-hibp-$name", ModuleId.BREACH, name, "d", Severity.EXPOSED, tags = classes, hotTags = setOf("Private messages"))

    @Test fun rowsSavedByOlderBuildsAreRescoredAndLabelled() {
        // An old build marked every breach with a password-ish class as EXPOSED and never re-derived anything else.
        val old = listOf(oldBreach("Cocospy", listOf("Email addresses")), oldBreach("Suno", listOf("Email addresses", "Partial credit card data")), Finding("REGISTRY-ID-A-1", ModuleId.REGISTRY, "x", "d", Severity.EXPOSED))
        val entity = ScanEntity("1", "a@b.com", "EMAIL", 5, json.encodeToString(kotlinx.serialization.builtins.ListSerializer(Finding.serializer()), old), scoreVersion = 0)
        val rec = entity.toRecord()
        assertTrue(rec.legacyScore)
        assertEquals(Severity.INFO, rec.findings.first { it.title == "Cocospy" }.severity)
        assertEquals(Severity.EXPOSED, rec.findings.first { it.title == "Suno" }.severity)
        assertEquals(Severity.INFO, rec.findings.first { it.module == ModuleId.REGISTRY }.severity)
        assertEquals(RiskScore.compute(1, 1), rec.risk)
        val current = entity.copy(scoreVersion = RiskScore.VERSION).toRecord()
        assertFalse(current.legacyScore); assertEquals(Severity.EXPOSED, current.findings.first { it.title == "Cocospy" }.severity, "current rows are trusted as saved")
    }

    @Test fun newScansAreSavedAtTheCurrentVersion() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ctx, HistoryDb::class.java).allowMainThreadQueries().build()
        val repo = RoomHistoryRepository(db.scans())
        repo.add(ScanRecord("n", ScanInput("a@b.com", InputType.EMAIL), 1, emptyList()))
        assertEquals(RiskScore.VERSION, db.scans().find("n")!!.scoreVersion)
        assertFalse(repo.records.first().single().legacyScore)
        db.close()
    }
}
