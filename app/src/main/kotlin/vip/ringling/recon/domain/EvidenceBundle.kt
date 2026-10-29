package vip.ringling.recon.domain

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A case as one ZIP someone else can check: the report, the latest stored scan of each target as JSON (registry and people findings are
 * never included, see [Export.exportable]), a manifest with when each scan was captured, and `SHA256SUMS` in the format `sha256sum -c`
 * reads. Any edit to any file, the manifest included, shows up as a mismatch. It proves the files are unchanged since export, not that the
 * sources were right.
 */
object EvidenceBundle {
    @Serializable data class FileEntry(val name: String, val sha256: String, val bytes: Int, val capturedAt: String? = null)
    @Serializable data class Manifest(val format: String = "recon-evidence", val version: Int = 1, val case: String, val createdAt: String, val files: List<FileEntry>)

    private val json = Json { prettyPrint = true; encodeDefaults = true }

    fun build(caseName: String, reportMarkdown: String, scans: List<ScanRecord>, createdAtMillis: Long): ByteArray {
        val files = linkedMapOf<String, Pair<ByteArray, String?>>("report.md" to (reportMarkdown.toByteArray() to null))
        scans.forEach { r ->
            val stamp = Instant.ofEpochMilli(r.timestampMillis).toString()
            val name = "scans/${r.input.type.name.lowercase()}-${slug(r.input.raw)}-${stamp.take(19).replace(':', '-')}.json"
            files[name] = Export.scan(r.input, r.findings).toByteArray() to stamp
        }
        val manifest = Manifest(case = caseName, createdAt = Instant.ofEpochMilli(createdAtMillis).toString(),
            files = files.map { (n, v) -> FileEntry(n, sha256(v.first), v.first.size, v.second) })
        val manifestBytes = json.encodeToString(Manifest.serializer(), manifest).toByteArray()
        val all = files.mapValues { it.value.first } + ("manifest.json" to manifestBytes)
        val sums = all.entries.joinToString("") { (n, b) -> "${sha256(b)}  $n\n" }.toByteArray()
        return ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z -> (all + ("SHA256SUMS" to sums)).forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        }.toByteArray()
    }

    /** Problems found when re-checking a bundle (empty = every file matches SHA256SUMS and the manifest). */
    fun verify(zip: ByteArray): List<String> {
        val files = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zip)).use { z -> generateSequence { z.nextEntry }.forEach { e -> files[e.name] = z.readBytes() } }
        val sums = files["SHA256SUMS"]?.decodeToString() ?: return listOf("SHA256SUMS missing")
        val problems = mutableListOf<String>()
        val listed = sums.lines().filter { it.isNotBlank() }.associate { it.substringAfter("  ") to it.substringBefore("  ") }
        listed.forEach { (n, h) -> val b = files[n]; if (b == null) problems += "$n missing" else if (sha256(b) != h) problems += "$n changed" }
        (files.keys - listed.keys - "SHA256SUMS").forEach { problems += "$it not listed" }
        files["manifest.json"]?.let { m ->
            runCatching { Json { ignoreUnknownKeys = true }.decodeFromString(Manifest.serializer(), m.decodeToString()) }.getOrNull()?.files?.forEach { f ->
                if (files[f.name]?.let(::sha256) != f.sha256) problems += "${f.name} doesn't match the manifest"
            }
        }
        return problems.distinct()
    }

    fun fileName(caseName: String, createdAtMillis: Long) = "recon-evidence-${slug(caseName)}-${Instant.ofEpochMilli(createdAtMillis).toString().take(10)}.zip"

    private fun slug(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifEmpty { "target" }
    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}
