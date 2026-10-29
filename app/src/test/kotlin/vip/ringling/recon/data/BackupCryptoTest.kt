package vip.ringling.recon.data

import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import vip.ringling.recon.domain.EvidenceBundle
import vip.ringling.recon.domain.Finding
import vip.ringling.recon.domain.InputType
import vip.ringling.recon.domain.ModuleId
import vip.ringling.recon.domain.ScanInput
import vip.ringling.recon.domain.ScanRecord
import vip.ringling.recon.domain.Severity

class BackupCryptoTest {
    private val pass = "correct horse battery".toCharArray()
    private val fast = 100_000

    @Test fun roundTripsAndHidesThePlaintext() {
        val plain = """{"format":"recon-backup","scans":[{"query":"ann@example.com"}]}"""
        val sealed = BackupCrypto.seal(plain, pass, fast)
        assertTrue(BackupCrypto.isSealed(sealed))
        assertFalse("ann@example.com" in sealed, "the address must not appear in the file")
        assertEquals(plain, BackupCrypto.open(sealed, pass))
        assertFalse(BackupCrypto.isSealed(plain))
    }

    @Test fun wrongPassphraseOrAnyEditFails() {
        val sealed = BackupCrypto.seal("secret", pass, fast)
        assertFailsWith<BackupCrypto.WrongPassphrase> { BackupCrypto.open(sealed, "wrong passphrase".toCharArray()) }
        val ct = Regex("\"ct\":\"([^\"]+)\"").find(sealed)!!.groupValues[1]
        val flipped = sealed.replace(ct, (if (ct[0] == 'A') 'B' else 'A') + ct.drop(1))
        assertFailsWith<BackupCrypto.WrongPassphrase> { BackupCrypto.open(flipped, pass) }
        val otherIter = sealed.replace("\"iter\":$fast", "\"iter\":${fast + 1}")
        assertFailsWith<BackupCrypto.WrongPassphrase> { BackupCrypto.open(otherIter, pass) }
    }

    @Test fun shortPassphrasesAreRefused() {
        assertFailsWith<IllegalArgumentException> { BackupCrypto.seal("x", "short".toCharArray(), fast) }
    }
}

class EvidenceBundleTest {
    private val input = ScanInput("ann@example.com", InputType.EMAIL)
    private val record = ScanRecord("r1", input, 1_760_000_000_000, listOf(
        Finding("BREACH-x", ModuleId.BREACH, "Adobe · 2013", "Passwords exposed", Severity.EXPOSED),
        Finding("PEOPLE-hit-1", ModuleId.PEOPLE, "Ann Example", "by name only", Severity.INFO),
    ))

    private fun entries(zip: ByteArray): Map<String, ByteArray> = buildMap {
        ZipInputStream(zip.inputStream()).use { z -> generateSequence { z.nextEntry }.forEach { put(it.name, z.readBytes()) } }
    }

    @Test fun bundleVerifiesAndLeavesOutPeopleAndRegistry() {
        val zip = EvidenceBundle.build("Case A", "# Case A", listOf(record), 1_760_000_100_000)
        assertEquals(emptyList(), EvidenceBundle.verify(zip))
        val files = entries(zip)
        assertTrue(setOf("report.md", "manifest.json", "SHA256SUMS").all { it in files }, files.keys.toString())
        val scan = files.entries.single { it.key.startsWith("scans/email-") }.value.decodeToString()
        assertTrue("Adobe" in scan); assertFalse("Ann Example" in scan, "people findings are never exported")
        assertTrue(files.getValue("SHA256SUMS").decodeToString().lines().first().matches(Regex("[0-9a-f]{64}  report.md")), "sha256sum -c format")
    }

    @Test fun anyEditedFileIsCaught() {
        val files = entries(EvidenceBundle.build("Case A", "# Case A", listOf(record), 1_760_000_100_000)).toMutableMap()
        files["report.md"] = "# Case A (edited)".toByteArray()
        val tampered = java.io.ByteArrayOutputStream().also { o -> ZipOutputStream(o).use { z -> files.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } } }.toByteArray()
        assertTrue(EvidenceBundle.verify(tampered).any { "report.md" in it })
    }
}
