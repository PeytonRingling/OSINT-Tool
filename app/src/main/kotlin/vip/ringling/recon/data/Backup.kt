package vip.ringling.recon.data

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import vip.ringling.recon.data.db.CaseFileDao
import vip.ringling.recon.data.db.CaseFileEntity
import vip.ringling.recon.data.db.CaseNoteEntity
import vip.ringling.recon.data.db.CaseTargetEntity
import vip.ringling.recon.data.db.ScanDao
import vip.ringling.recon.data.db.ScanEntity
import vip.ringling.recon.data.db.SnapshotDao
import vip.ringling.recon.data.db.SnapshotEntity
import vip.ringling.recon.data.watch.WatchRepository

@Serializable data class BScan(val id: String, val query: String, val type: String, val ts: Long, val findingsJson: String, val scoreVersion: Int)
@Serializable data class BSnap(val id: String, val type: String, val value: String, val ts: Long, val risk: Int, val exposed: Int, val info: Int, val findingIds: String)
@Serializable data class BTarget(val type: String, val value: String, val addedAt: Long)
@Serializable data class BNote(val text: String, val ts: Long)
@Serializable data class BCase(val id: String, val name: String, val createdAt: Long, val targets: List<BTarget>, val notes: List<BNote>)
@Serializable data class BackupFile(
    val format: String = FORMAT, val version: Int = 1, val createdAt: Long, val scans: List<BScan>, val snapshots: List<BSnap>, val cases: List<BCase>, val watch: List<String>,
) { companion object { const val FORMAT = "recon-backup" } }

data class ImportSummary(val scans: Int, val cases: Int, val watch: Int)

/**
 * Local backup so a reinstall doesn't lose scans, cases and the watchlist. The file is written where the user chooses (system file picker).
 * It NEVER contains API keys, tokens or settings, and registry findings are not in it because they are never stored in the first place.
 * Import merges (same ids are overwritten, nothing is deleted).
 */
@Singleton
class BackupManager @Inject constructor(
    private val scans: ScanDao, private val snapshots: SnapshotDao, private val caseDao: CaseFileDao, private val watch: WatchRepository, private val caseRepo: CaseFileRepository,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun export(now: Long = System.currentTimeMillis()): String {
        val cases = caseRepo.cases.first()
        val b = BackupFile(
            createdAt = now,
            scans = scans.all().first().map { BScan(it.id, it.query, it.type, it.timestamp, it.findingsJson, it.scoreVersion) },
            snapshots = snapshots.snapshot().map { BSnap(it.id, it.targetType, it.targetValue, it.ts, it.risk, it.exposedCount, it.infoCount, it.findingIds) },
            cases = cases.map { c -> BCase(c.id, c.name, c.createdAt, c.targets.map { BTarget(it.type.name, it.value, it.addedAt) }, c.notes.map { BNote(it.text, it.ts) }) },
            watch = watch.all().map { it.raw },
        )
        return json.encodeToString(BackupFile.serializer(), b)
    }

    suspend fun import(text: String): Result<ImportSummary> = runCatching {
        val b = json.decodeFromString(BackupFile.serializer(), text)
        require(b.format == BackupFile.FORMAT) { "Not a Recon backup" }
        require(b.version == 1) { "Backup is from a newer version of Recon" }
        b.scans.forEach { scans.insert(ScanEntity(it.id, it.query, it.type, it.ts, it.findingsJson, it.scoreVersion)) }
        snapshots.insertAll(b.snapshots.map { SnapshotEntity(it.id, it.type, it.value, it.ts, it.risk, it.exposed, it.info, it.findingIds) })
        b.cases.forEach { c ->
            caseDao.upsertCase(CaseFileEntity(c.id, c.name, c.createdAt))
            c.targets.forEach { caseDao.addTarget(CaseTargetEntity(c.id, it.type, it.value, it.addedAt)) }
            // Notes have no stable id: skip ones already present so importing twice doesn't duplicate them.
            val have = caseRepo.cases.first().firstOrNull { it.id == c.id }?.notes.orEmpty().map { it.text to it.ts }.toSet()
            c.notes.filter { (it.text to it.ts) !in have }.forEach { caseDao.addNote(CaseNoteEntity(caseId = c.id, text = it.text, ts = it.ts)) }
        }
        b.watch.forEach { watch.add(it) }
        ImportSummary(b.scans.size, b.cases.size, b.watch.size)
    }
}
